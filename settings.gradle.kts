pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 2026-08-29: the JitPack repository was removed together with the
        // com.github.tiny-computer:terminal-lib dependency it existed for
        // (GPL-3.0-only, replaced by the vendored Apache-2.0
        // :terminal-emulator / :terminal-view modules — see those modules'
        // PROVENANCE.md files). Every remaining dependency resolves from
        // google() / mavenCentral().
    }
}

rootProject.name = "Linexa"
include(":app")
// 2026-08-29 terminal migration: vendored Apache-2.0 Termux terminal modules
// (termux/termux-app @ v0.118.3, commit 5b657c6adf4304e5198951ce815fe0205dcac29c).
include(":terminal-emulator")
include(":terminal-view")
