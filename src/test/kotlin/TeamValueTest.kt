import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeamValueTest {
    private fun pick(id: Int) = PickRow(element = id, position = id, multiplier = 1, isCaptain = false, isViceCaptain = false)
    private fun player(id: Int, cost: Int?) =
        PlayerInfo(id = id, name = "P$id", webName = "P$id", teamId = 1, teamShortName = "T", elementType = 3, nowCost = cost)

    private val picks = (1..15).map { pick(it) }
    // 14 x 67 + 1 x 70 = 1008 tenths (£100.8m), like entry 5498977 on Oct 26.
    private val players = (1..15).associateWith { player(it, if (it == 15) 70 else 67) }

    @Test
    fun `value is current now_cost of the 15 picks plus bank`() {
        assertEquals(1019, currentTeamValueTenths(picks, players, bankTenths = 11))
        assertEquals(101.9, resolveTeamValue(picks, players, bankTenths = 11, deadlineValueTenths = 1015))
    }

    @Test
    fun `falls back to deadline value when prices or picks are unavailable`() {
        assertEquals(101.5, resolveTeamValue(emptyList(), players, 11, 1015))
        assertEquals(101.5, resolveTeamValue(picks, emptyMap(), 11, 1015))
        assertEquals(101.5, resolveTeamValue(picks.take(14), players, 11, 1015))
        assertEquals(101.5, resolveTeamValue(picks, players + (3 to player(3, null)), 11, 1015))
        assertEquals(101.5, resolveTeamValue(picks, players, null, 1015))
        assertEquals(101.5, resolveTeamValue(picks, players, 11, 1015, useCurrentPrices = false))
        assertNull(resolveTeamValue(emptyList(), players, 11, null))
    }
}
