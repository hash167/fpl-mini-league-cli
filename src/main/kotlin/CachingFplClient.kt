import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

const val DEFAULT_MAX_STALE_MS: Long = 12 * 60 * 60 * 1000L
/** After a failed reload, serve the last good value for this long before asking FPL again. */
const val DEFAULT_FAILURE_COOLDOWN_MS: Long = 15_000L
/**
 * When a reload of an expired-but-usable value takes longer than this, the caller gets the last
 * good value (flagged stale) and the reload keeps going in the background. This stops a page that
 * needs several FPL payloads from paying the full FPL timeout once per payload.
 */
const val DEFAULT_SOFT_WAIT_MS: Long = 2_000L

/**
 * Process-wide "FPL is struggling" signal. Once any reload has failed or blown its soft wait,
 * other caches stop waiting the full soft wait for a while, so a page built from several FPL
 * payloads falls back to saved data in well under a second instead of paying per payload.
 */
object UpstreamHealth {
    const val TROUBLE_WINDOW_MS = 30_000L
    const val TROUBLED_WAIT_MS = 200L
    @Volatile private var troubleAt = 0L
    fun trouble() { troubleAt = System.currentTimeMillis() }
    fun ok() { troubleAt = 0L }
    fun troubled(): Boolean = troubleAt > 0 && System.currentTimeMillis() - troubleAt < TROUBLE_WINDOW_MS
}

private val reloadPool = Executors.newCachedThreadPool { r ->
    Thread(r, "fpl-cache-reload").apply { isDaemon = true }
}

private val cacheLog = LoggerFactory.getLogger("fpl.cache")

/**
 * Single-value TTL cache with stale-on-error and single-flight loading.
 *
 * - Fresh (age < ttl): returned straight away.
 * - Expired: one caller (the owner) reloads, outside any lock. Callers that arrive while a reload
 *   is in flight get the last good value immediately instead of queueing behind FPL.
 * - Reload fails: the last good value is kept and served (up to [maxStaleMs]) and flagged stale;
 *   for [failureCooldownMs] after a failure no new reload is attempted.
 * - No value yet (cold) and the load fails: the error propagates (mapped to 503 at the edge).
 */
class TtlCache<T>(
    private val ttlMs: Long,
    private val maxStaleMs: Long = DEFAULT_MAX_STALE_MS,
    private val failureCooldownMs: Long = DEFAULT_FAILURE_COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val name: String = "cache",
    private val softWaitMs: Long = DEFAULT_SOFT_WAIT_MS
) {
    private val lock = Any()
    @Volatile private var value: T? = null
    @Volatile private var loadedAt: Long = 0
    @Volatile private var lastFailureAt: Long = 0
    private var inflight: CompletableFuture<T>? = null

    fun get(loader: () -> T): T {
        val now = clock()
        val current = value
        val age = now - loadedAt
        if (current != null && age < ttlMs) {
            Freshness.served(age)
            return current
        }
        val usable = current != null && age < maxStaleMs
        if (usable && lastFailureAt > 0 && now - lastFailureAt < failureCooldownMs) {
            Freshness.stale(age)
            return current!!
        }
        var owner = false
        val future = synchronized(lock) {
            inflight ?: CompletableFuture<T>().also { inflight = it; owner = true }
        }
        if (!owner) {
            if (usable) {
                // Someone else is already asking FPL; don't wait behind them.
                if (lastFailureAt > loadedAt || age > ttlMs + FPL_TOTAL_DEADLINE_MS) Freshness.stale(age)
                else Freshness.served(age)
                return current!!
            }
            return awaitCold(future)
        }
        if (!usable) return loadInline(future, loader)
        // Expired but we have a usable value: reload in the background and only wait briefly.
        reloadPool.execute { runLoad(future, loader, current, age) }
        return try {
            val wait = if (UpstreamHealth.troubled()) minOf(softWaitMs, UpstreamHealth.TROUBLED_WAIT_MS) else softWaitMs
            val v = future.get(wait, TimeUnit.MILLISECONDS)
            Freshness.served(0)
            v
        } catch (e: TimeoutException) {
            UpstreamHealth.trouble()
            Freshness.stale(age)
            current!!
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is Exception) {
                Freshness.stale(age)
                current!!
            } else throw cause
        }
    }

    private fun runLoad(future: CompletableFuture<T>, loader: () -> T, current: T?, age: Long) {
        try {
            val fresh = loader()
            loadedAt = clock()
            value = fresh
            lastFailureAt = 0
            UpstreamHealth.ok()
            future.complete(fresh)
        } catch (e: Throwable) {
            lastFailureAt = clock()
            UpstreamHealth.trouble()
            if (current != null && e is Exception) {
                cacheLog.warn("FPL reload failed for {} ({}); serving last good value, age {}s",
                    name, e.javaClass.simpleName + ": " + e.message, age / 1000)
            }
            future.completeExceptionally(e)
        } finally {
            synchronized(lock) { if (inflight === future) inflight = null }
        }
    }

    private fun loadInline(future: CompletableFuture<T>, loader: () -> T): T {
        try {
            val fresh = loader()
            loadedAt = clock()
            value = fresh
            lastFailureAt = 0
            future.complete(fresh)
            UpstreamHealth.ok()
            Freshness.served(0)
            return fresh
        } catch (e: Throwable) {
            lastFailureAt = clock()
            UpstreamHealth.trouble()
            future.completeExceptionally(e)
            throw e
        } finally {
            synchronized(lock) { if (inflight === future) inflight = null }
        }
    }

    private fun awaitCold(future: CompletableFuture<T>): T {
        return try {
            val v = future.get()
            Freshness.served(0)
            v
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    /** Test/diagnostic: age of the held value in ms, or null when empty. */
    fun ageMs(): Long? = if (value == null) null else clock() - loadedAt
}

/**
 * Per-key TTL caches with a hard cap on the number of keys (least-recently-used key is dropped).
 * Parsed FPL payloads are large (a single event-live JSON tree is ~9 MB on the heap), so keeping one
 * per gameweek / manager forever exhausted the heap. Each key gets [TtlCache] stale-on-error.
 */
class BoundedTtlCaches<K : Any, T>(
    private val ttlMs: Long,
    private val maxKeys: Int,
    private val maxStaleMs: Long = DEFAULT_MAX_STALE_MS,
    private val failureCooldownMs: Long = DEFAULT_FAILURE_COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val name: String = "cache"
) {
    private val map = object : LinkedHashMap<K, TtlCache<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, TtlCache<T>>): Boolean = size > maxKeys
    }

    fun get(key: K, loader: () -> T): T {
        val cache = synchronized(map) {
            map.getOrPut(key) { TtlCache(ttlMs, maxStaleMs, failureCooldownMs, clock, "$name[$key]") }
        }
        return cache.get(loader)
    }

    fun size(): Int = synchronized(map) { map.size }
}

const val ENTRY_TTL_MS: Long = 60_000
const val TRANSFERS_TTL_MS: Long = 5 * 60_000

class CachingFplClient(
    val inner: FplClient,
    private val ttlMs: Long = 20_000,
    private val entryTtlMs: Long = ENTRY_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : FplClient {
    private fun <T> single(name: String, ttl: Long = ttlMs) =
        TtlCache<T>(ttl, clock = clock, name = name)
    private fun <K : Any, T> bounded(name: String, maxKeys: Int, ttl: Long = ttlMs) =
        BoundedTtlCaches<K, T>(ttl, maxKeys, clock = clock, name = name)

    private val bootstrapCache = single<JsonObject>("bootstrap")
    private val liveCache = bounded<Int, JsonObject>("event-live", maxKeys = 3)
    private val fixturesCache = bounded<Int, List<JsonObject>>("fixtures", maxKeys = 4)
    private val historyCache = bounded<Int, JsonObject>("entry-history", maxKeys = 64, ttl = entryTtlMs)
    private val allFixturesCache = single<List<JsonObject>>("fixtures-all")
    // Small payloads (a few KB each); a few hundred keys stays well under a few MB of heap.
    private val entryCache = bounded<Int, JsonObject>("entry", maxKeys = 300, ttl = entryTtlMs)
    private val entryEventCache = bounded<Pair<Int, Int>, JsonObject>("entry-event", maxKeys = 300, ttl = entryTtlMs)
    private val leaguesCache = bounded<Int, List<LeagueRef>>("classic-leagues", maxKeys = 200, ttl = entryTtlMs)
    private val standingsCache = bounded<Pair<Int, Int>, LeagueStandingsPage>("standings", maxKeys = 200, ttl = entryTtlMs)
    private val picksCache = bounded<Pair<Int, Int>, List<PickRow>>("picks", maxKeys = 300, ttl = entryTtlMs)
    // Keyed by (entry, gameweek) and parsed down to a handful of ints, so a new gameweek never
    // reuses an old list and 300 keys stay tiny. Transfers for a gameweek are fixed after its deadline.
    private val transfersCache = bounded<Pair<Int, Int>, List<TransferRow>>("transfers", maxKeys = 300, ttl = TRANSFERS_TTL_MS)

    override fun bootstrap(): JsonObject = bootstrapCache.get { inner.bootstrap() }

    override fun userClassicLeagues(entryId: Int): List<LeagueRef> =
        leaguesCache.get(entryId) { inner.userClassicLeagues(entryId) }

    override fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage {
        // The overall-rank sampler walks hundreds of league-314 pages; caching those would only evict
        // the mini-league pages people actually look at.
        if (leagueId == OVERALL_CLASSIC_LEAGUE_ID) return inner.leagueStandingsPage(leagueId, page)
        return standingsCache.get(leagueId to page) { inner.leagueStandingsPage(leagueId, page) }
    }

    override fun eventLiveElementPoints(eventId: Int): Map<Int, Int> =
        parseLiveElementPoints(eventLive(eventId))

    override fun entryPicks(entryId: Int, eventId: Int): List<PickRow> =
        picksCache.get(entryId to eventId) { inner.entryPicks(entryId, eventId) }

    override fun entryTransfers(entryId: Int, eventId: Int): List<TransferRow> =
        transfersCache.get(entryId to eventId) { inner.entryTransfers(entryId, eventId) }

    override fun entry(entryId: Int): JsonObject = entryCache.get(entryId) { inner.entry(entryId) }

    override fun entryHistory(entryId: Int): JsonObject =
        historyCache.get(entryId) { inner.entryHistory(entryId) }

    override fun entryEvent(entryId: Int, eventId: Int): JsonObject =
        entryEventCache.get(entryId to eventId) { inner.entryEvent(entryId, eventId) }

    override fun eventLive(eventId: Int): JsonObject =
        liveCache.get(eventId) { inner.eventLive(eventId) }

    override fun allFixtures(): List<JsonObject> = allFixturesCache.get { inner.allFixtures() }

    override fun fixtures(eventId: Int): List<JsonObject> =
        fixturesCache.get(eventId) { inner.fixtures(eventId) }
}
