package io.github.kayden.dlq.storage

import io.github.kayden.dlq.config.DLQConfigurationProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

/**
 * 로컬 파일 시스템 기반 페이로드 저장소 구현
 * 
 * 기본 구현으로 제공되며, 프로덕션에서는 S3, MinIO 등의 외부 스토리지 사용을 권장한다.
 */
@Service
@ConditionalOnMissingBean(PayloadStorageService::class)
class LocalFileStorageService(
    private val properties: DLQConfigurationProperties
) : PayloadStorageService {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    private val baseDir = System.getProperty("java.io.tmpdir") + "/dlq-payloads"
    
    init {
        // 기본 디렉토리 생성
        File(baseDir).mkdirs()
    }
    
    override suspend fun store(messageId: String, payload: String): String {
        return withContext(Dispatchers.IO) {
            try {
                // 압축
                val compressed = compress(payload)
                
                // 파일 경로 생성 (날짜별 디렉토리)
                val date = Instant.now().toString().substring(0, 10)
                val dir = "$baseDir/$date"
                File(dir).mkdirs()
                
                val filePath = "$dir/$messageId.gz"
                val file = File(filePath)
                
                // 파일 저장
                file.writeBytes(compressed)
                
                logger.debug("Stored payload to local file: {}", filePath)
                
                "file://$filePath"
            } catch (e: Exception) {
                logger.error("Failed to store payload to file", e)
                throw e
            }
        }
    }
    
    override suspend fun retrieve(location: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (!location.startsWith("file://")) {
                    logger.warn("Invalid file location: {}", location)
                    return@withContext null
                }
                
                val filePath = location.removePrefix("file://")
                val file = File(filePath)
                
                if (!file.exists()) {
                    logger.warn("File not found: {}", filePath)
                    return@withContext null
                }
                
                // 압축 해제
                val compressed = file.readBytes()
                decompress(compressed)
            } catch (e: Exception) {
                logger.error("Failed to retrieve payload from file", e)
                null
            }
        }
    }
    
    override suspend fun delete(location: String) {
        withContext(Dispatchers.IO) {
            try {
                if (!location.startsWith("file://")) {
                    return@withContext
                }
                
                val filePath = location.removePrefix("file://")
                val file = File(filePath)
                
                if (file.exists()) {
                    file.delete()
                    logger.debug("Deleted payload file: {}", filePath)
                }
            } catch (e: Exception) {
                logger.error("Failed to delete payload file", e)
            }
        }
    }
    
    override suspend fun deleteBatch(locations: List<String>) {
        withContext(Dispatchers.IO) {
            locations.forEach { location ->
                try {
                    delete(location)
                } catch (e: Exception) {
                    logger.error("Failed to delete file in batch: {}", location, e)
                }
            }
        }
    }
    
    private fun compress(data: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(data.toByteArray())
        }
        return baos.toByteArray()
    }
    
    private fun decompress(compressed: ByteArray): String {
        return GZIPInputStream(compressed.inputStream()).use { gzip ->
            gzip.readBytes().toString(Charsets.UTF_8)
        }
    }
}