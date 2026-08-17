import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject

const val MINI_LEAGUE_ENTRY_LIMIT = 50
const val LIVE_LEAGUE_CAP = 50

fun isMiniLeague(entryCount: Int, hasNext: Boolean): Boolean =
    entryCount < MINI_LEAGUE_ENTRY_LIMIT && !hasNext

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

class FplService(private val api: FplClient) {
    private val pool = Executors.newFixedThreadPool(8)

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
            val page = api.leagueStandingsPage(league.id, 1)
            val entryCount = page.totalEntries ?: page.results.size
            if (!isMiniLeague(entryCount, page.hasNext)) return@mapNotNull null
            MiniLeagueRef(id = league.id, name = league.name, entryCount = entryCount)
        }
        return MiniLeaguesResponse(entryId = entryId, gameweek = gameweek, leagues = leagues)
    }

    fun miniLeagueSummaries(entryId: Int): List<LeagueSummary> {
        return api.userClassicLeagues(entryId).mapNotNull { league ->
            val standingsPage = api.leagueStandingsPage(league.id, 1)
            val entryCount = standingsPage.totalEntries ?: standingsPage.results.size
            if (!isMiniLeague(entryCount, standingsPage.hasNext)) return@mapNotNull null
            LeagueSummary(
                id = league.id,
                name = league.name,
                entryCount = entryCount,
                standings = standingsPage.results
            )
        }
    }

    fun liveLeague(leagueId: Int, gameweek: Int?): LiveLeagueResponse =
        liveLeague(leagueId, gameweek, applyAutosubs = true)

    fun liveLeague(leagueId: Int, gameweek: Int?, applyAutosubs: Boolean): LiveLeagueResponse {
        val gw = gameweek ?: currentGameweek()
        val snapshot = liveSnapshot(gw)
        val paged = pageStandings(leagueId, LIVE_LEAGUE_CAP)
        val evaluated = evaluateStandings(paged.results, snapshot, applyAutosubs)
        val ranked = assignLiveRanks(evaluated)
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
        val raw = api.entryEvent(entryId, gameweek)
        val event = if (raw.isEmpty()) {
            EntryEventPicks(
                activeChip = null,
                automaticSubs = emptyList(),
                history = EntryEventHistory(gameweek, 0, 0, null, null, null, 0, 0),
                picks = api.entryPicks(entryId, gameweek)
            )
        } else {
            parseEntryEventPicks(raw)
        }
        val profile = try {
            parseEntryProfile(api.entry(entryId))
        } catch (_: Exception) {
            "" to ""
        }
        val squad = evaluateLiveSquad(
            picks = event.picks,
            history = event.history,
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
            players = squad.players,
            managerName = profile.first.ifBlank { null },
            teamName = profile.second.ifBlank { null },
            gwGross = squad.gwGross,
            gwNet = squad.gwNet,
            transferCost = event.history.eventTransfersCost,
            liveTotal = squad.liveTotal,
            officialTotal = event.history.totalPoints,
            officialGwPoints = event.history.points,
            overallRank = event.history.overallRank,
            overallRankLabel = "official",
            teamValue = tenthsToMillions(event.history.value),
            bank = tenthsToMillions(event.history.bank),
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
            players = picks.players
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
        val squad = evaluateLiveSquad(
            picks = event.picks,
            history = event.history,
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
            liveTotal = squad.liveTotal,
            gwNet = squad.gwNet,
            gwGross = squad.gwGross,
            transferCost = event.history.eventTransfersCost,
            playersRemaining = squad.playersRemaining,
            overallRank = event.history.overallRank,
            freeTransfers = null,
            teamValue = tenthsToMillions(event.history.value),
            bank = tenthsToMillions(event.history.bank),
            activeChip = chipLabel(event.activeChip),
            projectedBonus = squad.projectedBonus,
            confirmedBonus = squad.confirmedBonus,
            autosubsApplied = squad.autosubsApplied,
            captainStatus = squad.captainStatus
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
