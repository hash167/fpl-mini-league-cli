import kotlinx.serialization.json.JsonObject

interface FplClient {
    fun bootstrap(): JsonObject
    fun userClassicLeagues(entryId: Int): List<LeagueRef>
    fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage
    fun eventLiveElementPoints(eventId: Int): Map<Int, Int>
    fun entryPicks(entryId: Int, eventId: Int): List<PickRow>
    fun entry(entryId: Int): JsonObject = JsonObject(emptyMap())
    fun entryHistory(entryId: Int): JsonObject = JsonObject(emptyMap())
    fun entryEvent(entryId: Int, eventId: Int): JsonObject = JsonObject(emptyMap())
    fun eventLive(eventId: Int): JsonObject = JsonObject(emptyMap())
    fun fixtures(eventId: Int): List<JsonObject> = emptyList()
}

class FplApiException(message: String, val statusCode: Int) : RuntimeException(message)
