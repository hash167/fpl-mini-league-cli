import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import telemetry.FplTelemetry

/** Local/dev default. In the container WORKDIR is /app, so this is /app/data/live-overall-snapshot.json. */
const val DEFAULT_OVERALL_SNAPSHOT_PATH = "data/live-overall-snapshot.json"

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
 * The last successful snapshot is atomically written to disk so a process
 * restart can peek immediately; stale-while-revalidate still applies.
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
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    snapshotPath: String? = null
) {
    private val snapshotFile: File? =
        snapshotPath?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }

    @Volatile private var snapshot: LiveOverallSnapshot? = loadSnapshot()
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
            } catch (e: Exception) {
                FplTelemetry.incrementSampleRefreshError(e)
                log.warn("live overall sample refresh failed: {}", e.message)
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
        return FplTelemetry.span("fpl.sample.refresh") { span ->
            span.setAttribute("fpl.gameweek", gameweek.toLong())
            refreshBlockingInner(gameweek)
        }
    }

    private fun refreshBlockingInner(gameweek: Int): LiveOverallSnapshot {
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
        val next = LiveOverallSnapshot(
            loadedAtMs = clock(),
            gameweek = gameweek,
            totalPlayers = totalPlayers,
            bands = stats,
            samples = samples,
            available = !isSampleThin(stats, minPopulatedBands, minSuccessfulSamples)
        )
        persistSnapshot(next)
        return next
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
                    FplTelemetry.incrementEvaluateError()
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

    private fun persistSnapshot(snap: LiveOverallSnapshot) {
        val dest = snapshotFile ?: return
        var tmp: File? = null
        try {
            val parent = dest.parentFile ?: File(".")
            if (!parent.exists() && !parent.mkdirs()) {
                log.warn("Could not create overall snapshot directory {}", parent)
                return
            }
            tmp = File(parent, dest.name + ".tmp")
            tmp.writeText(persistJson.encodeToString(PersistedOverallSnapshot.serializer(), snap.toPersisted()))
            try {
                Files.move(
                    tmp.toPath(),
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log.warn("Failed to persist overall snapshot to {}: {}", dest, e.message)
            try {
                tmp?.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadSnapshot(): LiveOverallSnapshot? {
        val dest = snapshotFile ?: return null
        return try {
            if (!dest.isFile) return null
            val persisted = persistJson.decodeFromString(PersistedOverallSnapshot.serializer(), dest.readText())
            persisted.toSnapshot(minPopulatedBands, minSuccessfulSamples)
        } catch (e: Exception) {
            log.warn("Failed to load overall snapshot from {}: {}", dest, e.message)
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LiveOverallSampleCache::class.java)
        private val persistJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

fun totalPlayers(bootstrap: JsonObject): Int? = bootstrap.int("total_players")

@Serializable
private data class PersistedOverallSnapshot(
    val loadedAtMs: Long,
    val gameweek: Int,
    val totalPlayers: Int,
    val bands: List<PersistedBandStats>,
    val samples: List<PersistedSampledManager>
)

@Serializable
private data class PersistedBandStats(
    val index: Int,
    val startRank: Int,
    val endRank: Int,
    val nB: Int,
    val nSlice: Int
)

@Serializable
private data class PersistedSampledManager(
    val entryId: Int,
    val officialRank: Int,
    val bandIndex: Int,
    val liveTotal: Int,
    val multipliers: Map<Int, Int>
)

private fun LiveOverallSnapshot.toPersisted() = PersistedOverallSnapshot(
    loadedAtMs = loadedAtMs,
    gameweek = gameweek,
    totalPlayers = totalPlayers,
    bands = bands.map {
        PersistedBandStats(it.index, it.startRank, it.endRank, it.nB, it.nSlice)
    },
    samples = samples.map {
        PersistedSampledManager(it.entryId, it.officialRank, it.bandIndex, it.liveTotal, it.multipliers)
    }
)

private fun PersistedOverallSnapshot.toSnapshot(
    minPopulatedBands: Int,
    minSuccessfulSamples: Int
): LiveOverallSnapshot? {
    if (loadedAtMs < 0 || gameweek <= 0 || totalPlayers <= 0) return null
    val bandStats = bands.map {
        BandSampleStats(it.index, it.startRank, it.endRank, it.nB, it.nSlice)
    }
    val managers = samples.map {
        SampledManager(it.entryId, it.officialRank, it.bandIndex, it.liveTotal, it.multipliers)
    }
    return LiveOverallSnapshot(
        loadedAtMs = loadedAtMs,
        gameweek = gameweek,
        totalPlayers = totalPlayers,
        bands = bandStats,
        samples = managers,
        available = !isSampleThin(bandStats, minPopulatedBands, minSuccessfulSamples)
    )
}
