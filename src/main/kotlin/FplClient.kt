interface FplClient {
    fun bootstrap(): kotlinx.serialization.json.JsonObject
    fun userClassicLeagues(entryId: Int): List<LeagueRef>
    fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage
    fun eventLiveElementPoints(eventId: Int): Map<Int, Int>
    fun entryPicks(entryId: Int, eventId: Int): List<PickRow>
}

class FplApiException(message: String, val statusCode: Int) : RuntimeException(message)
