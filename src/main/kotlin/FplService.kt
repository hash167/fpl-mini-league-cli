import kotlinx.serialization.json.JsonObject

const val MINI_LEAGUE_ENTRY_LIMIT = 50

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
            viceCaptain = pick.isViceCaptain
        )
    }.sortedBy { it.position }
}

class FplService(private val api: FplClient) {
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

    fun liveLeague(leagueId: Int, gameweek: Int?): LiveLeagueResponse {
        val gw = gameweek ?: currentGameweek()
        val page = api.leagueStandingsPage(leagueId, 1)
        val teams = liveTeamsForStandings(page.results, gw).map { summary ->
            TeamLiveStanding(
                rank = summary.standing.rank,
                entryId = summary.standing.entryId,
                entryName = summary.standing.entryName,
                managerName = summary.standing.managerName,
                livePoints = summary.livePoints
            )
        }
        return LiveLeagueResponse(leagueId = leagueId, gameweek = gw, teams = teams)
    }

    fun liveTeamsForStandings(standings: List<StandingRow>, gameweek: Int): List<TeamLiveSummary> {
        val liveElementPoints = api.eventLiveElementPoints(gameweek)
        val lookup = playerLookup(api.bootstrap())
        return standings.map { standing ->
            liveTeam(standing, gameweek, liveElementPoints, lookup)
        }
    }

    fun entryPicksLive(entryId: Int, gameweek: Int): EntryPicksResponse {
        val liveElementPoints = api.eventLiveElementPoints(gameweek)
        val lookup = playerLookup(api.bootstrap())
        val picks = api.entryPicks(entryId, gameweek)
        val players = buildPlayerLiveRows(picks, liveElementPoints, lookup)
        return EntryPicksResponse(
            entryId = entryId,
            gameweek = gameweek,
            livePoints = teamLivePoints(players),
            players = players
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
}
