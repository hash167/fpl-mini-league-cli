import kotlin.math.roundToLong

const val OVERALL_CLASSIC_LEAGUE_ID = 314
const val STANDINGS_PAGE_SIZE = 50
const val SAMPLES_PER_BAND = 35
const val TARGET_SAMPLE_COUNT = 9 * SAMPLES_PER_BAND
const val MIN_POPULATED_BANDS = 3
const val MIN_SUCCESSFUL_SAMPLES = 30
const val SAMPLE_CACHE_TTL_MS = 60 * 60 * 1000L

/**
 * Inclusive official-rank edges. These are our documented 9 bands, not claimed
 * as livefpl bucket edges. The last band ends at bootstrap-static total_players.
 */
val OVERALL_RANK_BAND_EDGES: List<Pair<Int, Int>> = listOf(
    1 to 1_000,
    1_001 to 10_000,
    10_001 to 100_000,
    100_001 to 500_000,
    500_001 to 1_000_000,
    1_000_001 to 2_000_000,
    2_000_001 to 4_000_000,
    4_000_001 to 7_000_000,
    7_000_001 to Int.MAX_VALUE
)

data class RankBand(
    val index: Int,
    val startRank: Int,
    val endRank: Int
) {
    val nSlice: Int get() = (endRank - startRank + 1).coerceAtLeast(0)
}

data class BandSampleStats(
    val index: Int,
    val startRank: Int,
    val endRank: Int,
    val nB: Int,
    val nSlice: Int
)

data class SampledManager(
    val entryId: Int,
    val officialRank: Int,
    val bandIndex: Int,
    val liveTotal: Int,
    val multipliers: Map<Int, Int>
)

data class PageSlot(
    val page: Int,
    val index: Int
)

data class SampledLive(
    val liveTotal: Int,
    val multipliers: Map<Int, Int>
)

fun rankBands(totalPlayers: Int): List<RankBand> {
    if (totalPlayers <= 0) return emptyList()
    return OVERALL_RANK_BAND_EDGES.mapIndexedNotNull { index, (start, rawEnd) ->
        if (start > totalPlayers) return@mapIndexedNotNull null
        val end = minOf(rawEnd, totalPlayers)
        if (end < start) null else RankBand(index, start, end)
    }
}

fun bandForRank(rank: Int, bands: List<RankBand>): RankBand? =
    bands.firstOrNull { rank in it.startRank..it.endRank }

/** Midpoints of [count] equal slices, clipped to the band. */
fun sampleTargetRanks(startRank: Int, endRank: Int, count: Int = SAMPLES_PER_BAND): List<Int> {
    val size = endRank - startRank + 1
    if (size <= 0 || count <= 0) return emptyList()
    val n = minOf(count, size)
    return (0 until n).map { i ->
        val offset = (((i * 2L + 1L) * size) / (2L * n)).toInt().coerceIn(0, size - 1)
        startRank + offset
    }.distinct()
}

fun rankToPageSlot(rank: Int, pageSize: Int = STANDINGS_PAGE_SIZE): PageSlot {
    val safeRank = rank.coerceAtLeast(1)
    val zero = safeRank - 1
    return PageSlot(page = zero / pageSize + 1, index = zero % pageSize)
}

fun isSampleThin(
    bands: List<BandSampleStats>,
    minPopulatedBands: Int = MIN_POPULATED_BANDS,
    minSuccessfulSamples: Int = MIN_SUCCESSFUL_SAMPLES
): Boolean {
    val populated = bands.count { it.nB > 0 }
    val total = bands.sumOf { it.nB }
    return populated < minPopulatedBands || total < minSuccessfulSamples
}

/**
 * Stratified estimate of how many managers have live season total [liveTotal].
 * people(T) = sum_over_bands (k_b / n_b) * N_b
 * A band with n_b == 0 contributes 0. Never uses naive (k / 315) * N_total.
 */
fun peopleAtTotal(liveTotal: Int, samples: List<SampledManager>, bands: List<BandSampleStats>): Double {
    if (samples.isEmpty()) return 0.0
    var sum = 0.0
    for (band in bands) {
        if (band.nB <= 0 || band.nSlice <= 0) continue
        val kB = samples.count { it.bandIndex == band.index && it.liveTotal == liveTotal }
        if (kB == 0) continue
        sum += (kB.toDouble() / band.nB) * band.nSlice
    }
    return sum
}

fun peopleHistogram(samples: List<SampledManager>, bands: List<BandSampleStats>): Map<Int, Double> {
    return samples.map { it.liveTotal }.toSet()
        .associateWith { t -> peopleAtTotal(t, samples, bands) }
        .filterValues { it > 0.0 }
}

/**
 * Estimated overall rank = 1 + sum_{T > myLiveTotal} people(T).
 * Ties stay tied: people at exactly [myLiveTotal] do not count as above you.
 * Returns null when the sample is too thin — never invent a number.
 */
fun estimatedOverallRank(
    myLiveTotal: Int,
    samples: List<SampledManager>,
    bands: List<BandSampleStats>,
    minPopulatedBands: Int = MIN_POPULATED_BANDS,
    minSuccessfulSamples: Int = MIN_SUCCESSFUL_SAMPLES
): Long? {
    if (isSampleThin(bands, minPopulatedBands, minSuccessfulSamples)) return null
    var above = 0.0
    for (band in bands) {
        if (band.nB <= 0 || band.nSlice <= 0) continue
        val kAbove = samples.count { it.bandIndex == band.index && it.liveTotal > myLiveTotal }
        if (kAbove == 0) continue
        above += (kAbove.toDouble() / band.nB) * band.nSlice
    }
    return 1L + above.roundToLong()
}

/**
 * Weighted field average of m (0 not in XI, 1 starter, 2 captain, 3 TC).
 * Each sampled manager is weighted by N_b / n_b for their band.
 * EO = start% + captain% + 2*TC% = mean m.
 */
fun effectiveOwnership(
    elementId: Int,
    samples: List<SampledManager>,
    bands: List<BandSampleStats>
): Double? {
    var weighted = 0.0
    var weight = 0.0
    val byIndex = bands.associateBy { it.index }
    for (sample in samples) {
        val band = byIndex[sample.bandIndex] ?: continue
        if (band.nB <= 0 || band.nSlice <= 0) continue
        val w = band.nSlice.toDouble() / band.nB
        val m = sample.multipliers[elementId] ?: 0
        weighted += w * m
        weight += w
    }
    if (weight == 0.0) return null
    return weighted / weight
}
