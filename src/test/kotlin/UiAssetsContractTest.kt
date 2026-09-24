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
        assertTrue(html.contains("rank-pill"), "cards must use rank pills for Pos")
        assertTrue(html.contains("playersRemaining"), "Yet column must map to playersRemaining")
    }

    @Test
    fun `sitewide LiveFPL navy and white card scheme`() {
        val html = Files.readString(
            Paths.get("src/main/resources/assets/index.html")
        )
        assertTrue(html.contains("--bg: #0e111d"), "page background must be LiveFPL navy")
        assertTrue(html.contains("--card: #ffffff"), "cards must use white LiveFPL surface")
        assertTrue(html.contains("background: var(--card); color: var(--card-text);"), "generic .card must use white LiveFPL card colors")
        assertTrue(html.contains(".card .muted { color: var(--card-muted); }"), "muted text on cards must use card-muted")
        assertTrue(html.contains("id=\"homeBoard\""), "home live board section must remain")
        assertTrue(html.contains("data-filter=\"price\""), "price rise filter must remain")
        assertTrue(html.contains("toastHost"), "toast host must remain for notifications")
        assertTrue(html.contains("#toastHost"), "toast host styles must remain")
        assertTrue(html.contains("boardChips"), "board filter chips must remain")
    }
}
