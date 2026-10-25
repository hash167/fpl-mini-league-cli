import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-request record of how old the cached FPL data behind a response is, and whether any of it
 * was served stale because a reload from FPL failed. Carried on a ThreadLocal and propagated
 * into pool tasks with [Freshness.wrap].
 */
class FreshnessRecorder {
    private val maxServedAgeMs = AtomicLong(0)
    private val maxStaleAgeMs = AtomicLong(0)
    private val staleFlag = AtomicBoolean(false)

    fun served(ageMs: Long) { maxServedAgeMs.accumulateAndGet(ageMs.coerceAtLeast(0), ::maxOf) }
    fun stale(ageMs: Long) {
        staleFlag.set(true)
        served(ageMs)
        maxStaleAgeMs.accumulateAndGet(ageMs.coerceAtLeast(0), ::maxOf)
    }

    val isStale: Boolean get() = staleFlag.get()
    /**
     * The response's `dataAge`: how old the data served *because FPL failed* is. 0 when nothing was
     * served stale. Normal cache hits (60s entry caches, 5-min transfers cache, idle caches being
     * refreshed) don't count: they made "Updated 2m ago" / "21m ago" show while FPL was fine.
     */
    val ageSeconds: Long get() = maxStaleAgeMs.get() / 1000L
    /** Oldest cache entry touched (diagnostics only). */
    val servedAgeSeconds: Long get() = maxServedAgeMs.get() / 1000L
}

object Freshness {
    private val current = ThreadLocal<FreshnessRecorder?>()

    fun served(ageMs: Long) { current.get()?.served(ageMs) }
    fun stale(ageMs: Long) { current.get()?.stale(ageMs) }

    /** Run [block] with a fresh recorder (or join the caller's) and return both. */
    fun <T> track(block: () -> T): Pair<T, FreshnessRecorder> {
        val outer = current.get()
        val recorder = outer ?: FreshnessRecorder()
        if (outer == null) current.set(recorder)
        try {
            return block() to recorder
        } finally {
            if (outer == null) current.remove()
        }
    }

    /** Capture the submitting thread's recorder so pool work counts toward the same request. */
    fun <T> wrap(task: Callable<T>): Callable<T> {
        val captured = current.get() ?: return task
        return Callable {
            val previous = current.get()
            current.set(captured)
            try { task.call() } finally { if (previous == null) current.remove() else current.set(previous) }
        }
    }
}
