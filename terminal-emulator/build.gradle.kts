// The vendored Apache-2.0 Termux terminal emulator module
// (termux/termux-app @ v0.118.3, commit 5b657c6adf4304e5198951ce815fe0205dcac29c
// — see PROVENANCE.md). Plain Java Android library: no Kotlin, no Compose.
// Depends only on androidx.annotation; ships libtermux.so (the JNI layer)
// under src/main/jniLibs and the upstream JVM emulator test suite under
// src/test, which runs with this project's `:app` checks via checkDebugUnitTest
// style wiring — run with `./gradlew :terminal-emulator:testDebugUnitTest`.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 34

    defaultConfig {
        // The app module requires API 28 (its own floor since the real-PTY
        // phase); upstream's 24 floor is raised here so the modules match the
        // repository they are vendored into (see app/build.gradle.kts minSdk
        // comment).
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Upstream's JVM tests exercise android.graphics stubs (for example
            // the cursor-brightness fallback in TerminalColorScheme) and expect
            // them to return defaults rather than throw "not mocked".
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
