import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * FotMob-style match view built only from the official FPL API
 * (fixtures/?event=N, event/N/live/, bootstrap-static, entry picks).
 *
 * FPL publishes no per-event minute timestamps, so events are grouped by
 * type and never placed on a minute timeline.
 */
const val MATCH_MINUTES_NOTE =
    "FPL doesn't publish when events happened, so facts are grouped by type rather than shown in match order."

fun badgeUrl(teamCode: Int?): String? =
    teamCode?.let { "https://resources.premierleague.com/premierleague/badges/70/t$it.png" }

data class MatchTeam(
    val id: Int,
    val name: String,
    val shortName: String,
    val code: Int?,
    val badgeUrl: String?,
    val score: Int?
)

data class MatchStatus(
    val phase: String,
    val label: String,
    val minutes: Int,
    val kickoffTime: String?,
    val bonusConfirmed: Boolean,
    /** "confirmed" (match finished), "provisional" (FPL's live bonus table) or "projected" (our BPS projection). */
    val bonusSource: String = "projected"
)

data class MatchFact(
    val type: String,
    val side: String,
    val scoringSide: String?,
    val elementId: Int,
    val player: String,
    val count: Int,
    val inSquad: Boolean
)

data class MatchBonusRow(
    val elementId: Int,
    val player: String,
    val side: String,
    val bps: Int,
    val bonus: Int,
    val inSquad: Boolean
)

data class MatchLineupPlayer(
    val elementId: Int,
    val player: String,
    val position: String,
    val minutes: Int,
    val points: Int,
    val projectedBonus: Int,
    val started: Boolean?,
    val goals: Int,
    val assists: Int,
    val yellow: Int,
    val red: Int,
    val inSquad: Boolean,
    val captain: Boolean,
    val viceCaptain: Boolean
)

data class MatchStatRow(val label: String, val home: String, val away: String, val source: String = "FPL")

data class MatchDetail(
    val fixtureId: Int,
    val gameweek: Int,
    val home: MatchTeam,
    val away: MatchTeam,
    val status: MatchStatus,
    val live: Boolean,
    val facts: List<MatchFact>,
    val bonus: List<MatchBonusRow>,
    val topBps: List<MatchBonusRow>,
    val homeLineup: List<MatchLineupPlayer>,
    val awayLineup: List<MatchLineupPlayer>,
    val stats: List<MatchStatRow>,
    val squadEntryId: Int?,
    val minutesAvailable: Boolean = false,
    val minutesNote: String = MATCH_MINUTES_NOTE,
    /** "ESPN" when the ESPN feed enriched this view, otherwise "FPL only". */
    val source: String = "FPL only",
    val espn: EspnMatch? = null
)

/** Merge ESPN data into the FPL view; FPL stays the source of truth for points and bonus. */
fun applyEspn(base: MatchDetail, espn: EspnMatch?): MatchDetail {
    if (espn == null) return base
    val phase = when (espn.state) { "in" -> "live"; "post" -> "ft"; else -> base.status.phase }
    val label = if (espn.state == "pre") base.status.label else espn.statusLabel
    return base.copy(
        status = base.status.copy(phase = phase, label = label),
        live = base.live || espn.state == "in",
        stats = espn.stats + base.stats,
        minutesAvailable = espn.timeline.isNotEmpty() || espn.state != "pre",
        minutesNote = "Minutes, lineups, commentary and team stats from ESPN; FPL points and bonus from the official FPL API.",
        source = "ESPN",
        espn = espn
    )
}

fun matchStatus(started: Boolean, finishedProvisional: Boolean, finished: Boolean, minutes: Int, kickoff: String?): MatchStatus {
    val (phase, label) = when {
        finished -> "ft" to "FT"
        finishedProvisional -> "ft" to "FT"
        !started -> "upcoming" to "Not started"
        minutes >= 90 -> "live" to "90'+"
        minutes == 45 -> "live" to "45'+ / HT"
        else -> "live" to "$minutes'"
    }
    return MatchStatus(phase, label, minutes, kickoff, bonusConfirmed = finished)
}

private val POSITIONS = mapOf(1 to "GKP", 2 to "DEF", 3 to "MID", 4 to "FWD", 5 to "MGR")

private fun JsonObject.dbl(key: String): Double? =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

private fun fixtureStat(fixture: JsonObject, identifier: String): Pair<Map<Int, Int>, Map<Int, Int>> {
    val stat = fixture.arr("stats").mapNotNull { it as? JsonObject }
        .firstOrNull { it.str("identifier") == identifier } ?: return emptyMap<Int, Int>() to emptyMap()
    fun side(key: String) = stat.arr(key).mapNotNull { it as? JsonObject }
        .mapNotNull { row -> row.int("element")?.let { el -> el to (row.int("value") ?: 0) } }
        .toMap()
    return side("h") to side("a")
}

fun buildMatchDetail(
    fixture: JsonObject,
    gameweek: Int,
    bootstrap: JsonObject,
    eventLive: JsonObject,
    gwFixtures: List<JsonObject>,
    squad: List<PickRow> = emptyList(),
    squadEntryId: Int? = null
): MatchDetail {
    val fixtureId = fixture.int("id") ?: 0
    val teamH = fixture.int("team_h") ?: 0
    val teamA = fixture.int("team_a") ?: 0
    val teams = bootstrap.arr("teams").mapNotNull { it as? JsonObject }.associateBy { it.int("id") ?: -1 }
    fun team(id: Int, score: Int?): MatchTeam {
        val t = teams[id]
        val code = t?.int("code")
        return MatchTeam(id, t?.str("name") ?: "Team $id", t?.str("short_name") ?: "T$id", code, badgeUrl(code), score)
    }
    val elements = bootstrap.arr("elements").mapNotNull { it as? JsonObject }.associateBy { it.int("id") ?: -1 }
    fun name(el: Int) = elements[el]?.str("web_name") ?: "Player $el"
    val squadById = squad.associateBy { it.element }
    fun inSquad(el: Int) = squadById.containsKey(el)

    val started = fixture.bool("started") == true
    val finished = fixture.bool("finished") == true
    val finishedProvisional = fixture.bool("finished_provisional") == true
    val minutes = fixture.int("minutes") ?: 0
    val status = matchStatus(started, finishedProvisional, finished, minutes, fixture.str("kickoff_time"))

    // Facts, grouped by type (no minute data exists in FPL).
    val facts = mutableListOf<MatchFact>()
    val factTypes = listOf("goals_scored", "own_goals", "assists", "penalties_saved", "penalties_missed", "yellow_cards", "red_cards")
    for (type in factTypes) {
        val (h, a) = fixtureStat(fixture, type)
        for ((side, rows) in listOf("h" to h, "a" to a)) {
            rows.filterValues { it > 0 }.forEach { (el, count) ->
                val scoring = when (type) {
                    "goals_scored" -> side
                    "own_goals" -> if (side == "h") "a" else "h"
                    else -> null
                }
                facts += MatchFact(type, side, scoring, el, name(el), count, inSquad(el))
            }
        }
    }

    // Per-fixture player lines from event/{gw}/live explain blocks.
    data class Line(val el: Int, val side: String, val minutes: Int, val points: Int, val stats: Map<String, Int>, val gwStats: JsonObject)
    val lines = eventLive.arr("elements").mapNotNull { it as? JsonObject }.mapNotNull { e ->
        val el = e.int("id") ?: return@mapNotNull null
        val block = e.arr("explain").mapNotNull { it as? JsonObject }.firstOrNull { it.int("fixture") == fixtureId }
            ?: return@mapNotNull null
        val statRows = block.arr("stats").mapNotNull { it as? JsonObject }
        val values = statRows.associate { (it.str("identifier") ?: "") to (it.int("value") ?: 0) }
        val points = statRows.sumOf { it.int("points") ?: 0 }
        val teamId = elements[el]?.int("team")
        val side = when (teamId) { teamH -> "h"; teamA -> "a"; else -> return@mapNotNull null }
        Line(el, side, values["minutes"] ?: 0, points, values, e.obj("stats"))
    }

    // Bonus: official once FPL publishes it in the fixture, otherwise projected from BPS.
    val (bpsH, bpsA) = fixtureStat(fixture, "bps")
    val (bonusH, bonusA) = fixtureStat(fixture, "bonus")
    val officialBonus = bonusH + bonusA
    val minutesByEl = lines.associate { it.el to it.minutes }
    val bpsRows = (bpsH.map { Triple(it.key, "h", it.value) } + bpsA.map { Triple(it.key, "a", it.value) })
        .sortedByDescending { it.third }
    val projected = if (officialBonus.isNotEmpty()) officialBonus else projectBpsBonus(
        bpsRows.map { (el, _, bps) -> BonusCandidate(el, minutesByEl[el] ?: if (started) 1 else 0, bps, 0) }
    )
    val fplBonus = officialBonus.isNotEmpty()
    fun bonusRow(el: Int, side: String, bps: Int) = MatchBonusRow(el, name(el), side, bps, projected[el] ?: 0, inSquad(el))
    val bonus = bpsRows.filter { (projected[it.first] ?: 0) > 0 }.map { (el, side, bps) -> bonusRow(el, side, bps) }
        .sortedWith(compareByDescending<MatchBonusRow> { it.bonus }.thenByDescending { it.bps })
    val topBps = bpsRows.take(6).map { (el, side, bps) -> bonusRow(el, side, bps) }

    // A team with two fixtures in the GW has aggregated starts/xG, so skip those there.
    val fixtureCount = gwFixtures.flatMap { listOf(it.int("team_h"), it.int("team_a")) }.groupingBy { it }.eachCount()
    fun single(teamId: Int) = (fixtureCount[teamId] ?: 1) <= 1

    fun lineup(side: String): List<MatchLineupPlayer> {
        val teamId = if (side == "h") teamH else teamA
        return lines.filter { it.side == side && it.minutes > 0 }.map { l ->
            val pick = squadById[l.el]
            val extra = if (fplBonus || l.stats.containsKey("bonus")) 0 else (projected[l.el] ?: 0)
            MatchLineupPlayer(
                elementId = l.el,
                player = name(l.el),
                position = POSITIONS[elements[l.el]?.int("element_type")] ?: "",
                minutes = l.minutes,
                points = l.points,
                projectedBonus = extra,
                started = if (single(teamId)) (l.gwStats.int("starts") ?: 0) > 0 else null,
                goals = l.stats["goals_scored"] ?: 0,
                assists = l.stats["assists"] ?: 0,
                yellow = l.stats["yellow_cards"] ?: 0,
                red = l.stats["red_cards"] ?: 0,
                inSquad = pick != null,
                captain = pick?.isCaptain == true,
                viceCaptain = pick?.isViceCaptain == true
            )
        }.sortedWith(
            compareBy<MatchLineupPlayer> { if (it.started == false) 1 else 0 }
                .thenBy { listOf("GKP", "DEF", "MID", "FWD", "MGR").indexOf(it.position) }
                .thenByDescending { it.minutes }
        )
    }

    fun sumSide(identifier: String): Pair<Int, Int> {
        val (h, a) = fixtureStat(fixture, identifier)
        return h.values.sum() to a.values.sum()
    }
    val stats = mutableListOf<MatchStatRow>()
    fun row(label: String, p: Pair<Int, Int>) { stats += MatchStatRow(label, p.first.toString(), p.second.toString()) }
    row("Goals", (fixture.int("team_h_score") ?: 0) to (fixture.int("team_a_score") ?: 0))
    if (single(teamH) && single(teamA)) {
        fun xg(side: String, key: String) = lines.filter { it.side == side && it.minutes > 0 }.sumOf { it.gwStats.dbl(key) ?: 0.0 }
        stats += MatchStatRow("Expected goals (xG)", "%.2f".format(xg("h", "expected_goals")), "%.2f".format(xg("a", "expected_goals")))
        stats += MatchStatRow("Expected assists (xA)", "%.2f".format(xg("h", "expected_assists")), "%.2f".format(xg("a", "expected_assists")))
    }
    row("Assists", sumSide("assists"))
    row("Saves", sumSide("saves"))
    row("Yellow cards", sumSide("yellow_cards"))
    row("Red cards", sumSide("red_cards"))
    row("Own goals", sumSide("own_goals"))
    row("Penalties saved", sumSide("penalties_saved"))
    row("Penalties missed", sumSide("penalties_missed"))
    row("Defensive contributions", sumSide("defensive_contribution"))
    row("Total BPS", sumSide("bps"))
    row("Players used", lines.count { it.side == "h" && it.minutes > 0 } to lines.count { it.side == "a" && it.minutes > 0 })

    return MatchDetail(
        fixtureId = fixtureId,
        gameweek = gameweek,
        home = team(teamH, fixture.int("team_h_score")),
        away = team(teamA, fixture.int("team_a_score")),
        status = status.copy(
            bonusConfirmed = finished && fplBonus,
            bonusSource = when { finished && fplBonus -> "confirmed"; fplBonus -> "provisional"; else -> "projected" }
        ),
        live = started && !finished && !finishedProvisional,
        facts = facts,
        bonus = bonus,
        topBps = topBps,
        homeLineup = lineup("h"),
        awayLineup = lineup("a"),
        stats = stats,
        squadEntryId = squadEntryId
    )
}
