import java.util.concurrent.ConcurrentHashMap
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

class CachingFplClient(
    private val inner: FplClient,
    private val ttlMs: Long = 20_000
) : FplClient {
    private val bootstrapCache = TtlCache<JsonObject>(ttlMs)
    private val liveCache = ConcurrentHashMap<Int, TtlCache<JsonObject>>()
    private val fixturesCache = ConcurrentHashMap<Int, TtlCache<List<JsonObject>>>()
    private val historyCache = ConcurrentHashMap<Int, TtlCache<JsonObject>>()

    override fun bootstrap(): JsonObject = bootstrapCache.get { inner.bootstrap() }

    override fun userClassicLeagues(entryId: Int): List<LeagueRef> = inner.userClassicLeagues(entryId)

    override fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage =
        inner.leagueStandingsPage(leagueId, page)

    override fun eventLiveElementPoints(eventId: Int): Map<Int, Int> =
        parseLiveElementPoints(eventLive(eventId))

    override fun entryPicks(entryId: Int, eventId: Int): List<PickRow> = inner.entryPicks(entryId, eventId)

    override fun entry(entryId: Int): JsonObject = inner.entry(entryId)

    override fun entryHistory(entryId: Int): JsonObject =
        historyCache.getOrPut(entryId) { TtlCache(ttlMs) }.get { inner.entryHistory(entryId) }

    override fun entryEvent(entryId: Int, eventId: Int): JsonObject = inner.entryEvent(entryId, eventId)

    override fun eventLive(eventId: Int): JsonObject =
        liveCache.getOrPut(eventId) { TtlCache(ttlMs) }.get { inner.eventLive(eventId) }

    override fun fixtures(eventId: Int): List<JsonObject> =
        fixturesCache.getOrPut(eventId) { TtlCache(ttlMs) }.get { inner.fixtures(eventId) }
}
