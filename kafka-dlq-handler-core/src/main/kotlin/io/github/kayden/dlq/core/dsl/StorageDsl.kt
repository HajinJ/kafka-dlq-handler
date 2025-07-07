package io.github.kayden.dlq.core.dsl

import io.github.kayden.dlq.core.storage.DLQStorage
import io.github.kayden.dlq.core.storage.InMemoryStorage
import java.time.Duration
import kotlin.time.Duration as KotlinDuration
import kotlin.time.toJavaDuration


/**
 * Builder for storage configuration using DSL.
 * 
 * Example:
 * ```kotlin
 * storage {
 *     inMemory {
 *         maxSize = 100_000
 *         evictionPolicy = EvictionPolicy.LRU
 *         ttl = 30.minutes
 *     }
 * }
 * ```
 * 
 * Or for external storage:
 * ```kotlin
 * storage {
 *     custom { config ->
 *         MyCustomStorage(
 *             connectionString = config.getString("connection"),
 *             poolSize = config.getInt("poolSize", 10)
 *         )
 *     }
 * }
 * ```
 * 
 * @since 0.1.0
 */
@DLQHandlerDslMarker
class StorageBuilder {
    private var storageFactory: () -> DLQStorage = { InMemoryStorage() }
    
    /**
     * Configures in-memory storage.
     */
    fun inMemory(block: InMemoryStorageBuilder.() -> Unit = {}) {
        val config = InMemoryStorageBuilder().apply(block).build()
        storageFactory = { 
            InMemoryStorage(
                maxSize = config.maxSize
            )
        }
    }
    
    /**
     * Configures Redis storage (placeholder for future implementation).
     */
    fun redis(block: RedisStorageBuilder.() -> Unit) {
        val config = RedisStorageBuilder().apply(block).build()
        // In a real implementation, this would create a Redis-backed storage
        storageFactory = {
            // RedisStorage(config)
            InMemoryStorage() // Placeholder
        }
    }
    
    /**
     * Configures database storage (placeholder for future implementation).
     */
    fun database(block: DatabaseStorageBuilder.() -> Unit) {
        val config = DatabaseStorageBuilder().apply(block).build()
        // In a real implementation, this would create a database-backed storage
        storageFactory = {
            // DatabaseStorage(config)
            InMemoryStorage() // Placeholder
        }
    }
    
    /**
     * Configures custom storage implementation.
     */
    fun custom(factory: (StorageConfig) -> DLQStorage) {
        val config = StorageConfig()
        storageFactory = { factory(config) }
    }
    
    /**
     * Uses a pre-configured storage instance.
     */
    fun use(storage: DLQStorage) {
        storageFactory = { storage }
    }
    
    /**
     * Builds the storage instance.
     */
    fun build(): DLQStorage = storageFactory()
}

/**
 * In-memory storage configuration builder.
 */
@DLQHandlerDslMarker
class InMemoryStorageBuilder {
    var maxSize: Int = 10_000
    var evictionPolicy: EvictionPolicy = EvictionPolicy.LRU
    var ttl: Duration? = null
    var concurrencyLevel: Int = 16
    
    /**
     * Sets TTL using Kotlin duration.
     */
    fun ttl(duration: KotlinDuration) {
        ttl = duration.toJavaDuration()
    }
    
    /**
     * Enables TTL with specified duration.
     */
    fun expireAfter(duration: KotlinDuration) {
        ttl = duration.toJavaDuration()
    }
    
    /**
     * Sets maximum entries with overflow policy.
     */
    fun maxEntries(size: Int, onOverflow: OverflowPolicy = OverflowPolicy.EVICT_OLDEST) {
        maxSize = size
        evictionPolicy = when (onOverflow) {
            OverflowPolicy.EVICT_OLDEST -> EvictionPolicy.FIFO
            OverflowPolicy.EVICT_LRU -> EvictionPolicy.LRU
            OverflowPolicy.REJECT -> EvictionPolicy.REJECT
        }
    }
    
    /**
     * Builds the configuration.
     */
    fun build(): InMemoryStorageConfig {
        return InMemoryStorageConfig(
            maxSize = maxSize,
            evictionPolicy = evictionPolicy,
            ttl = ttl,
            concurrencyLevel = concurrencyLevel
        )
    }
}

/**
 * Redis storage configuration builder (placeholder).
 */
@DLQHandlerDslMarker
class RedisStorageBuilder {
    var host: String = "localhost"
    var port: Int = 6379
    var password: String? = null
    var database: Int = 0
    var connectionPoolSize: Int = 10
    var timeout: Duration = Duration.ofSeconds(5)
    var ttl: Duration? = null
    var keyPrefix: String = "dlq:"
    
    /**
     * Configures Redis connection.
     */
    fun connection(block: RedisConnectionBuilder.() -> Unit) {
        val conn = RedisConnectionBuilder().apply(block)
        host = conn.host
        port = conn.port
        password = conn.password
        database = conn.database
    }
    
    /**
     * Configures connection pool.
     */
    fun pool(block: ConnectionPoolBuilder.() -> Unit) {
        val pool = ConnectionPoolBuilder().apply(block)
        connectionPoolSize = pool.size
        timeout = pool.timeout
    }
    
    /**
     * Sets key expiration.
     */
    fun expireKeys(duration: KotlinDuration) {
        ttl = duration.toJavaDuration()
    }
    
    /**
     * Builds the configuration.
     */
    fun build(): RedisStorageConfig {
        return RedisStorageConfig(
            host = host,
            port = port,
            password = password,
            database = database,
            connectionPoolSize = connectionPoolSize,
            timeout = timeout,
            ttl = ttl,
            keyPrefix = keyPrefix
        )
    }
}

/**
 * Database storage configuration builder (placeholder).
 */
@DLQHandlerDslMarker
class DatabaseStorageBuilder {
    var jdbcUrl: String = ""
    var username: String = ""
    var password: String = ""
    var driverClassName: String = ""
    var connectionPoolSize: Int = 10
    var tableName: String = "dlq_messages"
    var schema: String? = null
    var batchSize: Int = 100
    
    /**
     * Configures database connection.
     */
    fun connection(block: DatabaseConnectionBuilder.() -> Unit) {
        val conn = DatabaseConnectionBuilder().apply(block)
        jdbcUrl = conn.url
        username = conn.username
        password = conn.password
        driverClassName = conn.driver
    }
    
    /**
     * Configures table settings.
     */
    fun table(name: String, schema: String? = null) {
        tableName = name
        this.schema = schema
    }
    
    /**
     * Configures batch operations.
     */
    fun batching(size: Int = 100) {
        batchSize = size
    }
    
    /**
     * Builds the configuration.
     */
    fun build(): DatabaseStorageConfig {
        return DatabaseStorageConfig(
            jdbcUrl = jdbcUrl,
            username = username,
            password = password,
            driverClassName = driverClassName,
            connectionPoolSize = connectionPoolSize,
            tableName = tableName,
            schema = schema,
            batchSize = batchSize
        )
    }
}

/**
 * Redis connection configuration builder.
 */
@DLQHandlerDslMarker
class RedisConnectionBuilder {
    var host: String = "localhost"
    var port: Int = 6379
    var password: String? = null
    var database: Int = 0
    
    /**
     * Sets Redis URL.
     */
    fun url(redisUrl: String) {
        // Parse redis://[:password@]host[:port][/database]
        // Simplified implementation
        host = redisUrl.substringAfter("://").substringBefore(":")
    }
}

/**
 * Connection pool configuration builder.
 */
@DLQHandlerDslMarker
class ConnectionPoolBuilder {
    var size: Int = 10
    var timeout: Duration = Duration.ofSeconds(5)
    var maxIdle: Int = size
    var minIdle: Int = 0
    
    /**
     * Sets timeout using Kotlin duration.
     */
    fun timeout(duration: KotlinDuration) {
        timeout = duration.toJavaDuration()
    }
}

/**
 * Database connection configuration builder.
 */
@DLQHandlerDslMarker
class DatabaseConnectionBuilder {
    var url: String = ""
    var username: String = ""
    var password: String = ""
    var driver: String = ""
    
    /**
     * Configures PostgreSQL connection.
     */
    fun postgresql(host: String, port: Int = 5432, database: String) {
        url = "jdbc:postgresql://$host:$port/$database"
        driver = "org.postgresql.Driver"
    }
    
    /**
     * Configures MySQL connection.
     */
    fun mysql(host: String, port: Int = 3306, database: String) {
        url = "jdbc:mysql://$host:$port/$database"
        driver = "com.mysql.cj.jdbc.Driver"
    }
}

/**
 * Generic storage configuration.
 */
class StorageConfig {
    private val properties = mutableMapOf<String, Any>()
    
    /**
     * Sets a configuration property.
     */
    fun set(key: String, value: Any) {
        properties[key] = value
    }
    
    /**
     * Gets a string property.
     */
    fun getString(key: String, default: String = ""): String {
        return properties[key] as? String ?: default
    }
    
    /**
     * Gets an integer property.
     */
    fun getInt(key: String, default: Int = 0): Int {
        return properties[key] as? Int ?: default
    }
    
    /**
     * Gets a boolean property.
     */
    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return properties[key] as? Boolean ?: default
    }
}

/**
 * Eviction policy for in-memory storage.
 */
enum class EvictionPolicy {
    LRU,    // Least Recently Used
    LFU,    // Least Frequently Used
    FIFO,   // First In First Out
    REJECT  // Reject new entries
}

/**
 * Overflow policy for bounded storage.
 */
enum class OverflowPolicy {
    EVICT_OLDEST,
    EVICT_LRU,
    REJECT
}

/**
 * In-memory storage configuration.
 */
data class InMemoryStorageConfig(
    val maxSize: Int,
    val evictionPolicy: EvictionPolicy,
    val ttl: Duration?,
    val concurrencyLevel: Int
)

/**
 * Redis storage configuration.
 */
data class RedisStorageConfig(
    val host: String,
    val port: Int,
    val password: String?,
    val database: Int,
    val connectionPoolSize: Int,
    val timeout: Duration,
    val ttl: Duration?,
    val keyPrefix: String
)

/**
 * Database storage configuration.
 */
data class DatabaseStorageConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val driverClassName: String,
    val connectionPoolSize: Int,
    val tableName: String,
    val schema: String?,
    val batchSize: Int
)