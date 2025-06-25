package io.github.kayden.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
// @EnableKafkaDLQ  // 아직 구현 전이므로 주석 처리
class SimpleExampleApplication

fun main(args: Array<String>) {
    runApplication<SimpleExampleApplication>(*args)
}
