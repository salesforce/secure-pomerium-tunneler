import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.gradleIntelliJPlugin) // Gradle IntelliJ Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

// Configure project's dependencies
allprojects {
    repositories {
        mavenCentral()
    }

    configurations.configureEach {
        exclude(group = "ai.grazie.spell")
        exclude(group = "ai.grazie.nlp")
        exclude(group = "ai.grazie.utils")
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

// Exclude standard kotlinx-coroutines-core from test configurations to avoid
// conflicting with IntelliJ platform's patched coroutines (1.10.2-intellij-1)
configurations.matching { it.name.startsWith("test") }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    intellijPlatform {
        gateway(properties("platformVersion"))
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
        testFramework(TestFrameworkType.Bundled)
    }
    implementation(project(":tunneler")) {
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-core")
        exclude("com.jetbrains.rd", "rd-framework")
        exclude("io.ktor", "ktor-network")
        exclude("io.ktor", "ktor-network-tls")
        exclude("io.ktor", "ktor-client-core")
        exclude("io.ktor", "ktor-http-jvm")
        exclude("io.ktor", "ktor-utils-jvm")
        exclude("io.ktor", "ktor-io")
        exclude("org.slf4j", "*")
    }
    testImplementation(testFixtures(project(":tunneler")))
    testImplementation(libs.junitJupiterApi)
    testRuntimeOnly(libs.junitJupiterEngine)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testImplementation(libs.coroutineTest)
    testImplementation(libs.mockitoKotlin)
}

// Set the JVM language level used to build the project. Use Java 11 for 2020.3+, and Java 17 for 2022.2+.
kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        version = properties("pluginVersion")
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
                    val start = "<!-- Plugin description -->"
                    val end = "<!-- Plugin description end -->"

                    with (it.lines()) {
                        if (!containsAll(listOf(start, end))) {
                            throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                        }
                        subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
                    }
                }
        val changelog = project.changelog // local variable for configuration cache compatibility
        changeNotes = properties("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

// Workaround: Gateway 2026.1 product-info.json has productModuleV2 entries without classPath field.
// This causes intellij-plugin-structure to fail with NullPointerException during IDE validation.
// Tracked: https://youtrack.jetbrains.com/issue/IJPL-242405
// Fixed upstream: https://github.com/JetBrains/intellij-plugin-verifier/pull/1470
// Remove this workaround once IntelliJ Platform Gradle Plugin bundles the fixed version
configurations.named("intellijPlatformDependency") {
    incoming.afterResolve {
        resolutionResult.allComponents {
            val platformPath = moduleVersion?.let {
                configurations.getByName("intellijPlatformDependency").resolve().firstOrNull()
            }
            if (platformPath != null) {
                listOf(
                    File(platformPath, "product-info.json"),
                    File(platformPath, "Resources/product-info.json")
                ).filter { it.exists() }.forEach { productInfoFile ->
                    val content = productInfoFile.readText()
                    if (content.contains(Regex("\"kind\"\\s*:\\s*\"productModuleV2\"\\s*\\}"))) {
                        productInfoFile.writeText(
                            content.replace(
                                Regex("(\"kind\"\\s*:\\s*\"productModuleV2\")\\s*\\}"),
                                "$1, \"classPath\": []}"
                            )
                        )
                    }
                }
            }
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = properties("pluginRepositoryUrl")
}
tasks {
    kover {
        reports {
            check(true)
        }
    }

    check {
        dependsOn("koverXmlReport")
    }

    test {
        useJUnitPlatform()
        // Byte Buddy (used by Mockito) doesn't officially support Java 25 yet
        jvmArgs("-Dnet.bytebuddy.experimental=true")
    }

    signPlugin {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

//    publishPlugin {
//        dependsOn("patchChangelog")
//        token = environment("PUBLISH_TOKEN")
//        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
//        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
//        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
//        channels = properties("pluginVersion").map { listOf(it.split('-').getOrElse(1) { "default" }.split('.').first()) }
//    }
}
