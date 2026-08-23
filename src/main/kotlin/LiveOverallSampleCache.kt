import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject

data class LiveOverallSnapshot(
    val loadedAtMs: Long,
    val gameweek: Int,
    val totalPlayers: Int,
    val bands: List<BandSampleStats>,
    val samples: List<SampledManager>,
    val available: Boolean
) {
    val sampleCount: Int get() = samples.size

    fun ageSeconds(nowMs: Long): Long = ((nowMs - loadedAtMs) / 1000L).coerceAtLeast(0)

    fun rankFor(liveTotal: Int): Long? =
        if (!available) null else estimatedOverallRank(liveTotal, samples, bands)

    fun eoFor(elementId: Int): Double? =
        if (!available) null else effectiveOwnership(elementId, samples, bands)
}

/**
 * Hourly stratified sample of overall classic league 314.
 * Identities and live totals share the same TTL. A cold cache never blocks
 * the live-rank request path — callers peek and kick a background refresh.
 */
class LiveOverallSampleCache(
    private val api: FplClient,
    private val evaluate: (entryId: Int, gameweek: Int) -> SampledLive?,
    private val ttlMs: Long = SAMPLE_CACHE_TTL_MS,
    private val samplesPerBand: Int = SAMPLES_PER_BAND,
    private val pageSize: Int = STANDINGS_PAGE_SIZE,
    private val pageDelayMs: Long = 50L,
    private val minPopulatedBands: Int = MIN_POPULATED_BANDS,
    private val minSuccessfulSamples: Int = MIN_SUCCESSFUL_SAMPLES,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) }
) {
    @Volatile private var snapshot: LiveOverallSnapshot? = null
    @Volatile private var backoffUntilMs: Long = 0
    @Volatile private var failCount: Int = 0
    private val refreshing = AtomicBoolean(false)
    private val refreshExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-overall-refresh").apply { isDaemon = true }
    }
    private val evalPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "live-overall-eval").apply { isDaemon = true }
    }

    fun peek(): LiveOverallSnapshot? = snapshot

    fun isRefreshing(): Boolean = refreshing.get()

    fun requestRefresh(gameweek: Int) {
        val now = clock()
        if (now < backoffUntilMs) return
        val current = snapshot
        val stale = current == null ||
            current.gameweek != gameweek ||
            now - current.loadedAtMs >= ttlMs
        if (!stale) return
        if (!refreshing.compareAndSet(false, true)) return
        refreshExecutor.submit {
            try {
                snapshot = refreshBlocking(gameweek)
                failCount = 0
                backoffUntilMs = 0
            } catch (_: Exception) {
                failCount += 1
                val shift = failCount.coerceIn(1, 6) - 1
                val delay = (30_000L * (1L shl shift)).coerceAtMost(15 * 60_000L)
                backoffUntilMs = clock() + delay
            } finally {
                refreshing.set(false)
            }
        }
    }

    fun refreshNow(gameweek: Int): LiveOverallSnapshot {
        val next = refreshBlocking(gameweek)
        snapshot = next
        failCount = 0
        backoffUntilMs = 0
        return next
    }

    fun status(gameweek: Int?): LiveOverallEstimateStatus {
        val current = snapshot
        val now = clock()
        return LiveOverallEstimateStatus(
            warm = current != null,
            available = current?.available == true,
            sampleAgeSeconds = current?.ageSeconds(now),
            sampleCount = current?.sampleCount ?: 0,
            targetSampleCount = samplesPerBand * 9,
            totalPlayers = current?.totalPlayers,
            gameweek = current?.gameweek ?: gameweek,
            refreshing = refreshing.get(),
            bands = current?.bands?.map {
                LiveOverallBandStatus(
                    index = it.index,
                    startRank = it.startRank,
                    endRank = it.endRank,
                    nB = it.nB,
                    nSlice = it.nSlice
                )
            } ?: emptyList(),
            note = when {
                current == null -> "Cache cold. Official overall rank is served immediately; estimate attaches after the hourly sample warms."
                !current.available -> "Sample too thin (need ≥$minPopulatedBands bands with n_b>0 and ≥$minSuccessfulSamples successful samples). Estimate unavailable."
                else -> "Warm stratified sample of classic league $OVERALL_CLASSIC_LEAGUE_ID. This is an estimate, not a live 10M board."
            }
        )
    }

    private fun refreshBlocking(gameweek: Int): LiveOverallSnapshot {
        val bootstrap = api.bootstrap()
        val totalPlayers = bootstrap.int("total_players")
            ?: throw IllegalStateException("bootstrap-static missing total_players")
        val bands = rankBands(totalPlayers)
        val identities = sampleIdentities(bands)
        val samples = evaluateIdentities(identities, gameweek)
        val stats = bands.map { band ->
            BandSampleStats(
                index = band.index,
                startRank = band.startRank,
                endRank = band.endRank,
                nB = samples.count { it.bandIndex == band.index },
                nSlice = band.nSlice
            )
        }
        return LiveOverallSnapshot(
            loadedAtMs = clock(),
            gameweek = gameweek,
            totalPlayers = totalPlayers,
            bands = stats,
            samples = samples,
            available = !isSampleThin(stats, minPopulatedBands, minSuccessfulSamples)
        )
    }

    private data class SampleIdentity(
        val entryId: Int,
        val officialRank: Int,
        val bandIndex: Int
    )

    private fun sampleIdentities(bands: List<RankBand>): List<SampleIdentity> {
        val pageSlots = linkedMapOf<Int, MutableList<Pair<Int, Int>>>()
        val targetBand = HashMap<Int, Int>()
        for (band in bands) {
            for (rank in sampleTargetRanks(band.startRank, band.endRank, samplesPerBand)) {
                val slot = rankToPageSlot(rank, pageSize)
                pageSlots.getOrPut(slot.page) { mutableListOf() }.add(rank to slot.index)
                targetBand[rank] = band.index
            }
        }

        val pages = HashMap<Int, LeagueStandingsPage?>()
        for (page in pageSlots.keys) {
            pages[page] = try {
                api.leagueStandingsPage(OVERALL_CLASSIC_LEAGUE_ID, page)
            } catch (_: Exception) {
                null
            }
            sleeper(pageDelayMs)
        }

        val found = ArrayList<SampleIdentity>()
        val seen = HashSet<Int>()
        for ((page, slots) in pageSlots) {
            val batch = pages[page] ?: continue
            for ((targetRank, index) in slots) {
                val row = batch.results.getOrNull(index) ?: continue
                if (row.entryId <= 0 || !seen.add(row.entryId)) continue
                val official = if (row.rank > 0) row.rank else targetRank
                val bandIndex = bandForRank(official, bands)?.index ?: targetBand[targetRank] ?: continue
                found.add(SampleIdentity(row.entryId, official, bandIndex))
            }
        }
        return found
    }

    private fun evaluateIdentities(identities: List<SampleIdentity>, gameweek: Int): List<SampledManager> {
        if (identities.isEmpty()) return emptyList()
        val futures = identities.map { ident ->
            evalPool.submit(Callable {
                val live = try {
                    evaluate(ident.entryId, gameweek)
                } catch (_: Exception) {
                    null
                } ?: return@Callable null
                SampledManager(
                    entryId = ident.entryId,
                    officialRank = ident.officialRank,
                    bandIndex = ident.bandIndex,
                    liveTotal = live.liveTotal,
                    multipliers = live.multipliers
                )
            })
        }
        return futures.mapNotNull { future ->
            try {
                future.get()
            } catch (_: Exception) {
                null
            }
        }
    }
}

fun totalPlayers(bootstrap: JsonObject): Int? = bootstrap.int("total_players")
