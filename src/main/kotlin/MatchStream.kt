import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

private val streamLog = LoggerFactory.getLogger("fpl.stream")

/** One SSE client. Implementations must be non-blocking-ish and report closure. */
interface StreamSubscriber {
    /** Returns false when the client is gone. */
    fun send(event: String, id: Long, data: String): Boolean
    fun comment(text: String): Boolean
    fun close()
    val isClosed: Boolean
}

data class MatchStreamKey(val fixtureId: Int, val gameweek: Int?, val entryId: Int?)

/**
 * Push hub for live match views. While a match has subscribers, a single shared poller per
 * match reloads the merged FPL+ESPN payload every [pollMs] and pushes a full snapshot only when
 * the serialized payload changed. Pollers stop when the last subscriber leaves or the match
 * reaches full time (a final event is sent and streams are closed).
 */
fun MatchStreamHub(
    load: (MatchStreamKey) -> MatchDetail,
    serialize: (MatchDetail) -> String,
    scheduler: ScheduledExecutorService = defaultStreamScheduler(),
    pollMs: Long = 7_000,
    idlePollMs: Long = 30_000,
    keepaliveMs: Long = 15_000,
    maxSubscribersPerMatch: Int = 200,
    maxMatches: Int = 40
): StreamHub<MatchStreamKey, MatchDetail> = StreamHub(
    load, serialize,
    isFinal = { it.status.phase == "ft" },
    isLive = { it.live },
    scheduler = scheduler, pollMs = pollMs, idlePollMs = idlePollMs, keepaliveMs = keepaliveMs,
    maxSubscribersPerMatch = maxSubscribersPerMatch, maxMatches = maxMatches
)

/** GW scoreboard stream for the homepage fixtures strip; final once every fixture has finished. */
fun ScoreboardStreamHub(
    load: (Int) -> ScoreboardResponse,
    serialize: (ScoreboardResponse) -> String,
    scheduler: ScheduledExecutorService = defaultStreamScheduler(),
    pollMs: Long = 7_000,
    keepaliveMs: Long = 15_000
): StreamHub<Int, ScoreboardResponse> = StreamHub(
    load, serialize,
    isFinal = { sb -> sb.fixtures.isNotEmpty() && sb.fixtures.all { it.finished } },
    isLive = { it.live },
    scheduler = scheduler, pollMs = pollMs, idlePollMs = 30_000, keepaliveMs = keepaliveMs,
    maxSubscribersPerMatch = 500, maxMatches = 4
)

fun defaultStreamScheduler(): ScheduledExecutorService = Executors.newScheduledThreadPool(4) { r ->
    Thread(r, "match-stream").apply { isDaemon = true }
}

class StreamHub<K : Any, T : Any>(
    private val load: (K) -> T,
    private val serialize: (T) -> String,
    private val isFinal: (T) -> Boolean,
    private val isLive: (T) -> Boolean,
    private val scheduler: ScheduledExecutorService = defaultStreamScheduler(),
    private val pollMs: Long = 7_000,
    private val idlePollMs: Long = 30_000,
    private val keepaliveMs: Long = 15_000,
    private val maxSubscribersPerMatch: Int = 200,
    private val maxMatches: Int = 40
) {
    private inner class Channel(val key: K) {
        val subscribers = CopyOnWriteArrayList<StreamSubscriber>()
        val seq = AtomicLong(0)
        @Volatile var lastJson: String? = null
        @Volatile var lastDetail: T? = null
        @Volatile var poller: ScheduledFuture<*>? = null
        @Volatile var pollEvery: Long = 0
        @Volatile var pushes = 0
    }

    private val channels = ConcurrentHashMap<K, Channel>()
    private val keepalive: ScheduledFuture<*> =
        scheduler.scheduleWithFixedDelay({ safely("keepalive") { keepaliveTick() } }, keepaliveMs, keepaliveMs, TimeUnit.MILLISECONDS)

    fun activeMatches(): Int = channels.size
    fun totalSubscribers(): Int = channels.values.sumOf { ch -> ch.subscribers.count { !it.isClosed } }
    fun subscriberCount(key: K): Int = channels[key]?.subscribers?.count { !it.isClosed } ?: 0
    fun isPolling(key: K): Boolean = channels[key]?.poller?.let { !it.isDone } == true
    fun pushCount(key: K): Int = channels[key]?.pushes ?: 0

    /** Returns false when the subscriber was rejected (and closed). */
    fun subscribe(key: K, sub: StreamSubscriber): Boolean {
        val channel: Channel
        synchronized(this) {
            val existing = channels[key]
            if (existing == null && channels.size >= maxMatches) return reject(sub, "too many live matches streaming")
            channel = existing ?: Channel(key).also { channels[key] = it }
            channel.subscribers.removeIf { it.isClosed }
            if (channel.subscribers.size >= maxSubscribersPerMatch) return reject(sub, "too many viewers")
            channel.subscribers += sub
        }
        val detail = channel.lastDetail ?: try {
            load(key).also { d ->
                channel.lastDetail = d
                channel.lastJson = serialize(d)
            }
        } catch (e: Exception) {
            sub.send("error", 0, "{\"error\":${jsonString(e.message ?: "load failed")}}")
            unsubscribe(key, sub)
            sub.close()
            return false
        }
        val json = channel.lastJson ?: serialize(detail)
        if (isFinal(detail)) {
            sub.send("final", channel.seq.incrementAndGet(), json)
            unsubscribe(key, sub)
            sub.close()
            return true
        }
        if (!sub.send("snapshot", channel.seq.incrementAndGet(), json)) {
            unsubscribe(key, sub)
            return false
        }
        ensurePoller(channel, if (isLive(detail)) pollMs else idlePollMs)
        return true
    }

    fun unsubscribe(key: K, sub: StreamSubscriber) {
        val channel = channels[key] ?: return
        channel.subscribers.remove(sub)
        stopIfIdle(channel)
    }

    fun shutdown() {
        keepalive.cancel(false)
        channels.values.forEach { ch -> ch.poller?.cancel(false); ch.subscribers.forEach { it.close() } }
        channels.clear()
        scheduler.shutdownNow()
    }

    /** Exposed for tests: run one poll cycle synchronously. */
    fun pollNow(key: K) { channels[key]?.let { poll(it) } }

    /** Exposed for tests: run one keepalive cycle synchronously. */
    fun keepaliveNow() = keepaliveTick()

    private fun reject(sub: StreamSubscriber, why: String): Boolean {
        sub.send("error", 0, "{\"error\":${jsonString(why)}}")
        sub.close()
        return false
    }

    @Synchronized
    private fun ensurePoller(channel: Channel, every: Long) {
        val running = channel.poller?.let { !it.isDone } == true
        if (running && channel.pollEvery == every) return
        channel.poller?.cancel(false)
        channel.pollEvery = every
        channel.poller = scheduler.scheduleWithFixedDelay({ safely("poll") { poll(channel) } }, every, every, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun stopIfIdle(channel: Channel) {
        channel.subscribers.removeIf { it.isClosed }
        if (channel.subscribers.isEmpty()) {
            channel.poller?.cancel(false)
            channel.poller = null
            channels.remove(channel.key, channel)
        }
    }

    private fun poll(channel: Channel) {
        stopIfIdle(channel)
        if (channels[channel.key] !== channel) return
        val detail = try {
            load(channel.key)
        } catch (_: Exception) {
            return // keep the last snapshot; try again next tick
        }
        val json = serialize(detail)
        val final = isFinal(detail)
        if (json != channel.lastJson || final) {
            channel.lastJson = json
            channel.lastDetail = detail
            channel.pushes += 1
            broadcast(channel, if (final) "final" else "snapshot", json)
        }
        if (final) {
            channel.subscribers.forEach { it.close() }
            channel.subscribers.clear()
            stopIfIdle(channel)
            return
        }
        ensurePoller(channel, if (isLive(detail)) pollMs else idlePollMs)
    }

    private fun broadcast(channel: Channel, event: String, json: String) {
        val id = channel.seq.incrementAndGet()
        channel.subscribers.forEach { sub ->
            if (sub.isClosed || !sub.send(event, id, json)) channel.subscribers.remove(sub)
        }
        stopIfIdle(channel)
    }

    private fun keepaliveTick() {
        channels.values.forEach { channel ->
            channel.subscribers.forEach { sub ->
                if (sub.isClosed || !sub.comment("keepalive")) channel.subscribers.remove(sub)
            }
            stopIfIdle(channel)
        }
    }

    /**
     * Scheduled-task guard: an Exception must not cancel the periodic poll/keepalive (the executor
     * silently stops rescheduling a task that throws), so log it and carry on. Errors (OOM etc.)
     * propagate.
     */
    private inline fun safely(task: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            streamLog.warn("match stream {} failed: {}", task, e.toString(), e)
        }
    }
}

fun jsonString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
