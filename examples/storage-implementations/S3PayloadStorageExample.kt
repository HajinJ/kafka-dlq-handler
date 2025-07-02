package io.github.kayden.dlq.examples.storage

import io.github.kayden.dlq.config.DLQConfigurationProperties
import io.github.kayden.dlq.storage.PayloadStorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream

/**
 * S3 기반 페이로드 저장소 구현 예제
 * 
 * 이 클래스는 AWS SDK를 사용하여 S3에 대용량 메시지를 저장하는 예제입니다.
 * 실제 사용시에는 이 코드를 참고하여 자신의 프로젝트에 맞게 구현하세요.
 * 
 * 필요한 의존성:
 * ```
 * implementation("software.amazon.awssdk:s3:2.20.0")
 * ```
 * 
 * 설정 예시:
 * ```yaml
 * kafka:
 *   dlq:
 *     storage:
 *       external-type: S3
 *       external-bucket: my-dlq-bucket
 * ```
 */
class S3PayloadStorageExample(
    private val s3Client: S3Client,
    private val properties: DLQConfigurationProperties
) : PayloadStorageService {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    private val bucketName = properties.storage.externalBucket
    
    override suspend fun store(messageId: String, payload: String): String {
        return withContext(Dispatchers.IO) {
            try {
                // 압축
                val compressed = compress(payload)
                
                // S3 키 생성 (날짜별 파티셔닝)
                val date = Instant.now().toString().substring(0, 10)
                val key = "dlq-payloads/$date/$messageId.gz"
                
                // S3 업로드
                val putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/gzip")
                    .metadata(mapOf(
                        "message-id" to messageId,
                        "original-size" to payload.length.toString(),
                        "compressed-size" to compressed.size.toString()
                    ))
                    .build()
                
                s3Client.putObject(putRequest, RequestBody.fromBytes(compressed))
                
                logger.info("Stored payload to S3: s3://$bucketName/$key")
                
                "s3://$bucketName/$key"
            } catch (e: Exception) {
                logger.error("Failed to store payload to S3", e)
                throw e
            }
        }
    }
    
    override suspend fun retrieve(location: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // S3 URL 파싱
                val s3Url = parseS3Url(location) ?: return@withContext null
                
                // S3에서 다운로드
                val getRequest = GetObjectRequest.builder()
                    .bucket(s3Url.bucket)
                    .key(s3Url.key)
                    .build()
                
                val response = s3Client.getObject(getRequest)
                val compressed = response.readAllBytes()
                
                // 압축 해제
                decompress(compressed)
            } catch (e: NoSuchKeyException) {
                logger.warn("S3 object not found: {}", location)
                null
            } catch (e: Exception) {
                logger.error("Failed to retrieve payload from S3: {}", location, e)
                null
            }
        }
    }
    
    override suspend fun delete(location: String) {
        withContext(Dispatchers.IO) {
            try {
                val s3Url = parseS3Url(location) ?: return@withContext
                
                val deleteRequest = DeleteObjectRequest.builder()
                    .bucket(s3Url.bucket)
                    .key(s3Url.key)
                    .build()
                
                s3Client.deleteObject(deleteRequest)
                
                logger.info("Deleted payload from S3: {}", location)
            } catch (e: Exception) {
                logger.error("Failed to delete payload from S3: {}", location, e)
            }
        }
    }
    
    override suspend fun deleteBatch(locations: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                // S3 배치 삭제는 버킷별로 그룹화
                val deletesByBucket = locations
                    .mapNotNull { location -> 
                        parseS3Url(location)?.let { it.bucket to it.key }
                    }
                    .groupBy({ it.first }, { it.second })
                
                deletesByBucket.forEach { (bucket, keys) ->
                    if (keys.isEmpty()) return@forEach
                    
                    val objects = keys.map { key ->
                        ObjectIdentifier.builder().key(key).build()
                    }
                    
                    val deleteRequest = DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(objects).build())
                        .build()
                    
                    val response = s3Client.deleteObjects(deleteRequest)
                    
                    logger.info(
                        "Batch deleted {} objects from S3 bucket {}", 
                        response.deleted().size, 
                        bucket
                    )
                    
                    if (response.hasErrors()) {
                        response.errors().forEach { error ->
                            logger.error(
                                "Failed to delete S3 object {}: {}", 
                                error.key(), 
                                error.message()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to batch delete from S3", e)
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
    
    private fun parseS3Url(url: String): S3Url? {
        if (!url.startsWith("s3://")) return null
        
        val path = url.removePrefix("s3://")
        val parts = path.split("/", limit = 2)
        
        return if (parts.size == 2) {
            S3Url(parts[0], parts[1])
        } else {
            null
        }
    }
    
    private data class S3Url(val bucket: String, val key: String)
}