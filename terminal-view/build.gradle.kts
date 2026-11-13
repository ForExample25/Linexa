// The vendored Apache-2.0 Termux terminal view module
// (termux/termux-app @ v0.118.3, commit 5b657c6adf4304e5198951ce815fe0205dcac29c
// — see PROVENANCE.md). Plain Java Android library rendering the emulator;
// depends on :terminal-emulator and androidx.annotation only.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 34

    defaultConfig {
        // Matches :terminal-emulator and the app's own floor (see that file).
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.1")
    api(project(":terminal-emulator"))
}
