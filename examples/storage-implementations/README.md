# Storage Implementation Examples

이 디렉토리는 `PayloadStorageService` 인터페이스의 다양한 구현 예제를 제공합니다.

## 기본 제공 구현

### LocalFileStorageService (기본값)
- 로컬 파일 시스템에 페이로드 저장
- 개발 및 테스트 환경에 적합
- 별도 설정 없이 자동으로 활성화됨

## 커스텀 구현 예제

### S3PayloadStorageExample
AWS S3를 사용한 구현 예제입니다.

**필요한 의존성:**
```gradle
implementation("software.amazon.awssdk:s3:2.20.0")
```

**설정 방법:**
```yaml
kafka:
  dlq:
    storage:
      external-type: S3
      external-bucket: my-dlq-bucket
```

**빈 등록:**
```kotlin
@Configuration
class StorageConfig {
    
    @Bean
    @ConditionalOnProperty(
        prefix = "kafka.dlq.storage",
        name = ["external-type"],
        havingValue = "S3"
    )
    fun s3PayloadStorage(
        properties: DLQConfigurationProperties
    ): PayloadStorageService {
        val s3Client = S3Client.builder()
            .region(Region.AP_NORTHEAST_2)
            .build()
            
        return S3PayloadStorageExample(s3Client, properties)
    }
}
```

## 다른 스토리지 구현하기

`PayloadStorageService` 인터페이스를 구현하여 원하는 스토리지를 사용할 수 있습니다:

```kotlin
interface PayloadStorageService {
    suspend fun store(messageId: String, payload: String): String
    suspend fun retrieve(location: String): String?
    suspend fun delete(location: String)
    suspend fun deleteBatch(locations: List<String>)
}
```

### 구현 예시
- **MinIO**: S3 호환 오브젝트 스토리지
- **Azure Blob Storage**: Azure 클라우드 스토리지
- **Google Cloud Storage**: GCP 스토리지
- **Redis**: 인메모리 캐시 (단기 보관용)
- **MongoDB GridFS**: 대용량 파일 저장

### 구현시 고려사항
1. **압축**: 네트워크 전송량 감소를 위해 GZIP 압축 권장
2. **파티셔닝**: 날짜별 디렉토리/프리픽스 사용으로 관리 용이성 향상
3. **메타데이터**: 원본 크기, 압축 크기 등 저장
4. **에러 처리**: 네트워크 오류시 재시도 로직
5. **배치 작업**: 대량 삭제시 배치 API 활용

## 프로덕션 권장사항

1. **로컬 파일 시스템은 개발용으로만 사용**
   - 확장성과 내구성 문제
   - 서버 재시작시 데이터 손실 가능

2. **클라우드 스토리지 사용 권장**
   - 높은 내구성 (99.999999999%)
   - 자동 복제 및 백업
   - 비용 효율적인 장기 보관

3. **보안 고려사항**
   - IAM 역할 기반 접근 제어
   - 전송 중 암호화 (TLS)
   - 저장 시 암호화 (SSE)
   - 민감 데이터 마스킹