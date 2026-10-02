pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "riffbuddy"

// The desktop edition builds without the Android app (and so without the
// Android SDK) when RIFFBUDDY_DESKTOP_ONLY is set, as the installer builds do.
if (System.getenv("RIFFBUDDY_DESKTOP_ONLY") == null) include(":riff_buddy")
include(":desktop")
project(":desktop").projectDir = file("riff_buddy/desktop")
