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
        // The web edition's build tools, which Kotlin/Wasm downloads itself:
        // Node.js and Yarn to bundle, Binaryen to optimize.
        ivy("https://nodejs.org/dist") {
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            patternLayout { artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "riffbuddy"

// The desktop and web editions build without the Android app (and so without
// the Android SDK) when RIFFBUDDY_DESKTOP_ONLY or RIFFBUDDY_WEB_ONLY is set,
// as the release builds do; each also leaves the other out.
val desktopOnly = System.getenv("RIFFBUDDY_DESKTOP_ONLY") != null
val webOnly = System.getenv("RIFFBUDDY_WEB_ONLY") != null
if (!desktopOnly && !webOnly) include(":riff_buddy")
if (!webOnly) {
    include(":desktop")
    project(":desktop").projectDir = file("riff_buddy/desktop")
}
if (!desktopOnly) {
    include(":web")
    project(":web").projectDir = file("riff_buddy/web")
}
