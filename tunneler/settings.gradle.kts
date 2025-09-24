pluginManagement {
    plugins {
        kotlin("jvm") version "2.1.21"
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

