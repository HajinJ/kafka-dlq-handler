# Kafka DLQ Handler - Comprehensive Coding Conventions

> 이 문서는 Kafka DLQ Handler 프로젝트의 코드 품질을 보장하기 위한 상세한 코딩 가이드입니다.
> 모든 기여자는 이 가이드를 숙지하고 준수해야 합니다.

## 📚 목차

1. [핵심 원칙](#핵심-원칙)
2. [명명 규칙](#명명-규칙)
3. [함수 작성 가이드](#함수-작성-가이드)
4. [클래스 설계](#클래스-설계)
5. [주석과 문서화](#주석과-문서화)
6. [에러 처리](#에러-처리)
7. [테스트 작성](#테스트-작성)
8. [동시성과 성능](#동시성과-성능)
9. [보안 고려사항](#보안-고려사항)
10. [코드 리뷰 체크리스트](#코드-리뷰-체크리스트)
11. [Git 컨벤션](#git-컨벤션)

---

## 핵심 원칙

### 1. "읽기 좋은 코드가 좋은 코드다" - The Art of Readable Code

```kotlin
// ❌ Bad - 무엇을 하는지 이해하기 어려움
fun p(m: DLQMessage): Boolean {
    return m.s == "P" && m.r < 3 && (m.l == null || m.l!! < System.currentTimeMillis() - 300000)
}

// ✅ Good - 의도가 명확함
fun canRetryMessage(message: DLQMessage): Boolean {
    val isPending = message.status == DLQStatus.PENDING
    val hasRetriesLeft = message.retryCount < MAX_RETRY_ATTEMPTS
    val cooldownPassed = hasRetryCooltimePassed(message)
    
    return isPending && hasRetriesLeft && cooldownPassed
}

private fun hasRetryCooltimePassed(message: DLQMessage): Boolean {
    val lastRetry = message.lastRetryAt ?: return true
    val cooldownPeriod = Duration.ofMinutes(5)
    return Duration.between(lastRetry, Instant.now()) > cooldownPeriod
}
```

### 2. Clean Code 원칙 - Robert C. Martin

#### 함수는 한 가지 일만 해야 한다
```kotlin
// ❌ Bad - 여러 책임을 가진 함수
fun handleMessage(record: ConsumerRecord<String, String>) {
    // 1. 파싱
    val headers = record.headers()
    val originalTopic = headers.lastHeader("kafka_dlt-original-topic")?.let { String(it.value()) }
    val errorMessage = headers.lastHeader("kafka_dlt-exception-message")?.let { String(it.value()) }
    
    // 2. 변환
    val dlqMessage = DLQMessage(
        originalTopic = originalTopic ?: "unknown",
        errorMessage = errorMessage ?: "unknown"
    )
    
    // 3. 저장
    repository.save(dlqMessage)
    
    // 4. 메트릭 업데이트
    meterRegistry.counter("dlq.messages.saved").increment()
    
    // 5. 알림
    if (dlqMessage.errorType == "CRITICAL") {
        slackNotifier.send("Critical error in DLQ: ${dlqMessage.id}")
    }
}

// ✅ Good - 각 함수가 단일 책임
class DLQMessageHandler(
    private val parser: DLQMessageParser,
    private val repository: DLQMessageRepository,
    private val metrics: DLQMetrics,
    private val notifier: ErrorNotifier
) {
    fun handleMessage(record: ConsumerRecord<String, String>) {
        val message = parser.parse(record)
        val saved = repository.save(message)
        metrics.recordMessageSaved(saved)
        notifyIfCritical(saved)
    }
    
    private fun notifyIfCritical(message: DLQMessage) {
        if (message.isCritical()) {
            notifier.notifyCriticalError(message)
        }
    }
}
```

### 3. SOLID 원칙

#### Single Responsibility Principle (SRP)
```kotlin
// ✅ Good - 각 클래스가 하나의 이유로만 변경됨
class DLQMessageRepository {
    // 오직 데이터 저장/조회 책임
}

class DLQMessageValidator {
    // 오직 검증 책임
}

class DLQMessageReprocessor {
    // 오직 재처리 책임
}
```

#### Open/Closed Principle (OCP)
```kotlin
// ✅ Good - 확장에는 열려있고 수정에는 닫혀있음
interface BackoffStrategy {
    fun calculateDelay(attemptNumber: Int): Duration
}

class ExponentialBackoff : BackoffStrategy {
    override fun calculateDelay(attemptNumber: Int): Duration =
        Duration.ofSeconds(2.0.pow(attemptNumber).toLong())
}

class LinearBackoff : BackoffStrategy {
    override fun calculateDelay(attemptNumber: Int): Duration =
        Duration.ofSeconds(attemptNumber * 5L)
}

// 새로운 전략 추가 시 기존 코드 수정 없음
class FibonacciBackoff : BackoffStrategy {
    override fun calculateDelay(attemptNumber: Int): Duration =
        Duration.ofSeconds(fibonacci(attemptNumber))
}
```

---

## 명명 규칙

### 변수명 가이드

```kotlin
// ❌ Bad - 의미가 불명확한 이름들
val d = Date()
val yrs = calcYears()
val u = getUserById(id)
val temp = processData()
val data = fetchResults()
val flag = checkCondition()

// ✅ Good - 의도가 명확한 이름들
val currentDate = Date()
val yearsOfExperience = calculateYearsOfExperience()
val currentUser = getUserById(userId)
val processedInvoices = processInvoiceData()
val searchResults = fetchSearchResults()
val isEligibleForDiscount = checkDiscountEligibility()

// ✅ Good - 단위를 포함한 이름
val delayInSeconds = 5
val sizeInBytes = 1024
val priceInCents = 2500
val distanceInMeters = 100

// ✅ Good - 불린 변수는 is/has/can으로 시작
val isReady = true
val hasPermission = checkPermission()
val canRetry = retryCount < maxRetries
```

### 함수명 가이드

```kotlin
// ✅ Good - 동작을 명확히 표현
fun calculateTotalAmount(): BigDecimal
fun findUsersByRole(role: UserRole): List<User>
fun isEligibleForReprocessing(message: DLQMessage): Boolean
fun extractMetadataFromHeaders(headers: Headers): MessageMetadata

// ✅ Good - 일관된 CRUD 네이밍
fun createApplication(request: CreateApplicationRequest): Application
fun getApplicationById(id: Long): Application?
fun updateApplicationStatus(id: Long, status: ApplicationStatus): Application
fun deleteApplication(id: Long)

// ✅ Good - 변환 함수는 to/from 사용
fun toDTO(): ApplicationDTO
fun fromEntity(entity: ApplicationEntity): Application
fun asDomainModel(): DomainModel
```

### 클래스명 가이드

```kotlin
// ✅ Good - 명사 또는 명사구 사용
class DLQMessageProcessor
class RetryPolicyFactory
class ApplicationService
class UserAuthenticationFilter

// ✅ Good - 인터페이스와 구현체 구분
interface MessageRepository
class JpaMessageRepository : MessageRepository
class MongoMessageRepository : MessageRepository

// ✅ Good - 역할이 명확한 접미사
class ApplicationController    // REST 컨트롤러
class OrderService            // 비즈니스 서비스
class UserRepository          // 데이터 접근
class AuthenticationFilter    // 필터
class MessageValidator        // 검증기
class EventPublisher         // 이벤트 발행
class CacheManager           // 관리자
```

### 상수 네이밍

```kotlin
// ✅ Good - 의미가 명확한 상수명
companion object {
    const val MAX_RETRY_ATTEMPTS = 3
    const val DEFAULT_TIMEOUT_SECONDS = 30
    const val MINIMUM_PASSWORD_LENGTH = 8
    
    // 설정 키는 점 표기법 사용
    const val CONFIG_KEY_RETRY_ENABLED = "kafka.dlq.retry.enabled"
    const val CONFIG_KEY_MAX_BATCH_SIZE = "kafka.dlq.batch.max-size"
    
    // 에러 코드는 대문자와 언더스코어
    const val ERROR_CODE_MESSAGE_NOT_FOUND = "DLQ_001"
    const val ERROR_CODE_RETRY_LIMIT_EXCEEDED = "DLQ_002"
}
```

---

## 함수 작성 가이드

### 함수 크기 제한 (15줄 규칙)

```kotlin
// ❌ Bad - 너무 긴 함수
fun processApplicationBatch(applications: List<Application>) {
    val validApplications = mutableListOf<Application>()
    val invalidApplications = mutableListOf<Application>()
    
    for (app in applications) {
        if (app.status == null) {
            invalidApplications.add(app)
            continue
        }
        
        if (app.submittedAt == null) {
            app.submittedAt = Instant.now()
        }
        
        if (app.companyName.isBlank()) {
            invalidApplications.add(app)
            continue
        }
        
        // ... 더 많은 검증 로직 ...
        
        validApplications.add(app)
    }
    
    // 저장 로직
    for (app in validApplications) {
        try {
            repository.save(app)
            eventPublisher.publish(ApplicationSavedEvent(app))
        } catch (e: Exception) {
            logger.error("Failed to save application", e)
        }
    }
    
    // 실패 처리
    for (app in invalidApplications) {
        notifier.notifyInvalidApplication(app)
    }
}

// ✅ Good - 작은 함수로 분리
fun processApplicationBatch(applications: List<Application>) {
    val (valid, invalid) = applications.partition { isValidApplication(it) }
    
    valid.forEach { saveApplication(it) }
    invalid.forEach { handleInvalidApplication(it) }
}

private fun isValidApplication(app: Application): Boolean =
    app.status != null && app.companyName.isNotBlank()

private fun saveApplication(app: Application) {
    try {
        val saved = repository.save(prepareForSaving(app))
        eventPublisher.publish(ApplicationSavedEvent(saved))
    } catch (e: Exception) {
        logger.error("Failed to save application: ${app.id}", e)
    }
}

private fun prepareForSaving(app: Application): Application =
    app.copy(submittedAt = app.submittedAt ?: Instant.now())

private fun handleInvalidApplication(app: Application) {
    notifier.notifyInvalidApplication(app)
}
```

### Early Return 패턴

```kotlin
// ❌ Bad - 깊은 중첩
fun processMessage(message: DLQMessage?): ProcessingResult {
    if (message != null) {
        if (message.isValid()) {
            if (canProcess(message)) {
                return doProcess(message)
            } else {
                return ProcessingResult.NotEligible
            }
        } else {
            return ProcessingResult.Invalid
        }
    } else {
        return ProcessingResult.NullMessage
    }
}

// ✅ Good - Early return으로 중첩 제거
fun processMessage(message: DLQMessage?): ProcessingResult {
    if (message == null) return ProcessingResult.NullMessage
    if (!message.isValid()) return ProcessingResult.Invalid
    if (!canProcess(message)) return ProcessingResult.NotEligible
    
    return doProcess(message)
}
```

### 파라미터 개수 제한

```kotlin
// ❌ Bad - 너무 많은 파라미터
fun createUser(
    firstName: String,
    lastName: String,
    email: String,
    password: String,
    age: Int,
    address: String,
    phoneNumber: String,
    role: UserRole
): User

// ✅ Good - 데이터 클래스 사용
data class CreateUserRequest(
    val firstName: String,
    val lastName: String,
    val email: String,
    val password: String,
    val age: Int,
    val address: String,
    val phoneNumber: String,
    val role: UserRole
)

fun createUser(request: CreateUserRequest): User

// ✅ Good - 빌더 패턴
class UserBuilder {
    private var firstName: String = ""
    private var lastName: String = ""
    // ... other fields ...
    
    fun withName(first: String, last: String) = apply {
        firstName = first
        lastName = last
    }
    
    fun withContact(email: String, phone: String) = apply {
        this.email = email
        this.phoneNumber = phone
    }
    
    fun build(): User = User(firstName, lastName, ...)
}
```

---

## 클래스 설계

### 불변성 선호

```kotlin
// ❌ Bad - 가변 클래스
class MutableDLQMessage {
    var id: Long? = null
    var status: String = "PENDING"
    var retryCount: Int = 0
    
    fun retry() {
        retryCount++
        status = "RETRYING"
    }
}

// ✅ Good - 불변 데이터 클래스
data class DLQMessage(
    val id: Long? = null,
    val status: DLQStatus = DLQStatus.PENDING,
    val retryCount: Int = 0,
    val createdAt: Instant = Instant.now()
) {
    fun withRetry(): DLQMessage = copy(
        status = DLQStatus.RETRYING,
        retryCount = retryCount + 1
    )
    
    fun withStatus(newStatus: DLQStatus): DLQMessage = 
        copy(status = newStatus)
}
```

### 응집도 높은 클래스

```kotlin
// ✅ Good - 관련된 기능끼리 그룹화
class DLQMessageService(
    private val repository: DLQMessageRepository,
    private val validator: DLQMessageValidator,
    private val reprocessor: MessageReprocessor
) {
    fun saveMessage(message: DLQMessage): DLQMessage {
        validator.validate(message)
        return repository.save(message)
    }
    
    fun reprocessMessage(id: Long): ReprocessingResult {
        val message = repository.findById(id) 
            ?: throw MessageNotFoundException(id)
        
        return reprocessor.reprocess(message)
    }
}

// 각 컴포넌트는 단일 책임
class DLQMessageValidator {
    fun validate(message: DLQMessage) {
        require(message.originalTopic.isNotBlank()) {
            "Original topic must not be blank"
        }
        require(message.retryCount >= 0) {
            "Retry count must not be negative"
        }
    }
}
```

### 의존성 주입

```kotlin
// ❌ Bad - 직접 생성
class MessageProcessor {
    private val repository = DLQMessageRepository() // 직접 생성
    private val validator = MessageValidator()      // 테스트 어려움
    
    fun process(message: DLQMessage) {
        validator.validate(message)
        repository.save(message)
    }
}

// ✅ Good - 의존성 주입
class MessageProcessor(
    private val repository: DLQMessageRepository,
    private val validator: MessageValidator
) {
    fun process(message: DLQMessage) {
        validator.validate(message)
        repository.save(message)
    }
}

// 테스트 가능
class MessageProcessorTest {
    @Test
    fun `should process valid message`() {
        val repository = mockk<DLQMessageRepository>()
        val validator = mockk<MessageValidator>()
        val processor = MessageProcessor(repository, validator)
        
        // ... test implementation
    }
}
```

---

## 주석과 문서화

### KDoc 작성 가이드

```kotlin
/**
 * DLQ 메시지를 재처리한다.
 * 
 * 이 메서드는 실패한 메시지를 원본 토픽으로 다시 전송하는 역할을 한다.
 * 재처리 전에 메시지의 상태와 재시도 횟수를 검증하며,
 * 재처리 정책에 따라 백오프 지연을 적용한다.
 * 
 * @param messageId 재처리할 메시지의 ID
 * @param options 재처리 옵션 (선택사항)
 * @return 재처리 결과를 담은 [ReprocessingResult]
 * @throws MessageNotFoundException 메시지를 찾을 수 없을 때
 * @throws ReprocessingNotAllowedException 재처리 조건을 만족하지 않을 때
 * @throws KafkaException Kafka 전송 중 오류 발생 시
 * 
 * @see ReprocessingPolicy
 * @see BackoffStrategy
 * 
 * @sample
 * ```
 * val result = service.reprocessMessage(123L)
 * when (result) {
 *     is ReprocessingResult.Success -> logger.info("Successfully reprocessed")
 *     is ReprocessingResult.Failed -> logger.error("Failed: ${result.reason}")
 * }
 * ```
 * 
 * @since 0.1.0
 */
fun reprocessMessage(
    messageId: Long,
    options: ReprocessingOptions = ReprocessingOptions.DEFAULT
): ReprocessingResult
```

### 인라인 주석 가이드

```kotlin
// ✅ Good - WHY를 설명
class DLQMessageListener {
    @KafkaListener(topicPattern = ".*\\.DLQ")
    fun handle(record: ConsumerRecord<String, String>) {
        // Kafka는 실패한 메시지의 원본 정보를 헤더에 저장한다
        // 이 정보는 재처리 시 원본 토픽을 찾는 데 필수적이다
        val originalTopic = extractOriginalTopic(record.headers())
        
        // 5초 이내 중복 메시지는 무시한다 (Kafka의 at-least-once 보장으로 인한 중복 방지)
        if (isDuplicate(record, Duration.ofSeconds(5))) {
            return
        }
        
        // 에러 타입에 따라 재처리 가능 여부가 결정된다
        // TransientError는 재시도 가능, PermanentError는 불가능
        val errorType = classifyError(record)
        
        processMessage(record, originalTopic, errorType)
    }
}

// ❌ Bad - WHAT을 설명 (코드를 읽으면 알 수 있는 내용)
fun calculateTotal(items: List<Item>): BigDecimal {
    // total을 0으로 초기화한다
    var total = BigDecimal.ZERO
    
    // items를 순회한다
    for (item in items) {
        // 가격을 더한다
        total = total.add(item.price)
    }
    
    // total을 반환한다
    return total
}
```

### TODO 주석

```kotlin
// ✅ Good - 구체적인 TODO
// TODO(2025-07-01): MongoDB 지원 추가 - Issue #123
// TODO(john@example.com): 성능 최적화 필요 - 현재 O(n²), O(n log n)으로 개선 가능

// ❌ Bad - 모호한 TODO
// TODO: 나중에 수정
// TODO: 리팩토링 필요
```

---

## 에러 처리

### 예외 계층 구조

```kotlin
// 기본 예외 클래스
sealed class DLQException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

// 비즈니스 예외
class MessageNotFoundException(
    val messageId: Long
) : DLQException("DLQ message not found: $messageId")

class ReprocessingNotAllowedException(
    val messageId: Long,
    val reason: String
) : DLQException("Cannot reprocess message $messageId: $reason")

class RetryLimitExceededException(
    val messageId: Long,
    val retryCount: Int,
    val maxRetries: Int
) : DLQException(
    "Message $messageId exceeded retry limit: $retryCount/$maxRetries"
)

// 기술적 예외
class KafkaConnectionException(
    cause: Throwable
) : DLQException("Failed to connect to Kafka", cause)
```

### 예외 처리 패턴

```kotlin
// ✅ Good - 구체적인 예외 처리
class DLQService {
    fun reprocessMessage(id: Long): ReprocessingResult {
        return try {
            val message = repository.findById(id)
                ?: throw MessageNotFoundException(id)
            
            validateReprocessing(message)
            
            val result = kafkaTemplate.send(
                message.originalTopic,
                message.messageKey,
                message.payload
            ).get(5, TimeUnit.SECONDS)
            
            ReprocessingResult.Success(
                messageId = id,
                partition = result.recordMetadata.partition(),
                offset = result.recordMetadata.offset()
            )
            
        } catch (e: MessageNotFoundException) {
            logger.warn("Message not found for reprocessing: ${e.messageId}")
            ReprocessingResult.Failed(id, "Message not found")
            
        } catch (e: ReprocessingNotAllowedException) {
            logger.info("Reprocessing not allowed: ${e.reason}")
            ReprocessingResult.Skipped(id, e.reason)
            
        } catch (e: TimeoutException) {
            logger.error("Kafka send timeout for message: $id", e)
            ReprocessingResult.Failed(id, "Kafka timeout")
            
        } catch (e: Exception) {
            logger.error("Unexpected error during reprocessing: $id", e)
            ReprocessingResult.Failed(id, "Internal error")
        }
    }
}
```

### Result 타입 사용

```kotlin
// ✅ Good - Result 타입으로 명시적 에러 처리
sealed class ValidationResult {
    object Success : ValidationResult()
    data class Failure(val errors: List<ValidationError>) : ValidationResult()
}

fun validateMessage(message: DLQMessage): ValidationResult {
    val errors = mutableListOf<ValidationError>()
    
    if (message.originalTopic.isBlank()) {
        errors.add(ValidationError("originalTopic", "Must not be blank"))
    }
    
    if (message.retryCount < 0) {
        errors.add(ValidationError("retryCount", "Must not be negative"))
    }
    
    return if (errors.isEmpty()) {
        ValidationResult.Success
    } else {
        ValidationResult.Failure(errors)
    }
}

// 사용 예시
when (val result = validateMessage(message)) {
    is ValidationResult.Success -> processMessage(message)
    is ValidationResult.Failure -> handleValidationErrors(result.errors)
}
```

---

## 테스트 작성

### 테스트 구조 (Given-When-Then)

```kotlin
class DLQMessageServiceTest {
    private val repository = mockk<DLQMessageRepository>()
    private val validator = mockk<DLQMessageValidator>()
    private val reprocessor = mockk<MessageReprocessor>()
    
    private val service = DLQMessageService(repository, validator, reprocessor)
    
    @Test
    fun `should successfully reprocess eligible message`() {
        // Given - 테스트 데이터와 목 설정
        val messageId = 123L
        val message = createTestMessage(
            id = messageId,
            status = DLQStatus.PENDING,
            retryCount = 1
        )
        
        every { repository.findById(messageId) } returns message
        every { validator.canReprocess(message) } returns true
        every { reprocessor.reprocess(message) } returns ReprocessingResult.Success
        
        // When - 테스트 대상 메서드 실행
        val result = service.reprocessMessage(messageId)
        
        // Then - 결과 검증
        assertThat(result).isInstanceOf(ReprocessingResult.Success::class.java)
        
        verify(exactly = 1) { repository.findById(messageId) }
        verify(exactly = 1) { validator.canReprocess(message) }
        verify(exactly = 1) { reprocessor.reprocess(message) }
    }
    
    @Test
    fun `should throw exception when message not found`() {
        // Given
        val messageId = 999L
        every { repository.findById(messageId) } returns null
        
        // When & Then
        assertThrows<MessageNotFoundException> {
            service.reprocessMessage(messageId)
        }.also { exception ->
            assertThat(exception.messageId).isEqualTo(messageId)
        }
        
        verify(exactly = 1) { repository.findById(messageId) }
        verify(exactly = 0) { validator.canReprocess(any()) }
    }
}
```

### 파라미터화 테스트

```kotlin
@ParameterizedTest
@CsvSource(
    "PENDING, 0, true",
    "PENDING, 2, true",
    "PENDING, 3, false",
    "SUCCESS, 0, false",
    "FAILED, 5, false"
)
fun `should determine reprocess eligibility based on status and retry count`(
    status: DLQStatus,
    retryCount: Int,
    expectedResult: Boolean
) {
    // Given
    val message = createTestMessage(
        status = status,
        retryCount = retryCount
    )
    
    // When
    val canReprocess = validator.canReprocess(message)
    
    // Then
    assertThat(canReprocess).isEqualTo(expectedResult)
}
```

### 통합 테스트

```kotlin
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DLQIntegrationTest {
    
    @Container
    val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
    
    @Container
    val postgres = PostgreSQLContainer<Nothing>("postgres:15")
    
    @Autowired
    lateinit var kafkaTemplate: KafkaTemplate<String, String>
    
    @Autowired
    lateinit var repository: DLQMessageRepository
    
    @Test
    fun `should process DLQ message end-to-end`() {
        // Given - Kafka 메시지 생성
        val dlqTopic = "orders.DLQ"
        val payload = """{"orderId": 123, "amount": 99.99}"""
        val headers = RecordHeaders().apply {
            add("kafka_dlt-original-topic", "orders".toByteArray())
            add("kafka_dlt-exception-message", "Processing failed".toByteArray())
        }
        
        // When - DLQ 토픽으로 메시지 전송
        kafkaTemplate.send(
            ProducerRecord(dlqTopic, null, null, "order-123", payload, headers)
        ).get()
        
        // Then - 메시지가 저장되었는지 확인
        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            val messages = repository.findByOriginalTopic("orders")
            assertThat(messages).hasSize(1)
            assertThat(messages[0].payload).isEqualTo(payload)
            assertThat(messages[0].errorMessage).isEqualTo("Processing failed")
        }
    }
}
```

### 테스트 데이터 빌더

```kotlin
// 테스트 데이터 생성을 위한 DSL
class DLQMessageBuilder {
    var id: Long? = null
    var originalTopic: String = "test-topic"
    var status: DLQStatus = DLQStatus.PENDING
    var retryCount: Int = 0
    var errorType: String = "TestError"
    var createdAt: Instant = Instant.now()
    
    fun build(): DLQMessage = DLQMessage(
        id = id,
        originalTopic = originalTopic,
        status = status,
        retryCount = retryCount,
        errorType = errorType,
        createdAt = createdAt
    )
}

fun dlqMessage(block: DLQMessageBuilder.() -> Unit): DLQMessage =
    DLQMessageBuilder().apply(block).build()

// 사용 예시
@Test
fun `test with builder`() {
    val message = dlqMessage {
        originalTopic = "orders"
        status = DLQStatus.FAILED
        retryCount = 3
    }
    
    // ... test logic
}
```

---

## 동시성과 성능

### 동시성 안전 코드

```kotlin
// ✅ Good - 스레드 안전한 싱글톤
object MetricsCollector {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    
    fun increment(metric: String) {
        counters.computeIfAbsent(metric) { AtomicLong() }
            .incrementAndGet()
    }
    
    fun getCount(metric: String): Long =
        counters[metric]?.get() ?: 0L
}

// ✅ Good - 불변 객체로 동시성 문제 회피
@Service
class DLQStatisticsService {
    // 통계는 불변 객체로 반환
    fun getStatistics(): DLQStatistics {
        return DLQStatistics(
            totalMessages = repository.count(),
            pendingMessages = repository.countByStatus(DLQStatus.PENDING),
            failedMessages = repository.countByStatus(DLQStatus.FAILED),
            timestamp = Instant.now()
        )
    }
}

// ✅ Good - 코루틴을 활용한 비동기 처리
class AsyncMessageProcessor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    fun processMessagesAsync(messages: List<DLQMessage>) {
        scope.launch {
            messages
                .chunked(100) // 배치 처리
                .map { batch ->
                    async {
                        processBatch(batch)
                    }
                }
                .awaitAll()
        }
    }
    
    private suspend fun processBatch(batch: List<DLQMessage>) {
        withContext(Dispatchers.IO) {
            batch.forEach { message ->
                processMessage(message)
            }
        }
    }
}
```

### 성능 최적화 패턴

```kotlin
// ✅ Good - 레이지 로딩과 캐싱
class CachedDLQService(
    private val repository: DLQMessageRepository,
    private val cacheManager: CacheManager
) {
    companion object {
        private const val CACHE_NAME = "dlq-messages"
        private const val CACHE_TTL_MINUTES = 5
    }
    
    @Cacheable(CACHE_NAME, key = "#id")
    fun findById(id: Long): DLQMessage? =
        repository.findById(id)
    
    @CacheEvict(CACHE_NAME, key = "#message.id")
    fun update(message: DLQMessage): DLQMessage =
        repository.save(message)
    
    // 배치 조회 최적화
    fun findByIds(ids: List<Long>): List<DLQMessage> {
        // 캐시에서 먼저 조회
        val cached = ids.mapNotNull { id ->
            cacheManager.getCache(CACHE_NAME)?.get(id)?.get() as? DLQMessage
        }
        
        val cachedIds = cached.map { it.id!! }.toSet()
        val missingIds = ids.filterNot { it in cachedIds }
        
        // 캐시되지 않은 것만 DB 조회
        val fromDb = if (missingIds.isNotEmpty()) {
            repository.findAllById(missingIds).also { messages ->
                // 조회한 결과를 캐시에 저장
                messages.forEach { message ->
                    cacheManager.getCache(CACHE_NAME)?.put(message.id!!, message)
                }
            }
        } else {
            emptyList()
        }
        
        return cached + fromDb
    }
}

// ✅ Good - 쿼리 최적화
interface DLQMessageRepository : JpaRepository<DLQMessage, Long> {
    // N+1 문제 방지
    @EntityGraph(attributePaths = ["metadata"])
    override fun findAll(): List<DLQMessage>
    
    // 인덱스 활용
    @Query("""
        SELECT m FROM DLQMessage m
        WHERE m.status = :status
        AND m.createdAt >= :since
        ORDER BY m.createdAt DESC
    """)
    fun findRecentByStatus(
        @Param("status") status: DLQStatus,
        @Param("since") since: Instant,
        pageable: Pageable
    ): Page<DLQMessage>
    
    // 배치 업데이트
    @Modifying
    @Query("""
        UPDATE DLQMessage m
        SET m.status = :newStatus
        WHERE m.id IN :ids
    """)
    fun updateStatusBatch(
        @Param("ids") ids: List<Long>,
        @Param("newStatus") newStatus: DLQStatus
    ): Int
}
```

---

## 보안 고려사항

### 입력 검증

```kotlin
// ✅ Good - 철저한 입력 검증
@RestController
@RequestMapping("/api/v1/dlq")
class DLQController(
    private val service: DLQService,
    private val validator: InputValidator
) {
    @PostMapping("/messages/{id}/reprocess")
    fun reprocess(
        @PathVariable @Positive id: Long,
        @Valid @RequestBody request: ReprocessRequest
    ): ResponseEntity<ReprocessResult> {
        // 추가 보안 검증
        validator.validateReprocessRequest(request)
        
        // SQL Injection 방지 - 파라미터화된 쿼리 사용
        val result = service.reprocessMessage(id)
        
        return ResponseEntity.ok(result)
    }
}

// 입력 검증 클래스
data class ReprocessRequest(
    @field:NotBlank(message = "Target topic is required")
    @field:Pattern(
        regexp = "^[a-zA-Z0-9._-]+$",
        message = "Invalid topic name format"
    )
    val targetTopic: String?,
    
    @field:Min(0, message = "Delay must be non-negative")
    @field:Max(3600, message = "Delay cannot exceed 1 hour")
    val delaySeconds: Int = 0
)
```

### 민감 정보 보호

```kotlin
// ✅ Good - 민감 정보 마스킹
data class DLQMessageResponse(
    val id: Long,
    val originalTopic: String,
    val errorType: String,
    val payload: String,
    val createdAt: Instant
) {
    companion object {
        fun from(message: DLQMessage, maskPayload: Boolean = true): DLQMessageResponse {
            return DLQMessageResponse(
                id = message.id!!,
                originalTopic = message.originalTopic,
                errorType = message.errorType,
                payload = if (maskPayload) maskSensitiveData(message.payload) else message.payload,
                createdAt = message.createdAt
            )
        }
        
        private fun maskSensitiveData(payload: String): String {
            return payload
                .replace(Regex(""""password"\s*:\s*"[^"]+""""), """"password":"***"""")
                .replace(Regex(""""apiKey"\s*:\s*"[^"]+""""), """"apiKey":"***"""")
                .replace(Regex(""""ssn"\s*:\s*"[^"]+""""), """"ssn":"***"""")
        }
    }
}

// 로깅에서 민감 정보 제외
@Component
class SecureLogger {
    private val logger = LoggerFactory.getLogger(this::class.java)
    
    fun logMessage(message: DLQMessage) {
        logger.info("Processing DLQ message: id=${message.id}, topic=${message.originalTopic}")
        // payload는 로깅하지 않음
    }
}
```

### 권한 검증

```kotlin
// ✅ Good - 메서드 레벨 보안
@Service
class SecureDLQService(
    private val repository: DLQMessageRepository,
    private val authService: AuthorizationService
) {
    @PreAuthorize("hasRole('ADMIN') or hasRole('DLQ_OPERATOR')")
    fun reprocessAll(): BatchResult {
        // 관리자만 실행 가능
        return processAllPendingMessages()
    }
    
    @PreAuthorize("hasPermission(#id, 'DLQMessage', 'REPROCESS')")
    fun reprocessMessage(id: Long): ReprocessResult {
        // 세밀한 권한 검증
        return doReprocess(id)
    }
    
    @PostAuthorize("returnObject == null or hasPermission(returnObject, 'READ')")
    fun findById(id: Long): DLQMessage? {
        // 조회 결과에 대한 권한 검증
        return repository.findById(id)
    }
}
```

---

## 코드 리뷰 체크리스트

### 일반 사항
- [ ] 함수가 15줄을 넘지 않는가?
- [ ] 함수가 한 가지 일만 하는가?
- [ ] 변수/함수/클래스 이름이 의도를 명확히 표현하는가?
- [ ] 불필요한 주석은 없는가?
- [ ] 매직 넘버가 상수로 추출되었는가?

### 설계
- [ ] SOLID 원칙을 준수하는가?
- [ ] 의존성이 올바른 방향으로 흐르는가?
- [ ] 인터페이스가 적절히 사용되었는가?
- [ ] 불변성을 최대한 활용했는가?

### 에러 처리
- [ ] 예외가 적절한 수준에서 처리되는가?
- [ ] 에러 메시지가 명확한가?
- [ ] null 체크가 적절히 되어 있는가?
- [ ] 리소스가 올바르게 정리되는가?

### 테스트
- [ ] 테스트가 Given-When-Then 패턴을 따르는가?
- [ ] 테스트 이름이 테스트하는 내용을 명확히 설명하는가?
- [ ] 엣지 케이스가 테스트되었는가?
- [ ] 목(Mock)이 과도하게 사용되지 않았는가?

### 성능
- [ ] N+1 쿼리 문제가 없는가?
- [ ] 불필요한 데이터베이스 호출이 없는가?
- [ ] 대용량 데이터 처리 시 메모리 효율적인가?
- [ ] 동시성 문제가 없는가?

### 보안
- [ ] 입력 검증이 적절한가?
- [ ] SQL Injection 위험이 없는가?
- [ ] 민감 정보가 로그에 노출되지 않는가?
- [ ] 권한 검증이 적절한가?

---

## Git 컨벤션

### 브랜치 전략

```bash
main                    # 프로덕션 브랜치
├── develop            # 개발 브랜치
    ├── feature/       # 기능 개발
    ├── bugfix/        # 버그 수정
    ├── hotfix/        # 긴급 수정
    └── refactor/      # 리팩토링

# 브랜치 명명 규칙
feature/add-retry-mechanism
bugfix/fix-null-pointer-in-parser
hotfix/critical-memory-leak
refactor/simplify-validation-logic
```

### 커밋 메시지 컨벤션

```bash
# 형식
<type>(<scope>): <subject>

<body>

<footer>

# 타입
feat: 새로운 기능 추가
fix: 버그 수정
docs: 문서 수정
style: 코드 포맷팅, 세미콜론 누락 등
refactor: 코드 리팩토링
test: 테스트 코드
chore: 빌드 업무, 패키지 매니저 수정 등

# 예시
feat(retry): Add exponential backoff strategy

- Implement ExponentialBackoffStrategy class
- Add configurable max attempts and base delay
- Include jitter to prevent thundering herd

Closes #123

# 깨진 커밋 표시
fix(parser): Fix NPE when parsing empty headers

Previous commit 3a5c7d9 introduced a regression where
empty headers would cause NullPointerException.

This commit adds proper null checking.

BREAKING CHANGE: parseHeaders() now returns empty map instead of null
```

### PR 템플릿

```markdown
## 📋 작업 내용
<!-- 이 PR에서 수행한 작업을 간단히 설명해주세요 -->

## 🔗 관련 이슈
<!-- 관련 이슈 번호를 적어주세요 -->
Closes #

## ✅ 체크리스트
- [ ] 코드 컨벤션을 준수했습니다
- [ ] 테스트를 작성했습니다
- [ ] 문서를 업데이트했습니다
- [ ] CHANGELOG를 업데이트했습니다

## 📸 스크린샷
<!-- 필요한 경우 스크린샷을 첨부해주세요 -->

## 💬 리뷰어에게
<!-- 리뷰어가 특별히 봐야 할 부분이나 질문이 있다면 적어주세요 -->
```

---

## 추가 권장사항

### 1. 페어 프로그래밍
- 복잡한 기능은 페어 프로그래밍으로 구현
- 실시간 코드 리뷰로 품질 향상
- 지식 공유 및 팀 역량 강화

### 2. 리팩토링 규칙
- 리팩토링은 별도 PR로 진행
- 기능 변경과 리팩토링을 섞지 않음
- 리팩토링 전후 테스트 통과 확인

### 3. 기술 부채 관리
- TODO 주석은 이슈로 등록
- 정기적인 기술 부채 정리 시간
- 코드 품질 메트릭 모니터링

### 4. 문서화 우선순위
1. Public API는 반드시 문서화
2. 복잡한 비즈니스 로직 설명
3. 설정 옵션 및 기본값
4. 트러블슈팅 가이드

---

*이 문서는 지속적으로 업데이트됩니다. 제안사항이 있다면 이슈를 생성해주세요.*

**Last Updated**: 2025-06-25