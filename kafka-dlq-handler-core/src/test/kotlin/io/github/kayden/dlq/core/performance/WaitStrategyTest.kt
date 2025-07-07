package io.github.kayden.dlq.core.performance

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaitStrategyTest {
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `BusySpinWaitStrategy should wait for sequence`() {
        val strategy = BusySpinWaitStrategy()
        val cursor = SequenceImpl(0L)
        val dependent = SequenceImpl(0L)
        val barrier = TestBarrier()
        
        // Test immediate availability
        val available = strategy.waitFor(0L, cursor, dependent, barrier)
        assertEquals(0L, available)
        
        // Test waiting in another thread
        runBlocking {
            val waitJob = async(Dispatchers.Default) {
                strategy.waitFor(5L, cursor, dependent, barrier)
            }
            
            delay(10)
            dependent.set(5L)
            
            val result = waitJob.await()
            assertEquals(5L, result)
        }
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `YieldingWaitStrategy should wait for sequence`() {
        val strategy = YieldingWaitStrategy()
        val cursor = SequenceImpl(0L)
        val dependent = SequenceImpl(0L)
        val barrier = TestBarrier()
        
        runBlocking {
            val waitJob = async(Dispatchers.Default) {
                strategy.waitFor(10L, cursor, dependent, barrier)
            }
            
            delay(10)
            dependent.set(10L)
            
            val result = waitJob.await()
            assertEquals(10L, result)
        }
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `SleepingWaitStrategy should wait for sequence`() {
        val strategy = SleepingWaitStrategy(100_000L) // 100 microseconds
        val cursor = SequenceImpl(0L)
        val dependent = SequenceImpl(0L)
        val barrier = TestBarrier()
        
        runBlocking {
            val waitJob = async(Dispatchers.Default) {
                strategy.waitFor(5L, cursor, dependent, barrier)
            }
            
            delay(10)
            dependent.set(5L)
            
            val result = waitJob.await()
            assertEquals(5L, result)
        }
    }
    
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `BlockingWaitStrategy should wait and signal`() {
        val strategy = BlockingWaitStrategy()
        val cursor = SequenceImpl(0L)
        val dependent = SequenceImpl(0L)
        val barrier = TestBarrier()
        
        runBlocking {
            val waitJob = async(Dispatchers.Default) {
                strategy.waitFor(3L, cursor, dependent, barrier)
            }
            
            delay(10)
            cursor.set(3L)
            dependent.set(3L)
            strategy.signalAllWhenBlocking()
            
            val result = waitJob.await()
            assertEquals(3L, result)
        }
    }
    
    @Test
    fun `wait strategies should handle alerts`() {
        val strategies = listOf(
            BusySpinWaitStrategy(),
            YieldingWaitStrategy(),
            SleepingWaitStrategy(),
            BlockingWaitStrategy()
        )
        
        strategies.forEach { strategy ->
            val cursor = SequenceImpl(0L)
            val dependent = SequenceImpl(0L)
            val barrier = TestBarrier()
            
            runBlocking {
                val waitJob = async(Dispatchers.Default) {
                    try {
                        strategy.waitFor(100L, cursor, dependent, barrier)
                        null
                    } catch (e: AlertException) {
                        e
                    }
                }
                
                delay(10)
                barrier.alert()
                
                val result = waitJob.await()
                assertTrue(result is AlertException, 
                    "${strategy::class.simpleName} should throw AlertException on alert")
            }
        }
    }
    
    @Test
    fun `wait strategies performance comparison`() {
        val strategies = mapOf(
            "BusySpin" to BusySpinWaitStrategy(),
            "Yielding" to YieldingWaitStrategy(),
            "Sleeping" to SleepingWaitStrategy(100_000L),
            "Blocking" to BlockingWaitStrategy()
        )
        
        strategies.forEach { (name, strategy) ->
            val cursor = SequenceImpl(0L)
            val dependent = SequenceImpl(0L)
            val barrier = TestBarrier()
            
            runBlocking {
                val timeMillis = measureTimeMillis {
                    val waitJob = async(Dispatchers.Default) {
                        strategy.waitFor(1000L, cursor, dependent, barrier)
                    }
                    
                    // Simulate delayed publish
                    delay(1)
                    if (strategy is BlockingWaitStrategy) {
                        cursor.set(1000L)
                        strategy.signalAllWhenBlocking()
                    }
                    dependent.set(1000L)
                    
                    waitJob.await()
                }
                
                println("$name strategy wait time: ${timeMillis}ms")
            }
        }
    }
    
    private class TestBarrier : SequenceBarrier {
        @Volatile
        private var alerted = false
        
        override fun waitFor(sequence: Long): Long {
            throw UnsupportedOperationException("Not used in tests")
        }
        
        override fun getCursor(): Long = 0L
        
        override fun isAlerted(): Boolean = alerted
        
        override fun alert() {
            alerted = true
        }
        
        override fun clearAlert() {
            alerted = false
        }
        
        override fun checkAlert() {
            if (alerted) {
                throw AlertException()
            }
        }
    }
}