import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Live Premier League table ("as it stands"): baseline standings + in-progress scores. */
data class LeagueTableRow(
    val position: Int,
    val preMatchPosition: Int,
    val movement: Int,
    val teamId: Int,
    val name: String,
    val shortName: String,
    val badgeUrl: String?,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val goalDifference: Int,
    val points: Int,
    val live: Boolean,
    val liveScore: String?,
    val inMatch: Boolean
)

data class LeagueTable(val source: String, val rows: List<LeagueTableRow>, val note: String?)

private data class Tally(var p: Int = 0, var w: Int = 0, var d: Int = 0, var l: Int = 0, var gf: Int = 0, var ga: Int = 0, var pts: Int = 0) {
    fun add(f: Int, a: Int) {
        p++; gf += f; ga += a
        when { f > a -> { w++; pts += 3 }; f == a -> { d++; pts += 1 }; else -> l++ }
    }
    fun copyOf() = copy()
}

private fun JsonObject.statNum(name: String): Int? =
    arr("stats").mapNotNull { it as? JsonObject }.firstOrNull { it.str("name") == name }?.let { s ->
        (s["value"] as? JsonPrimitive)?.doubleOrNull?.toInt()
            ?: (s["displayValue"] as? JsonPrimitive)?.contentOrNull?.replace("+", "")?.toIntOrNull()
    }

/** Parse ESPN standings into FPL team id -> tally; null unless every FPL team is mapped. */
fun parseEspnStandings(standings: JsonObject?, teams: List<JsonObject>): Map<Int, Pair<Int, IntArray>>? {
    if (standings == null) return null
    val entries = standings.arr("children").mapNotNull { it as? JsonObject }
        .flatMap { it.obj("standings").arr("entries").mapNotNull { e -> e as? JsonObject } }
        .ifEmpty { standings.obj("standings").arr("entries").mapNotNull { it as? JsonObject } }
    if (entries.isEmpty()) return null
    val out = mutableMapOf<Int, Pair<Int, IntArray>>()
    for (t in teams) {
        val id = t.int("id") ?: continue
        val keys = fplTeamKeys(t.str("name").orEmpty())
        val e = entries.firstOrNull { espnTeamKeys(it.obj("team")).any { k -> k in keys } } ?: return null
        val gp = e.statNum("gamesPlayed") ?: return null
        // [w, d, l, gf, ga, pts]
        out[id] = gp to intArrayOf(
            e.statNum("wins") ?: 0, e.statNum("ties") ?: 0, e.statNum("losses") ?: 0,
            e.statNum("pointsFor") ?: 0, e.statNum("pointsAgainst") ?: 0, e.statNum("points") ?: 0
        )
    }
    return out
}

fun buildLeagueTable(
    fixtures: List<JsonObject>,
    bootstrap: JsonObject,
    espnStandings: JsonObject?,
    matchTeams: Pair<Int, Int>,
    scoreOverrides: Map<Int, Pair<Int, Int>> = emptyMap()
): LeagueTable {
    val teams = bootstrap.arr("teams").mapNotNull { it as? JsonObject }.filter { it.int("id") != null }
    val tallies = teams.associate { it.int("id")!! to Tally() }
    val liveFixtures = mutableListOf<Triple<JsonObject, Int, Int>>()
    for (f in fixtures) {
        val h = f.int("team_h") ?: continue
        val a = f.int("team_a") ?: continue
        val done = f.bool("finished") == true || f.bool("finished_provisional") == true
        val started = f.bool("started") == true
        val override = f.int("id")?.let { scoreOverrides[it] }
        val hs = override?.first ?: f.int("team_h_score")
        val aws = override?.second ?: f.int("team_a_score")
        if (done && hs != null && aws != null) {
            tallies[h]?.add(hs, aws); tallies[a]?.add(aws, hs)
        } else if (started && !done) {
            liveFixtures += Triple(f, hs ?: 0, aws ?: 0)
        }
    }

    // Baseline: ESPN standings when they agree with FPL's completed results, else FPL-derived.
    val espn = parseEspnStandings(espnStandings, teams)
    val consistent = espn != null && espn.all { (id, v) -> v.first == (tallies[id]?.p ?: -1) }
    val base: Map<Int, Tally> = if (consistent) {
        espn!!.mapValues { (_, v) ->
            val (gp, s) = v
            Tally(gp, s[0], s[1], s[2], s[3], s[4], s[5])
        }
    } else tallies.mapValues { it.value.copyOf() }
    val source = if (consistent) "ESPN" else "FPL"
    val note = when {
        consistent -> null
        espn != null -> "ESPN standings not yet updated for every result; table derived from FPL results."
        else -> "ESPN standings unavailable; table derived from FPL results."
    }

    val names = teams.associate { it.int("id")!! to it }
    fun order(m: Map<Int, Tally>): List<Int> = m.keys.sortedWith(
        compareByDescending<Int> { m.getValue(it).pts }
            .thenByDescending { m.getValue(it).gf - m.getValue(it).ga }
            .thenByDescending { m.getValue(it).gf }
            .thenBy { names[it]?.str("name").orEmpty() }
    )
    val preOrder = order(base)
    val live = base.mapValues { it.value.copyOf() }
    val liveScore = mutableMapOf<Int, String>()
    for ((f, hs, aws) in liveFixtures) {
        val h = f.int("team_h")!!; val a = f.int("team_a")!!
        live[h]?.add(hs, aws); live[a]?.add(aws, hs)
        liveScore[h] = "$hs–$aws v ${names[a]?.str("short_name") ?: ""}"
        liveScore[a] = "$aws–$hs @ ${names[h]?.str("short_name") ?: ""}"
    }
    val liveOrder = order(live)
    val rows = liveOrder.mapIndexed { i, id ->
        val t = live.getValue(id)
        val pre = preOrder.indexOf(id) + 1
        val team = names[id]
        LeagueTableRow(
            position = i + 1,
            preMatchPosition = pre,
            movement = pre - (i + 1),
            teamId = id,
            name = team?.str("name").orEmpty(),
            shortName = team?.str("short_name").orEmpty(),
            badgeUrl = badgeUrl(team?.int("code")),
            played = t.p, won = t.w, drawn = t.d, lost = t.l,
            goalsFor = t.gf, goalsAgainst = t.ga, goalDifference = t.gf - t.ga, points = t.pts,
            live = liveScore.containsKey(id),
            liveScore = liveScore[id],
            inMatch = id == matchTeams.first || id == matchTeams.second
        )
    }
    return LeagueTable(source, rows, note)
}
