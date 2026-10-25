import kotlinx.serialization.json.JsonObject

/** One scoring line from FPL's live `explain` (e.g. Goals 1 → 6 pts). */
data class BreakdownStat(val identifier: String, val label: String, val value: Int, val points: Int)

data class FixtureBreakdown(
    val fixtureId: Int,
    /** e.g. "EVE (H)". */
    val opponent: String,
    val score: String?,
    val started: Boolean,
    val finished: Boolean,
    val minutes: Int,
    val stats: List<BreakdownStat>,
    /** Points FPL counts for this fixture (sum of explain lines). */
    val points: Int,
    val bps: Int?,
    /** Bonus for this fixture and its status: confirmed | provisional (FPL, match not finished) | projected (from BPS) | none. */
    val bonus: Int,
    val bonusStatus: String,
    /** Projected bonus not yet in FPL's total (status projected). */
    val projectedExtra: Int
)

data class PlayerBreakdown(
    val elementId: Int,
    val webName: String,
    val team: String,
    val position: String,
    val gameweek: Int,
    val live: Boolean,
    val fixtures: List<FixtureBreakdown>,
    /** FPL's live total_points (what the price-watch board shows). */
    val officialTotal: Int,
    /** Projected BPS bonus not yet included by FPL. */
    val projectedBonus: Int,
    /** officialTotal + projectedBonus (what squad/league views count, before captain multiplier). */
    val liveTotal: Int,
    val bps: Int
)

private val STAT_LABELS = mapOf(
    "minutes" to "Minutes played",
    "goals_scored" to "Goals",
    "assists" to "Assists",
    "clean_sheets" to "Clean sheet",
    "goals_conceded" to "Goals conceded",
    "saves" to "Saves",
    "penalties_saved" to "Penalty saved",
    "penalties_missed" to "Penalty missed",
    "yellow_cards" to "Yellow card",
    "red_cards" to "Red card",
    "own_goals" to "Own goal",
    "bonus" to "Bonus",
    "defensive_contribution" to "Defensive contribution"
)

fun statLabel(identifier: String): String =
    STAT_LABELS[identifier] ?: identifier.split('_').joinToString(" ").replaceFirstChar { it.uppercase() }

/** Reads one element's explain blocks out of the (already cached) event-live tree; no copies of the tree. */
fun explainFor(eventLive: JsonObject, elementId: Int): Pair<JsonObject?, Map<Int, List<BreakdownStat>>> {
    val element = eventLive.arr("elements").firstOrNull { (it as? JsonObject)?.int("id") == elementId } as? JsonObject
        ?: return null to emptyMap()
    val byFixture = linkedMapOf<Int, List<BreakdownStat>>()
    element.arr("explain").forEach { block ->
        val b = block as? JsonObject ?: return@forEach
        val fixtureId = b.int("fixture") ?: return@forEach
        val lines = b.arr("stats").mapNotNull { st ->
            val o = st as? JsonObject ?: return@mapNotNull null
            val id = o.str("identifier") ?: return@mapNotNull null
            val pts = (o.int("points") ?: 0) + (o.int("points_modification") ?: 0)
            BreakdownStat(id, statLabel(id), o.int("value") ?: 0, pts)
        }
        byFixture[fixtureId] = (byFixture[fixtureId] ?: emptyList()) + lines
    }
    return element.obj("stats") to byFixture
}

fun buildPlayerBreakdown(
    elementId: Int,
    gameweek: Int,
    eventLive: JsonObject,
    fixtures: List<FixtureInfo>,
    players: Map<Int, PlayerInfo>,
    teamShortNames: Map<Int, String>,
    liveStats: Map<Int, LiveElementStats>
): PlayerBreakdown? {
    val info = players[elementId] ?: return null
    val (stats, explain) = explainFor(eventLive, elementId)
    val teamFixtures = fixtures.filter { it.teamH == info.teamId || it.teamA == info.teamId }
    val ids = (teamFixtures.map { it.id } + explain.keys).distinct()
    val byId = fixtures.associateBy { it.id }
    val rows = ids.mapNotNull { fid ->
        val fx = byId[fid]
        val lines = explain[fid].orEmpty()
        val explainBonus = lines.filter { it.identifier == "bonus" }.sumOf { it.points }
        val bps = fx?.bpsByElement?.get(elementId) ?: if (ids.size <= 1) stats?.int("bps") else null
        var projectedExtra = 0
        val (bonus, status) = when {
            explainBonus > 0 -> explainBonus to (if (fx?.finished == true) "confirmed" else "provisional")
            fx != null && fx.started -> {
                val (awards, confirmed) = fixtureBonusAwards(bonusCandidates(fx, liveStats, players))
                val award = awards[elementId] ?: 0
                when {
                    award == 0 -> 0 to "none"
                    confirmed -> award to "confirmed"
                    else -> { projectedExtra = award; award to "projected" }
                }
            }
            else -> 0 to "none"
        }
        val home = fx?.let { it.teamH == info.teamId } ?: true
        val oppId = fx?.let { if (home) it.teamA else it.teamH }
        FixtureBreakdown(
            fixtureId = fid,
            opponent = if (oppId == null) "Fixture $fid" else "${teamShortNames[oppId] ?: "T$oppId"} (${if (home) "H" else "A"})",
            score = fx?.takeIf { it.started && it.teamHScore != null && it.teamAScore != null }?.let { "${it.teamHScore}–${it.teamAScore}" },
            started = fx?.started ?: lines.isNotEmpty(),
            finished = fx?.finished ?: false,
            minutes = lines.firstOrNull { it.identifier == "minutes" }?.value ?: 0,
            stats = lines,
            points = lines.sumOf { it.points },
            bps = bps,
            bonus = bonus,
            bonusStatus = status,
            projectedExtra = projectedExtra
        )
    }
    val official = stats?.int("total_points") ?: rows.sumOf { it.points }
    // Same rule as the squad views (extraProjectedBonus): projected only counts while FPL shows no bonus.
    val officialBonus = stats?.int("bonus") ?: 0
    val projected = if (officialBonus > 0) 0 else rows.sumOf { it.projectedExtra }
    return PlayerBreakdown(
        elementId = elementId,
        webName = info.webName,
        team = info.teamShortName,
        position = elementTypeName(info.elementType),
        gameweek = gameweek,
        live = fixtures.any { it.started } && fixtures.any { !it.finished },
        fixtures = rows,
        officialTotal = official,
        projectedBonus = projected,
        liveTotal = official + projected,
        bps = stats?.int("bps") ?: rows.sumOf { it.bps ?: 0 }
    )
}
