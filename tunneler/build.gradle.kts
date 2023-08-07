import org.jetbrains.kotlin.gradle.utils.extendsFrom

plugins {
    id("java")
    id("java-test-fixtures")
    kotlin("jvm")
}

group = "com.salesforce.pomerium"
version = "1.0-SNAPSHOT"

configurations {
    testFixturesImplementation.extendsFrom(implementation)
    testImplementation.extendsFrom(testFixturesImplementation)
}

dependencies {
    val ktorVersion = "2.3.3"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2")
    api("com.jetbrains.rd:rd-framework:2023.2.2")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("com.athaydes.rawhttp:rawhttp-core:2.5.2")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-network:$ktorVersion")
    implementation("io.ktor:ktor-network-tls:$ktorVersion")
    implementation("org.slf4j:slf4j-api:2.0.5")
    testFixturesImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.2")
    testFixturesImplementation("com.squareup.okhttp3:mockwebserver:4.11.0")
    testFixturesImplementation(platform("org.junit:junit-bom:5.9.1"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}