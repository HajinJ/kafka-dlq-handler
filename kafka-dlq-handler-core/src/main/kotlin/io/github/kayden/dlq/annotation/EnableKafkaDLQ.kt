package io.github.kayden.dlq.annotation

import io.github.kayden.dlq.autoconfigure.DLQAutoConfiguration
import org.springframework.context.annotation.Import
import kotlin.annotation.AnnotationRetention
import kotlin.annotation.AnnotationTarget
import kotlin.annotation.MustBeDocumented
import kotlin.annotation.Retention
import kotlin.annotation.Target

/**
 * Kafka DLQ Handler 자동 설정을 활성화하는 어노테이션
 * 
 * 이 어노테이션을 Spring Boot 애플리케이션 클래스에 추가하면
 * DLQ 메시지 처리에 필요한 모든 컴포넌트가 자동으로 구성된다.
 * 
 * @sample
 * ```
 * @SpringBootApplication
 * @EnableKafkaDLQ
 * class MyApplication
 * 
 * fun main(args: Array<String>) {
 *     runApplication<MyApplication>(*args)
 * }
 * ```
 * 
 * @see DLQAutoConfiguration
 * @since 0.1.0
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(DLQAutoConfiguration::class)
annotation class EnableKafkaDLQ