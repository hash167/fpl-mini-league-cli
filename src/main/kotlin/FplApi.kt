import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val FPL_BASE_URL = "https://fantasy.premierleague.com/api"

class FplApi {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun bootstrap(): JsonObject = getJson("$FPL_BASE_URL/bootstrap-static/")

    fun userClassicLeagues(entryId: Int): List<LeagueRef> {
        val response = getJson("$FPL_BASE_URL/entry/$entryId/")
        val classicLeagues = response.obj("leagues").arr("classic")
        return classicLeagues.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.int("id") ?: return@mapNotNull null
            val name = obj.str("name") ?: "League $id"
            LeagueRef(id = id, name = name)
        }
    }

    fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage {
        val response = getJson("$FPL_BASE_URL/leagues-classic/$leagueId/standings/?page_standings=$page")
        val standings = response.obj("standings")
        val results = standings.arr("results").mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val entryId = obj.int("entry") ?: return@mapNotNull null
            StandingRow(
                rank = obj.int("rank") ?: 0,
                entryId = entryId,
                entryName = obj.str("entry_name") ?: "Team $entryId",
                managerName = obj.str("player_name") ?: "Manager $entryId"
            )
        }

        return LeagueStandingsPage(
            results = results,
            hasNext = standings.bool("has_next") == true,
            totalEntries = response.obj("league").int("entries")
        )
    }

    fun eventLiveElementPoints(eventId: Int): Map<Int, Int> {
        val response = getJson("$FPL_BASE_URL/event/$eventId/live/")
        return response.arr("elements").mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val elementId = obj.int("id") ?: return@mapNotNull null
            val points = obj.obj("stats").int("total_points") ?: 0
            elementId to points
        }.toMap()
    }

    fun entryPicks(entryId: Int, eventId: Int): List<PickRow> {
        val response = getJson("$FPL_BASE_URL/entry/$entryId/event/$eventId/picks/")
        return response.arr("picks").mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val element = obj.int("element") ?: return@mapNotNull null
            PickRow(
                element = element,
                position = obj.int("position") ?: 0,
                multiplier = obj.int("multiplier") ?: 1,
                isCaptain = obj.bool("is_captain") == true,
                isViceCaptain = obj.bool("is_vice_captain") == true
            )
        }
    }

    private fun getJson(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", "fpl-live-leagues-cli")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("FPL API request failed (${response.statusCode()}): $url")
        }
        return json.parseToJsonElement(response.body()).jsonObject
    }
}
