pluginManagement {
    plugins {
        kotlin("jvm") version "1.9.0"
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    dependencyResolutionManagement {
        versionCatalogs {
            create("libs") {
                from(files("../gradle/libs.versions.toml"))
            }
        }
    }
}
rootProject.name = "tunneler"

