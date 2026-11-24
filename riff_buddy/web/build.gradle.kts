// Riff Buddy in the browser: an installable web app, compiled to WebAssembly.
//
// The screens are the same Compose code the phone and desktop editions run;
// Compose draws them onto a canvas that fills the page. It compiles only
// shared/src/commonMain, the code with nothing Java-specific in it; the
// Android and desktop editions compile that and shared/src/jvmMain besides.
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("riffbuddy")
        browser {
            commonWebpackConfig { outputFileName = "riffbuddy.js" }
        }
        binaries.executable()
    }
    sourceSets {
        wasmJsMain {
            kotlin.srcDir("../shared/src/commonMain/kotlin")
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.materialIconsExtended)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.okio)
                // Files live in memory until the browser storage behind them is written.
                implementation(libs.okio.fakefilesystem)
                implementation(libs.ktor.client.js)
                // SQLite itself, built for WebAssembly by the SQLite project.
                implementation(npm("@sqlite.org/sqlite-wasm", "3.54.1-build1"))
                // The tab renderer and player, the same version the other editions use.
                implementation(npm("@coderline/alphatab", "1.8.4"))
            }
        }
    }
}

// alphaTab runs as an ordinary script beside the app, with its music font and
// SoundFont served next to it, rather than bundled: it starts its own workers
// from its script's address.
tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(":kotlinWasmNpmInstall")
    from(rootProject.layout.buildDirectory.dir("wasm/node_modules/@coderline/alphatab/dist")) {
        include("alphaTab.js", "font/**", "soundfont/**")
        into("alphatab")
    }
    // The bundled samples and fonts, the same files the other editions ship.
    from("../src/main/assets/samples") { exclude("README.md"); into("samples") }
    from("../src/main/assets/fonts") { include("*.ttf", "*-OFL.txt"); into("fonts") }
}

// The Wasm optimizer's download site is declared in settings.gradle.kts;
// modules may not add their own.
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
}

// The compiled library folder keeps entries for sources that have since moved
// away, and the compiler then fails reading them (as an out-of-memory error),
// so the task empties it first.
tasks.named<org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile>("compileKotlinWasmJs") {
    val out = destinationDirectory
    doFirst { out.get().asFile.deleteRecursively() }
}

// The service worker keeps every file the app needs, so it runs offline after
// the first visit. Its list is the build's own files, whose names carry
// content hashes, and its version is a hash of them all: any change makes a
// new worker, which the page offers as an update.
tasks.named("wasmJsBrowserDistribution") {
    val template = file("src/wasmJsMain/pwa/sw.template.js")
    inputs.file(template)
    doLast {
        val dist = outputs.files.files.first { it.isDirectory }
        val files = dist.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dist).invariantSeparatorsPath }
            .filter { !it.endsWith(".map") && !it.endsWith(".LICENSE.txt") && it != "sw.js" && !it.endsWith(".sf3") }
            .filter { !it.startsWith("alphatab/font/") || it.endsWith(".woff2") || it.endsWith(".otf") || it.endsWith(".txt") }
            .sorted().toList()
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { digest.update(it.toByteArray()); digest.update(File(dist, it).readBytes()) }
        val version = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        val list = (listOf("./") + files).joinToString(",\n  ", "[\n  ", "\n]") { "'$it'" }
        File(dist, "sw.js").writeText(template.readText().replace("__VERSION__", version).replace("__FILES__", list))
    }
}
