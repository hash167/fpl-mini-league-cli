import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class FplLiveLeaguesCliTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `currentGameweekId returns current event id when present`() {
        val bootstrap = parseJsonObject(
            """
            {
              "events": [
                {"id": 1, "is_current": false},
                {"id": 2, "is_current": true},
                {"id": 3, "is_next": true}
              ]
            }
            """.trimIndent()
        )

        val result = currentGameweekId(bootstrap)

        assertEquals(2, result)
    }

    @Test
    fun `currentGameweekId returns next event id when current missing`() {
        val bootstrap = parseJsonObject(
            """
            {
              "events": [
                {"id": 8, "is_current": false},
                {"id": 9, "is_next": true}
              ]
            }
            """.trimIndent()
        )

        val result = currentGameweekId(bootstrap)

        assertEquals(9, result)
    }

    @Test
    fun `currentGameweekId falls back to max event id`() {
        val bootstrap = parseJsonObject(
            """
            {
              "events": [
                {"id": 31},
                {"id": 34},
                {"id": 33}
              ]
            }
            """.trimIndent()
        )

        val result = currentGameweekId(bootstrap)

        assertEquals(34, result)
    }

    @Test
    fun `currentGameweekId returns null when no events`() {
        val bootstrap = parseJsonObject("""{"events": []}""")

        val result = currentGameweekId(bootstrap)

        assertNull(result)
    }

    @Test
    fun `playerLookup maps player names and team short names`() {
        val bootstrap = parseJsonObject(
            """
            {
              "teams": [
                {"id": 1, "short_name": "ARS"},
                {"id": 2, "short_name": "MCI"}
              ],
              "elements": [
                {"id": 10, "first_name": "Bukayo", "second_name": "Saka", "team": 1},
                {"id": 11, "web_name": "Haaland", "team": 2},
                {"id": 12, "first_name": "", "second_name": "", "web_name": ""}
              ]
            }
            """.trimIndent()
        )

        val lookup = playerLookup(bootstrap)

        assertEquals(PlayerRef(name = "Bukayo Saka", teamShortName = "ARS"), lookup[10])
        assertEquals(PlayerRef(name = "Haaland", teamShortName = "MCI"), lookup[11])
        assertEquals(PlayerRef(name = "Player 12", teamShortName = ""), lookup[12])
    }

    private fun parseJsonObject(raw: String): JsonObject {
        return json.parseToJsonElement(raw).jsonObject
    }
}
