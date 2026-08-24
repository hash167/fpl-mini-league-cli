data class LeagueRef(
    val id: Int,
    val name: String,
    val leagueType: String = "x"
)

data class StandingRow(
    val rank: Int,
    val entryId: Int,
    val entryName: String,
    val managerName: String,
    val lastRank: Int? = null,
    val officialTotal: Int? = null,
    val eventTotal: Int? = null
)

data class LeagueStandingsPage(
    val results: List<StandingRow>,
    val hasNext: Boolean,
    val totalEntries: Int?,
    val leagueName: String? = null
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

data class PlayerInfo(
    val id: Int,
    val name: String,
    val webName: String,
    val teamId: Int,
    val teamShortName: String,
    val elementType: Int,
    val nowCost: Int? = null,
    val costChangeEvent: Int? = null,
    val costChangeStart: Int? = null,
    val transfersInEvent: Int? = null,
    val transfersOutEvent: Int? = null,
    val selectedBy: String? = null
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
    val viceCaptain: Boolean,
    val webName: String = "",
    val elementType: Int = 0,
    val elementTypeName: String = "",
    val minutes: Int = 0,
    val confirmedBonus: Int = 0,
    val projectedBonus: Int = 0,
    val effectivePoints: Int = 0,
    val autoSubIn: Boolean = false,
    val autoSubOut: Boolean = false,
    val onBench: Boolean = false,
    val fixtureStatus: String = "",
    val eo: Double? = null,
    val nowCost: Double? = null,
    val costChangeEvent: Double? = null,
    val costChangeStart: Double? = null
)

data class MiniLeaguesResponse(
    val entryId: Int,
    val gameweek: Int,
    val leagues: List<MiniLeagueRef>
)

data class MiniLeagueRef(
    val id: Int,
    val name: String,
    val entryCount: Int
)

data class LiveLeagueResponse(
    val leagueId: Int,
    val gameweek: Int,
    val teams: List<TeamLiveStanding>,
    val leagueName: String? = null,
    val autosubs: Boolean = true,
    val capped: Boolean = false,
    val capNote: String? = null,
    val entryCount: Int? = null,
    val shown: Int = 0,
    val live: Boolean = false,
    val fixtures: List<FixtureView> = emptyList()
)

data class TeamLiveStanding(
    val rank: Int,
    val entryId: Int,
    val entryName: String,
    val managerName: String,
    val livePoints: Int,
    val liveRank: Int = 0,
    val officialRank: Int = 0,
    val liveTotal: Int = 0,
    val gwNet: Int = 0,
    val gwGross: Int = 0,
    val transferCost: Int = 0,
    val playersRemaining: Int = 0,
    val overallRank: Int? = null,
    val liveOverallRankEstimate: Long? = null,
    val freeTransfers: Int? = null,
    val teamValue: Double? = null,
    val bank: Double? = null,
    val activeChip: String? = null,
    val projectedBonus: Int = 0,
    val confirmedBonus: Int = 0,
    val autosubsApplied: Int = 0,
    val captainStatus: String = "",
    val players: List<PlayerLiveRow> = emptyList()
)

data class EntryPicksResponse(
    val entryId: Int,
    val gameweek: Int,
    val livePoints: Int,
    val players: List<PlayerLiveRow>,
    val managerName: String? = null,
    val teamName: String? = null,
    val gwGross: Int = 0,
    val gwNet: Int = 0,
    val transferCost: Int = 0,
    val liveTotal: Int = 0,
    val officialTotal: Int = 0,
    val officialGwPoints: Int = 0,
    val overallRank: Int? = null,
    val overallRankLabel: String = "official",
    val teamValue: Double? = null,
    val bank: Double? = null,
    val freeTransfers: Int? = null,
    val activeChip: String? = null,
    val projectedBonus: Int = 0,
    val confirmedBonus: Int = 0,
    val autosubsApplied: Int = 0,
    val autosubs: Boolean = true,
    val playersRemaining: Int = 0,
    val captainStatus: String = "",
    val fixtures: List<FixtureView> = emptyList()
)

data class EntryLiveResponse(
    val entryId: Int,
    val gameweek: Int,
    val managerName: String,
    val teamName: String,
    val gwGross: Int,
    val gwNet: Int,
    val transferCost: Int,
    val liveTotal: Int,
    val officialTotal: Int,
    val officialGwPoints: Int,
    val overallRank: Int?,
    val overallRankLabel: String,
    val teamValue: Double?,
    val bank: Double?,
    val freeTransfers: Int?,
    val activeChip: String?,
    val projectedBonus: Int,
    val confirmedBonus: Int,
    val autosubsApplied: Int,
    val autosubs: Boolean,
    val playersRemaining: Int,
    val captainStatus: String,
    val live: Boolean,
    val fixtures: List<FixtureView>,
    val players: List<PlayerLiveRow>,
    val liveOverallRankEstimate: Long? = null,
    val liveOverallEstimateAvailable: Boolean = false,
    val liveOverallSampleAgeSeconds: Long? = null,
    val liveOverallSampleCount: Int? = null
)

data class FixtureView(
    val id: Int,
    val home: String,
    val away: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val minutes: Int,
    val started: Boolean,
    val finished: Boolean,
    val kickoffTime: String?
)

data class HealthResponse(
    val status: String,
    val fpl: String,
    val gameweek: Int? = null,
    val error: String? = null
)

data class LiveElementStats(
    val id: Int,
    val minutes: Int,
    val totalPoints: Int,
    val bonus: Int,
    val bps: Int,
    val explainFixtureIds: List<Int> = emptyList(),
    val officialBonusByFixture: Map<Int, Int> = emptyMap()
)

data class FixtureInfo(
    val id: Int,
    val event: Int,
    val teamH: Int,
    val teamA: Int,
    val teamHScore: Int?,
    val teamAScore: Int?,
    val started: Boolean,
    val finished: Boolean,
    val minutes: Int,
    val kickoffTime: String?,
    val bpsByElement: Map<Int, Int> = emptyMap(),
    val officialBonusByElement: Map<Int, Int> = emptyMap()
)

data class EntryEventPicks(
    val activeChip: String?,
    val automaticSubs: List<AppliedSub>,
    val history: EntryEventHistory,
    val picks: List<PickRow>
)

data class EntryEventHistory(
    val event: Int,
    val points: Int,
    val totalPoints: Int,
    val overallRank: Int?,
    val bank: Int?,
    val value: Int?,
    val eventTransfers: Int,
    val eventTransfersCost: Int
)

data class AppliedSub(
    val elementOut: Int,
    val elementIn: Int
)

data class BonusCandidate(
    val elementId: Int,
    val minutes: Int,
    val bps: Int,
    val officialBonus: Int
)

data class AutoSubPick(
    val element: Int,
    val position: Int,
    val elementType: Int,
    val minutes: Int,
    val fixturesFinished: Boolean,
    val fixtureStarted: Boolean,
    val isCaptain: Boolean,
    val isViceCaptain: Boolean
)

data class LiveOverallEstimateStatus(
    val warm: Boolean,
    val available: Boolean,
    val sampleAgeSeconds: Long?,
    val sampleCount: Int,
    val targetSampleCount: Int,
    val totalPlayers: Int?,
    val gameweek: Int?,
    val refreshing: Boolean,
    val bands: List<LiveOverallBandStatus>,
    val note: String
)

data class LiveOverallBandStatus(
    val index: Int,
    val startRank: Int,
    val endRank: Int,
    val nB: Int,
    val nSlice: Int
)


data class LiveBoardResponse(
    val gameweek: Int,
    val live: Boolean,
    val fixtures: List<FixtureView> = emptyList(),
    val estimateNote: String,
    val players: List<LiveBoardPlayer>
)

data class LiveBoardPlayer(
    val id: Int,
    val webName: String,
    val team: String,
    val position: String,
    val minutes: Int,
    val livePoints: Int,
    val nowCost: Int,
    val costChangeEvent: Int,
    val transfersInEvent: Int,
    val transfersOutEvent: Int,
    val netTransfers: Int,
    val selectedBy: String?,
    val priceEstimate: String,
    val priceEstimateReason: String
)


data class PriceRiseEstimate(
    val id: Int,
    val webName: String,
    val team: String,
    val position: String,
    val nowCost: Int,
    val nowPounds: String,
    val netTransfers: Int,
    val transfersInEvent: Int,
    val transfersOutEvent: Int,
    val selectedBy: String?,
    val priceEstimate: String,
    val priceEstimateReason: String
)

data class PriceRiseEstimatesResponse(
    val computedAt: String,
    val gameweek: Int,
    val estimateNote: String,
    val rises: List<PriceRiseEstimate>
)
