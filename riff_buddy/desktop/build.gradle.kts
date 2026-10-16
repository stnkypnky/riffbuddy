// Riff Buddy for Linux, Windows and macOS.
//
// The app's shared code lives in ../shared and is compiled here and into the
// Android app alike. Compose Multiplatform publishes the same androidx.compose
// packages as Jetpack Compose, so a screen compiles against either. Nothing in
// this module's classpath is Android, so an Android import in shared code
// fails this build — that is the guard that keeps the shared code portable.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    sourceSets {
        main { kotlin.srcDir("../shared/src/main/kotlin") }
        test {
            kotlin.srcDir("../shared/src/test/kotlin")
            resources.srcDir("../shared/src/test/resources")
        }
    }
    compilerOptions {
        // alphaTab's Kotlin is translated from TypeScript and asks for these.
        optIn.addAll("kotlin.ExperimentalUnsignedTypes", "kotlin.contracts.ExperimentalContracts")
    }
}

// alphaTab publishes only an Android library (AAR), but everything in it except
// its Android view and audio output is plain JVM code: the file parsers, the
// score renderer and the synthesizer. The AAR's classes.jar, its music font
// (Bravura) and its SoundFont are unpacked and used as they are, the same
// version the Android app ships.
val alphaTabAar: Configuration by configurations.creating { isTransitive = false }

interface Archives { @get:Inject val ops: ArchiveOperations }

val unpackAlphaTab by tasks.registering(Sync::class) {
    // Locals, not script values: the configuration cache can store a service
    // and a file collection, but not a reference back into this script.
    val ops = objects.newInstance<Archives>().ops
    val aar = alphaTabAar.elements
    from(aar.map { files -> files.map { ops.zipTree(it.asFile) } }) {
        include("classes.jar", "assets/**")
    }
    into(layout.buildDirectory.dir("alphatab"))
}

sourceSets.main {
    resources.srcDir(unpackAlphaTab.map { layout.buildDirectory.dir("alphatab/assets").get() })
    // The sample recordings and tabs, shared with the Android app's assets.
    resources.srcDir("../src/main/assets")
}

// One version for every edition: the Android app's versionName, read from its
// build script so a release changes it in one place.
val appVersion: String = Regex("""versionName\s*=\s*"([^"]+)"""")
    .find(file("../build.gradle.kts").readText())!!.groupValues[1]

// Each installer is built on the system it's for.
val hostOs = System.getProperty("os.name").lowercase()
val hostArm = System.getProperty("os.arch").lowercase().let { "aarch64" in it || "arm64" in it }

compose.desktop {
    application {
        mainClass = "com.riffbuddy.desktop.MainKt"
        jvmArgs += "-Driffbuddy.version=$appVersion"
        // The splash: Java shows it the moment the app starts, before any of the
        // app has loaded, and takes it down when the window opens. Installed
        // apps find it beside them; desktop:run shows none.
        jvmArgs += "-splash:\$APPDIR/resources/splash.png"
        // On Linux, the app names its window class to match its menu entry,
        // which takes reaching into Java's X11 toolkit (see AppIdentity).
        if ("linux" in hostOs) jvmArgs += "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"

        // No shrinking: alphaTab and its drawing library are reached by name
        // from native code and by reflection, which a shrinker can't follow.
        buildTypes.release.proguard { isEnabled = false }

        nativeDistributions {
            // Files every installer carries beside the app: the splash.
            appResourcesRootDir = layout.projectDirectory.dir("launch")
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Riff Buddy"
            packageVersion = appVersion
            description = "Guitar practice companion"
            vendor = "Riff Buddy"
            licenseFile = file("../LICENSES.md")
            // The Java runtime inside each installer carries only what the app
            // uses. jdk.crypto.ec is not found by scanning the code, but HTTPS
            // to the AI providers can't connect without it.
            modules("java.instrument", "java.prefs", "java.sql", "jdk.security.auth", "jdk.unsupported", "jdk.crypto.ec")

            linux {
                packageName = "riffbuddy"
                iconFile = file("icons/riffbuddy.png")
                shortcut = true
                appCategory = "Audio"
                menuGroup = "AudioVideo;Audio;Music"
                debMaintainer = "riffbuddy@users.noreply.github.com"
            }
            windows {
                iconFile = file("icons/riffbuddy.ico")
                menuGroup = "Riff Buddy"
                shortcut = true
                // Installs for the current user only, so no administrator
                // password is needed. The same code on every release lets a new
                // installer replace the old one.
                perUserInstall = true
                dirChooser = false
                upgradeUuid = "6f0b6f43-6a3a-4c7e-9a52-2f3c1d6e9b81"
            }
            macOS {
                iconFile = file("icons/riffbuddy.icns")
                bundleID = "com.riffbuddy.desktop"
                appCategory = "public.app-category.music"
                // macOS asks before any app hears the microphone, showing this.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Riff Buddy listens for the tuner and records your playing. Nothing leaves this Mac.</string>
                    """.trimIndent()
                }
            }
        }
    }
}

// Each installer is built on its own OS and carries only what that OS and
// processor use: these name the folders inside the native-library jars.
val alphaSkiaNatives = when {
    "win" in hostOs -> libs.alphaskia.windows
    "mac" in hostOs -> libs.alphaskia.macos
    else -> libs.alphaskia.linux
}
val alphaSkiaFolder = "native/" + when {
    "win" in hostOs -> "windows-"
    "mac" in hostOs -> "macos-"
    else -> "linux-"
} + if (hostArm) "arm64" else "x64"
val sqliteFolder = "org/sqlite/native/" + when {
    "win" in hostOs -> "Windows/"
    "mac" in hostOs -> "Mac/"
    else -> "Linux/"
} + if (hostArm) "aarch64" else "x86_64"

// Libraries that ship far more than the app uses, each trimmed on its way into
// the installer: the full Material icon set (tens of megabytes; the app draws a
// few dozen), and native libraries built for every system there is.
val iconsFull: Configuration by configurations.creating { isTransitive = false }
val alphaSkiaFull: Configuration by configurations.creating { isTransitive = false }
val sqliteFull: Configuration by configurations.creating { isTransitive = false }

/** Copies the icon jar keeping only the icons the compiled app names: each icon is a class of its own. */
abstract class TrimIconsJar : DefaultTask() {
    @get:InputFiles abstract val source: ConfigurableFileCollection
    @get:InputDirectory abstract val classes: DirectoryProperty
    @get:Input abstract val pattern: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun trim() {
        val re = Regex(pattern.get())
        val used = classes.get().asFile.walkTopDown().filter { it.extension == "class" }
            .flatMap { re.findAll(it.readBytes().toString(Charsets.ISO_8859_1)).map { m -> m.value } }
            .toSet()
        check(used.isNotEmpty()) { "found no icons in the compiled app" }
        ZipOutputStream(output.get().asFile.outputStream().buffered()).use { out ->
            for (jar in source.files) ZipFile(jar).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (entry.isDirectory) continue
                    val icon = name.startsWith("androidx/compose/material/icons/") && name.endsWith(".class")
                    if (icon && used.none { name.startsWith("$it.") || name.startsWith("$it$") }) continue
                    out.putNextEntry(ZipEntry(name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}

val trimIcons by tasks.registering(TrimIconsJar::class) {
    source.from(iconsFull)
    // The compiled app, as a task output, so this runs after it is compiled.
    classes.set(tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory })
    // Icons sit one or two folders deep: icons/filled/…, icons/automirrored/filled/….
    pattern.set("androidx/compose/material/icons/(?:[a-z]+/)+[A-Za-z0-9_]+Kt")
    output.set(layout.buildDirectory.file("trimmed/material-icons-extended-used.jar"))
}

fun nativeTrim(name: String, full: Configuration, keepFolder: String, outName: String, nativeRoot: String) =
    tasks.register<Jar>(name) {
        val ops = objects.newInstance<Archives>().ops
        from(full.elements.map { files -> files.map { ops.zipTree(it.asFile) } })
        archiveFileName = outName
        destinationDirectory = layout.buildDirectory.dir("trimmed")
        exclude { it.path.startsWith(nativeRoot) && !it.isDirectory && !it.path.startsWith(keepFolder) }
    }

val trimAlphaSkia = nativeTrim("trimAlphaSkia", alphaSkiaFull, alphaSkiaFolder, "alphaSkia-natives.jar", "native/")
val trimSqlite = nativeTrim("trimSqlite", sqliteFull, sqliteFolder, "sqlite-jdbc.jar", "org/sqlite/native/")

dependencies {
    implementation(compose.desktop.currentOs)
    // Dispatchers.Main, which shared screens use, is the Swing event thread here.
    implementation(libs.kotlinx.coroutines.swing)
    implementation(compose.material3)
    compileOnly(compose.materialIconsExtended)
    iconsFull(compose.materialIconsExtended)
    runtimeOnly(files(trimIcons))
    runtimeOnly("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation(libs.okhttp)
    // Android has org.json built in; the desktop needs the real library.
    implementation(libs.json.jvm)
    // The desktop's SQLite: the same database engine Android has built in.
    sqliteFull(libs.sqlite.jdbc)
    runtimeOnly(files(trimSqlite))
    // API keys in the OS keychain: Secret Service, Credential Manager, Keychain.
    implementation(libs.java.keyring)
    implementation(libs.javamp3)
    // One native call, setenv, for the font setup on Linux.
    implementation(libs.jna)

    alphaTabAar(variantOf(libs.alphatab) { artifactType("aar") })
    implementation(files(unpackAlphaTab.map { it.destinationDir.resolve("classes.jar") }))
    implementation(libs.alphaskia)
    alphaSkiaFull(alphaSkiaNatives)
    runtimeOnly(files(trimAlphaSkia))

    testImplementation(libs.junit)
}

// Renders the app off-screen to PNGs, for checking the UI where no screenshot
// tool is allowed. Usage: ./gradlew :apps:riff_buddy:desktop:screenshots -Pshots="<out> <w>x<h> step..."
tasks.register<JavaExec>("screenshots") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.riffbuddy.desktop.ScreenshotToolKt"
    args = (providers.gradleProperty("shots").orNull ?: "build/shots 1100x820 shot:home").split(" ")
}

// The in-app help as JSON, for the illustrated guide (release/guide/make_guide.py).
// Usage: ./gradlew :apps:riff_buddy:desktop:helpDump -Phelp="<out.json> phone|computer"
tasks.register<JavaExec>("helpDump") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.riffbuddy.desktop.HelpDumpKt"
    args = (providers.gradleProperty("help").orNull ?: "build/help.json phone").split(" ")
}

// Remakes the installers' icons and the launch splash from the master SVGs.
// Run after changing the icon, and commit desktop/icons/ and desktop/launch/.
tasks.register<JavaExec>("icons") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.riffbuddy.desktop.IconToolKt"
    args = listOf(
        file("../icon/variation-a-body.svg").path, file("icons").path,
        file("src/main/resources/icon/foreground.svg").path, file("launch/common").path,
        file("src/main/resources/icon/window").path,
    )
}
