import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

class UiAssetsContractTest {
    @Test
    fun `mini-league standings UI shows GW live points beside overall`() {
        val html = Files.readString(
            Paths.get("src/main/resources/assets/index.html")
        )
        assertTrue(html.contains("league-board"), "standings must use LiveFPL-style league board")
        assertTrue(html.contains(">GW</span>"), "column headers must include GW")
        assertTrue(html.contains(">Total</span>"), "column headers must include Total (season live total)")
        assertTrue(html.contains("fmt(team.gwNet)"), "cards must display existing gwNet, not a new score")
        assertTrue(html.contains("fmt(team.liveTotal)"), "cards must display existing liveTotal as Total")
        assertTrue(html.contains("class='lf-gw'"), "cards must render a GW score cell")
        assertTrue(html.contains("class='lf-total'"), "cards must render a Total score cell")
        assertTrue(!html.contains("Compact View") && !html.contains("Detailed View"), "density toggle removed")
        assertTrue(!html.contains("densCompact") && !html.contains("densDetail") && !html.contains("state.detailed"), "no density state")
        assertTrue(html.contains("state.expandedEntry = team.entryId;") && html.contains("buildLeagueDetail(team)"), "tapping a team expands it in place")
        assertTrue(html.contains("state.expandedEntry = null;"), "second tap collapses the card")
        assertTrue(html.contains("if (matchState.tabAuto && m.status.phase === \"upcoming\" && hasEspnLineups(m)) matchState.tab = \"lineup\";"), "pre-match lineups open by default")
        assertTrue(html.contains("const MATCH_PRE_POLL_MS = 60000;"), "pre-match match view re-checks every 60s")
        assertTrue(html.contains("Lineups ✓"), "strip marks confirmed lineups")
        assertTrue(html.contains("if (!state.picks || state.picks.fromEntry) state.picks = {"), "notify poll must not replace a picked team")
        assertTrue(html.contains("Autosubs \" + (state.autosubs ? \"On\" : \"Off\")"), "autosubs toggle kept")
        assertTrue(html.contains("rank-pill"), "cards must use rank pills for Pos")
        assertTrue(html.contains("playersRemaining"), "Yet column must map to playersRemaining")
    }

    @Test
    fun `sitewide LiveFPL navy and white card scheme`() {
        val html = Files.readString(
            Paths.get("src/main/resources/assets/index.html")
        )
        assertTrue(html.contains("--bg: #f4f6fb"), "page background must be light soft gray")
        assertTrue(html.contains("id=\"gwStats\""), "GW Stats card must exist")
        assertTrue(html.contains("/live/gw-stats/top10k"), "GW Stats must load top10k endpoint")
        assertTrue(html.contains("--card: #ffffff"), "cards must use white LiveFPL surface")
        assertTrue(html.contains("background: var(--card); color: var(--card-text);"), "generic .card must use white LiveFPL card colors")
        assertTrue(html.contains(".card .muted { color: var(--card-muted); }"), "muted text on cards must use card-muted")
        assertTrue(html.contains("id=\"homeBoard\""), "home live board section must remain")
        assertTrue(html.contains("data-filter=\"price\""), "price rise filter must remain")
        assertTrue(html.contains("toastHost"), "toast host must remain for notifications")
        assertTrue(html.contains("#toastHost"), "toast host styles must remain")
        assertTrue(html.contains("boardChips"), "board filter chips must remain")
    }

    @Test
    fun `fixtures open a light FotMob-style match view`() {
        val html = Files.readString(Paths.get("src/main/resources/assets/index.html"))
        assertTrue(html.contains("openMatch(fx.id)"), "fixture tiles must open the match view")
        assertTrue(html.contains("id=\"matchSheet\""), "match sheet container must exist")
        assertTrue(html.contains("\"/fixtures/\" + id + \"/match\""), "match view must load the match endpoint")
        assertTrue(html.contains("[\"facts\", \"Facts\"], [\"lineup\", \"Lineup\"], [\"stats\", \"Stats\"]"), "Facts/Lineup/Stats tabs")
        assertTrue(html.contains("#matchSheet {\n      position: fixed; inset: 0; z-index: 40; background: var(--bg)"), "match sheet must use the light page background")
        assertTrue(html.contains("overflow-x: hidden"), "no horizontal overflow")
        assertTrue(html.contains("setInterval(() => loadMatch(true)"), "live matches auto-refresh")
        val css = html.substring(html.indexOf("/* FotMob-style match view"), html.indexOf("</style>"))
        assertTrue(html.contains("[\"commentary\", \"Commentary\"]"), "Commentary tab when ESPN is available")
        assertTrue(html.contains("Source: ESPN + FPL") && html.contains("\"FPL only\""), "source note")
        assertTrue(html.contains("function renderTimeline(m)"), "minute timeline")
        assertTrue(html.contains("new EventSource(\"/fixtures/\" + id + \"/match/stream\""), "match view subscribes to SSE stream")
        assertTrue(html.contains("const MATCH_POLL_MS = 10000;"), "polling fallback is 10s")
        assertTrue(html.contains("tabList.push([\"table\", \"Table\"])"), "live table tab after commentary")
        assertTrue(html.contains("function renderLiveTable(m)"), "live table renderer")
        assertTrue(html.contains("api(\"/live/scoreboard?gameweek=\""), "fixtures strip uses the live scoreboard")
        assertTrue(html.contains("const SCOREBOARD_POLL_MS = 10000;"), "strip fallback poll is 10s")
        assertTrue(html.contains("new EventSource(\"/live/scoreboard/stream?gameweek=\""), "strip uses scoreboard SSE")
        assertTrue(html.contains("function updateStripInPlace()"), "strip updates in place")
        assertTrue(html.contains("if (state.timer) return;"), "90s refresh must not be reset by the notify poll")
        assertTrue(html.contains("function freshestScore(fx)"), "strip never shows an older score than the newest data")
        assertTrue(html.contains("window.addEventListener(\"pageshow\""), "resume handling for iOS Home Screen apps")
        assertTrue(html.contains("const APP_BUILD = \"1.36\";"), "embedded build for self-reload")
        val sw = Files.readString(Paths.get("src/main/resources/assets/sw.js"))
        assertTrue(!sw.contains("addEventListener(\"fetch\"") && sw.contains("caches.delete"), "service worker is network-only and purges caches")
        assertTrue(html.contains("addEventListener(\"visibilitychange\""), "refresh on visibility change")
        assertTrue(html.contains("stopMatchStream();"), "stream closes with the view")
        assertTrue(!Regex("background:\\s*#(0|1)[0-9a-f]{5}").containsMatchIn(css), "match view must not use dark backgrounds")
    }
}
