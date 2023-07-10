import org.jetbrains.kotlin.gradle.utils.extendsFrom

plugins {
    id("java")
    id("java-test-fixtures")
    kotlin("jvm") version "1.9.0"
}

group = "com.salesforce.pomerium"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

configurations {
    testFixturesImplementation.extendsFrom(implementation)
    testImplementation.extendsFrom(testFixturesImplementation)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")
    api("com.jetbrains.rd:rd-framework:2023.2.2")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("com.athaydes.rawhttp:rawhttp-core:2.5.2")
    implementation("io.ktor:ktor-network:2.2.3")
    implementation("io.ktor:ktor-network-tls:2.2.3")
    implementation("org.slf4j:slf4j-api:2.0.5")
    testFixturesImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    testFixturesImplementation("com.squareup.okhttp3:mockwebserver:4.11.0")
    testFixturesImplementation(platform("org.junit:junit-bom:5.9.1"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}