import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverallRankResolutionTest {
    private fun profile(currentEvent: Int?, summaryRank: Int?) = JsonObject(buildMap {
        currentEvent?.let { put("current_event", JsonPrimitive(it)) }
        summaryRank?.let { put("summary_overall_rank", JsonPrimitive(it)) }
    })

    @Test
    fun `current gameweek uses summary overall rank`() {
        assertEquals(419154, resolveOverallRank(700242, profile(6, 419154), 6))
    }

    @Test
    fun `finished gameweek keeps history overall rank`() {
        assertEquals(700242, resolveOverallRank(700242, profile(7, 419154), 6))
    }

    @Test
    fun `missing summary falls back to history rank`() {
        assertEquals(700242, resolveOverallRank(700242, profile(6, null), 6))
        assertEquals(700242, resolveOverallRank(700242, JsonObject(emptyMap()), 6))
        assertNull(resolveOverallRank(null, JsonObject(emptyMap()), 6))
    }
}
