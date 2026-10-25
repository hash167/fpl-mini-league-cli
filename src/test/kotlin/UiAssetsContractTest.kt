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
        assertTrue(html.contains("""["gwNet", "GW"]"""), "desktop standings must expose a GW column bound to gwNet")
        assertTrue(html.contains("""["liveTotal", "Overall"]"""), "desktop standings must label liveTotal as Overall")
        assertTrue(html.contains("class='mgr-gw'"), "mobile cards must render a GW secondary score")
        assertTrue(html.contains("fmt(team.gwNet)"), "mobile cards must display existing gwNet, not a new score")
        assertTrue(html.contains("<span>Overall</span>"), "mobile cards must label season live total as Overall")
        assertTrue(html.contains("td.className = \"gw-live\""), "desktop GW cells must be marked for styling")
    }
}
