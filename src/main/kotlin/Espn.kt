import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * ESPN's keyless public soccer feed (site.api.espn.com), used only to enrich the match view
 * with minute timelines, commentary, lineups and team stats. Every call is optional: any
 * failure returns null and the match view falls back to FPL-only data.
 */
interface EspnSource {
    /** Scoreboard for a UTC calendar date, yyyyMMdd. */
    fun scoreboard(date: String): JsonObject?
    fun summary(eventId: String): JsonObject?
}

const val ESPN_BASE_URL = "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1"

class HttpEspnSource(
    private val baseUrl: String = ESPN_BASE_URL,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timeout: Duration = Duration.ofSeconds(4)
) : EspnSource {
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(timeout)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private data class Entry(val value: JsonObject?, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var consecutiveFailures = 0
    @Volatile private var backoffUntil = 0L

    override fun scoreboard(date: String): JsonObject? =
        cached("sb:$date", { 60_000L }) { get("$baseUrl/scoreboard?dates=$date") }

    override fun summary(eventId: String): JsonObject? =
        cached("sum:$eventId", { body ->
            when (espnState(body?.obj("header")?.arr("competitions")?.firstOrNull() as? JsonObject)) {
                "post" -> 6 * 60 * 60_000L
                "pre" -> 5 * 60_000L
                else -> 30_000L
            }
        }) { get("$baseUrl/summary?event=$eventId") }

    /**
     * Fail fast: a failed key is not retried for 20s, and after 3 consecutive ESPN failures all
     * calls are skipped for 30s. Stale cached data is served meanwhile when available.
     */
    private fun cached(key: String, ttl: (JsonObject?) -> Long, load: () -> JsonObject?): JsonObject? {
        val now = clock()
        cache[key]?.let { if (it.expiresAt > now) return it.value }
        if (now < backoffUntil || now < (failedUntil[key] ?: 0L)) return cache[key]?.value
        val fresh = try {
            load()
        } catch (_: Exception) {
            failedUntil[key] = now + 20_000L
            consecutiveFailures += 1
            if (consecutiveFailures >= 3) backoffUntil = now + 30_000L
            return cache[key]?.value
        }
        consecutiveFailures = 0
        failedUntil.remove(key)
        cache[key] = Entry(fresh, now + ttl(fresh))
        return fresh
    }

    private fun get(url: String): JsonObject? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", "fpl-live-tracker/1.32")
            .timeout(timeout)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("ESPN HTTP ${response.statusCode()}")
        return json.parseToJsonElement(response.body()) as? JsonObject
    }
}

fun espnState(competition: JsonObject?): String? =
    competition?.obj("status")?.obj("type")?.str("state")

// ---------- name normalisation & mapping ----------

fun normalizeName(s: String?): String {
    if (s == null) return ""
    val stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    return stripped.lowercase()
        .replace("ø", "o").replace("æ", "ae").replace("ß", "ss").replace("ł", "l").replace("đ", "d").replace("ı", "i")
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

/** FPL team name -> ESPN displayName where they differ. */
val FPL_TO_ESPN_TEAM = mapOf(
    "Man City" to "Manchester City",
    "Man Utd" to "Manchester United",
    "Spurs" to "Tottenham Hotspur",
    "Nott'm Forest" to "Nottingham Forest",
    "Wolves" to "Wolverhampton Wanderers",
    "Brighton" to "Brighton & Hove Albion",
    "Newcastle" to "Newcastle United",
    "West Ham" to "West Ham United",
    "Leeds" to "Leeds United",
    "Leicester" to "Leicester City",
    "Ipswich" to "Ipswich Town",
    "Hull" to "Hull City",
    "Coventry" to "Coventry City",
    "Norwich" to "Norwich City",
    "Stoke" to "Stoke City",
    "Cardiff" to "Cardiff City",
    "Swansea" to "Swansea City",
    "Luton" to "Luton Town",
    "Sheffield Utd" to "Sheffield United",
    "Sheffield Weds" to "Sheffield Wednesday",
    "West Brom" to "West Bromwich Albion",
    "QPR" to "Queens Park Rangers",
    "Bournemouth" to "AFC Bournemouth",
    "Middlesbrough" to "Middlesbrough",
    "Birmingham" to "Birmingham City"
)

fun fplTeamKeys(name: String, shortName: String? = null): Set<String> {
    val keys = mutableSetOf(normalizeName(name))
    FPL_TO_ESPN_TEAM[name]?.let { keys += normalizeName(it) }
    return keys.filter { it.isNotBlank() }.toSet()
}

fun espnTeamKeys(team: JsonObject): Set<String> =
    listOf("displayName", "shortDisplayName", "name", "location")
        .mapNotNull { team.str(it) }
        .map { normalizeName(it) }
        .filter { it.isNotBlank() }
        .toSet()

private fun competitorFor(competition: JsonObject, side: String): JsonObject? =
    competition.arr("competitors").mapNotNull { it as? JsonObject }.firstOrNull { it.str("homeAway") == side }

/** Find the ESPN event id for an FPL fixture among scoreboard events (same home/away teams, kickoff within 36h). */
fun findEspnEvent(
    scoreboard: JsonObject,
    homeName: String,
    awayName: String,
    kickoff: String?
): String? {
    val homeKeys = fplTeamKeys(homeName)
    val awayKeys = fplTeamKeys(awayName)
    val ko = kickoff?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return scoreboard.arr("events").mapNotNull { it as? JsonObject }.firstOrNull { ev ->
        val comp = ev.arr("competitions").firstOrNull() as? JsonObject ?: return@firstOrNull false
        val h = competitorFor(comp, "home")?.obj("team") ?: return@firstOrNull false
        val a = competitorFor(comp, "away")?.obj("team") ?: return@firstOrNull false
        val teamsMatch = espnTeamKeys(h).any { it in homeKeys } && espnTeamKeys(a).any { it in awayKeys }
        val dateOk = ko == null || ev.str("date")?.let { parseEspnDate(it) }?.let {
            Duration.between(it, ko).abs() <= Duration.ofHours(36)
        } ?: true
        teamsMatch && dateOk
    }?.str("id")
}

fun parseEspnDate(s: String): Instant? =
    runCatching { Instant.parse(s) }.getOrNull()
        ?: runCatching { Instant.parse(s.replace(Regex("Z$"), ":00Z")) }.getOrNull()

fun espnDateKey(instant: Instant): String =
    DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(instant)

data class FplPlayerName(val id: Int, val teamId: Int, val webName: String, val firstName: String, val secondName: String)

/** Match an ESPN display name to an FPL element on the given team; null when not confidently unique. */
fun matchFplPlayer(espnName: String?, teamId: Int, players: List<FplPlayerName>): Int? {
    val target = normalizeName(espnName)
    if (target.isBlank()) return null
    val targetTokens = target.split(" ").toSet()
    val team = players.filter { it.teamId == teamId }
    fun full(p: FplPlayerName) = normalizeName("${p.firstName} ${p.secondName}")
    val levels: List<(FplPlayerName) -> Boolean> = listOf(
        { p -> full(p) == target },
        { p -> normalizeName(p.webName) == target },
        { p -> targetTokens.isNotEmpty() && full(p).split(" ").toSet().containsAll(targetTokens) },
        { p ->
            val web = normalizeName(p.webName).split(" ").filter { it.length > 1 }
            web.isNotEmpty() && targetTokens.containsAll(web)
        },
        { p -> normalizeName(p.secondName).split(" ").any { it.length > 2 && it == target.split(" ").last() } }
    )
    for (level in levels) {
        val hits = team.filter(level)
        if (hits.size == 1) return hits.single().id
        if (hits.size > 1) {
            val first = target.split(" ").first()
            val narrowed = hits.filter { normalizeName(it.firstName).split(" ").contains(first) }
            if (narrowed.size == 1) return narrowed.single().id
            return null
        }
    }
    return null
}

fun fplPlayerNames(bootstrap: JsonObject): List<FplPlayerName> =
    bootstrap.arr("elements").mapNotNull { it as? JsonObject }.mapNotNull { e ->
        FplPlayerName(
            id = e.int("id") ?: return@mapNotNull null,
            teamId = e.int("team") ?: return@mapNotNull null,
            webName = e.str("web_name").orEmpty(),
            firstName = e.str("first_name").orEmpty(),
            secondName = e.str("second_name").orEmpty()
        )
    }

// ---------- enrichment models ----------

data class TimelineItem(
    val minute: String,
    val type: String,
    val side: String?,
    val player: String?,
    val secondary: String?,
    val homeScore: Int?,
    val awayScore: Int?,
    val detail: String?,
    val elementId: Int? = null,
    val inSquad: Boolean = false,
    /** Side whose score increased (goals and own goals only). */
    val scoringSide: String? = null
)

data class CommentaryItem(val sequence: Int, val minute: String, val text: String)

data class EspnLineupPlayer(
    val name: String,
    val position: String,
    val jersey: String?,
    val starter: Boolean,
    val subbedIn: Boolean,
    val subbedOut: Boolean,
    val elementId: Int?,
    val fplPoints: Int?,
    val projectedBonus: Int,
    val inSquad: Boolean,
    val captain: Boolean,
    val viceCaptain: Boolean,
    val goals: Int,
    val assists: Int,
    val yellow: Int,
    val red: Int
)

data class EspnTeamLineup(val formation: String?, val starters: List<EspnLineupPlayer>, val subs: List<EspnLineupPlayer>)

data class EspnMatch(
    val eventId: String,
    val state: String,
    val statusLabel: String,
    val timeline: List<TimelineItem>,
    val commentary: List<CommentaryItem>,
    val stats: List<MatchStatRow>,
    val homeLineup: EspnTeamLineup?,
    val awayLineup: EspnTeamLineup?,
    val homeScore: Int?,
    val awayScore: Int?
)

// ---------- parsing ----------

private fun JsonObject.num(key: String): Double? =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

private val SCORE_IN_TEXT = Regex("""([^,!.]+?) (\d+), ([^,!.]+?) (\d+)\.""")

fun timelineType(espnType: String, text: String?): String? {
    val t = espnType.lowercase()
    return when {
        t.contains("own-goal") || t.contains("own goal") -> "own_goal"
        t.contains("penalty") && (t.contains("missed") || t.contains("saved")) -> "pen_missed"
        t.contains("penalty") && t.contains("scored") -> "penalty_goal"
        t.startsWith("goal") -> "goal"
        t == "yellow-card" -> "yellow"
        t.contains("red-card") || t == "yellow-red-card" -> "red"
        t == "substitution" -> "sub"
        t == "halftime" -> "ht"
        t == "end-regular-time" || t == "full-time" -> "ft"
        t == "kickoff" -> "ko"
        else -> null
    }
}

fun parseTimeline(
    summary: JsonObject,
    homeEspnTeamId: String?,
    sideTeamIds: Pair<Int, Int>,
    players: List<FplPlayerName>,
    squad: Set<Int>,
    finished: Boolean
): List<TimelineItem> {
    var h = 0
    var a = 0
    val items = mutableListOf<TimelineItem>()
    for (ev in summary.arr("keyEvents").mapNotNull { it as? JsonObject }) {
        val espnType = ev.obj("type").str("type") ?: continue
        val text = ev.str("text")
        val type = timelineType(espnType, text) ?: continue
        if (type == "ft" && !finished) continue
        val minute = ev.obj("clock").str("displayValue").orEmpty()
        val teamId = (ev["team"] as? JsonObject)?.str("id")
        val side = teamId?.let { if (it == homeEspnTeamId) "h" else "a" }
        val names = ev.arr("participants").mapNotNull { (it as? JsonObject)?.obj("athlete")?.str("displayName") }
        var player = names.getOrNull(0)
        var secondary = names.getOrNull(1)
        if (type == "goal" || type == "penalty_goal") {
            secondary = secondary ?: text?.let { Regex("""Assisted by ([^.]+)\.""").find(it)?.groupValues?.get(1) }
        }
        var homeScore: Int? = null
        var awayScore: Int? = null
        var scoringSide: String? = null
        if (type == "goal" || type == "penalty_goal" || type == "own_goal") {
            val prevH = h
            val parsed = text?.let { SCORE_IN_TEXT.find(it.substringAfter("!", it)) }
            if (parsed != null) {
                h = parsed.groupValues[2].toInt(); a = parsed.groupValues[4].toInt()
            } else {
                val scoring = if (type == "own_goal") (if (side == "h") "a" else "h") else side
                if (scoring == "h") h++ else if (scoring == "a") a++
            }
            homeScore = h; awayScore = a
            scoringSide = if (h > prevH) "h" else "a"
            if (type == "own_goal" && player == null) player = text?.let { Regex("""Own Goal by ([^,]+),""").find(it)?.groupValues?.get(1) }
        }
        if (type == "ht" || type == "ft") { homeScore = h; awayScore = a }
        val fplTeam = when (side) { "h" -> sideTeamIds.first; "a" -> sideTeamIds.second; else -> null }
        val elementId = if (fplTeam != null && player != null) matchFplPlayer(player, fplTeam, players) else null
        val detail = when {
            type == "goal" && espnType.contains("header") -> "Header"
            type == "red" && espnType.contains("yellow") -> "Second yellow"
            type == "pen_missed" -> if (espnType.contains("saved")) "Penalty saved" else "Penalty missed"
            type == "penalty_goal" -> "Penalty"
            else -> null
        }
        items += TimelineItem(minute, type, side, player, secondary, homeScore, awayScore, detail, elementId, elementId != null && elementId in squad, scoringSide)
    }
    return items
}

fun parseCommentary(summary: JsonObject): List<CommentaryItem> =
    summary.arr("commentary").mapNotNull { it as? JsonObject }.mapNotNull { c ->
        val text = c.str("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CommentaryItem(c.int("sequence") ?: 0, c.obj("time").str("displayValue").orEmpty(), text)
    }.sortedByDescending { it.sequence }

private val ESPN_STAT_ROWS = listOf(
    "possessionPct" to "Possession",
    "totalShots" to "Shots",
    "shotsOnTarget" to "Shots on target",
    "blockedShots" to "Blocked shots",
    "wonCorners" to "Corners",
    "foulsCommitted" to "Fouls",
    "offsides" to "Offsides",
    "totalPasses" to "Passes",
    "passPct" to "Pass accuracy",
    "totalCrosses" to "Crosses",
    "totalTackles" to "Tackles",
    "interceptions" to "Interceptions",
    "totalClearance" to "Clearances"
)

fun parseEspnStats(summary: JsonObject, homeEspnTeamId: String?): List<MatchStatRow> {
    val teams = summary.obj("boxscore").arr("teams").mapNotNull { it as? JsonObject }
    if (teams.size < 2) return emptyList()
    val home = teams.firstOrNull { it.str("homeAway") == "home" || it.obj("team").str("id") == homeEspnTeamId } ?: teams[0]
    val away = teams.firstOrNull { it !== home } ?: return emptyList()
    fun stats(t: JsonObject) = t.arr("statistics").mapNotNull { it as? JsonObject }.associate { (it.str("name") ?: "") to (it.str("displayValue") ?: "") }
    val hs = stats(home)
    val aws = stats(away)
    return ESPN_STAT_ROWS.mapNotNull { (key, label) ->
        val hv = hs[key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val av = aws[key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        fun fmt(v: String) = when (key) {
            "possessionPct" -> (v.toDoubleOrNull()?.let { "%.0f%%".format(it) } ?: v)
            "passPct" -> (v.toDoubleOrNull()?.let { if (it <= 1.0) "%.0f%%".format(it * 100) else "%.0f%%".format(it) } ?: v)
            else -> v
        }
        MatchStatRow(label, fmt(hv), fmt(av), source = "ESPN")
    }
}

fun parseEspnLineup(
    summary: JsonObject,
    side: String,
    fplTeamId: Int,
    players: List<FplPlayerName>,
    fplLines: Map<Int, MatchLineupPlayer>,
    squad: Map<Int, PickRow>
): EspnTeamLineup? {
    val roster = summary.arr("rosters").mapNotNull { it as? JsonObject }
        .firstOrNull { it.str("homeAway") == (if (side == "h") "home" else "away") } ?: return null
    val rows = roster.arr("roster").mapNotNull { it as? JsonObject }.mapNotNull { r ->
        val name = r.obj("athlete").str("displayName") ?: return@mapNotNull null
        val el = matchFplPlayer(name, fplTeamId, players)
        val line = el?.let { fplLines[it] }
        val pick = el?.let { squad[it] }
        Pair(r.str("formationPlace")?.toIntOrNull() ?: 99, EspnLineupPlayer(
            name = name,
            position = r.obj("position").str("abbreviation").orEmpty(),
            jersey = r.str("jersey"),
            starter = r.bool("starter") == true,
            subbedIn = r.bool("subbedIn") == true,
            subbedOut = r.bool("subbedOut") == true,
            elementId = el,
            fplPoints = line?.points,
            projectedBonus = line?.projectedBonus ?: 0,
            inSquad = pick != null,
            captain = pick?.isCaptain == true,
            viceCaptain = pick?.isViceCaptain == true,
            goals = line?.goals ?: 0,
            assists = line?.assists ?: 0,
            yellow = line?.yellow ?: 0,
            red = line?.red ?: 0
        ))
    }
    if (rows.isEmpty()) return null
    val starters = rows.filter { it.second.starter }.sortedBy { it.first }.map { it.second }
    val subs = rows.filter { !it.second.starter }.sortedWith(compareByDescending<Pair<Int, EspnLineupPlayer>> { it.second.subbedIn }).map { it.second }
    return EspnTeamLineup(roster.str("formation"), starters, subs)
}

/**
 * Locate and parse the ESPN event for an FPL fixture. Returns null (FPL-only view) when ESPN is
 * unavailable, the event can't be matched, or the payload is unusable.
 */
fun enrichWithEspn(
    espn: EspnSource,
    base: MatchDetail,
    bootstrap: JsonObject,
    squadPicks: List<PickRow>
): EspnMatch? {
    val ko = base.status.kickoffTime?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val dates = listOf(ko, ko.minus(Duration.ofDays(1)), ko.plus(Duration.ofDays(1))).map { espnDateKey(it) }.distinct()
    var eventId: String? = null
    for (d in dates) {
        val sb = espn.scoreboard(d) ?: continue
        eventId = findEspnEvent(sb, base.home.name, base.away.name, base.status.kickoffTime)
        if (eventId != null) break
    }
    if (eventId == null) return null
    val summary = espn.summary(eventId) ?: return null
    val comp = summary.obj("header").arr("competitions").firstOrNull() as? JsonObject ?: return null
    val homeComp = competitorFor(comp, "home")
    val awayComp = competitorFor(comp, "away")
    val homeId = homeComp?.obj("team")?.str("id")
    val status = comp.obj("status").obj("type")
    val state = status.str("state") ?: "pre"
    val label = when (state) {
        "pre" -> "Not started"
        else -> status.str("shortDetail")?.takeIf { it.isNotBlank() } ?: comp.obj("status").str("displayClock").orEmpty()
    }
    val players = fplPlayerNames(bootstrap)
    val squadById = squadPicks.associateBy { it.element }
    val fplLines = (base.homeLineup + base.awayLineup).associateBy { it.elementId }
    return EspnMatch(
        eventId = eventId,
        state = state,
        statusLabel = label,
        timeline = parseTimeline(summary, homeId, base.home.id to base.away.id, players, squadById.keys, state == "post"),
        commentary = parseCommentary(summary),
        stats = parseEspnStats(summary, homeId),
        homeLineup = parseEspnLineup(summary, "h", base.home.id, players, fplLines, squadById),
        awayLineup = parseEspnLineup(summary, "a", base.away.id, players, fplLines, squadById),
        homeScore = homeComp?.str("score")?.toIntOrNull(),
        awayScore = awayComp?.str("score")?.toIntOrNull()
    )
}
