const val ELEMENT_GK = 1
const val ELEMENT_DEF = 2
const val ELEMENT_MID = 3
const val ELEMENT_FWD = 4

const val MIN_DEF = 3
const val MIN_MID = 2
const val MIN_FWD = 1

fun gwNet(gross: Int, transferCost: Int): Int = gross - transferCost

/**
 * Official GW `points` are GROSS. Official `total_points` is already NET of
 * `event_transfers_cost` once that GW is on the books. Peeling the gross GW
 * score without restoring the hit double-counts the hit
 * (56 - 33 + 25 = 48 instead of 56).
 *
 * Prefer previous completed GW `total_points` + live gwNet. If history is
 * missing, derive the start-of-GW total from official fields.
 */
fun startOfGwSeasonTotal(officialTotal: Int, officialGwPoints: Int, transferCost: Int): Int =
    if (officialGwPoints > 0) officialTotal - officialGwPoints + transferCost
    else officialTotal

fun liveSeasonTotal(
    officialTotal: Int,
    officialGwPoints: Int,
    gwNetPoints: Int,
    transferCost: Int = 0,
    previousTotal: Int? = null
): Int {
    val startTotal = previousTotal ?: startOfGwSeasonTotal(officialTotal, officialGwPoints, transferCost)
    return startTotal + gwNetPoints
}

/** Positive = improved (green). Baseline is start-of-GW official overall, not current official. */
fun rankMovement(liveRank: Long?, baselineRank: Long?): Long? {
    if (liveRank == null || baselineRank == null) return null
    if (liveRank <= 0L || baselineRank <= 0L) return null
    return baselineRank - liveRank
}

fun startOfGwOverallRank(history: List<EntrySeasonSnapshot>, gameweek: Int): Int? {
    if (gameweek <= 1) return null
    return history.firstOrNull { it.event == gameweek - 1 }?.overallRank
}

fun previousEventTotal(history: List<EntrySeasonSnapshot>, gameweek: Int): Int? {
    if (gameweek <= 1) return 0
    return history.firstOrNull { it.event == gameweek - 1 }?.totalPoints
}

fun tenthsToMillions(value: Int?): Double? = value?.let { it / 10.0 }

fun chipLabel(chip: String?): String? = when (chip?.lowercase()) {
    "wildcard" -> "WC"
    "freehit" -> "FH"
    "bboost" -> "BB"
    "3xc" -> "TC"
    "manager", "assistant", "assistant_manager" -> "AM"
    null, "" -> null
    else -> chip.uppercase()
}

fun elementTypeName(type: Int): String = when (type) {
    ELEMENT_GK -> "GKP"
    ELEMENT_DEF -> "DEF"
    ELEMENT_MID -> "MID"
    ELEMENT_FWD -> "FWD"
    else -> "?"
}

/**
 * Standard FPL BPS bonus: players with minutes, highest BPS get 3/2/1.
 * Ties share the points for that place; later places are skipped.
 */
fun projectBpsBonus(candidates: List<BonusCandidate>): Map<Int, Int> {
    val eligible = candidates.filter { it.minutes > 0 }.sortedByDescending { it.bps }
    val result = mutableMapOf<Int, Int>()
    val places = listOf(3, 2, 1)
    var i = 0
    var placeIdx = 0
    while (i < eligible.size && placeIdx < places.size) {
        val bps = eligible[i].bps
        var j = i
        while (j < eligible.size && eligible[j].bps == bps) j++
        val award = places[placeIdx]
        for (k in i until j) {
            result[eligible[k].elementId] = award
        }
        placeIdx += (j - i)
        i = j
    }
    return result
}

fun resolvedBonus(officialBonus: Int, projectedBonus: Int): Int =
    if (officialBonus > 0) officialBonus else projectedBonus

fun extraProjectedBonus(officialBonus: Int, projectedBonus: Int): Int =
    if (officialBonus > 0) 0 else projectedBonus

fun effectivePlayerPoints(officialTotalPoints: Int, officialBonus: Int, projectedBonus: Int): Int =
    officialTotalPoints + extraProjectedBonus(officialBonus, projectedBonus)

/**
 * Per-fixture bonus. If any player already has official bonus, treat the
 * fixture as confirmed and use official values. Otherwise project from BPS.
 */
fun fixtureBonusAwards(candidates: List<BonusCandidate>): Pair<Map<Int, Int>, Boolean> {
    val confirmed = candidates.any { it.officialBonus > 0 }
    return if (confirmed) {
        candidates.associate { it.elementId to it.officialBonus } to true
    } else {
        projectBpsBonus(candidates) to false
    }
}

fun fixturesForTeam(teamId: Int, fixtures: List<FixtureInfo>): List<FixtureInfo> =
    fixtures.filter { it.teamH == teamId || it.teamA == teamId }

fun fixtureFinished(fixture: FixtureInfo): Boolean = fixture.finished

fun allFixturesFinished(teamFixtures: List<FixtureInfo>): Boolean =
    teamFixtures.isNotEmpty() && teamFixtures.all { fixtureFinished(it) }

fun anyFixtureStarted(teamFixtures: List<FixtureInfo>): Boolean =
    teamFixtures.any { it.started }

fun needsAutoSub(minutes: Int, teamFixtures: List<FixtureInfo>): Boolean {
    if (minutes > 0) return false
    if (teamFixtures.isEmpty()) return true
    return teamFixtures.all { fixtureFinished(it) }
}

fun benchCanComeOn(minutes: Int, teamFixtures: List<FixtureInfo>): Boolean {
    if (minutes > 0) return true
    return teamFixtures.any { it.started }
}

fun playerRemaining(teamFixtures: List<FixtureInfo>): Boolean {
    if (teamFixtures.isEmpty()) return false
    return teamFixtures.any { !fixtureFinished(it) }
}

fun captainStatus(minutes: Int, teamFixtures: List<FixtureInfo>): String {
    if (teamFixtures.isEmpty()) return "not started"
    val started = teamFixtures.any { it.started }
    val allDone = teamFixtures.all { fixtureFinished(it) }
    return when {
        allDone && minutes > 0 -> "played"
        allDone && minutes == 0 -> "did not play"
        started -> "playing"
        else -> "not started"
    }
}

fun fixtureStatus(teamFixtures: List<FixtureInfo>): String {
    if (teamFixtures.isEmpty()) return "none"
    val started = teamFixtures.any { it.started }
    val allDone = teamFixtures.all { fixtureFinished(it) }
    return when {
        allDone -> "finished"
        started -> "playing"
        else -> "not_started"
    }
}

fun isEligibleSwap(
    playingTypes: Map<Int, Int>,
    outElement: Int,
    inType: Int
): Boolean {
    val outType = playingTypes[outElement] ?: return false
    if (outType == ELEMENT_GK) return inType == ELEMENT_GK
    if (inType == ELEMENT_GK) return false
    val types = playingTypes.toMutableMap()
    types.remove(outElement)
    types[inElementPlaceholder(inType)] = inType
    val counts = types.values.groupingBy { it }.eachCount()
    val gk = counts[ELEMENT_GK] ?: 0
    val def = counts[ELEMENT_DEF] ?: 0
    val mid = counts[ELEMENT_MID] ?: 0
    val fwd = counts[ELEMENT_FWD] ?: 0
    return gk == 1 && def >= MIN_DEF && mid >= MIN_MID && fwd >= MIN_FWD && types.size == 11
}

private fun inElementPlaceholder(inType: Int): Int = -1_000 - inType

fun isEligibleSwapElements(
    playingTypes: Map<Int, Int>,
    outElement: Int,
    inElement: Int,
    inType: Int
): Boolean {
    val outType = playingTypes[outElement] ?: return false
    if (outType == ELEMENT_GK) return inType == ELEMENT_GK
    if (inType == ELEMENT_GK) return false
    val types = playingTypes.toMutableMap()
    types.remove(outElement)
    types[inElement] = inType
    val counts = types.values.groupingBy { it }.eachCount()
    val gk = counts[ELEMENT_GK] ?: 0
    val def = counts[ELEMENT_DEF] ?: 0
    val mid = counts[ELEMENT_MID] ?: 0
    val fwd = counts[ELEMENT_FWD] ?: 0
    return gk == 1 && def >= MIN_DEF && mid >= MIN_MID && fwd >= MIN_FWD && types.size == 11
}

/**
 * Apply official FPL automatic_subs first, then compute any remaining
 * prospective subs the way FPL does (bench order, GK-only-for-GK,
 * 3 DEF / 2 MID / 1 FWD minimums).
 */
fun computeAutoSubs(
    picks: List<AutoSubPick>,
    officialSubs: List<AppliedSub> = emptyList(),
    benchBoost: Boolean = false
): List<AppliedSub> {
    if (benchBoost) return emptyList()

    val starters = picks.filter { it.position in 1..11 }.sortedBy { it.position }
    val bench = picks.filter { it.position >= 12 }.sortedBy { it.position }
    val playing = starters.map { it.element }.toMutableSet()
    val usedBench = mutableSetOf<Int>()
    val applied = mutableListOf<AppliedSub>()

    fun playingTypes(): Map<Int, Int> =
        playing.associateWith { el -> picks.first { it.element == el }.elementType }

    for (sub in officialSubs) {
        if (sub.elementOut in playing && bench.any { it.element == sub.elementIn }) {
            playing.remove(sub.elementOut)
            playing.add(sub.elementIn)
            usedBench.add(sub.elementIn)
            applied.add(sub)
        }
    }

    for (starter in starters) {
        if (starter.element !in playing) continue
        if (!starter.fixturesFinished || starter.minutes > 0) continue
        val replacement = bench.firstOrNull { b ->
            b.element !in usedBench &&
                (b.minutes > 0 || b.fixtureStarted) &&
                isEligibleSwapElements(playingTypes(), starter.element, b.element, b.elementType)
        } ?: continue
        playing.remove(starter.element)
        playing.add(replacement.element)
        usedBench.add(replacement.element)
        applied.add(AppliedSub(elementOut = starter.element, elementIn = replacement.element))
    }
    return applied
}

fun playingXi(picks: List<AutoSubPick>, subs: List<AppliedSub>): Set<Int> {
    val xi = picks.filter { it.position in 1..11 }.map { it.element }.toMutableSet()
    for (sub in subs) {
        if (sub.elementOut in xi) {
            xi.remove(sub.elementOut)
            xi.add(sub.elementIn)
        }
    }
    return xi
}

fun armbandHolder(picks: List<AutoSubPick>, playing: Set<Int>): Int? {
    val cap = picks.firstOrNull { it.isCaptain } ?: return null
    val vc = picks.firstOrNull { it.isViceCaptain }
    val captainDoneBlank = cap.fixturesFinished && cap.minutes == 0
    return if (!captainDoneBlank) {
        cap.element
    } else if (vc != null && (vc.element in playing || !vc.fixturesFinished)) {
        vc.element
    } else {
        cap.element
    }
}

fun multipliersAfterSubs(
    picks: List<AutoSubPick>,
    subs: List<AppliedSub>,
    tripleCaptain: Boolean,
    benchBoost: Boolean
): Map<Int, Int> {
    val playing = if (benchBoost) picks.map { it.element }.toSet() else playingXi(picks, subs)
    val armband = armbandHolder(picks, playing)
    val capMult = if (tripleCaptain) 3 else 2
    return picks.associate { pick ->
        val onPitch = pick.element in playing
        val mult = when {
            !onPitch -> 0
            pick.element == armband -> capMult
            else -> 1
        }
        pick.element to mult
    }
}

data class ProjectedBonusSheet(
    val bonusByElement: Map<Int, Int>,
    val confirmedByElement: Map<Int, Int>,
    val extraByElement: Map<Int, Int>
)

fun buildBonusSheet(
    fixtures: List<FixtureInfo>,
    liveStats: Map<Int, LiveElementStats>,
    players: Map<Int, PlayerInfo>
): ProjectedBonusSheet {
    val bonus = mutableMapOf<Int, Int>()
    val confirmed = mutableMapOf<Int, Int>()
    val extra = mutableMapOf<Int, Int>()

    for (fixture in fixtures) {
        if (!fixture.started) continue
        val onFixture = players.values
            .filter { it.teamId == fixture.teamH || it.teamId == fixture.teamA }
            .map { info ->
                val stats = liveStats[info.id]
                val minutes = stats?.minutes ?: 0
                val bps = fixture.bpsByElement[info.id]
                    ?: stats?.bps
                    ?: 0
                val official = fixture.officialBonusByElement[info.id]
                    ?: stats?.officialBonusByFixture?.get(fixture.id)
                    ?: if ((stats?.explainFixtureIds?.size ?: 0) <= 1) (stats?.bonus ?: 0) else 0
                BonusCandidate(info.id, minutes, bps, official)
            }
        val (awards, isConfirmed) = fixtureBonusAwards(onFixture)
        for ((elementId, amount) in awards) {
            if (isConfirmed) {
                confirmed[elementId] = (confirmed[elementId] ?: 0) + amount
                bonus[elementId] = (bonus[elementId] ?: 0) + amount
            } else {
                extra[elementId] = (extra[elementId] ?: 0) + amount
                bonus[elementId] = (bonus[elementId] ?: 0) + amount
            }
        }
    }

    // Players whose official bonus is already in live stats but fixture stats missed them.
    for ((id, stats) in liveStats) {
        if (stats.bonus > 0 && (confirmed[id] ?: 0) == 0) {
            confirmed[id] = stats.bonus
            bonus[id] = (bonus[id] ?: 0).coerceAtLeast(stats.bonus)
            extra.remove(id)
        }
    }

    return ProjectedBonusSheet(bonus, confirmed, extra)
}

data class LiveSquadResult(
    val players: List<PlayerLiveRow>,
    val gwGross: Int,
    val gwNet: Int,
    val liveTotal: Int,
    val projectedBonus: Int,
    val confirmedBonus: Int,
    val autosubsApplied: Int,
    val playersRemaining: Int,
    val captainStatus: String,
    val appliedSubs: List<AppliedSub>
)

fun evaluateLiveSquad(
    picks: List<PickRow>,
    history: EntryEventHistory,
    activeChip: String?,
    officialSubs: List<AppliedSub>,
    players: Map<Int, PlayerInfo>,
    liveStats: Map<Int, LiveElementStats>,
    fixtures: List<FixtureInfo>,
    bonusSheet: ProjectedBonusSheet,
    applyAutosubs: Boolean
): LiveSquadResult {
    val benchBoost = activeChip == "bboost"
    val tripleCaptain = activeChip == "3xc"

    val autoPicks = picks.map { pick ->
        val info = players[pick.element]
        val stats = liveStats[pick.element]
        val teamFixtures = info?.let { fixturesForTeam(it.teamId, fixtures) } ?: emptyList()
        AutoSubPick(
            element = pick.element,
            position = pick.position,
            elementType = info?.elementType ?: 0,
            minutes = stats?.minutes ?: 0,
            fixturesFinished = fixtures.isNotEmpty() && (teamFixtures.isEmpty() || teamFixtures.all { fixtureFinished(it) }),
            fixtureStarted = teamFixtures.any { it.started },
            isCaptain = pick.isCaptain,
            isViceCaptain = pick.isViceCaptain
        )
    }

    val subs = if (!applyAutosubs || benchBoost) {
        emptyList()
    } else {
        computeAutoSubs(autoPicks, officialSubs, benchBoost = false)
    }
    val mults = multipliersAfterSubs(autoPicks, subs, tripleCaptain, benchBoost)
    val playing = if (benchBoost) picks.map { it.element }.toSet() else playingXi(autoPicks, subs)
    val subbedOut = subs.map { it.elementOut }.toSet()
    val subbedIn = subs.map { it.elementIn }.toSet()

    val rows = picks.map { pick ->
        val info = players[pick.element]
        val stats = liveStats[pick.element]
        val teamFixtures = info?.let { fixturesForTeam(it.teamId, fixtures) } ?: emptyList()
        val officialPts = stats?.totalPoints ?: 0
        val officialBonus = stats?.bonus ?: 0
        val extraBonus = bonusSheet.extraByElement[pick.element] ?: 0
        val confirmed = bonusSheet.confirmedByElement[pick.element] ?: officialBonus
        val effective = officialPts + extraBonus
        val multiplier = mults[pick.element] ?: 0
        PlayerLiveRow(
            element = pick.element,
            name = info?.name ?: "Unknown (${pick.element})",
            team = info?.teamShortName ?: "",
            position = pick.position,
            multiplier = multiplier,
            rawPoints = officialPts,
            contribution = liveContribution(effective, multiplier),
            captain = pick.isCaptain,
            viceCaptain = pick.isViceCaptain,
            webName = info?.webName ?: "",
            elementType = info?.elementType ?: 0,
            elementTypeName = elementTypeName(info?.elementType ?: 0),
            minutes = stats?.minutes ?: 0,
            confirmedBonus = confirmed,
            projectedBonus = extraBonus,
            effectivePoints = effective,
            autoSubIn = pick.element in subbedIn,
            autoSubOut = pick.element in subbedOut,
            onBench = pick.element !in playing,
            fixtureStatus = fixtureStatus(teamFixtures),
            nowCost = tenthsToMillions(info?.nowCost),
            costChangeEvent = tenthsToMillions(info?.costChangeEvent),
            costChangeStart = tenthsToMillions(info?.costChangeStart)
        )
    }.sortedBy { it.position }

    val gwGross = teamLivePoints(rows)
    val transferCost = history.eventTransfersCost
    val net = gwNet(gwGross, transferCost)
    val liveTotal = liveSeasonTotal(
        officialTotal = history.totalPoints,
        officialGwPoints = history.points,
        gwNetPoints = net,
        transferCost = transferCost,
        previousTotal = history.previousTotalPoints
    )

    val remaining = rows.count { !it.onBench && playerRemaining(players[it.element]?.let { info -> fixturesForTeam(info.teamId, fixtures) } ?: emptyList()) }

    val extraOnPitch = rows.filter { !it.onBench }.sumOf { it.projectedBonus * it.multiplier }
    val confirmedOnPitch = rows.filter { !it.onBench }.sumOf { it.confirmedBonus * it.multiplier }

    val cap = autoPicks.firstOrNull { it.isCaptain }
    val capFixtures = players[cap?.element]?.let { fixturesForTeam(it.teamId, fixtures) } ?: emptyList()
    val capStatus = if (cap == null) "" else captainStatus(cap.minutes, capFixtures)

    return LiveSquadResult(
        players = rows,
        gwGross = gwGross,
        gwNet = net,
        liveTotal = liveTotal,
        projectedBonus = extraOnPitch,
        confirmedBonus = confirmedOnPitch,
        autosubsApplied = subs.size,
        playersRemaining = remaining,
        captainStatus = capStatus,
        appliedSubs = subs
    )
}
