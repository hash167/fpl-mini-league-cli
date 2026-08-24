import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class LiveOverallEstimatorTest {
    @Test
    fun `nine bands clip last slice to total_players`() {
        val bands = rankBands(11_000_000)
        assertEquals(9, bands.size)
        assertEquals(1, bands.first().startRank)
        assertEquals(1_000, bands.first().endRank)
        assertEquals(7_000_001, bands.last().startRank)
        assertEquals(11_000_000, bands.last().endRank)
        assertEquals(4_000_000, bands.last().nSlice)
        assertEquals(1_000, bands[0].nSlice)
        assertEquals(9_000, bands[1].nSlice)
        assertEquals(90_000, bands[2].nSlice)
        assertEquals(400_000, bands[3].nSlice)
        assertEquals(500_000, bands[4].nSlice)
        assertEquals(1_000_000, bands[5].nSlice)
        assertEquals(2_000_000, bands[6].nSlice)
        assertEquals(3_000_000, bands[7].nSlice)
    }

    @Test
    fun `last band N_b uses total_players and empty bands are dropped`() {
        val clipped = rankBands(6_500_000)
        assertEquals(8, clipped.size)
        assertEquals(4_000_001, clipped.last().startRank)
        assertEquals(6_500_000, clipped.last().endRank)
        assertEquals(2_500_000, clipped.last().nSlice)
        assertTrue(clipped.none { it.startRank == 7_000_001 })

        val tiny = rankBands(10_000)
        assertEquals(2, tiny.size)
        assertEquals(1_000, tiny[0].nSlice)
        assertEquals(9_000, tiny[1].nSlice)
    }

    @Test
    fun `sample targets spread evenly and never leave the band`() {
        val ranks = sampleTargetRanks(1, 1_000, 35)
        assertEquals(35, ranks.size)
        assertEquals(35, ranks.toSet().size)
        assertTrue(ranks.all { it in 1..1_000 })
        assertTrue(ranks.first() < 50)
        assertTrue(ranks.last() > 950)
        val gaps = ranks.zipWithNext { a, b -> b - a }
        assertTrue(gaps.maxOrNull()!! - gaps.minOrNull()!! <= 2)

        val last = sampleTargetRanks(7_000_001, 11_000_000, 35)
        assertEquals(35, last.size)
        assertTrue(last.all { it in 7_000_001..11_000_000 })
    }

    @Test
    fun `n_b less than 35 still uses the successes that landed`() {
        val short = sampleTargetRanks(1, 20, 35)
        assertEquals(20, short.size)
        assertEquals((1..20).toList(), short)
    }

    @Test
    fun `rank maps to standings page of 50`() {
        assertEquals(PageSlot(1, 0), rankToPageSlot(1))
        assertEquals(PageSlot(1, 49), rankToPageSlot(50))
        assertEquals(PageSlot(2, 0), rankToPageSlot(51))
        assertEquals(PageSlot(20, 49), rankToPageSlot(1_000))
        assertEquals(PageSlot(21, 0), rankToPageSlot(1_001))
        assertEquals(PageSlot(140_001, 0), rankToPageSlot(7_000_001))
    }

    @Test
    fun `people T is stratified not naive k over 315 times N`() {
        val bands = listOf(
            BandSampleStats(0, 1, 1_000, nB = 2, nSlice = 1_000),
            BandSampleStats(1, 1_001, 10_000, nB = 3, nSlice = 9_000)
        )
        val samples = listOf(
            mgr(1, 100, 0, 100),
            mgr(2, 200, 0, 110),
            mgr(3, 2_000, 1, 80),
            mgr(4, 3_000, 1, 110),
            mgr(5, 4_000, 1, 110)
        )
        assertEquals(6_500.0, peopleAtTotal(110, samples, bands), 1e-9)
        assertEquals(500.0, peopleAtTotal(100, samples, bands), 1e-9)
        assertEquals(3_000.0, peopleAtTotal(80, samples, bands), 1e-9)
        assertEquals(0.0, peopleAtTotal(99, samples, bands), 1e-9)
        val naive = (3.0 / 5.0) * 10_000.0
        assertTrue(kotlin.math.abs(peopleAtTotal(110, samples, bands) - naive) > 1.0)
    }

    @Test
    fun `empty band n_b 0 contributes nothing`() {
        val bands = listOf(
            BandSampleStats(0, 1, 1_000, nB = 0, nSlice = 1_000),
            BandSampleStats(1, 1_001, 10_000, nB = 2, nSlice = 9_000)
        )
        val samples = listOf(
            mgr(10, 2_000, 1, 50),
            mgr(11, 3_000, 1, 50)
        )
        assertEquals(9_000.0, peopleAtTotal(50, samples, bands), 1e-9)
    }

    @Test
    fun `estimated rank is one plus people above and ties stay tied`() {
        val bands = listOf(
            BandSampleStats(0, 1, 1_000, nB = 2, nSlice = 1_000),
            BandSampleStats(1, 1_001, 10_000, nB = 3, nSlice = 9_000),
            BandSampleStats(2, 10_001, 100_000, nB = 2, nSlice = 90_000)
        )
        val samples = listOf(
            mgr(1, 10, 0, 120),
            mgr(2, 20, 0, 100),
            mgr(3, 2_000, 1, 110),
            mgr(4, 3_000, 1, 90),
            mgr(5, 4_000, 1, 90),
            mgr(6, 20_000, 2, 80),
            mgr(7, 30_000, 2, 70)
        )
        assertEquals(1L + 500L, estimatedOverallRank(110, samples, bands, minPopulatedBands = 3, minSuccessfulSamples = 7))
        assertEquals(1L + 500L + 3_000L, estimatedOverallRank(100, samples, bands, minPopulatedBands = 3, minSuccessfulSamples = 7))
        val atNinety = estimatedOverallRank(90, samples, bands, minPopulatedBands = 3, minSuccessfulSamples = 7)
        assertEquals(1L + 500L + 3_000L + 500L, atNinety)
        assertEquals(1L, estimatedOverallRank(200, samples, bands, minPopulatedBands = 3, minSuccessfulSamples = 7))
    }

    @Test
    fun `thin sample returns unavailable rather than a made-up rank`() {
        val twoBands = listOf(
            BandSampleStats(0, 1, 1_000, nB = 20, nSlice = 1_000),
            BandSampleStats(1, 1_001, 10_000, nB = 20, nSlice = 9_000),
            BandSampleStats(2, 10_001, 100_000, nB = 0, nSlice = 90_000)
        )
        val samples = (1..40).map { mgr(it, it, if (it <= 20) 0 else 1, 100 + it) }
        assertTrue(isSampleThin(twoBands))
        assertNull(estimatedOverallRank(100, samples, twoBands))

        val shallow = List(5) { i ->
            BandSampleStats(i, i * 10 + 1, i * 10 + 10, nB = 4, nSlice = 10)
        }
        assertEquals(20, shallow.sumOf { it.nB })
        assertTrue(isSampleThin(shallow))
        assertNull(estimatedOverallRank(50, emptyList(), shallow))

        val ok = List(3) { i ->
            BandSampleStats(i, 1, 10, nB = 10, nSlice = 10)
        }
        assertFalse(isSampleThin(ok))
    }

    @Test
    fun `n_b below 35 still scales by the actual successes`() {
        val bands = listOf(
            BandSampleStats(0, 1, 1_000, nB = 5, nSlice = 1_000)
        )
        val samples = listOf(
            mgr(1, 1, 0, 10),
            mgr(2, 2, 0, 10),
            mgr(3, 3, 0, 8),
            mgr(4, 4, 0, 8),
            mgr(5, 5, 0, 8)
        )
        assertEquals(400.0, peopleAtTotal(10, samples, bands), 1e-9)
        assertEquals(600.0, peopleAtTotal(8, samples, bands), 1e-9)
    }

    @Test
    fun `EO is N_b over n_b weighted mean of m`() {
        val bands = listOf(
            BandSampleStats(0, 1, 1_000, nB = 1, nSlice = 1_000),
            BandSampleStats(1, 1_001, 10_000, nB = 1, nSlice = 9_000)
        )
        val samples = listOf(
            SampledManager(1, 10, 0, 100, mapOf(7 to 2)),
            SampledManager(2, 2_000, 1, 90, mapOf(7 to 1))
        )
        val eo = effectiveOwnership(7, samples, bands)!!
        assertEquals(1.1, eo, 1e-9)
        assertEquals(0.0, effectiveOwnership(99, samples, bands)!!)
        assertNull(effectiveOwnership(7, emptyList(), bands))
    }

    @Test
    fun `EO treats missing pick as m 0 and TC as 3`() {
        val bands = listOf(BandSampleStats(0, 1, 100, nB = 4, nSlice = 100))
        val samples = listOf(
            SampledManager(1, 1, 0, 10, mapOf(5 to 1)),
            SampledManager(2, 2, 0, 10, mapOf(5 to 2)),
            SampledManager(3, 3, 0, 10, mapOf(5 to 3)),
            SampledManager(4, 4, 0, 10, emptyMap())
        )
        assertEquals(1.5, effectiveOwnership(5, samples, bands)!!, 1e-9)
    }

    private fun mgr(entryId: Int, officialRank: Int, band: Int, liveTotal: Int) =
        SampledManager(entryId, officialRank, band, liveTotal, emptyMap())
}

class LiveOverallSampleCacheTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `failed standings page is dropped and never fabricated`() {
        val bootstrap = parse("""{"total_players":8000,"events":[{"id":4,"is_current":true}]}""")
        val client = RecordingOverallClient(
            bootstrap = bootstrap,
            pages = mapOf(
                6 to pageOf(StandingRow(251, 101, "A", "Ann")),
                56 to pageOf(StandingRow(2751, 201, "B", "Bob")),
                126 to pageOf(StandingRow(6251, 301, "C", "Cam"))
            ),
            failingPages = setOf(16)
        )
        val lives = mapOf(
            101 to SampledLive(120, mapOf(1 to 2)),
            201 to SampledLive(110, mapOf(1 to 1)),
            301 to SampledLive(90, mapOf(1 to 0))
        )
        val cache = LiveOverallSampleCache(
            api = client,
            evaluate = { id, _ -> lives[id] },
            samplesPerBand = 2,
            pageDelayMs = 0,
            minPopulatedBands = 1,
            minSuccessfulSamples = 1,
            sleeper = {}
        )
        val snap = cache.refreshNow(4)
        assertEquals(setOf(6, 16, 56, 126), client.pagesRequested.toSet())
        assertEquals(3, snap.sampleCount)
        assertFalse(snap.samples.any { it.entryId == 0 })
        assertEquals(1, snap.bands.first { it.index == 0 }.nB)
        assertEquals(2, snap.bands.first { it.index == 1 }.nB)
        assertTrue(snap.available)
        assertEquals(120, snap.samples.single { it.entryId == 101 }.liveTotal)
    }

    @Test
    fun `cold peek is empty so live path can serve official rank immediately`() {
        val cache = LiveOverallSampleCache(
            api = RecordingOverallClient(parse("""{"total_players":100}""")),
            evaluate = { _, _ -> null },
            pageDelayMs = 0,
            sleeper = {}
        )
        assertNull(cache.peek())
        val status = cache.status(1)
        assertFalse(status.warm)
        assertFalse(status.available)
        assertNull(status.sampleAgeSeconds)
    }

    @Test
    fun `thin snapshot stays unavailable`() {
        val bootstrap = parse("""{"total_players":8000}""")
        val client = RecordingOverallClient(
            bootstrap = bootstrap,
            pages = mapOf(6 to pageOf(StandingRow(251, 101, "A", "Ann")))
        )
        val cache = LiveOverallSampleCache(
            api = client,
            evaluate = { _, _ -> SampledLive(50, emptyMap()) },
            samplesPerBand = 2,
            pageDelayMs = 0,
            sleeper = {}
        )
        val snap = cache.refreshNow(1)
        assertTrue(snap.sampleCount < 30)
        assertFalse(snap.available)
        assertNull(snap.rankFor(50))
    }

    @Test
    fun `successful refresh persists and a new cache reloads people rank and EO`() {
        val dir = Files.createTempDirectory("overall-snap-roundtrip")
        val path = dir.resolve("live-overall-snapshot.json").toString()
        try {
            val bootstrap = parse("""{"total_players":8000,"events":[{"id":4,"is_current":true}]}""")
            val client = RecordingOverallClient(
                bootstrap = bootstrap,
                pages = mapOf(
                    6 to pageOf(StandingRow(251, 101, "A", "Ann")),
                    16 to pageOf(StandingRow(751, 102, "D", "Dot")),
                    56 to pageOf(StandingRow(2751, 201, "B", "Bob")),
                    126 to pageOf(StandingRow(6251, 301, "C", "Cam"))
                )
            )
            val lives = mapOf(
                101 to SampledLive(120, mapOf(7 to 2)),
                102 to SampledLive(110, mapOf(7 to 1)),
                201 to SampledLive(110, mapOf(7 to 1)),
                301 to SampledLive(90, mapOf(7 to 0))
            )
            val writer = LiveOverallSampleCache(
                api = client,
                evaluate = { id, _ -> lives[id] },
                samplesPerBand = 2,
                pageDelayMs = 0,
                minPopulatedBands = 1,
                minSuccessfulSamples = 1,
                sleeper = {},
                snapshotPath = path
            )
            val saved = writer.refreshNow(4)
            assertTrue(saved.available)
            assertTrue(Files.isRegularFile(dir.resolve("live-overall-snapshot.json")))

            val reader = LiveOverallSampleCache(
                api = RecordingOverallClient(parse("""{"total_players":1}""")),
                evaluate = { _, _ -> null },
                samplesPerBand = 2,
                pageDelayMs = 0,
                minPopulatedBands = 1,
                minSuccessfulSamples = 1,
                sleeper = {},
                snapshotPath = path
            )
            val loaded = requireNotNull(reader.peek())
            assertTrue(loaded.available)
            assertEquals(saved.loadedAtMs, loaded.loadedAtMs)
            assertEquals(saved.gameweek, loaded.gameweek)
            assertEquals(saved.totalPlayers, loaded.totalPlayers)
            assertEquals(saved.bands, loaded.bands)
            assertEquals(saved.samples, loaded.samples)
            assertEquals(peopleAtTotal(110, saved.samples, saved.bands), peopleAtTotal(110, loaded.samples, loaded.bands), 1e-9)
            assertEquals(saved.rankFor(110), loaded.rankFor(110))
            assertEquals(saved.eoFor(7), loaded.eoFor(7))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt or incomplete snapshot file stays cold`() {
        val dir = Files.createTempDirectory("overall-snap-corrupt")
        try {
            val missing = LiveOverallSampleCache(
                api = RecordingOverallClient(parse("""{"total_players":100}""")),
                evaluate = { _, _ -> null },
                pageDelayMs = 0,
                sleeper = {},
                snapshotPath = dir.resolve("missing.json").toString()
            )
            assertNull(missing.peek())

            val bad = dir.resolve("corrupt.json")
            Files.writeString(bad, "{not-json")
            val corrupt = LiveOverallSampleCache(
                api = RecordingOverallClient(parse("""{"total_players":100}""")),
                evaluate = { _, _ -> null },
                pageDelayMs = 0,
                sleeper = {},
                snapshotPath = bad.toString()
            )
            assertNull(corrupt.peek())

            val thinFields = dir.resolve("partial.json")
            Files.writeString(thinFields, """{"loadedAtMs":1,"gameweek":4}""")
            val incomplete = LiveOverallSampleCache(
                api = RecordingOverallClient(parse("""{"total_players":100}""")),
                evaluate = { _, _ -> null },
                pageDelayMs = 0,
                sleeper = {},
                snapshotPath = thinFields.toString()
            )
            assertNull(incomplete.peek())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `thin sample on disk is unavailable and does not invent a rank`() {
        val dir = Files.createTempDirectory("overall-snap-thin")
        val path = dir.resolve("live-overall-snapshot.json")
        try {
            Files.writeString(
                path,
                """
                {
                  "loadedAtMs": 1,
                  "gameweek": 4,
                  "totalPlayers": 10000000,
                  "bands": [
                    {"index":0,"startRank":1,"endRank":1000,"nB":1,"nSlice":1000}
                  ],
                  "samples": [
                    {"entryId":1,"officialRank":10,"bandIndex":0,"liveTotal":100,"multipliers":{"7":2}}
                  ]
                }
                """.trimIndent()
            )
            val cache = LiveOverallSampleCache(
                api = RecordingOverallClient(parse("""{"total_players":100}""")),
                evaluate = { _, _ -> null },
                pageDelayMs = 0,
                sleeper = {},
                snapshotPath = path.toString()
            )
            val loaded = requireNotNull(cache.peek())
            assertFalse(loaded.available)
            assertFalse(cache.status(4).available)
            assertNull(loaded.rankFor(100))
            assertNull(loaded.eoFor(7))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unwritable snapshot path does not throw out of refresh`() {
        val dir = Files.createTempDirectory("overall-snap-ro")
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
            val bootstrap = parse("""{"total_players":8000}""")
            val client = RecordingOverallClient(
                bootstrap = bootstrap,
                pages = mapOf(6 to pageOf(StandingRow(251, 101, "A", "Ann")))
            )
            val cache = LiveOverallSampleCache(
                api = client,
                evaluate = { _, _ -> SampledLive(50, mapOf(1 to 1)) },
                samplesPerBand = 2,
                pageDelayMs = 0,
                minPopulatedBands = 1,
                minSuccessfulSamples = 1,
                sleeper = {},
                snapshotPath = dir.resolve("live-overall-snapshot.json").toString()
            )
            val snap = cache.refreshNow(1)
            assertEquals(1, snap.sampleCount)
            assertTrue(snap.available)
            assertEquals(50, snap.samples.single().liveTotal)
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
            dir.toFile().deleteRecursively()
        }
    }

    private fun parse(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun pageOf(vararg rows: StandingRow) = LeagueStandingsPage(
        results = padded(rows.toList()),
        hasNext = true,
        totalEntries = 8_000,
        leagueName = "Overall"
    )

    private fun padded(rows: List<StandingRow>): List<StandingRow> {
        if (rows.isEmpty()) return emptyList()
        val byIndex = rows.associateBy { rankToPageSlot(it.rank).index }
        return (0 until 50).map { idx ->
            byIndex[idx] ?: StandingRow(rank = 0, entryId = 0, entryName = "", managerName = "")
        }
    }
}

class RecordingOverallClient(
    private val bootstrap: JsonObject,
    private val pages: Map<Int, LeagueStandingsPage> = emptyMap(),
    private val failingPages: Set<Int> = emptySet()
) : FplClient {
    val pagesRequested = mutableListOf<Int>()
    override fun bootstrap(): JsonObject = bootstrap
    override fun userClassicLeagues(entryId: Int) = emptyList<LeagueRef>()
    override fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage {
        pagesRequested += page
        if (page in failingPages) throw FplApiException("standings $page failed", 500)
        return pages[page] ?: throw FplApiException("missing page $page", 500)
    }
    override fun eventLiveElementPoints(eventId: Int) = emptyMap<Int, Int>()
    override fun entryPicks(entryId: Int, eventId: Int) = emptyList<PickRow>()
}
