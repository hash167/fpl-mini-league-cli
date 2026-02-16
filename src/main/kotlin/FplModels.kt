data class LeagueRef(
    val id: Int,
    val name: String
)

data class StandingRow(
    val rank: Int,
    val entryId: Int,
    val entryName: String,
    val managerName: String
)

data class LeagueStandingsPage(
    val results: List<StandingRow>,
    val hasNext: Boolean,
    val totalEntries: Int?
)

data class LeagueSummary(
    val id: Int,
    val name: String,
    val entryCount: Int,
    val standings: List<StandingRow>
)

data class PickRow(
    val element: Int,
    val position: Int,
    val multiplier: Int,
    val isCaptain: Boolean,
    val isViceCaptain: Boolean
)

data class PlayerRef(
    val name: String,
    val teamShortName: String
)

data class TeamLiveSummary(
    val standing: StandingRow,
    val livePoints: Int,
    val players: List<PlayerLiveRow>
)

data class PlayerLiveRow(
    val element: Int,
    val name: String,
    val team: String,
    val position: Int,
    val multiplier: Int,
    val rawPoints: Int,
    val contribution: Int,
    val captain: Boolean,
    val viceCaptain: Boolean
)
