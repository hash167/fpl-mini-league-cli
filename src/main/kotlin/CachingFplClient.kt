import kotlinx.serialization.json.JsonObject

class TtlCache<T>(private val ttlMs: Long) {
    @Volatile private var value: T? = null
    @Volatile private var loadedAt: Long = 0

    @Synchronized
    fun get(loader: () -> T): T {
        val now = System.currentTimeMillis()
        val current = value
        if (current != null && now - loadedAt < ttlMs) return current
        val fresh = loader()
        value = fresh
        loadedAt = now
        return fresh
    }
}

/**
 * Per-key TTL caches with a hard cap on the number of keys (least-recently-used key is dropped).
 * Parsed FPL payloads are large (a single event-live JSON tree is ~9 MB on the heap), so keeping one
 * per gameweek / manager forever exhausted the heap.
 */
class BoundedTtlCaches<K : Any, T>(private val ttlMs: Long, private val maxKeys: Int) {
    private val map = object : LinkedHashMap<K, TtlCache<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, TtlCache<T>>): Boolean = size > maxKeys
    }

    fun get(key: K, loader: () -> T): T {
        val cache = synchronized(map) { map.getOrPut(key) { TtlCache(ttlMs) } }
        return cache.get(loader)
    }

    fun size(): Int = synchronized(map) { map.size }
}

class CachingFplClient(
    private val inner: FplClient,
    private val ttlMs: Long = 20_000
) : FplClient {
    private val bootstrapCache = TtlCache<JsonObject>(ttlMs)
    private val liveCache = BoundedTtlCaches<Int, JsonObject>(ttlMs, maxKeys = 3)
    private val fixturesCache = BoundedTtlCaches<Int, List<JsonObject>>(ttlMs, maxKeys = 4)
    private val historyCache = BoundedTtlCaches<Int, JsonObject>(ttlMs, maxKeys = 32)

    override fun bootstrap(): JsonObject = bootstrapCache.get { inner.bootstrap() }

    override fun userClassicLeagues(entryId: Int): List<LeagueRef> = inner.userClassicLeagues(entryId)

    override fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage =
        inner.leagueStandingsPage(leagueId, page)

    override fun eventLiveElementPoints(eventId: Int): Map<Int, Int> =
        parseLiveElementPoints(eventLive(eventId))

    override fun entryPicks(entryId: Int, eventId: Int): List<PickRow> = inner.entryPicks(entryId, eventId)

    override fun entry(entryId: Int): JsonObject = inner.entry(entryId)

    override fun entryHistory(entryId: Int): JsonObject =
        historyCache.get(entryId) { inner.entryHistory(entryId) }

    override fun entryEvent(entryId: Int, eventId: Int): JsonObject = inner.entryEvent(entryId, eventId)

    override fun eventLive(eventId: Int): JsonObject =
        liveCache.get(eventId) { inner.eventLive(eventId) }

    private val allFixturesCache = TtlCache<List<JsonObject>>(ttlMs)
    override fun allFixtures(): List<JsonObject> = allFixturesCache.get { inner.allFixtures() }

    override fun fixtures(eventId: Int): List<JsonObject> =
        fixturesCache.get(eventId) { inner.fixtures(eventId) }
}
