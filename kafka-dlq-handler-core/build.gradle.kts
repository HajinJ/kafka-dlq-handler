plugins {
    kotlin("jvm")
    `java-library`
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    // Core 모듈은 프레임워크 독립적이므로 Spring 의존성 없음
    
    // Kotlin 기본
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    
    // Coroutines
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.7.3")
    
    // 테스트
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    
    // Kotest
    testImplementation("io.kotest:kotest-runner-junit5:5.7.2")
    testImplementation("io.kotest:kotest-assertions-core:5.7.2")
    testImplementation("io.kotest:kotest-property:5.7.2")
    
    // JMH for benchmarking
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    jmh("org.openjdk.jmh:jmh-generator-bytecode:1.37")
}

tasks.test {
    useJUnitPlatform()
}

// bootJar 관련 코드 제거하고 다음으로 교체
java {
    withSourcesJar()
    withJavadocJar()
}

// JMH configuration
jmh {
    warmupIterations.set(2)
    iterations.set(5)
    fork.set(2)
    threads.set(4)
    
    // JVM args for better performance measurement
    jvmArgs.set(listOf(
        "-Xms2G",
        "-Xmx2G",
        "-XX:+UseG1GC",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseStringDeduplication",
        "-XX:+AlwaysPreTouch"
    ))
    
    // Report formats
    resultFormat.set("JSON")
    resultsFile.set(file("$buildDir/reports/jmh/results.json"))
    
    // Profilers
    profilers.set(listOf("gc", "stack"))
}