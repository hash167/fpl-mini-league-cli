import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

const val DEFAULT_PRICES_SNAPSHOT_PATH = "data/prices-snapshot.json"

data class OfficialPriceMover(
    val id: Int,
    val webName: String,
    val team: String,
    val position: String,
    val oldCost: Int,
    val newCost: Int,
    val delta: Int,
    val oldPounds: String,
    val newPounds: String
)

data class OfficialPricesResponse(
    val computedAt: String,
    val snapshotAt: String,
    val previousSnapshotAt: String?,
    val rises: List<OfficialPriceMover>,
    val falls: List<OfficialPriceMover>,
    val unchangedCount: Int,
    val firstRun: Boolean = false
)

fun tenthsToPoundsString(tenths: Int): String = "£${"%.1f".format(tenths / 10.0)}m"

fun computeOfficialMovers(
    previousCosts: Map<Int, Int>,
    players: Collection<PlayerInfo>
): Triple<List<OfficialPriceMover>, List<OfficialPriceMover>, Int> {
    val rises = mutableListOf<OfficialPriceMover>()
    val falls = mutableListOf<OfficialPriceMover>()
    var unchanged = 0
    for (p in players) {
        val newCost = p.nowCost ?: continue
        val oldCost = previousCosts[p.id]
        if (oldCost == null) {
            unchanged += 1
            continue
        }
        val delta = newCost - oldCost
        if (delta == 0) {
            unchanged += 1
            continue
        }
        val mover = OfficialPriceMover(
            id = p.id,
            webName = p.webName,
            team = p.teamShortName,
            position = elementTypeName(p.elementType),
            oldCost = oldCost,
            newCost = newCost,
            delta = delta,
            oldPounds = tenthsToPoundsString(oldCost),
            newPounds = tenthsToPoundsString(newCost)
        )
        if (delta > 0) rises += mover else falls += mover
    }
    return Triple(
        rises.sortedWith(compareByDescending<OfficialPriceMover> { it.delta }.thenBy { it.webName }),
        falls.sortedWith(compareBy<OfficialPriceMover> { it.delta }.thenBy { it.webName }),
        unchanged
    )
}

class OfficialPriceSnapshotStore(
    snapshotPath: String? = DEFAULT_PRICES_SNAPSHOT_PATH,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val snapshotFile: File? =
        snapshotPath?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) }

    @Volatile
    private var last: OfficialPricesResponse? = loadLast()

    fun latest(): OfficialPricesResponse {
        last?.let { return it }
        return OfficialPricesResponse(
            computedAt = iso(clock()),
            snapshotAt = "",
            previousSnapshotAt = null,
            rises = emptyList(),
            falls = emptyList(),
            unchangedCount = 0,
            firstRun = true
        )
    }

    @Synchronized
    fun refresh(players: Collection<PlayerInfo>): OfficialPricesResponse {
        val nowIso = iso(clock())
        val persisted = loadPersisted()
        val currentCosts = players.mapNotNull { p ->
            val cost = p.nowCost ?: return@mapNotNull null
            p.id to cost
        }.toMap()
        val response = if (persisted == null) {
            OfficialPricesResponse(
                computedAt = nowIso,
                snapshotAt = nowIso,
                previousSnapshotAt = null,
                rises = emptyList(),
                falls = emptyList(),
                unchangedCount = currentCosts.size,
                firstRun = true
            )
        } else {
            val (rises, falls, unchanged) = computeOfficialMovers(persisted.costs, players)
            OfficialPricesResponse(
                computedAt = nowIso,
                snapshotAt = nowIso,
                previousSnapshotAt = persisted.snapshotAt,
                rises = rises,
                falls = falls,
                unchangedCount = unchanged,
                firstRun = false
            )
        }
        persist(
            PersistedPrices(
                snapshotAt = response.snapshotAt,
                previousSnapshotAt = response.previousSnapshotAt,
                computedAt = response.computedAt,
                firstRun = response.firstRun,
                unchangedCount = response.unchangedCount,
                costs = currentCosts,
                rises = response.rises.map { it.toPersisted() },
                falls = response.falls.map { it.toPersisted() }
            )
        )
        last = response
        return response
    }

    private fun loadLast(): OfficialPricesResponse? {
        val p = loadPersisted() ?: return null
        return OfficialPricesResponse(
            computedAt = p.computedAt,
            snapshotAt = p.snapshotAt,
            previousSnapshotAt = p.previousSnapshotAt,
            rises = p.rises.map { it.toMover() },
            falls = p.falls.map { it.toMover() },
            unchangedCount = p.unchangedCount,
            firstRun = p.firstRun
        )
    }

    private fun loadPersisted(): PersistedPrices? {
        val dest = snapshotFile ?: return null
        return try {
            if (!dest.isFile) return null
            persistJson.decodeFromString(PersistedPrices.serializer(), dest.readText())
        } catch (e: Exception) {
            log.warn("Failed to load prices snapshot from {}: {}", dest, e.message)
            null
        }
    }

    private fun persist(data: PersistedPrices) {
        val dest = snapshotFile ?: return
        var tmp: File? = null
        try {
            val parent = dest.parentFile ?: File(".")
            if (!parent.exists() && !parent.mkdirs()) {
                log.warn("Could not create prices snapshot directory {}", parent)
                return
            }
            tmp = File(parent, dest.name + ".tmp")
            tmp.writeText(persistJson.encodeToString(PersistedPrices.serializer(), data))
            try {
                Files.move(
                    tmp.toPath(),
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            log.warn("Failed to persist prices snapshot to {}: {}", dest, e.message)
            try {
                tmp?.delete()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(OfficialPriceSnapshotStore::class.java)
        private val persistJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val isoFmt = DateTimeFormatter.ISO_INSTANT

        fun iso(epochMs: Long): String = isoFmt.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))
    }
}

@Serializable
private data class PersistedPrices(
    val snapshotAt: String,
    val previousSnapshotAt: String? = null,
    val computedAt: String,
    val firstRun: Boolean = false,
    val unchangedCount: Int = 0,
    val costs: Map<Int, Int> = emptyMap(),
    val rises: List<PersistedMover> = emptyList(),
    val falls: List<PersistedMover> = emptyList()
)

@Serializable
private data class PersistedMover(
    val id: Int,
    val webName: String,
    val team: String,
    val position: String,
    val oldCost: Int,
    val newCost: Int,
    val delta: Int,
    val oldPounds: String,
    val newPounds: String
)

private fun OfficialPriceMover.toPersisted() = PersistedMover(
    id, webName, team, position, oldCost, newCost, delta, oldPounds, newPounds
)

private fun PersistedMover.toMover() = OfficialPriceMover(
    id, webName, team, position, oldCost, newCost, delta, oldPounds, newPounds
)
