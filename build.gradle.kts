import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.spring") version "1.9.25" apply false
    kotlin("plugin.jpa") version "1.9.25" apply false
    id("org.springframework.boot") version "3.5.3" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.jetbrains.dokka") version "1.9.10" apply false
    `maven-publish`
    signing
}

allprojects {
    group = "io.github.kayden"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "kotlin")
    apply(plugin = "org.jetbrains.dokka")

    // Spring Boot BOM 적용 (버전 관리만)
    if (name.startsWith("kafka-dlq-handler-") || name.contains("example")) {
        apply(plugin = "io.spring.dependency-management")

        configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
            imports {
                mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.3")
            }
        }
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict")
            jvmTarget = "21"
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
}

configure(subprojects.filter {
    it.name.startsWith("kafka-dlq-handler-")
}) {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])

                pom {
                    name.set("Kafka DLQ Handler")
                    description.set("Simple Kafka Dead Letter Queue Handler for Spring Boot")
                    url.set("https://github.com/kayden/kafka-dlq-handler")

                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }

                    developers {
                        developer {
                            id.set("kayden")
                            name.set("Kayden Jung")
                            email.set("your-email@example.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/kayden/kafka-dlq-handler.git")
                        developerConnection.set("scm:git:ssh://github.com/kayden/kafka-dlq-handler.git")
                        url.set("https://github.com/kayden/kafka-dlq-handler")
                    }
                }
            }
        }
    }
}