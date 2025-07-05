package io.github.kayden.dlq.core.performance

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class WorkStealingQueueTest {
    
    @Test
    fun `should create queue with power of 2 capacity`() {
        val queue = WorkStealingQueue<Int>(16)
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }
    
    @Test
    fun `should reject non power of 2 capacity`() {
        assertThrows<IllegalArgumentException> {
            WorkStealingQueue<Int>(15)
        }
    }
    
    @Test
    fun `should push and pop elements in LIFO order`() {
        val queue = WorkStealingQueue<Int>(16)
        
        // Push elements
        assertTrue(queue.push(1))
        assertTrue(queue.push(2))
        assertTrue(queue.push(3))
        
        assertEquals(3, queue.size())
        
        // Pop should return in LIFO order
        assertEquals(3, queue.pop())
        assertEquals(2, queue.pop())
        assertEquals(1, queue.pop())
        assertNull(queue.pop())
        
        assertTrue(queue.isEmpty())
    }
    
    @Test
    fun `should steal elements in FIFO order`() {
        val queue = WorkStealingQueue<Int>(16)
        
        // Push elements
        queue.push(1)
        queue.push(2)
        queue.push(3)
        
        // Steal should return in FIFO order
        assertEquals(1, queue.steal())
        assertEquals(2, queue.steal())
        assertEquals(3, queue.steal())
        assertNull(queue.steal())
    }
    
    @Test
    fun `should handle concurrent push and pop operations`() = runTest {
        val queue = WorkStealingQueue<Int>(1024)
        val itemCount = 10000
        val pushed = AtomicInteger(0)
        val popped = ConcurrentLinkedQueue<Int>()
        
        // Single producer pushing items
        val producer = launch {
            repeat(itemCount) { i ->
                while (!queue.push(i)) {
                    yield()
                }
                pushed.incrementAndGet()
            }
        }
        
        // Single consumer popping items
        val consumer = launch {
            while (pushed.get() < itemCount || !queue.isEmpty()) {
                queue.pop()?.let { popped.add(it) }
                yield()
            }
        }
        
        producer.join()
        consumer.join()
        
        assertEquals(itemCount, popped.size)
        assertEquals(itemCount, popped.toSet().size) // All unique items
    }
    
    @Test
    fun `should handle concurrent steal operations`() = runTest {
        val queue = WorkStealingQueue<Int>(1024)
        val itemCount = 10000
        val stolen = ConcurrentLinkedQueue<Int>()
        
        // Push all items first
        repeat(itemCount) { i ->
            assertTrue(queue.push(i))
        }
        
        // Multiple stealers
        val stealers = List(4) { 
            launch {
                while (!queue.isEmpty()) {
                    queue.steal()?.let { stolen.add(it) }
                    yield()
                }
            }
        }
        
        stealers.forEach { it.join() }
        
        assertEquals(itemCount, stolen.size)
        assertEquals(itemCount, stolen.toSet().size) // All unique items
    }
    
    @Test
    fun `should handle mixed push pop and steal operations`() = runTest {
        val queue = WorkStealingQueue<Int>(256)
        val results = ConcurrentLinkedQueue<String>()
        
        val producer = launch {
            repeat(1000) { i ->
                if (queue.push(i)) {
                    results.add("pushed-$i")
                }
                delay(1)
            }
        }
        
        val consumer = launch {
            repeat(500) {
                queue.pop()?.let { 
                    results.add("popped-$it")
                }
                delay(2)
            }
        }
        
        val stealer = launch {
            repeat(500) {
                queue.steal()?.let {
                    results.add("stolen-$it")
                }
                delay(2)
            }
        }
        
        listOf(producer, consumer, stealer).joinAll()
        
        // Verify all operations completed
        val pushed = results.count { it.startsWith("pushed") }
        val popped = results.count { it.startsWith("popped") }
        val stolen = results.count { it.startsWith("stolen") }
        
        assertEquals(1000, pushed)
        assertEquals(1000, popped + stolen)
    }
    
    @Test
    fun `should return false when queue is full`() {
        val queue = WorkStealingQueue<Int>(4) // Small capacity
        
        // Fill the queue
        assertTrue(queue.push(1))
        assertTrue(queue.push(2))
        assertTrue(queue.push(3))
        assertTrue(queue.push(4))
        
        // Next push should fail
        assertFalse(queue.push(5))
        
        // After pop, push should succeed
        assertEquals(4, queue.pop())
        assertTrue(queue.push(5))
    }
    
    @Test
    fun `should steal batch of elements`() {
        val queue = WorkStealingQueue<Int>(64)
        
        // Push 20 elements
        repeat(20) { i ->
            assertTrue(queue.push(i))
        }
        
        // Steal batch of 10
        val stolen = queue.stealBatch(10)
        assertEquals(10, stolen.size)
        assertEquals((0..9).toList(), stolen)
        
        // Should have 10 elements left
        assertEquals(10, queue.size())
    }
    
    @Test
    fun `should steal partial batch when not enough elements`() {
        val queue = WorkStealingQueue<Int>(64)
        
        // Push only 5 elements
        repeat(5) { i ->
            assertTrue(queue.push(i))
        }
        
        // Try to steal 10
        val stolen = queue.stealBatch(10)
        assertEquals(3, stolen.size) // Steals half + 1
    }
    
    @Test
    fun `should clear queue`() {
        val queue = WorkStealingQueue<Int>(16)
        
        // Push elements
        repeat(10) { i ->
            assertTrue(queue.push(i))
        }
        
        assertEquals(10, queue.size())
        
        // Clear
        queue.clear()
        
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
        assertNull(queue.pop())
        assertNull(queue.steal())
    }
}

class WorkStealingQueuePoolTest {
    
    @Test
    fun `should create pool with specified number of queues`() {
        val pool = WorkStealingQueuePool<Int>(4, 16)
        
        // Should be able to access all queues
        repeat(4) { i ->
            val queue = pool.getQueue(i)
            assertNotNull(queue)
            assertTrue(queue.isEmpty())
        }
    }
    
    @Test
    fun `should reject invalid worker id`() {
        val pool = WorkStealingQueuePool<Int>(4, 16)
        
        assertThrows<IllegalArgumentException> {
            pool.getQueue(4) // Out of bounds
        }
        
        assertThrows<IllegalArgumentException> {
            pool.getQueue(-1) // Negative
        }
    }
    
    @Test
    fun `should steal from other workers`() {
        val pool = WorkStealingQueuePool<Int>(4, 64)
        
        // Fill worker 2's queue
        val queue2 = pool.getQueue(2)
        repeat(20) { i ->
            assertTrue(queue2.push(i))
        }
        
        // Worker 0 steals from others
        val stolen = pool.stealFor(0, 5)
        assertEquals(5, stolen.size)
        
        // Verify stolen from worker 2
        assertEquals(15, queue2.size())
    }
    
    @Test
    fun `should return empty when no work to steal`() {
        val pool = WorkStealingQueuePool<Int>(4, 16)
        
        // All queues empty
        val stolen = pool.stealFor(0, 5)
        assertTrue(stolen.isEmpty())
    }
    
    @Test
    fun `should calculate total size`() {
        val pool = WorkStealingQueuePool<Int>(3, 64)
        
        pool.getQueue(0).push(1)
        pool.getQueue(0).push(2)
        pool.getQueue(1).push(3)
        pool.getQueue(2).push(4)
        pool.getQueue(2).push(5)
        
        assertEquals(5, pool.totalSize())
    }
    
    @Test
    fun `should find busiest queue`() {
        val pool = WorkStealingQueuePool<Int>(4, 64)
        
        // Fill queues with different amounts
        repeat(2) { pool.getQueue(0).push(it) }
        repeat(5) { pool.getQueue(1).push(it) }
        repeat(1) { pool.getQueue(2).push(it) }
        repeat(3) { pool.getQueue(3).push(it) }
        
        val (queueId, size) = pool.findBusiestQueue()
        assertEquals(1, queueId)
        assertEquals(5, size)
    }
    
    @Test
    fun `should balance work across queues`() {
        val pool = WorkStealingQueuePool<Int>(4, 256)
        
        // Create imbalanced load
        repeat(40) { pool.getQueue(0).push(it) }
        repeat(5) { pool.getQueue(1).push(it) }
        repeat(5) { pool.getQueue(2).push(it) }
        repeat(0) { pool.getQueue(3).push(it) }
        
        val totalBefore = pool.totalSize()
        
        // Balance
        pool.balance()
        
        val totalAfter = pool.totalSize()
        
        // Total should remain the same
        assertEquals(totalBefore, totalAfter)
        
        // Load should be more balanced
        val sizes = (0..3).map { pool.getQueue(it).size() }
        val variance = sizes.map { size ->
            val avg = sizes.average()
            (size - avg) * (size - avg)
        }.average()
        
        // Variance should be relatively small
        assertTrue(variance < 100, "Variance too high: $variance")
    }
}