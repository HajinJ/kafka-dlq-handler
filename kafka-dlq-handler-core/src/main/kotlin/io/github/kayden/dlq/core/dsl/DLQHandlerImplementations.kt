package io.github.kayden.dlq.core.dsl

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.performance.*
import io.github.kayden.dlq.core.storage.DLQStorage
import kotlinx.coroutines.withTimeout
import java.time.Duration

/**
 * Batch-based DLQ handler implementation.
 * 
 * @since 0.1.0
 */
class BatchDLQHandler(
    private val processor: BatchProcessor,
    private val storage: DLQStorage,
    private val errorHandler: ErrorHandler,
    private val metricsCollector: MetricsCollector,
    private val config: ProcessingConfiguration
) : DLQHandler {
    
    override suspend fun start() {
        processor.start()
    }
    
    override suspend fun stop(timeout: Duration) {
        processor.stop(timeout)
    }
    
    override suspend fun process(record: DLQRecord): Result<Unit> {
        return try {
            withTimeout(config.processingTimeout.toMillis()) {
                val result = config.recordProcessor?.invoke(record)
                    ?: Result.success(Unit)
                
                if (result.isSuccess) {
                    storage.save(record)
                    metricsCollector.recordProcessed(1, Duration.ZERO)
                } else {
                    val error = result.exceptionOrNull() ?: Exception("Unknown error")
                    errorHandler.handleError(record, error)
                    metricsCollector.recordError(1, error.javaClass.simpleName)
                }
                
                result
            }
        } catch (e: Exception) {
            errorHandler.handleError(record, e)
            metricsCollector.recordError(1, e.javaClass.simpleName)
            Result.failure(e)
        }
    }
    
    override suspend fun processBatch(records: List<DLQRecord>): BatchResult {
        return try {
            val result = processor.processBatch(records)
            
            metricsCollector.recordProcessed(
                result.successful.toLong(),
                result.duration
            )
            
            if (result.failed > 0) {
                metricsCollector.recordError(
                    result.failed.toLong(),
                    "BatchProcessingError"
                )
            }
            
            result
        } catch (e: Exception) {
            errorHandler.handleBatchError(records, e)
            metricsCollector.recordError(records.size.toLong(), e.javaClass.simpleName)
            
            BatchResult(
                successful = 0,
                failed = records.size,
                duration = Duration.ZERO
            )
        }
    }
    
    override fun getMetrics(): DLQHandlerMetrics {
        val processorMetrics = processor.getMetrics()
        val collectorMetrics = metricsCollector.getMetrics()
        
        return DLQHandlerMetrics(
            totalProcessed = processorMetrics.totalProcessed,
            totalErrors = collectorMetrics.totalErrors,
            totalDropped = collectorMetrics.totalDropped
        )
    }
}

/**
 * Parallel processing DLQ handler implementation.
 * 
 * @since 0.1.0
 */
class ParallelDLQHandler(
    private val processor: ParallelProcessor,
    private val storage: DLQStorage,
    private val errorHandler: ErrorHandler,
    private val metricsCollector: MetricsCollector,
    private val config: ProcessingConfiguration
) : DLQHandler {
    
    override suspend fun start() {
        processor.start()
    }
    
    override suspend fun stop(timeout: Duration) {
        processor.stop(timeout)
    }
    
    override suspend fun process(record: DLQRecord): Result<Unit> {
        return try {
            val ticket = processor.submitRecord(record)
            val result = ticket.await()
            
            if (result.isSuccess) {
                metricsCollector.recordProcessed(1, Duration.ZERO)
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                metricsCollector.recordError(1, error.javaClass.simpleName)
            }
            
            result
        } catch (e: Exception) {
            errorHandler.handleError(record, e)
            metricsCollector.recordError(1, e.javaClass.simpleName)
            Result.failure(e)
        }
    }
    
    override suspend fun processBatch(records: List<DLQRecord>): BatchResult {
        return try {
            val result = processor.processParallel(records)
            
            metricsCollector.recordProcessed(
                result.totalSuccessful.toLong(),
                result.duration
            )
            
            if (result.totalFailed > 0) {
                metricsCollector.recordError(
                    result.totalFailed.toLong(),
                    "ParallelProcessingError"
                )
            }
            
            BatchResult(
                successful = result.totalSuccessful,
                failed = result.totalFailed,
                duration = result.duration
            )
        } catch (e: Exception) {
            errorHandler.handleBatchError(records, e)
            metricsCollector.recordError(records.size.toLong(), e.javaClass.simpleName)
            
            BatchResult(
                successful = 0,
                failed = records.size,
                duration = Duration.ZERO
            )
        }
    }
    
    override fun getMetrics(): DLQHandlerMetrics {
        val processorMetrics = processor.getMetrics()
        val collectorMetrics = metricsCollector.getMetrics()
        
        return DLQHandlerMetrics(
            totalProcessed = processorMetrics.totalProcessed,
            totalErrors = collectorMetrics.totalErrors,
            totalDropped = collectorMetrics.totalDropped
        )
    }
}

/**
 * Hybrid DLQ handler that combines batch and parallel processing.
 * 
 * @since 0.1.0
 */
class HybridDLQHandler(
    private val batchProcessor: BatchProcessor,
    private val parallelProcessor: ParallelProcessor,
    private val storage: DLQStorage,
    private val errorHandler: ErrorHandler,
    private val metricsCollector: MetricsCollector,
    private val config: ProcessingConfiguration
) : DLQHandler {
    
    override suspend fun start() {
        batchProcessor.start()
        parallelProcessor.start()
    }
    
    override suspend fun stop(timeout: Duration) {
        batchProcessor.stop(timeout)
        parallelProcessor.stop(timeout)
    }
    
    override suspend fun process(record: DLQRecord): Result<Unit> {
        // Use parallel processor for individual records
        return try {
            val ticket = parallelProcessor.submitRecord(record)
            val result = ticket.await()
            
            if (result.isSuccess) {
                metricsCollector.recordProcessed(1, Duration.ZERO)
            } else {
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                metricsCollector.recordError(1, error.javaClass.simpleName)
            }
            
            result
        } catch (e: Exception) {
            errorHandler.handleError(record, e)
            metricsCollector.recordError(1, e.javaClass.simpleName)
            Result.failure(e)
        }
    }
    
    override suspend fun processBatch(records: List<DLQRecord>): BatchResult {
        // Decide between batch and parallel based on size
        return if (records.size > 1000) {
            // Use parallel for large batches
            processBatchParallel(records)
        } else {
            // Use batch processor for smaller batches
            processBatchSequential(records)
        }
    }
    
    private suspend fun processBatchSequential(records: List<DLQRecord>): BatchResult {
        return try {
            val result = batchProcessor.processBatch(records)
            
            metricsCollector.recordProcessed(
                result.successful.toLong(),
                result.duration
            )
            
            if (result.failed > 0) {
                metricsCollector.recordError(
                    result.failed.toLong(),
                    "BatchProcessingError"
                )
            }
            
            result
        } catch (e: Exception) {
            errorHandler.handleBatchError(records, e)
            metricsCollector.recordError(records.size.toLong(), e.javaClass.simpleName)
            
            BatchResult(
                successful = 0,
                failed = records.size,
                duration = Duration.ZERO
            )
        }
    }
    
    private suspend fun processBatchParallel(records: List<DLQRecord>): BatchResult {
        return try {
            val result = parallelProcessor.processParallel(records)
            
            metricsCollector.recordProcessed(
                result.totalSuccessful.toLong(),
                result.duration
            )
            
            if (result.totalFailed > 0) {
                metricsCollector.recordError(
                    result.totalFailed.toLong(),
                    "ParallelProcessingError"
                )
            }
            
            BatchResult(
                successful = result.totalSuccessful,
                failed = result.totalFailed,
                duration = result.duration
            )
        } catch (e: Exception) {
            errorHandler.handleBatchError(records, e)
            metricsCollector.recordError(records.size.toLong(), e.javaClass.simpleName)
            
            BatchResult(
                successful = 0,
                failed = records.size,
                duration = Duration.ZERO
            )
        }
    }
    
    override fun getMetrics(): DLQHandlerMetrics {
        val batchMetrics = batchProcessor.getMetrics()
        val parallelMetrics = parallelProcessor.getMetrics()
        val collectorMetrics = metricsCollector.getMetrics()
        
        return DLQHandlerMetrics(
            totalProcessed = batchMetrics.totalProcessed + parallelMetrics.totalProcessed,
            totalErrors = collectorMetrics.totalErrors,
            totalDropped = collectorMetrics.totalDropped
        )
    }
}