import org.jetbrains.kotlin.gradle.utils.extendsFrom

plugins {
    id("java")
    id("java-test-fixtures")
    alias(libs.plugins.kotlin) // Kotlin support
}

fun properties(key: String) = providers.gradleProperty(key)

group = properties("tunnelerGroup").get()
version = properties("pluginVersion").get()

configurations {
    testFixturesImplementation.extendsFrom(implementation)
    testImplementation.extendsFrom(testFixturesImplementation)
}

dependencies {
    api(libs.rdFramework)
    implementation(libs.coroutine)
    implementation(libs.httpClient)
    implementation(libs.rawHttp)
    implementation(libs.ktorClientCore)
    implementation(libs.ktorClientOkhttp)
    implementation(libs.ktorServerCore)
    implementation(libs.ktorServerNetty)
    implementation(libs.ktorNetwork)
    implementation(libs.ktorNetworkTls)
    implementation(libs.slf4jApi)
    testRuntimeOnly(libs.junitJupiterEngine)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testFixturesImplementation(libs.mockitoKotlin)
    testFixturesImplementation(libs.coroutineTest)
    testFixturesImplementation(libs.mockWebServer)
    testFixturesImplementation(libs.junitJupiterApi)
}

tasks.test {
    useJUnitPlatform()
}