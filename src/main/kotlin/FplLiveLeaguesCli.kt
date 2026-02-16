import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonObject
import org.jline.terminal.TerminalBuilder

class FplLiveLeaguesCli : CliktCommand(
    name = "fpl-live-leagues",
    help = "Show your mini leagues (<50 teams) and live points for the current gameweek."
) {
    private val teamId by argument(help = "Your FPL entry/team id").int()

    override fun run() {
        val api = FplApi()
        val bootstrap = api.bootstrap()
        val currentGameweek = currentGameweekId(bootstrap)
            ?: throw IllegalStateException("Could not determine current gameweek from FPL bootstrap data.")

        val leagues = api.userClassicLeagues(teamId)
        if (leagues.isEmpty()) {
            echo("No classic leagues found for team id $teamId.")
            return
        }

        val miniLeagues = leagues.mapNotNull { league ->
            val standingsPage = api.leagueStandingsPage(league.id, 1)
            val entryCount = standingsPage.totalEntries ?: standingsPage.results.size
            val isMiniLeague = entryCount < 50 && !standingsPage.hasNext
            if (!isMiniLeague) return@mapNotNull null
            LeagueSummary(
                id = league.id,
                name = league.name,
                entryCount = entryCount,
                standings = standingsPage.results
            )
        }

        if (miniLeagues.isEmpty()) {
            echo("No mini leagues (<50 teams) found for team id $teamId.")
            return
        }

        leagueLoop@ while (true) {
            val leagueSelection = selectWithNavigation(
                prompt = "Select a mini league",
                options = miniLeagues,
                allowQuit = true,
                labelProvider = { "${it.name} (${it.entryCount} teams)" }
            )
            when (leagueSelection) {
                is MenuSelection.Quit, MenuSelection.Back -> return
                is MenuSelection.Selected -> {
                    val selectedLeague = leagueSelection.value
                    echo("")
                    echo("Loading live points for ${selectedLeague.name} (GW$currentGameweek)...")
                    val liveElementPoints = api.eventLiveElementPoints(currentGameweek)
                    val playerLookup = playerLookup(bootstrap)

                    val teamsWithLive = selectedLeague.standings.map { standing ->
                        val picks = api.entryPicks(standing.entryId, currentGameweek)
                        val playerRows = picks.map { pick ->
                            val details = playerLookup[pick.element]
                            val rawPoints = liveElementPoints[pick.element] ?: 0
                            val contribution = rawPoints * pick.multiplier
                            PlayerLiveRow(
                                element = pick.element,
                                name = details?.name ?: "Unknown (${pick.element})",
                                team = details?.teamShortName ?: "",
                                position = pick.position,
                                multiplier = pick.multiplier,
                                rawPoints = rawPoints,
                                contribution = contribution,
                                captain = pick.isCaptain,
                                viceCaptain = pick.isViceCaptain
                            )
                        }.sortedBy { it.position }

                        TeamLiveSummary(
                            standing = standing,
                            livePoints = playerRows.sumOf { it.contribution },
                            players = playerRows
                        )
                    }

                    while (true) {
                        val selectedTeam = selectWithNavigation(
                            prompt = "Select a team in ${selectedLeague.name}",
                            options = teamsWithLive,
                            allowBack = true,
                            allowQuit = true,
                            labelProvider = {
                                "#${it.standing.rank} ${it.standing.managerName} | ${it.standing.entryName} | live ${it.livePoints}"
                            }
                        )
                        when (selectedTeam) {
                            MenuSelection.Back -> continue@leagueLoop
                            is MenuSelection.Quit -> return
                            is MenuSelection.Selected -> {
                                showTeamDetails(selectedTeam.value)
                                echo("")
                                echo("Press Enter to go back to teams, Left Arrow for leagues, or q to quit.")
                                when (readPostDetailsAction()) {
                                    PostDetailsAction.BACK_TO_TEAMS -> {}
                                    PostDetailsAction.BACK_TO_LEAGUES -> continue@leagueLoop
                                    PostDetailsAction.QUIT -> return
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun showTeamDetails(selectedTeam: TeamLiveSummary) {
    println("")
    println("${selectedTeam.standing.managerName} - ${selectedTeam.standing.entryName}")
    println("Live points: ${selectedTeam.livePoints}")
    println("Players:")
    selectedTeam.players.forEach { player ->
        val captainTag = when {
            player.captain -> " (C)"
            player.viceCaptain -> " (VC)"
            else -> ""
        }
        val teamTag = if (player.team.isBlank()) "" else " ${player.team}"
        println(
            "${player.position.toString().padStart(2, ' ')}. " +
                "${player.name}$teamTag$captainTag | " +
                "raw ${player.rawPoints} x${player.multiplier} = ${player.contribution}"
        )
    }
}

internal fun currentGameweekId(bootstrap: JsonObject): Int? {
    val events = bootstrap.arr("events").mapNotNull { it as? JsonObject }
    val current = events.firstOrNull { it.bool("is_current") == true }?.int("id")
    if (current != null) return current
    val next = events.firstOrNull { it.bool("is_next") == true }?.int("id")
    if (next != null) return next
    return events.maxOfOrNull { it.int("id") ?: 0 }?.takeIf { it > 0 }
}

internal fun playerLookup(bootstrap: JsonObject): Map<Int, PlayerRef> {
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

private fun <T> selectWithNavigation(
    prompt: String,
    options: List<T>,
    allowBack: Boolean = false,
    allowQuit: Boolean = false,
    labelProvider: (T) -> String
): MenuSelection<T> {
    if (options.isEmpty()) return MenuSelection.Back

    try {
        val terminal = TerminalBuilder.builder()
            .system(true)
            .build()
        val previousAttributes = terminal.enterRawMode()
        try {
            var index = 0
            while (true) {
                clearScreen()
                println(prompt)
                println(menuHint(allowBack, allowQuit))
                println("")
                options.forEachIndexed { i, item ->
                    val marker = if (i == index) "> " else "  "
                    println(marker + labelProvider(item))
                }
                System.out.flush()

                when (readKeyPress(terminal)) {
                    KeyPress.UP -> index = if (index == 0) options.lastIndex else index - 1
                    KeyPress.DOWN -> index = if (index == options.lastIndex) 0 else index + 1
                    KeyPress.ENTER -> return MenuSelection.Selected(options[index])
                    KeyPress.LEFT -> if (allowBack) return MenuSelection.Back
                    KeyPress.ESC -> {
                        if (allowBack) return MenuSelection.Back
                        if (allowQuit) return MenuSelection.Quit
                    }
                    KeyPress.Q -> if (allowQuit) return MenuSelection.Quit
                    KeyPress.EOF -> return MenuSelection.Quit
                    else -> {}
                }
            }
        } finally {
            terminal.setAttributes(previousAttributes)
            terminal.flush()
            terminal.close()
        }
    } catch (_: Throwable) {
        return selectByNumberFallback(prompt, options, allowBack, allowQuit, labelProvider)
    }
}

private fun <T> selectByNumberFallback(
    prompt: String,
    options: List<T>,
    allowBack: Boolean = false,
    allowQuit: Boolean = false,
    labelProvider: (T) -> String
): MenuSelection<T> {
    println(prompt)
    options.forEachIndexed { index, item ->
        println("${index + 1}. ${labelProvider(item)}")
    }

    while (true) {
        print("$prompt: ")
        val inputRaw = readlnOrNull() ?: run {
            println("Input stream is closed; exiting selection.")
            return MenuSelection.Quit
        }
        val input = inputRaw.trim()
        if (allowBack && (input.equals("b", true) || input.equals("back", true))) {
            return MenuSelection.Back
        }
        if (allowQuit && (input.equals("q", true) || input.equals("quit", true))) {
            return MenuSelection.Quit
        }
        val index = input.toIntOrNull()
        if (index != null && index in 1..options.size) {
            return MenuSelection.Selected(options[index - 1])
        }
        val hints = buildList {
            if (allowBack) add("'b'")
            if (allowQuit) add("'q'")
        }
        val suffix = if (hints.isEmpty()) "" else " or ${hints.joinToString(" or ")}"
        println("Please enter a number between 1 and ${options.size}$suffix.")
    }
}

private fun menuHint(allowBack: Boolean, allowQuit: Boolean): String {
    val controls = mutableListOf("Use ↑/↓ and Enter")
    if (allowBack) controls.add("← or Esc to go back")
    if (allowQuit) controls.add("q to quit")
    return controls.joinToString(". ") + "."
}

private fun readPostDetailsAction(): PostDetailsAction {
    try {
        val terminal = TerminalBuilder.builder()
            .system(true)
            .build()
        val previousAttributes = terminal.enterRawMode()
        try {
            while (true) {
                when (readKeyPress(terminal)) {
                    KeyPress.ENTER -> return PostDetailsAction.BACK_TO_TEAMS
                    KeyPress.LEFT, KeyPress.ESC -> return PostDetailsAction.BACK_TO_LEAGUES
                    KeyPress.Q, KeyPress.EOF -> return PostDetailsAction.QUIT
                    else -> {}
                }
            }
        } finally {
            terminal.setAttributes(previousAttributes)
            terminal.flush()
            terminal.close()
        }
    } catch (_: Throwable) {
        val input = readlnOrNull()?.trim().orEmpty()
        return when {
            input.equals("q", true) || input.equals("quit", true) -> PostDetailsAction.QUIT
            input.equals("b", true) || input.equals("back", true) -> PostDetailsAction.BACK_TO_LEAGUES
            else -> PostDetailsAction.BACK_TO_TEAMS
        }
    }
}

private fun clearScreen() {
    print("\u001B[2J\u001B[H")
}

private fun readKeyPress(terminal: org.jline.terminal.Terminal): KeyPress {
    val reader = terminal.reader()
    val first = reader.read()
    if (first == -1) return KeyPress.EOF

    return when (first) {
        10, 13 -> KeyPress.ENTER
        27 -> {
            val second = reader.read(250L)
            if (second == '['.code || second == 'O'.code) {
                var third = reader.read(250L)
                // Handle CSI with modifiers, e.g. ESC [ 1 ; 2 A
                if (second == '['.code && third in '0'.code..'9'.code) {
                    while (third != -1 && third !in setOf('A'.code, 'B'.code, 'C'.code, 'D'.code)) {
                        third = reader.read(250L)
                    }
                }
                when (third) {
                    'A'.code -> KeyPress.UP
                    'B'.code -> KeyPress.DOWN
                    'C'.code -> KeyPress.RIGHT
                    'D'.code -> KeyPress.LEFT
                    else -> KeyPress.ESC
                }
            } else {
                KeyPress.ESC
            }
        }
        'k'.code, 'K'.code -> KeyPress.UP
        'j'.code, 'J'.code -> KeyPress.DOWN
        'q'.code, 'Q'.code -> KeyPress.Q
        else -> KeyPress.OTHER
    }
}

private enum class KeyPress {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    ENTER,
    ESC,
    Q,
    EOF,
    OTHER
}

private sealed interface MenuSelection<out T> {
    data class Selected<T>(val value: T) : MenuSelection<T>
    object Back : MenuSelection<Nothing>
    object Quit : MenuSelection<Nothing>
}

private enum class PostDetailsAction {
    BACK_TO_TEAMS,
    BACK_TO_LEAGUES,
    QUIT
}
