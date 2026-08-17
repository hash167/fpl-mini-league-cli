import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val DEFAULT_FPL_BASE_URL = "https://fantasy.premierleague.com/api"

class FplApi(
    baseUrl: String = DEFAULT_FPL_BASE_URL,
    private val userAgent: String = "fpl-live-leagues/1.1"
) : FplClient {
    private val baseUrl = baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun bootstrap(): JsonObject = getJsonObject("$baseUrl/bootstrap-static/")

    override fun entry(entryId: Int): JsonObject = getJsonObject("$baseUrl/entry/$entryId/")

    override fun entryHistory(entryId: Int): JsonObject =
        getJsonObject("$baseUrl/entry/$entryId/history/")

    override fun entryEvent(entryId: Int, eventId: Int): JsonObject =
        getJsonObjectOrEmpty("$baseUrl/entry/$entryId/event/$eventId/picks/")

    override fun eventLive(eventId: Int): JsonObject =
        getJsonObject("$baseUrl/event/$eventId/live/")

    override fun fixtures(eventId: Int): List<JsonObject> {
        val element = getJson("$baseUrl/fixtures/?event=$eventId")
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { it as? JsonObject }
    }

    override fun userClassicLeagues(entryId: Int): List<LeagueRef> {
        val response = entry(entryId)
        val classicLeagues = response.obj("leagues").arr("classic")
        return classicLeagues.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.int("id") ?: return@mapNotNull null
            val name = obj.str("name") ?: "League $id"
            LeagueRef(id = id, name = name)
        }
    }

    override fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage {
        val response = getJsonObject("$baseUrl/leagues-classic/$leagueId/standings/?page_standings=$page")
        val standings = response.obj("standings")
        val results = standings.arr("results").mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val entryId = obj.int("entry") ?: return@mapNotNull null
            StandingRow(
                rank = obj.int("rank") ?: 0,
                entryId = entryId,
                entryName = obj.str("entry_name") ?: "Team $entryId",
                managerName = obj.str("player_name") ?: "Manager $entryId",
                lastRank = obj.int("last_rank"),
                officialTotal = obj.int("total"),
                eventTotal = obj.int("event_total")
            )
        }

        return LeagueStandingsPage(
            results = results,
            hasNext = standings.bool("has_next") == true,
            totalEntries = response.obj("league").int("entries"),
            leagueName = response.obj("league").str("name")
        )
    }

    override fun eventLiveElementPoints(eventId: Int): Map<Int, Int> {
        return parseLiveElementPoints(eventLive(eventId))
    }

    override fun entryPicks(entryId: Int, eventId: Int): List<PickRow> {
        return parsePicks(entryEvent(entryId, eventId))
    }

    private fun getJsonObject(url: String): JsonObject {
        val element = getJson(url)
        return element as? JsonObject
            ?: throw FplApiException("FPL API did not return a JSON object: $url", 502)
    }

    /** GW picks 404 before a manager has a published squad (pre-season / yet to start). */
    private fun getJsonObjectOrEmpty(url: String): JsonObject {
        return try {
            getJsonObject(url)
        } catch (e: FplApiException) {
            if (e.statusCode == 404) JsonObject(emptyMap()) else throw e
        }
    }

    private fun getJson(url: String): JsonElement {
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
        return json.parseToJsonElement(response.body())
    }
}

fun parseLiveElementPoints(response: JsonObject): Map<Int, Int> {
    return response.arr("elements").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val elementId = obj.int("id") ?: return@mapNotNull null
        val points = obj.obj("stats").int("total_points") ?: 0
        elementId to points
    }.toMap()
}

fun parsePicks(response: JsonObject): List<PickRow> {
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

fun parseEntryEventPicks(response: JsonObject): EntryEventPicks {
    val history = response.obj("entry_history")
    val subs = response.arr("automatic_subs").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val inn = obj.int("element_in") ?: return@mapNotNull null
        val out = obj.int("element_out") ?: return@mapNotNull null
        AppliedSub(elementOut = out, elementIn = inn)
    }
    return EntryEventPicks(
        activeChip = response.str("active_chip"),
        automaticSubs = subs,
        history = EntryEventHistory(
            event = history.int("event") ?: 0,
            points = history.int("points") ?: 0,
            totalPoints = history.int("total_points") ?: 0,
            overallRank = history.int("overall_rank"),
            bank = history.int("bank"),
            value = history.int("value"),
            eventTransfers = history.int("event_transfers") ?: 0,
            eventTransfersCost = history.int("event_transfers_cost") ?: 0
        ),
        picks = parsePicks(response)
    )
}

fun parseLiveElementStats(response: JsonObject): Map<Int, LiveElementStats> {
    return response.arr("elements").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = obj.int("id") ?: return@mapNotNull null
        val stats = obj.obj("stats")
        val explain = obj.arr("explain")
        val fixtureIds = mutableListOf<Int>()
        val bonusByFixture = mutableMapOf<Int, Int>()
        explain.forEach blockLoop@{ block ->
            val blockObj = block as? JsonObject ?: return@blockLoop
            val fixtureId = blockObj.int("fixture") ?: return@blockLoop
            fixtureIds.add(fixtureId)
            blockObj.arr("stats").forEach statLoop@{ stat ->
                val statObj = stat as? JsonObject ?: return@statLoop
                if (statObj.str("identifier") == "bonus") {
                    bonusByFixture[fixtureId] = statObj.int("points") ?: statObj.int("value") ?: 0
                }
            }
        }
        id to LiveElementStats(
            id = id,
            minutes = stats.int("minutes") ?: 0,
            totalPoints = stats.int("total_points") ?: 0,
            bonus = stats.int("bonus") ?: 0,
            bps = stats.int("bps") ?: 0,
            explainFixtureIds = fixtureIds,
            officialBonusByFixture = bonusByFixture
        )
    }.toMap()
}

fun parseFixtures(raw: List<JsonObject>): List<FixtureInfo> {
    return raw.mapNotNull { obj ->
        val id = obj.int("id") ?: return@mapNotNull null
        val bps = mutableMapOf<Int, Int>()
        val bonus = mutableMapOf<Int, Int>()
        obj.arr("stats").forEach fixtureStat@{ stat ->
            val statObj = stat as? JsonObject ?: return@fixtureStat
            val identifier = statObj.str("identifier") ?: return@fixtureStat
            val target = when (identifier) {
                "bps" -> bps
                "bonus" -> bonus
                else -> return@fixtureStat
            }
            listOf("h", "a").forEach { side ->
                statObj.arr(side).forEach sideRow@{ row ->
                    val rowObj = row as? JsonObject ?: return@sideRow
                    val element = rowObj.int("element") ?: return@sideRow
                    val value = rowObj.int("value") ?: 0
                    target[element] = value
                }
            }
        }
        FixtureInfo(
            id = id,
            event = obj.int("event") ?: 0,
            teamH = obj.int("team_h") ?: 0,
            teamA = obj.int("team_a") ?: 0,
            teamHScore = obj.int("team_h_score"),
            teamAScore = obj.int("team_a_score"),
            started = obj.bool("started") == true,
            finished = obj.bool("finished") == true || obj.bool("finished_provisional") == true,
            minutes = obj.int("minutes") ?: 0,
            kickoffTime = obj.str("kickoff_time"),
            bpsByElement = bps,
            officialBonusByElement = bonus
        )
    }
}

fun parsePlayerInfo(bootstrap: JsonObject): Map<Int, PlayerInfo> {
    val teams = bootstrap.arr("teams")
        .mapNotNull { it as? JsonObject }
        .mapNotNull { team ->
            val id = team.int("id") ?: return@mapNotNull null
            val short = team.str("short_name") ?: return@mapNotNull null
            id to short
        }
        .toMap()

    return bootstrap.arr("elements")
        .mapNotNull { it as? JsonObject }
        .mapNotNull { player ->
            val id = player.int("id") ?: return@mapNotNull null
            val firstName = player.str("first_name").orEmpty()
            val secondName = player.str("second_name").orEmpty()
            val webName = player.str("web_name").orEmpty()
            val fullName = "$firstName $secondName".trim().ifBlank { webName }.ifBlank { "Player $id" }
            val teamId = player.int("team") ?: 0
            id to PlayerInfo(
                id = id,
                name = fullName,
                webName = webName.ifBlank { fullName },
                teamId = teamId,
                teamShortName = teams[teamId].orEmpty(),
                elementType = player.int("element_type") ?: 0
            )
        }
        .toMap()
}

fun parseTeamShortNames(bootstrap: JsonObject): Map<Int, String> {
    return bootstrap.arr("teams")
        .mapNotNull { it as? JsonObject }
        .mapNotNull { team ->
            val id = team.int("id") ?: return@mapNotNull null
            val short = team.str("short_name") ?: return@mapNotNull null
            id to short
        }
        .toMap()
}

fun parseEntryProfile(response: JsonObject): Pair<String, String> {
    val first = response.str("player_first_name").orEmpty()
    val last = response.str("player_last_name").orEmpty()
    val manager = "$first $last".trim().ifBlank { "Manager ${response.int("id") ?: ""}" }
    val team = response.str("name") ?: "Team ${response.int("id") ?: ""}"
    return manager to team
}

fun historyFromEntry(entry: JsonObject, gameweek: Int): EntryEventHistory =
    EntryEventHistory(
        event = gameweek,
        points = entry.int("summary_event_points") ?: 0,
        totalPoints = entry.int("summary_overall_points") ?: 0,
        overallRank = entry.int("summary_overall_rank"),
        bank = entry.int("last_deadline_bank"),
        value = entry.int("last_deadline_value"),
        eventTransfers = 0,
        eventTransfersCost = 0
    )
