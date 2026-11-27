import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * F-044 / R-04: a monotonic, reproducible `versionCode`.
 *
 * The published 0.1.0-beta artifact used the literal `1`. 0.2.0 therefore
 * starts at 1002: strictly greater than every published build, and clear of
 * the historical low numbers. The value is pinned here (and can be overridden
 * for a CI hotfix with `LINEXA_VERSION_CODE` or `-Plinexa.versionCode=…`) so a
 * release artifact and the `ReleaseNotesCatalog` entry it ships with always
 * agree, regardless of how many commits the checkout contains.
 *
 * Bump this once per release, together with `versionName` and the catalog.
 */
val linexaVersionCode: Int = providers.environmentVariable("LINEXA_VERSION_CODE")
    .orNull
    ?.toIntOrNull()
    ?: providers.gradleProperty("linexa.versionCode").orNull?.toIntOrNull()
    ?: 1002

/**
 * Whether this artifact is a beta. 0.2.0's version name is exactly `0.2.0`, so the
 * update channel cannot infer beta-ness from a `-beta` suffix. This flag drives the
 * default pre-release channel and the in-app badge; flip it to `false` for a stable
 * release (`-Plinexa.isBeta=false`).
 */
val linexaIsBeta: Boolean = providers.gradleProperty("linexa.isBeta")
    .orNull
    ?.toBoolean()
    ?: true

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
        versionCode = linexaVersionCode
        versionName = "0.2.0"
        buildConfigField("boolean", "IS_BETA", linexaIsBeta.toString())
        vectorDrawables.useSupportLibrary = true

        // 10B (OPT-TEST-002): instrumentation smoke suite runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    // Post-remediation recovery acceptance (2026-11-26): a *test-only* release
    // artifact with an isolated applicationId, so the verified workspace backup
    // can be restored into its own data directory with `adb shell run-as` and
    // exercised on hardware WITHOUT touching the production package
    // `com.fem.linexa` or its data. Off by default: without
    // `-Plinexa.recoveryTest=true` the release artifact is exactly the
    // production configuration (no suffix, not debuggable).
    //
    // `isDebuggable` stays false deliberately. AGP tells R8 not to obfuscate or
    // optimize a debuggable variant, which would make the artifact stop
    // representing the production shrinker output (measured: 21.5 MB vs 13.5 MB,
    // 324k vs 6.9k mapped classes). Instead the `src/recoveryTest` manifest
    // overlay sets `android:debuggable="true"` in the merged manifest only, so
    // `run-as` works while R8 still obfuscates and optimizes exactly as it does
    // for production.
    val recoveryTestArtifact =
        providers.gradleProperty("linexa.recoveryTest").orNull?.toBoolean() == true

    buildTypes {
        release {
            // LINEXA-PERF-012: the audited release variant shipped unshrunk and
            // unoptimized (73 MB code / ~67 MB APK mmap in a debuggable
            // artifact). R8 shrinking + resource shrinking is the production
            // configuration; every JNI/reflection/serialization surface this
            // app actually uses is pinned in proguard-rules.pro, and the
            // shrinker's own consumer rules cover Hilt, Compose, Room and
            // kotlinx.serialization.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
            if (recoveryTestArtifact) {
                applicationIdSuffix = ".recoverytest"
                versionNameSuffix = "-recoverytest"
                // R8 must stay in production mode: see the comment above.
                isDebuggable = false
            }
        }
    }

    if (recoveryTestArtifact) {
        // Test-only manifest overlay (android:debuggable for `run-as`); never
        // part of a normal release build.
        sourceSets.getByName("release").manifest.srcFile("src/recoveryTest/AndroidManifest.xml")
    }

    // F-022: the default artifact stays the universal APK that every existing
    // doc and release step names (`app-release.apk`), and the AAB already gives
    // Play per-ABI delivery. For GitHub distribution a maintainer can opt into
    // per-ABI APKs with `-Plinexa.abiSplits=true`; the universal fallback is
    // still produced alongside them.
    splits {
        abi {
            isEnable = providers.gradleProperty("linexa.abiSplits")
                .orNull
                ?.toBoolean() == true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // F-013: `lintDebug` is a CI gate and `lintRelease` runs before signing.
    lint {
        // AGP 8.5's lint bundles a kotlinx-metadata reader that stops at Kotlin
        // metadata 2.0.0, while the project compiles with Kotlin 2.1.20. The
        // Compose detectors below read that metadata and crash the analysis of
        // every file that touches a StateFlow, a coroutine or a flow operator
        // (`[LintError]`), which would fail the gate for a tooling mismatch
        // rather than a defect. Disable those detectors until the AGP/Kotlin
        // upgrade (F-032/R-09) brings a matching lint.
        disable += setOf(
            "StateFlowValueCalledInComposition",
            "CoroutineCreationDuringComposition",
            "FlowOperatorInvokedInComposition"
        )
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
    // F-005 / R-02: only `Close` and `Share` come from the generated icon set;
    // the five others Linexa renders are local vectors (ui/icons/LinexaIcons).
    // The `-extended` artifact (≈11,400 generated classes for seven icons) is
    // no longer a dependency.
    implementation("androidx.compose.material:material-icons-core")
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

    // 10B (OPT-TEST-002): instrumentation smoke suite — mirrors the existing
    // testImplementation versions exactly (ext:junit 1.2.1, espresso 3.6.1,
    // the 2024.06.00 BOM); androidx.test:runner arrives transitively via
    // espresso-core. Nothing bumped, nothing production.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
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
