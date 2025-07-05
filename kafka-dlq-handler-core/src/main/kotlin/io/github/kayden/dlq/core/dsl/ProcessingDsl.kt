package io.github.kayden.dlq.core.dsl

import io.github.kayden.dlq.core.model.DLQRecord
import io.github.kayden.dlq.core.model.BackoffStrategy
import io.github.kayden.dlq.core.model.RetryPolicy
import io.github.kayden.dlq.core.model.ErrorType
import java.time.Duration
import kotlin.time.Duration as KotlinDuration
import kotlin.time.toJavaDuration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes


/**
 * Builder for processing configuration using DSL.
 * 
 * Example:
 * ```kotlin
 * processing {
 *     retry {
 *         maxAttempts = 3
 *         backoff = exponential {
 *             initialDelay = 1.seconds
 *             maxDelay = 30.seconds
 *             multiplier = 2.0
 *         }
 *         retryOn<TransientException>()
 *         skipOn<PermanentException>()
 *     }
 *     
 *     timeout = 5.minutes
 *     
 *     processor { record ->
 *         // Custom processing logic
 *         processRecord(record)
 *     }
 *     
 *     deduplication {
 *         enabled = true
 *         window = 5.minutes
 *         keyExtractor = { it.messageKey ?: it.id }
 *     }
 * }
 * ```
 * 
 * @since 0.1.0
 */
@DLQHandlerDslMarker
class ProcessingConfigBuilder {
    private var retryConfig: RetryConfiguration? = null
    private var timeoutDuration = Duration.ofMinutes(5)
    private var recordProcessor: (suspend (DLQRecord) -> Result<Unit>)? = null
    private var deduplicationConfig: DeduplicationConfig? = null
    
    /**
     * Configures retry behavior.
     */
    fun retry(block: RetryConfigBuilder.() -> Unit) {
        retryConfig = RetryConfigBuilder().apply(block).build()
    }
    
    /**
     * Sets processing timeout.
     */
    var timeout: KotlinDuration
        get() = kotlin.time.Duration.ZERO // Placeholder
        set(value) {
            timeoutDuration = value.toJavaDuration()
        }
    
    /**
     * Sets the record processor function.
     */
    fun processor(handler: suspend (DLQRecord) -> Result<Unit>) {
        recordProcessor = handler
    }
    
    /**
     * Configures deduplication.
     */
    fun deduplication(block: DeduplicationBuilder.() -> Unit) {
        deduplicationConfig = DeduplicationBuilder().apply(block).build()
    }
    
    /**
     * Builds the configuration.
     */
    fun build(): ProcessingConfiguration {
        return ProcessingConfiguration(
            maxRetries = retryConfig?.maxAttempts ?: 3,
            retryBackoff = retryConfig?.backoffDuration ?: Duration.ofSeconds(1),
            processingTimeout = timeoutDuration,
            recordProcessor = recordProcessor,
            enableDeduplication = deduplicationConfig?.enabled ?: false,
            deduplicationWindow = deduplicationConfig?.window ?: Duration.ofMinutes(5)
        )
    }
}

/**
 * Retry configuration builder.
 */
@DLQHandlerDslMarker
class RetryConfigBuilder {
    var maxAttempts: Int = 3
    private var backoffStrategy: BackoffStrategy = BackoffStrategy.Exponential.DEFAULT
    internal val retryableExceptions = mutableSetOf<Class<out Throwable>>()
    internal val skipExceptions = mutableSetOf<Class<out Throwable>>()
    private var retryPredicate: ((DLQRecord, Throwable) -> Boolean)? = null
    
    /**
     * Configures exponential backoff.
     */
    fun exponential(block: ExponentialBackoffBuilder.() -> Unit = {}) {
        backoffStrategy = ExponentialBackoffBuilder().apply(block).build()
    }
    
    /**
     * Configures linear backoff.
     */
    fun linear(block: LinearBackoffBuilder.() -> Unit = {}) {
        backoffStrategy = LinearBackoffBuilder().apply(block).build()
    }
    
    /**
     * Configures fixed backoff.
     */
    fun fixed(delay: KotlinDuration) {
        backoffStrategy = BackoffStrategy.Fixed(delay)
    }
    
    /**
     * Uses a pre-configured backoff strategy.
     */
    var backoff: BackoffStrategy
        get() = backoffStrategy
        set(value) {
            backoffStrategy = value
        }
    
    /**
     * Adds exception types to retry on.
     */
    fun retryOn(clazz: Class<out Throwable>) {
        retryableExceptions.add(clazz)
    }
    
    /**
     * Adds exception types to skip (not retry).
     */
    fun skipOn(clazz: Class<out Throwable>) {
        skipExceptions.add(clazz)
    }
    
    /**
     * Sets custom retry predicate.
     */
    fun retryIf(predicate: (DLQRecord, Throwable) -> Boolean) {
        retryPredicate = predicate
    }
    
    /**
     * Uses predefined retry policy.
     */
    fun usePolicy(policy: RetryPolicy) {
        maxAttempts = policy.maxRetries
        backoffStrategy = policy.backoffStrategy
    }
    
    /**
     * Builds the configuration.
     */
    fun build(): RetryConfiguration {
        return RetryConfiguration(
            maxAttempts = maxAttempts,
            backoffStrategy = backoffStrategy,
            backoffDuration = backoffStrategy.calculateDelay(1).toJavaDuration(),
            retryableExceptions = retryableExceptions,
            skipExceptions = skipExceptions,
            retryPredicate = retryPredicate
        )
    }
}

/**
 * Exponential backoff configuration builder.
 */
@DLQHandlerDslMarker
class ExponentialBackoffBuilder {
    var initialDelay: KotlinDuration = 1.seconds
    var maxDelay: KotlinDuration = 60.seconds
    var multiplier: Double = 2.0
    var withJitter: Boolean = false
    
    /**
     * Builds the backoff strategy.
     */
    fun build(): BackoffStrategy {
        return if (withJitter) {
            BackoffStrategy.ExponentialWithJitter(
                baseStrategy = BackoffStrategy.Exponential(
                    baseDelay = initialDelay,
                    multiplier = multiplier,
                    maxDelay = maxDelay
                )
            )
        } else {
            BackoffStrategy.Exponential(
                baseDelay = initialDelay,
                multiplier = multiplier,
                maxDelay = maxDelay
            )
        }
    }
}

/**
 * Linear backoff configuration builder.
 */
@DLQHandlerDslMarker
class LinearBackoffBuilder {
    var initialDelay: KotlinDuration = 1.seconds
    var increment: KotlinDuration = 1.seconds
    var maxDelay: KotlinDuration = 60.seconds
    
    /**
     * Builds the backoff strategy.
     */
    fun build(): BackoffStrategy {
        return BackoffStrategy.Linear(
            initialDelay = initialDelay,
            increment = increment,
            maxDelay = maxDelay
        )
    }
}

/**
 * Deduplication configuration builder.
 */
@DLQHandlerDslMarker
class DeduplicationBuilder {
    var enabled: Boolean = false
    var window: Duration = Duration.ofMinutes(5)
    var keyExtractor: (DLQRecord) -> String = { it.id }
    var cacheSize: Int = 10000
    
    /**
     * Sets window using Kotlin duration.
     */
    fun window(duration: KotlinDuration) {
        window = duration.toJavaDuration()
    }
    
    /**
     * Builds the configuration.
     */
    fun build(): DeduplicationConfig {
        return DeduplicationConfig(
            enabled = enabled,
            window = window,
            keyExtractor = keyExtractor,
            cacheSize = cacheSize
        )
    }
}

/**
 * Error handling configuration builder.
 */
@DLQHandlerDslMarker
class ErrorHandlingBuilder {
    internal val handlers = mutableMapOf<Class<out Throwable>, ErrorHandlerFunction>()
    private var defaultHandler: ErrorHandlerFunction = { _, _ -> }
    private var batchErrorHandler: BatchErrorHandlerFunction = { _, _ -> }
    
    /**
     * Registers handler for specific exception type.
     */
    fun <T : Throwable> on(clazz: Class<T>, handler: suspend (DLQRecord, T) -> Unit) {
        handlers[clazz] = { record, error ->
            if (clazz.isInstance(error)) {
                @Suppress("UNCHECKED_CAST")
                handler(record, error as T)
            }
        }
    }
    
    /**
     * Sets default error handler.
     */
    fun default(handler: ErrorHandlerFunction) {
        defaultHandler = handler
    }
    
    /**
     * Sets batch error handler.
     */
    fun onBatchError(handler: BatchErrorHandlerFunction) {
        batchErrorHandler = handler
    }
    
    /**
     * Configures error classification.
     */
    fun classify(block: ErrorClassifierBuilder.() -> Unit) {
        // Error classification logic
    }
    
    /**
     * Builds the error handler.
     */
    fun build(): ErrorHandler {
        return CustomErrorHandler(
            handlers = handlers,
            defaultHandler = defaultHandler,
            batchErrorHandler = batchErrorHandler
        )
    }
}

/**
 * Error classifier configuration builder.
 */
@DLQHandlerDslMarker
class ErrorClassifierBuilder {
    internal val classifiers = mutableMapOf<Class<out Throwable>, ErrorType>()
    
    /**
     * Maps exception type to error type.
     */
    fun map(clazz: Class<out Throwable>, errorType: ErrorType) {
        classifiers[clazz] = errorType
    }
    
    /**
     * Maps exceptions matching predicate.
     */
    fun mapIf(predicate: (Throwable) -> Boolean, errorType: ErrorType) {
        // Custom classification logic
    }
}

/**
 * Metrics configuration builder.
 */
@DLQHandlerDslMarker
class MetricsBuilder {
    var enabled: Boolean = true
    var interval: Duration = Duration.ofSeconds(10)
    var includeHistograms: Boolean = true
    var includePercentiles: Boolean = true
    private var customCollector: MetricsCollector? = null
    
    /**
     * Sets metrics interval using Kotlin duration.
     */
    fun interval(duration: KotlinDuration) {
        interval = duration.toJavaDuration()
    }
    
    /**
     * Uses custom metrics collector.
     */
    fun collector(collector: MetricsCollector) {
        customCollector = collector
    }
    
    /**
     * Configures metric tags.
     */
    fun tags(vararg pairs: Pair<String, String>) {
        // Configure tags
    }
    
    /**
     * Builds the metrics collector.
     */
    fun build(): MetricsCollector {
        return customCollector ?: DefaultMetricsCollector()
    }
}

// Type aliases for handlers
typealias ErrorHandlerFunction = suspend (DLQRecord, Throwable) -> Unit
typealias BatchErrorHandlerFunction = suspend (List<DLQRecord>, Throwable) -> Unit

/**
 * Retry configuration data.
 */
data class RetryConfiguration(
    val maxAttempts: Int,
    val backoffStrategy: BackoffStrategy,
    val backoffDuration: Duration,
    val retryableExceptions: Set<Class<out Throwable>>,
    val skipExceptions: Set<Class<out Throwable>>,
    val retryPredicate: ((DLQRecord, Throwable) -> Boolean)?
)

/**
 * Deduplication configuration data.
 */
data class DeduplicationConfig(
    val enabled: Boolean,
    val window: Duration,
    val keyExtractor: (DLQRecord) -> String,
    val cacheSize: Int
)

/**
 * Custom error handler implementation.
 */
class CustomErrorHandler(
    private val handlers: Map<Class<out Throwable>, ErrorHandlerFunction>,
    private val defaultHandler: ErrorHandlerFunction,
    private val batchErrorHandler: BatchErrorHandlerFunction
) : ErrorHandler {
    
    override suspend fun handleError(record: DLQRecord, error: Throwable) {
        val handler = handlers.entries.firstOrNull { (type, _) ->
            type.isAssignableFrom(error.javaClass)
        }?.value ?: defaultHandler
        
        handler(record, error)
    }
    
    override suspend fun handleBatchError(records: List<DLQRecord>, error: Throwable) {
        batchErrorHandler(records, error)
    }
}