import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing credentials live outside the repo, so a fresh clone still builds —
// the release APK just goes unsigned rather than failing.
//
// Riff Buddy's shared releases have a key of their own, named only "Riff
// Buddy": a certificate is readable by anyone holding the APK, and the
// workspace key carries a personal name. A `riffbuddyKeystore` Gradle property
// (in ~/.gradle/gradle.properties, never in a project) points at its
// properties file; without one, the usual keystore.properties is used.
val keystorePropsFile = providers.gradleProperty("riffbuddyKeystore").orNull
    ?.let { file(it) }
    ?: rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.riffbuddy"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.riffbuddy"
        // Matches Record Buddy: API 26 covers the S22 Ultra and the Fire HD 10
        // (Fire OS 7 = API 28), which are the two test devices (the spec).
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        // 1.0.0 with the desktop edition. Also the desktop installers' version,
        // and macOS refuses one that starts with 0.
        versionName = "1.0.4"

        // alphaTab brings Skia as native code, about 7 MB per ABI. Only ARM is
        // kept: the S22 is arm64 and the Fire HD 10 is armeabi-v7a *only* — a
        // 32-bit device, so dropping v7a would make the app uninstallable there.
        // x86 exists for emulators, which this project does not use.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Code shared with the desktop edition lives in shared/ and is
    // compiled into this app as if it were in src/. The desktop module
    // compiles the same folder, which is what keeps Android imports out of it.
    sourceSets {
        getByName("main") { kotlin.srcDir("shared/src/main/kotlin") }
        getByName("test") {
            kotlin.srcDir("shared/src/test/kotlin")
            resources.srcDir("shared/src/test/resources")
        }
    }

    buildFeatures {
        compose = true
    }

    // NewApi is fatal: calling something newer than minSdk is a crash on the
    // Fire HD 10 (API 28) that the S22 (API 36) never shows. It happened
    // twice — SQL UPSERT and LocalDate.ofInstant — so it
    // now fails every release build, where lint runs automatically. The
    // debug-time guard is `./gradlew :apps:riff_buddy:lintDebug`.
    lint {
        fatal += "NewApi"
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // The AI provider layer is ported from an earlier app, which is
    // built on OkHttp. Present from M0 so the port has nothing to add later.
    implementation(libs.okhttp)

    // The tab library and player. A design note.
    implementation(libs.alphatab)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    // Android stubs org.json in unit tests; the real implementation on the test
    // classpath is what lets tone-card JSON validation be tested at all.
    testImplementation(libs.json.jvm)
    // The upgrade fixture runs the migrations for real (MigrationTest).
    testImplementation(libs.sqlite.jdbc)
}
