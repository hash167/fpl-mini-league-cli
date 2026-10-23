import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CacheBoundsTest {
    @Test
    fun `per-key caches never hold more than maxKeys entries`() {
        val caches = BoundedTtlCaches<Int, String>(ttlMs = 60_000, maxKeys = 3)
        (1..50).forEach { k -> assertEquals("v$k", caches.get(k) { "v$k" }) }
        assertEquals(3, caches.size())
    }

    @Test
    fun `cached value is reused within ttl and least recently used key is evicted`() {
        val caches = BoundedTtlCaches<Int, Int>(ttlMs = 60_000, maxKeys = 2)
        var loads = 0
        fun load(k: Int) = caches.get(k) { loads += 1; k }
        load(1); load(2); load(1)           // 1 reused
        assertEquals(2, loads)
        load(3)                              // evicts 2 (LRU)
        load(1)                              // still cached
        assertEquals(3, loads)
        load(2)                              // reloaded
        assertEquals(4, loads)
        assertTrue(caches.size() <= 2)
    }
}
