package io.github.kayden.dlq.performance

import kotlin.time.Duration

/**
 * Result of batch processing
 */
data class BatchResult(
    val successful: Int,
    val failed: Int,
    val duration: Duration,
    val throughput: Double = successful.toDouble() / duration.inWholeSeconds.coerceAtLeast(1)
)