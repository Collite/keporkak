pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "keporkak"
include("kp-model", "kp-rules", "kp-engine", "kp-log", "kp-facts", "kp-ops", "kp-driver", "kp-seat-api", "kp-mailroom", "kp-board", "kp-daemon")
