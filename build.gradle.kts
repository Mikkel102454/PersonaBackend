plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "nu.miguel"
version = "0.0.1-SNAPSHOT"
description = "PersonaBackend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-micrometer-metrics")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.0")
    implementation("org.yaml:snakeyaml:2.6")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework:spring-websocket")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<Zip>("sourceDistribution") {
    group = "distribution"
    description = "Packages the auditable, self-hostable editor, relay, protocol, deployment, and migration sources."
    archiveBaseName.set("persona-hosted-editor")
    archiveClassifier.set("sources")
    from(layout.projectDirectory) {
        include("src/**", "protocol/src/**", "build.gradle.kts", "settings.gradle.kts",
                "protocol/build.gradle.kts", "gradle/**", "gradlew", "gradlew.bat",
                "Dockerfile", "compose.yaml", ".dockerignore", ".env.example",
                "deploy/**", "DEPLOYMENT.md", "EDITOR_ARCHITECTURE.md", "SELF_HOSTING_SOURCE.md")
        exclude("**/build/**", "**/.gradle/**", "**/.idea/**")
    }
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}
