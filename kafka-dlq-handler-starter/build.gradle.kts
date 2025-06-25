plugins {
    kotlin("jvm")
    id("io.spring.dependency-management")
    `java-library`  // java-library 플러그인 추가
}

dependencies {
    api(project(":kafka-dlq-handler-core"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")
}

// bootJar 관련 코드 제거
java {
    withSourcesJar()
    withJavadocJar()
}