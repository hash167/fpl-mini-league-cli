import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveEngineTest {
    @Test
    fun `projectBpsBonus awards 3 2 1 by BPS`() {
        val awards = projectBpsBonus(
            listOf(
                BonusCandidate(1, minutes = 90, bps = 40, officialBonus = 0),
                BonusCandidate(2, minutes = 90, bps = 30, officialBonus = 0),
                BonusCandidate(3, minutes = 90, bps = 20, officialBonus = 0),
                BonusCandidate(4, minutes = 90, bps = 10, officialBonus = 0),
                BonusCandidate(5, minutes = 0, bps = 99, officialBonus = 0)
            )
        )
        assertEquals(3, awards[1])
        assertEquals(2, awards[2])
        assertEquals(1, awards[3])
        assertFalse(awards.containsKey(4))
        assertFalse(awards.containsKey(5))
    }

    @Test
    fun `projectBpsBonus ties share a place and skip the next`() {
        val awards = projectBpsBonus(
            listOf(
                BonusCandidate(1, 90, 35, 0),
                BonusCandidate(2, 90, 35, 0),
                BonusCandidate(3, 90, 20, 0)
            )
        )
        assertEquals(3, awards[1])
        assertEquals(3, awards[2])
        assertEquals(1, awards[3])
    }

    @Test
    fun `resolved bonus skips projection when official bonus is already set`() {
        assertEquals(3, resolvedBonus(officialBonus = 3, projectedBonus = 2))
        assertEquals(2, resolvedBonus(officialBonus = 0, projectedBonus = 2))
        assertEquals(0, extraProjectedBonus(officialBonus = 3, projectedBonus = 2))
        assertEquals(2, extraProjectedBonus(officialBonus = 0, projectedBonus = 2))
        assertEquals(8, effectivePlayerPoints(officialTotalPoints = 8, officialBonus = 3, projectedBonus = 2))
        assertEquals(10, effectivePlayerPoints(officialTotalPoints = 8, officialBonus = 0, projectedBonus = 2))
    }

    @Test
    fun `fixtureBonusAwards uses official values once any bonus is confirmed`() {
        val candidates = listOf(
            BonusCandidate(1, 90, 40, officialBonus = 3),
            BonusCandidate(2, 90, 10, officialBonus = 0)
        )
        val (awards, confirmed) = fixtureBonusAwards(candidates)
        assertTrue(confirmed)
        assertEquals(3, awards[1])
        assertEquals(0, awards[2])
    }

    @Test
    fun `gw net is gross minus transfer cost`() {
        assertEquals(47, gwNet(51, 4))
        assertEquals(51, gwNet(51, 0))
        assertEquals(-4, gwNet(0, 4))
    }

    @Test
    fun `live season total does not double-count official GW points`() {
        assertEquals(1847, liveSeasonTotal(officialTotal = 1800, officialGwPoints = 0, gwNetPoints = 47))
        assertEquals(1847, liveSeasonTotal(officialTotal = 1840, officialGwPoints = 40, gwNetPoints = 47))
        assertEquals(1800, liveSeasonTotal(officialTotal = 1800, officialGwPoints = 0, gwNetPoints = 0))
    }

    @Test
    fun `Erik-like liveTotal is 56 not 48 when official GW points are gross of an 8-pt hit`() {
        // officialTotal already NET: 31 (GW1) + 33 gross - 8 hit = 56
        // old formula officialTotal - officialGwPoints + gwNet = 56 - 33 + 25 = 48
        assertEquals(56, liveSeasonTotal(
            officialTotal = 56,
            officialGwPoints = 33,
            gwNetPoints = 25,
            transferCost = 8,
            previousTotal = 31
        ))
        assertEquals(56, liveSeasonTotal(
            officialTotal = 56,
            officialGwPoints = 33,
            gwNetPoints = 25,
            transferCost = 8
        ))
        assertEquals(31, startOfGwSeasonTotal(officialTotal = 56, officialGwPoints = 33, transferCost = 8))
        assertEquals(48, 56 - 33 + 25)
    }

    @Test
    fun `Hashim-like start-of-GW overall arrow is millions green not mid-GW noise`() {
        val history = listOf(
            EntrySeasonSnapshot(event = 1, points = 46, totalPoints = 46, overallRank = 5_572_584, eventTransfersCost = 0),
            EntrySeasonSnapshot(event = 2, points = 31, totalPoints = 77, overallRank = 2_639_389, eventTransfersCost = 0)
        )
        val start = startOfGwOverallRank(history, gameweek = 2)
        assertEquals(5_572_584, start)
        val liveEst = 2_542_858L
        val movement = rankMovement(liveEst, start?.toLong())
        assertEquals(3_029_726L, movement)
        assertTrue(movement!! > 2_000_000L)
        val vsCurrentOfficial = rankMovement(liveEst, 2_639_389L)
        assertEquals(96_531L, vsCurrentOfficial)
        assertTrue(movement > vsCurrentOfficial!!)
    }

    @Test
    fun `auto-sub replaces blank starter with first eligible bench player`() {
        val picks = squad(
            starter(1, ELEMENT_GK, minutes = 0, finished = true),
            starter(2, ELEMENT_DEF, minutes = 90, finished = true),
            starter(3, ELEMENT_DEF, minutes = 90, finished = true),
            starter(4, ELEMENT_DEF, minutes = 90, finished = true),
            starter(5, ELEMENT_MID, minutes = 90, finished = true),
            starter(6, ELEMENT_MID, minutes = 90, finished = true),
            starter(7, ELEMENT_MID, minutes = 90, finished = true),
            starter(8, ELEMENT_MID, minutes = 90, finished = true),
            starter(9, ELEMENT_FWD, minutes = 90, finished = true),
            starter(10, ELEMENT_FWD, minutes = 90, finished = true),
            starter(11, ELEMENT_FWD, minutes = 90, finished = true),
            bench(12, ELEMENT_GK, minutes = 90, started = true),
            bench(13, ELEMENT_DEF, minutes = 90, started = true),
            bench(14, ELEMENT_MID, minutes = 0, started = false),
            bench(15, ELEMENT_FWD, minutes = 0, started = false)
        )
        val subs = computeAutoSubs(picks)
        assertEquals(listOf(AppliedSub(1, 12)), subs)
    }

    @Test
    fun `auto-sub GK can only be replaced by GK`() {
        val picks = squad(
            starter(1, ELEMENT_GK, minutes = 0, finished = true),
            starter(2, ELEMENT_DEF, minutes = 90, finished = true),
            starter(3, ELEMENT_DEF, minutes = 90, finished = true),
            starter(4, ELEMENT_DEF, minutes = 90, finished = true),
            starter(5, ELEMENT_MID, minutes = 90, finished = true),
            starter(6, ELEMENT_MID, minutes = 90, finished = true),
            starter(7, ELEMENT_MID, minutes = 90, finished = true),
            starter(8, ELEMENT_MID, minutes = 90, finished = true),
            starter(9, ELEMENT_FWD, minutes = 90, finished = true),
            starter(10, ELEMENT_FWD, minutes = 90, finished = true),
            starter(11, ELEMENT_FWD, minutes = 90, finished = true),
            bench(12, ELEMENT_DEF, minutes = 90, started = true),
            bench(13, ELEMENT_MID, minutes = 90, started = true),
            bench(14, ELEMENT_FWD, minutes = 90, started = true),
            bench(15, ELEMENT_MID, minutes = 90, started = true)
        )
        assertEquals(emptyList(), computeAutoSubs(picks))
    }

    @Test
    fun `auto-sub refuses a swap that would drop below 3 DEF`() {
        // 3 DEF start; one DEF blanks; only MID on bench who played
        val picks = squad(
            starter(1, ELEMENT_GK, minutes = 90, finished = true),
            starter(2, ELEMENT_DEF, minutes = 0, finished = true),
            starter(3, ELEMENT_DEF, minutes = 90, finished = true),
            starter(4, ELEMENT_DEF, minutes = 90, finished = true),
            starter(5, ELEMENT_MID, minutes = 90, finished = true),
            starter(6, ELEMENT_MID, minutes = 90, finished = true),
            starter(7, ELEMENT_MID, minutes = 90, finished = true),
            starter(8, ELEMENT_MID, minutes = 90, finished = true),
            starter(9, ELEMENT_FWD, minutes = 90, finished = true),
            starter(10, ELEMENT_FWD, minutes = 90, finished = true),
            starter(11, ELEMENT_FWD, minutes = 90, finished = true),
            bench(12, ELEMENT_GK, minutes = 0, started = false),
            bench(13, ELEMENT_MID, minutes = 90, started = true),
            bench(14, ELEMENT_MID, minutes = 90, started = true),
            bench(15, ELEMENT_FWD, minutes = 90, started = true)
        )
        assertEquals(emptyList(), computeAutoSubs(picks))
    }

    @Test
    fun `auto-sub allows outfield cover when formation stays legal`() {
        // 4 DEF start; one DEF blanks; MID on bench is legal (3 DEF remain)
        val picks = squad(
            starter(1, ELEMENT_GK, minutes = 90, finished = true),
            starter(2, ELEMENT_DEF, minutes = 0, finished = true),
            starter(3, ELEMENT_DEF, minutes = 90, finished = true),
            starter(4, ELEMENT_DEF, minutes = 90, finished = true),
            starter(5, ELEMENT_DEF, minutes = 90, finished = true),
            starter(6, ELEMENT_MID, minutes = 90, finished = true),
            starter(7, ELEMENT_MID, minutes = 90, finished = true),
            starter(8, ELEMENT_MID, minutes = 90, finished = true),
            starter(9, ELEMENT_MID, minutes = 90, finished = true),
            starter(10, ELEMENT_FWD, minutes = 90, finished = true),
            starter(11, ELEMENT_FWD, minutes = 90, finished = true),
            bench(12, ELEMENT_GK, minutes = 0, started = false),
            bench(13, ELEMENT_MID, minutes = 90, started = true),
            bench(14, ELEMENT_FWD, minutes = 0, started = false),
            bench(15, ELEMENT_DEF, minutes = 0, started = false)
        )
        assertEquals(listOf(AppliedSub(2, 13)), computeAutoSubs(picks))
    }

    @Test
    fun `bench boost disables auto-subs`() {
        val picks = squad(
            starter(1, ELEMENT_GK, minutes = 0, finished = true),
            starter(2, ELEMENT_DEF, minutes = 90, finished = true),
            starter(3, ELEMENT_DEF, minutes = 90, finished = true),
            starter(4, ELEMENT_DEF, minutes = 90, finished = true),
            starter(5, ELEMENT_MID, minutes = 90, finished = true),
            starter(6, ELEMENT_MID, minutes = 90, finished = true),
            starter(7, ELEMENT_MID, minutes = 90, finished = true),
            starter(8, ELEMENT_MID, minutes = 90, finished = true),
            starter(9, ELEMENT_FWD, minutes = 90, finished = true),
            starter(10, ELEMENT_FWD, minutes = 90, finished = true),
            starter(11, ELEMENT_FWD, minutes = 90, finished = true),
            bench(12, ELEMENT_GK, minutes = 90, started = true),
            bench(13, ELEMENT_DEF, minutes = 90, started = true),
            bench(14, ELEMENT_MID, minutes = 90, started = true),
            bench(15, ELEMENT_FWD, minutes = 90, started = true)
        )
        assertEquals(emptyList(), computeAutoSubs(picks, benchBoost = true))
    }

    @Test
    fun `official automatic_subs are applied before computed ones`() {
        val picks = squad(
            starter(1, ELEMENT_GK, minutes = 90, finished = true),
            starter(2, ELEMENT_DEF, minutes = 0, finished = true),
            starter(3, ELEMENT_DEF, minutes = 90, finished = true),
            starter(4, ELEMENT_DEF, minutes = 90, finished = true),
            starter(5, ELEMENT_DEF, minutes = 90, finished = true),
            starter(6, ELEMENT_MID, minutes = 90, finished = true),
            starter(7, ELEMENT_MID, minutes = 90, finished = true),
            starter(8, ELEMENT_MID, minutes = 90, finished = true),
            starter(9, ELEMENT_MID, minutes = 90, finished = true),
            starter(10, ELEMENT_FWD, minutes = 90, finished = true),
            starter(11, ELEMENT_FWD, minutes = 90, finished = true),
            bench(12, ELEMENT_GK, minutes = 0, started = false),
            bench(13, ELEMENT_MID, minutes = 90, started = true),
            bench(14, ELEMENT_DEF, minutes = 90, started = true),
            bench(15, ELEMENT_FWD, minutes = 0, started = false)
        )
        val subs = computeAutoSubs(picks, officialSubs = listOf(AppliedSub(2, 14)))
        assertEquals(listOf(AppliedSub(2, 14)), subs)
    }

    @Test
    fun `chip labels match livefpl abbreviations`() {
        assertEquals("WC", chipLabel("wildcard"))
        assertEquals("FH", chipLabel("freehit"))
        assertEquals("BB", chipLabel("bboost"))
        assertEquals("TC", chipLabel("3xc"))
        assertEquals("AM", chipLabel("manager"))
        assertEquals("AM", chipLabel("assistant"))
        assertEquals("AM", chipLabel("assistant_manager"))
        assertEquals(null, chipLabel(null))
    }


    @Test
    fun `evaluateLiveSquad applies hit and avoids double-counting official GW points`() {
        val picks = listOf(
            PickRow(10, 1, 2, isCaptain = true, isViceCaptain = false),
            PickRow(11, 2, 1, isCaptain = false, isViceCaptain = true)
        )
        val players = mapOf(
            10 to PlayerInfo(10, "Saka", "Saka", 1, "ARS", ELEMENT_MID),
            11 to PlayerInfo(11, "Haaland", "Haaland", 2, "MCI", ELEMENT_FWD)
        )
        val live = mapOf(
            10 to LiveElementStats(10, minutes = 90, totalPoints = 8, bonus = 0, bps = 20),
            11 to LiveElementStats(11, minutes = 90, totalPoints = 5, bonus = 0, bps = 10)
        )
        val history = EntryEventHistory(
            event = 4,
            points = 12,
            totalPoints = 1812,
            overallRank = 1234,
            bank = 15,
            value = 1015,
            eventTransfers = 1,
            eventTransfersCost = 4,
            previousTotalPoints = 1800
        )
        val result = evaluateLiveSquad(
            picks = picks,
            history = history,
            activeChip = null,
            officialSubs = emptyList(),
            players = players,
            liveStats = live,
            fixtures = emptyList(),
            bonusSheet = ProjectedBonusSheet(emptyMap(), emptyMap(), emptyMap()),
            applyAutosubs = true
        )
        assertEquals(21, result.gwGross)
        assertEquals(17, result.gwNet)
        assertEquals(1817, result.liveTotal)
    }

    @Test
    fun `evaluateLiveSquad Erik-like 8-pt hit keeps liveTotal at 56`() {
        val picks = listOf(
            PickRow(10, 1, 2, isCaptain = true, isViceCaptain = false),
            PickRow(11, 2, 1, isCaptain = false, isViceCaptain = true)
        )
        val players = mapOf(
            10 to PlayerInfo(10, "Saka", "Saka", 1, "ARS", ELEMENT_MID),
            11 to PlayerInfo(11, "Haaland", "Haaland", 2, "MCI", ELEMENT_FWD)
        )
        val live = mapOf(
            10 to LiveElementStats(10, minutes = 90, totalPoints = 10, bonus = 0, bps = 20),
            11 to LiveElementStats(11, minutes = 90, totalPoints = 13, bonus = 0, bps = 10)
        )
        val history = EntryEventHistory(
            event = 2,
            points = 33,
            totalPoints = 56,
            overallRank = 7_010_202,
            bank = 0,
            value = 1003,
            eventTransfers = 3,
            eventTransfersCost = 8,
            previousTotalPoints = 31
        )
        val result = evaluateLiveSquad(
            picks = picks,
            history = history,
            activeChip = null,
            officialSubs = emptyList(),
            players = players,
            liveStats = live,
            fixtures = emptyList(),
            bonusSheet = ProjectedBonusSheet(emptyMap(), emptyMap(), emptyMap()),
            applyAutosubs = true
        )
        assertEquals(33, result.gwGross)
        assertEquals(25, result.gwNet)
        assertEquals(56, result.liveTotal)
        assertEquals(48, 56 - 33 + 25)
    }

    private fun starter(
        element: Int,
        type: Int,
        minutes: Int,
        finished: Boolean,
        captain: Boolean = false,
        vice: Boolean = false
    ) = AutoSubPick(
        element = element,
        position = element,
        elementType = type,
        minutes = minutes,
        fixturesFinished = finished,
        fixtureStarted = minutes > 0 || !finished,
        isCaptain = captain,
        isViceCaptain = vice
    )

    private fun bench(element: Int, type: Int, minutes: Int, started: Boolean) =
        AutoSubPick(
            element = element,
            position = element,
            elementType = type,
            minutes = minutes,
            fixturesFinished = minutes > 0 || !started,
            fixtureStarted = started,
            isCaptain = false,
            isViceCaptain = false
        )

    private fun squad(vararg picks: AutoSubPick) = picks.toList()
}
