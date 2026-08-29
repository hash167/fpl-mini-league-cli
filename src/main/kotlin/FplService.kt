import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject

const val MINI_LEAGUE_ENTRY_LIMIT = 50
const val LIVE_LEAGUE_CAP = 50

fun isMiniLeague(entryCount: Int, hasNext: Boolean): Boolean =
    entryCount < MINI_LEAGUE_ENTRY_LIMIT && !hasNext

fun isSystemLeague(leagueType: String?): Boolean =
    leagueType?.equals("s", ignoreCase = true) == true

fun liveContribution(rawPoints: Int, multiplier: Int): Int = rawPoints * multiplier

fun teamLivePoints(players: List<PlayerLiveRow>): Int = players.sumOf { it.contribution }

fun currentGameweekId(bootstrap: JsonObject): Int? {
    val events = bootstrap.arr("events").mapNotNull { it as? JsonObject }
    val current = events.firstOrNull { it.bool("is_current") == true }?.int("id")
    if (current != null) return current
    val next = events.firstOrNull { it.bool("is_next") == true }?.int("id")
    if (next != null) return next
    return events.maxOfOrNull { it.int("id") ?: 0 }?.takeIf { it > 0 }
}

fun playerLookup(bootstrap: JsonObject): Map<Int, PlayerRef> {
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
            val fallback = player.str("web_name").orEmpty()
            val fullName = "$firstName $secondName".trim().ifBlank { fallback }.ifBlank { "Player $id" }
            val teamId = player.int("team")
            val teamShortName = teamId?.let { teams[it] }.orEmpty()
            id to PlayerRef(name = fullName, teamShortName = teamShortName)
        }
        .toMap()
}

fun buildPlayerLiveRows(
    picks: List<PickRow>,
    liveElementPoints: Map<Int, Int>,
    players: Map<Int, PlayerRef>
): List<PlayerLiveRow> {
    return picks.map { pick ->
        val details = players[pick.element]
        val rawPoints = liveElementPoints[pick.element] ?: 0
        PlayerLiveRow(
            element = pick.element,
            name = details?.name ?: "Unknown (${pick.element})",
            team = details?.teamShortName ?: "",
            position = pick.position,
            multiplier = pick.multiplier,
            rawPoints = rawPoints,
            contribution = liveContribution(rawPoints, pick.multiplier),
            captain = pick.isCaptain,
            viceCaptain = pick.isViceCaptain,
            effectivePoints = rawPoints
        )
    }.sortedBy { it.position }
}

class FplService(
    private val api: FplClient,
    overallSnapshotPath: String? = null,
    pricesSnapshotPath: String? = DEFAULT_PRICES_SNAPSHOT_PATH,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val pool = Executors.newFixedThreadPool(8)
    private val overallCache = LiveOverallSampleCache(
        api = api,
        evaluate = { entryId, gameweek -> evaluateSampleEntry(entryId, gameweek) },
        snapshotPath = overallSnapshotPath
    )
    private val priceStore = OfficialPriceSnapshotStore(pricesSnapshotPath, clock)

    fun currentGameweek(): Int {
        return currentGameweekId(api.bootstrap())
            ?: throw IllegalStateException("Could not determine current gameweek from FPL bootstrap data.")
    }

    fun health(): HealthResponse {
        return try {
            val gameweek = currentGameweekId(api.bootstrap())
            HealthResponse(status = "ok", fpl = "ok", gameweek = gameweek)
        } catch (e: Exception) {
            HealthResponse(status = "ok", fpl = "unavailable", error = e.message)
        }
    }

    fun miniLeagues(entryId: Int): MiniLeaguesResponse {
        val gameweek = currentGameweek()
        val leagues = api.userClassicLeagues(entryId).mapNotNull { league ->
            val page = standingsIfMini(league) ?: return@mapNotNull null
            val entryCount = page.totalEntries ?: page.results.size
            MiniLeagueRef(id = league.id, name = league.name, entryCount = entryCount)
        }
        return MiniLeaguesResponse(entryId = entryId, gameweek = gameweek, leagues = leagues)
    }

    fun miniLeagueSummaries(entryId: Int): List<LeagueSummary> {
        return api.userClassicLeagues(entryId).mapNotNull { league ->
            val standingsPage = standingsIfMini(league) ?: return@mapNotNull null
            val entryCount = standingsPage.totalEntries ?: standingsPage.results.size
            LeagueSummary(
                id = league.id,
                name = league.name,
                entryCount = entryCount,
                standings = standingsPage.results
            )
        }
    }

    /** Skip system leagues; a standings 4xx/5xx/timeout is "not a mini league". */
    private fun standingsIfMini(league: LeagueRef): LeagueStandingsPage? {
        if (isSystemLeague(league.leagueType)) return null
        val page = try {
            api.leagueStandingsPage(league.id, 1)
        } catch (_: Exception) {
            return null
        }
        val entryCount = page.totalEntries ?: page.results.size
        if (!isMiniLeague(entryCount, page.hasNext)) return null
        return page
    }

    fun liveLeague(leagueId: Int, gameweek: Int?): LiveLeagueResponse =
        liveLeague(leagueId, gameweek, applyAutosubs = true)

    fun liveLeague(leagueId: Int, gameweek: Int?, applyAutosubs: Boolean): LiveLeagueResponse {
        val gw = gameweek ?: currentGameweek()
        val snapshot = liveSnapshot(gw)
        val paged = pageStandings(leagueId, LIVE_LEAGUE_CAP)
        val evaluated = evaluateStandings(paged.results, snapshot, applyAutosubs)
        overallCache.requestRefresh(gw)
        val estimate = overallCache.peek()
        val ranked = assignLiveRanks(evaluated).map { team ->
            team.copy(liveOverallRankEstimate = estimate?.rankFor(team.liveTotal))
        }
        return LiveLeagueResponse(
            leagueId = leagueId,
            gameweek = gw,
            teams = ranked,
            leagueName = paged.leagueName,
            autosubs = applyAutosubs,
            capped = paged.capped,
            capNote = if (paged.capped) {
                "Showing first ${ranked.size} of ${paged.entryCount ?: "many"} teams. Open a smaller league or page is capped at $LIVE_LEAGUE_CAP."
            } else null,
            entryCount = paged.entryCount,
            shown = ranked.size,
            live = snapshot.live,
            fixtures = snapshot.fixtureViews
        )
    }

    fun liveTeamsForStandings(standings: List<StandingRow>, gameweek: Int): List<TeamLiveSummary> {
        val liveElementPoints = api.eventLiveElementPoints(gameweek)
        val lookup = playerLookup(api.bootstrap())
        return standings.map { standing ->
            liveTeam(standing, gameweek, liveElementPoints, lookup)
        }
    }

    fun entryPicksLive(entryId: Int, gameweek: Int): EntryPicksResponse =
        entryPicksLive(entryId, gameweek, applyAutosubs = true)

    fun entryPicksLive(entryId: Int, gameweek: Int, applyAutosubs: Boolean): EntryPicksResponse {
        val snapshot = liveSnapshot(gameweek)
        val raw = try {
            api.entryEvent(entryId, gameweek)
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        val profileJson = try {
            api.entry(entryId)
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        val event = if (raw.isEmpty()) {
            EntryEventPicks(
                activeChip = null,
                automaticSubs = emptyList(),
                history = historyFromEntry(profileJson, gameweek),
                picks = try {
                    api.entryPicks(entryId, gameweek)
                } catch (_: Exception) {
                    emptyList()
                }
            )
        } else {
            parseEntryEventPicks(raw)
        }
        val profile = try {
            parseEntryProfile(profileJson)
        } catch (_: Exception) {
            "" to ""
        }
        val snapshots = entrySeasonHistory(entryId)
        val history = withPreviousTotal(event.history, gameweek, snapshots)
        val startRank = startOfGwOverallRank(snapshots, gameweek)
        val squad = evaluateLiveSquad(
            picks = event.picks,
            history = history,
            activeChip = event.activeChip,
            officialSubs = event.automaticSubs,
            players = snapshot.players,
            liveStats = snapshot.liveStats,
            fixtures = snapshot.fixtures,
            bonusSheet = snapshot.bonusSheet,
            applyAutosubs = applyAutosubs
        )
        return EntryPicksResponse(
            entryId = entryId,
            gameweek = gameweek,
            livePoints = squad.gwGross,
            players = attachEo(squad.players, overallCache.peek()),
            managerName = profile.first.ifBlank { null },
            teamName = profile.second.ifBlank { null },
            gwGross = squad.gwGross,
            gwNet = squad.gwNet,
            transferCost = history.eventTransfersCost,
            liveTotal = squad.liveTotal,
            officialTotal = history.totalPoints,
            officialGwPoints = history.points,
            overallRank = history.overallRank,
            overallRankLabel = "official",
            startOfGwOverallRank = startRank,
            teamValue = tenthsToMillions(history.value),
            bank = tenthsToMillions(history.bank),
            freeTransfers = null,
            activeChip = chipLabel(event.activeChip),
            projectedBonus = squad.projectedBonus,
            confirmedBonus = squad.confirmedBonus,
            autosubsApplied = squad.autosubsApplied,
            autosubs = applyAutosubs,
            playersRemaining = squad.playersRemaining,
            captainStatus = squad.captainStatus,
            fixtures = snapshot.fixtureViews
        )
    }

    fun entryLive(entryId: Int, gameweek: Int?, applyAutosubs: Boolean): EntryLiveResponse {
        val gw = gameweek ?: currentGameweek()
        val picks = entryPicksLive(entryId, gw, applyAutosubs)
        val snapshot = liveSnapshot(gw)
        overallCache.requestRefresh(gw)
        val estimate = overallCache.peek()
        val now = System.currentTimeMillis()
        val players = attachEo(picks.players, estimate)
        return EntryLiveResponse(
            entryId = entryId,
            gameweek = gw,
            managerName = picks.managerName ?: "Manager $entryId",
            teamName = picks.teamName ?: "Team $entryId",
            gwGross = picks.gwGross,
            gwNet = picks.gwNet,
            transferCost = picks.transferCost,
            liveTotal = picks.liveTotal,
            officialTotal = picks.officialTotal,
            officialGwPoints = picks.officialGwPoints,
            overallRank = picks.overallRank,
            overallRankLabel = "official",
            startOfGwOverallRank = picks.startOfGwOverallRank,
            teamValue = picks.teamValue,
            bank = picks.bank,
            freeTransfers = picks.freeTransfers,
            activeChip = picks.activeChip,
            projectedBonus = picks.projectedBonus,
            confirmedBonus = picks.confirmedBonus,
            autosubsApplied = picks.autosubsApplied,
            autosubs = applyAutosubs,
            playersRemaining = picks.playersRemaining,
            captainStatus = picks.captainStatus,
            live = snapshot.live,
            fixtures = snapshot.fixtureViews,
            players = players,
            liveOverallRankEstimate = estimate?.rankFor(picks.liveTotal),
            liveOverallEstimateAvailable = estimate?.available == true && estimate.rankFor(picks.liveTotal) != null,
            liveOverallSampleAgeSeconds = estimate?.ageSeconds(now),
            liveOverallSampleCount = estimate?.sampleCount
        )
    }

    fun liveBoard(gameweek: Int? = null): LiveBoardResponse {
        val gw = gameweek ?: currentGameweek()
        val snapshot = liveSnapshot(gw)
        val rows = snapshot.players.values.map { info ->
            val live = snapshot.liveStats[info.id]
            val tin = info.transfersInEvent ?: 0
            val tout = info.transfersOutEvent ?: 0
            LiveBoardPlayer(
                id = info.id,
                webName = info.webName,
                team = info.teamShortName,
                position = elementTypeName(info.elementType),
                minutes = live?.minutes ?: 0,
                livePoints = live?.totalPoints ?: 0,
                nowCost = info.nowCost ?: 0,
                costChangeEvent = info.costChangeEvent ?: 0,
                transfersInEvent = tin,
                transfersOutEvent = tout,
                netTransfers = tin - tout,
                selectedBy = info.selectedBy,
                priceEstimate = "stable",
                priceEstimateReason = ""
            )
        }
        return LiveBoardResponse(
            gameweek = gw,
            live = snapshot.live,
            fixtures = snapshot.fixtureViews,
            estimateNote = PRICE_ESTIMATE_NOTE,
            players = applyPriceEstimates(rows)
        )
    }


    fun estimatePriceRises(): PriceRiseEstimatesResponse {
        val bootstrap = api.bootstrap()
        val gw = currentGameweekId(bootstrap)
            ?: throw IllegalStateException("Could not determine current gameweek from FPL bootstrap data.")
        return PriceRiseEstimatesResponse(
            computedAt = OfficialPriceSnapshotStore.iso(clock()),
            gameweek = gw,
            estimateNote = PRICE_ESTIMATE_NOTE + " Estimate before tonight's official change.",
            rises = estimateLikelyRises(parsePlayerInfo(bootstrap).values)
        )
    }

    fun officialPrices(): OfficialPricesResponse = priceStore.latest()

    fun refreshOfficialPrices(): OfficialPricesResponse {
        val players = parsePlayerInfo(api.bootstrap()).values
        return priceStore.refresh(players)
    }

    fun liveOverallEstimateStatus(gameweek: Int? = null): LiveOverallEstimateStatus {
        val gw = try {
            gameweek ?: currentGameweek()
        } catch (_: Exception) {
            gameweek
        }
        if (gw != null) overallCache.requestRefresh(gw)
        return overallCache.status(gw)
    }

    private fun entrySeasonHistory(entryId: Int): List<EntrySeasonSnapshot> {
        return try {
            parseEntryHistoryCurrent(api.entryHistory(entryId))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun withPreviousTotal(history: EntryEventHistory, gameweek: Int, snapshots: List<EntrySeasonSnapshot>): EntryEventHistory {
        val previous = previousEventTotal(snapshots, gameweek)
        return if (previous == null) history else history.copy(previousTotalPoints = previous)
    }

    private fun attachEo(players: List<PlayerLiveRow>, estimate: LiveOverallSnapshot?): List<PlayerLiveRow> {
        if (estimate == null || !estimate.available) return players
        return players.map { row -> row.copy(eo = estimate.eoFor(row.element)) }
    }

    private fun evaluateSampleEntry(entryId: Int, gameweek: Int): SampledLive? {
        val snapshot = liveSnapshot(gameweek)
        val raw = try {
            api.entryEvent(entryId, gameweek)
        } catch (_: Exception) {
            return null
        }
        if (raw.isEmpty()) return null
        val event = parseEntryEventPicks(raw)
        val snapshots = entrySeasonHistory(entryId)
        val history = withPreviousTotal(event.history, gameweek, snapshots)
        val squad = evaluateLiveSquad(
            picks = event.picks,
            history = history,
            activeChip = event.activeChip,
            officialSubs = event.automaticSubs,
            players = snapshot.players,
            liveStats = snapshot.liveStats,
            fixtures = snapshot.fixtures,
            bonusSheet = snapshot.bonusSheet,
            applyAutosubs = true
        )
        return SampledLive(
            liveTotal = squad.liveTotal,
            multipliers = squad.players.associate { it.element to it.multiplier }
        )
    }

    private fun liveTeam(
        standing: StandingRow,
        gameweek: Int,
        liveElementPoints: Map<Int, Int>,
        lookup: Map<Int, PlayerRef>
    ): TeamLiveSummary {
        val picks = api.entryPicks(standing.entryId, gameweek)
        val players = buildPlayerLiveRows(picks, liveElementPoints, lookup)
        return TeamLiveSummary(
            standing = standing,
            livePoints = teamLivePoints(players),
            players = players
        )
    }

    private data class LiveSnapshot(
        val gameweek: Int,
        val players: Map<Int, PlayerInfo>,
        val liveStats: Map<Int, LiveElementStats>,
        val fixtures: List<FixtureInfo>,
        val bonusSheet: ProjectedBonusSheet,
        val fixtureViews: List<FixtureView>,
        val live: Boolean
    )

    private fun liveSnapshot(gameweek: Int): LiveSnapshot {
        val bootstrap = api.bootstrap()
        val players = parsePlayerInfo(bootstrap)
        val teams = parseTeamShortNames(bootstrap)
        val liveStats = parseLiveElementStats(api.eventLive(gameweek)).ifEmpty {
            api.eventLiveElementPoints(gameweek).mapValues { (id, pts) ->
                LiveElementStats(id = id, minutes = 0, totalPoints = pts, bonus = 0, bps = 0)
            }
        }
        val fixtures = parseFixtures(api.fixtures(gameweek))
        val bonusSheet = buildBonusSheet(fixtures, liveStats, players)
        val views = fixtures.map { fx ->
            FixtureView(
                id = fx.id,
                home = teams[fx.teamH] ?: "T${fx.teamH}",
                away = teams[fx.teamA] ?: "T${fx.teamA}",
                homeScore = fx.teamHScore,
                awayScore = fx.teamAScore,
                minutes = fx.minutes,
                started = fx.started,
                finished = fx.finished,
                kickoffTime = fx.kickoffTime
            )
        }
        val live = fixtures.any { it.started } && fixtures.any { !it.finished }
        return LiveSnapshot(gameweek, players, liveStats, fixtures, bonusSheet, views, live)
    }

    private data class PagedStandings(
        val results: List<StandingRow>,
        val hasNext: Boolean,
        val entryCount: Int?,
        val leagueName: String?,
        val capped: Boolean
    )

    private fun pageStandings(leagueId: Int, cap: Int): PagedStandings {
        val collected = mutableListOf<StandingRow>()
        var page = 1
        var hasNext = true
        var entryCount: Int? = null
        var leagueName: String? = null
        while (hasNext && collected.size < cap) {
            val batch = api.leagueStandingsPage(leagueId, page)
            leagueName = batch.leagueName ?: leagueName
            entryCount = batch.totalEntries ?: entryCount
            collected.addAll(batch.results)
            hasNext = batch.hasNext
            page += 1
            if (page > 10) break
        }
        val trimmed = collected.take(cap)
        val capped = hasNext || collected.size > cap || ((entryCount ?: 0) > cap)
        return PagedStandings(
            results = trimmed,
            hasNext = hasNext,
            entryCount = entryCount ?: collected.size,
            leagueName = leagueName,
            capped = capped
        )
    }

    private fun evaluateStandings(
        standings: List<StandingRow>,
        snapshot: LiveSnapshot,
        applyAutosubs: Boolean
    ): List<TeamLiveStanding> {
        if (standings.isEmpty()) return emptyList()
        val futures = standings.map { standing ->
            pool.submit(Callable {
                evaluateStanding(standing, snapshot, applyAutosubs)
            })
        }
        return futures.map { it.get() }
    }

    private fun evaluateStanding(
        standing: StandingRow,
        snapshot: LiveSnapshot,
        applyAutosubs: Boolean
    ): TeamLiveStanding {
        val raw = try {
            api.entryEvent(standing.entryId, snapshot.gameweek)
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        val event = if (raw.isEmpty()) {
            EntryEventPicks(
                activeChip = null,
                automaticSubs = emptyList(),
                history = EntryEventHistory(
                    event = snapshot.gameweek,
                    points = standing.eventTotal ?: 0,
                    totalPoints = standing.officialTotal ?: 0,
                    overallRank = null,
                    bank = null,
                    value = null,
                    eventTransfers = 0,
                    eventTransfersCost = 0
                ),
                picks = try {
                    api.entryPicks(standing.entryId, snapshot.gameweek)
                } catch (_: Exception) {
                    emptyList()
                }
            )
        } else {
            parseEntryEventPicks(raw)
        }
        val snapshots = entrySeasonHistory(standing.entryId)
        val history = withPreviousTotal(event.history, snapshot.gameweek, snapshots)
        val startRank = startOfGwOverallRank(snapshots, snapshot.gameweek)
        val squad = evaluateLiveSquad(
            picks = event.picks,
            history = history,
            activeChip = event.activeChip,
            officialSubs = event.automaticSubs,
            players = snapshot.players,
            liveStats = snapshot.liveStats,
            fixtures = snapshot.fixtures,
            bonusSheet = snapshot.bonusSheet,
            applyAutosubs = applyAutosubs
        )
        return TeamLiveStanding(
            rank = standing.rank,
            entryId = standing.entryId,
            entryName = standing.entryName,
            managerName = standing.managerName,
            livePoints = squad.gwGross,
            liveRank = 0,
            officialRank = standing.rank,
            lastRank = standing.lastRank,
            liveTotal = squad.liveTotal,
            gwNet = squad.gwNet,
            gwGross = squad.gwGross,
            transferCost = history.eventTransfersCost,
            playersRemaining = squad.playersRemaining,
            overallRank = history.overallRank,
            overallRankLabel = "official",
            startOfGwOverallRank = startRank,
            freeTransfers = null,
            teamValue = tenthsToMillions(history.value),
            bank = tenthsToMillions(history.bank),
            activeChip = chipLabel(event.activeChip),
            projectedBonus = squad.projectedBonus,
            confirmedBonus = squad.confirmedBonus,
            autosubsApplied = squad.autosubsApplied,
            captainStatus = squad.captainStatus,
            players = squad.players
        )
    }

    private fun assignLiveRanks(teams: List<TeamLiveStanding>): List<TeamLiveStanding> {
        val ordered = teams.sortedWith(
            compareByDescending<TeamLiveStanding> { it.liveTotal }
                .thenByDescending { it.gwNet }
                .thenBy { it.officialRank }
        )
        return ordered.mapIndexed { index, team ->
            team.copy(liveRank = index + 1)
        }
    }
}
