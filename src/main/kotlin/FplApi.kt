import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

const val DEFAULT_FPL_BASE_URL = "https://fantasy.premierleague.com/api"

class FplApi(
    baseUrl: String = DEFAULT_FPL_BASE_URL,
    private val userAgent: String = "fpl-live-leagues/1.0"
) : FplClient {
    private val baseUrl = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun bootstrap(): JsonObject = getJson("$baseUrl/bootstrap-static/")

    override fun userClassicLeagues(entryId: Int): List<LeagueRef> {
        val response = getJson("$baseUrl/entry/$entryId/")
        val classicLeagues = response.obj("leagues").arr("classic")
        return classicLeagues.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.int("id") ?: return@mapNotNull null
            val name = obj.str("name") ?: "League $id"
            LeagueRef(id = id, name = name)
        }
    }

    override fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage {
        val response = getJson("$baseUrl/leagues-classic/$leagueId/standings/?page_standings=$page")
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

    override fun eventLiveElementPoints(eventId: Int): Map<Int, Int> {
        val response = getJson("$baseUrl/event/$eventId/live/")
        return response.arr("elements").mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val elementId = obj.int("id") ?: return@mapNotNull null
            val points = obj.obj("stats").int("total_points") ?: 0
            elementId to points
        }.toMap()
    }

    override fun entryPicks(entryId: Int, eventId: Int): List<PickRow> {
        val response = getJson("$baseUrl/entry/$entryId/event/$eventId/picks/")
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
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw FplApiException("FPL API request failed (${response.statusCode()}): $url", response.statusCode())
        }
        return json.parseToJsonElement(response.body()).jsonObject
    }
}
