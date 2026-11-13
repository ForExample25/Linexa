import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fem.linexa"
    compileSdk = 34

    val signingProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val storeFilePath = signingProps.getProperty("signing.storeFile", "")

    gradle.taskGraph.whenReady {
        val wantsReleaseArtifact = allTasks.any {
            (it.name.startsWith("assemble") || it.name.startsWith("bundle")) &&
                it.name.contains("Release")
        }
        if (wantsReleaseArtifact && storeFilePath.isEmpty()) {
            throw GradleException(
                "Release build has no signing credentials. Set signing.storeFile, " +
                    "signing.storePassword, signing.keyAlias and signing.keyPassword " +
                    "in local.properties (gitignored). The persistent keystore is " +
                    "keystore/linexa-release.jks — reuse it, do not regenerate."
            )
        }
        if (wantsReleaseArtifact && storeFilePath.isNotEmpty() &&
            !rootProject.file(storeFilePath).exists()
        ) {
            throw GradleException(
                "Signing store file '$storeFilePath' (from local.properties) does not " +
                    "exist. Restore keystore/linexa-release.jks from backup — do not " +
                    "generate a new keystore for this project."
            )
        }
    }

    if (storeFilePath.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingProps.getProperty("signing.storePassword", "")
                keyAlias = signingProps.getProperty("signing.keyAlias", "")
                keyPassword = signingProps.getProperty("signing.keyPassword", "")
            }
        }
    }

    defaultConfig {
        applicationId = "com.fem.linexa"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-beta"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        // §1.147 audit hook: RealArchiveAnalysisTest is opt-in because it
        // scans the device's multi-gigabyte archive folder. Forward the three
        // switches to the test worker so `-Dlinexa.realArchives=true` on the
        // Gradle command line actually reaches the test.
        unitTests.all { test ->
            listOf(
                "linexa.realArchives",
                "linexa.realArchives.filter",
                "linexa.realArchives.dir"
            ).forEach { key ->
                System.getProperty(key)?.let { value -> test.systemProperty(key, value) }
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-android-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("org.yaml:snakeyaml:2.2")

    implementation("com.github.luben:zstd-jni:1.5.6-5@aar")

    testImplementation("com.github.luben:zstd-jni:1.5.6-5")

    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.tukaani:xz:1.9")

    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.mockito:mockito-core:5.14.2")

    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test.espresso:espresso-core:3.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")

    testImplementation("com.tngtech.archunit:archunit-junit4:1.3.0")
}

// ── Environment-only test exclusions ────────────────────────────────────────
//
// Four Robolectric classes cannot run on an aarch64 Linux host: the pinned
// Robolectric/Conscrypt stack has no `conscrypt_openjdk_jni-linux-aarch_64`
// native, and every one of their failures is an UnsatisfiedLinkError at
// class-load. They run on x86_64. The default task therefore excludes them
// ONLY when `os.arch` is aarch64; a dedicated task can still run them by name.
val environmentOnlyClasses = listOf(
    "com.fem.linexa.data.room.RoomSchemaRuntimeHashTest",
    "com.fem.linexa.ui.screens.device.HostInfoScreensCrashReproTest",
    "com.fem.linexa.ui.screens.workspace.FeaturesTabCrashReproTest",
    "com.fem.linexa.ui.screens.workspace.WorkspaceSegmentsCrashReproTest"
)

afterEvaluate {
    val conscryptNativeUnavailable = System.getProperty("os.arch") == "aarch64"

    tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest") {
        if (conscryptNativeUnavailable) {
            filter {
                environmentOnlyClasses.forEach { excludeTestsMatching(it) }
            }
        }
    }

    tasks.register<org.gradle.api.tasks.testing.Test>("testDebugUnitTestEnvironmentOnly") {
        group = "verification"
        description =
            "Runs the four Conscrypt-native tests that the default task excludes on aarch64 " +
            "Linux hosts. On x86_64 CI these are ordinary default-task tests; on this host " +
            "they are expected red here and documented as environment-only."
        val standardTask = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest").get()
        classpath = standardTask.classpath
        testClassesDirs = standardTask.testClassesDirs
        reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/environmentOnly"))
        reports.html.outputLocation.set(layout.buildDirectory.dir("reports/environmentOnly"))
        binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/environmentOnly/binary"))
        filter {
            environmentOnlyClasses.forEach { includeTestsMatching(it) }
        }
    }

    // Report check — fails when the suite produced no tests, or when any suite
    // failed. Run it after `testDebugUnitTest` (with `--continue`) when the
    // default test task is red, to get a compact failure census.
    tasks.register("verifyUnitTestReport") {
        group = "verification"
        description =
            "Fails when the JVM unit-test XML report has zero tests or any failing suite."
        doLast {
            val resultsDir = layout.buildDirectory.dir("test-results/testDebugUnitTest").get()
            val xmlFiles = fileTree(resultsDir) { include("TEST-*.xml") }.files
            if (xmlFiles.isEmpty()) {
                throw GradleException("verifyUnitTestReport: no TEST-*.xml under $resultsDir — the suite produced nothing.")
            }
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            var totalTests = 0L
            val failingSuites = mutableSetOf<String>()
            for (xml in xmlFiles) {
                val root = factory.newDocumentBuilder().parse(xml).documentElement
                totalTests += root.getAttribute("tests").toLong()
                if (root.getAttribute("failures").toLong() > 0 || root.getAttribute("errors").toLong() > 0) {
                    failingSuites.add(root.getAttribute("name").substringAfterLast('.'))
                }
            }
            if (failingSuites.isNotEmpty()) {
                throw GradleException(
                    "verifyUnitTestReport: failing suites $failingSuites — fix them; the release gate accepts no known failures."
                )
            }
            println(
                "verifyUnitTestReport: $totalTests tests across ${xmlFiles.size} classes; no failing suites."
            )
        }
    }
}
