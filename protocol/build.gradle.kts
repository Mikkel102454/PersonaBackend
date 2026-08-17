plugins {
    `java-library`
    `maven-publish`
}

group = "nu.miguel"
version = "1.0.0-SNAPSHOT"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

repositories { mavenCentral() }

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations:2.20")
}

publishing {
    publications {
        create<MavenPublication>("protocol") { from(components["java"]) }
    }
}
