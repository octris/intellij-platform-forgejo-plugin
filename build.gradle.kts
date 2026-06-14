import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.1.3")

        // Provides the VCS log table API (VcsLogCustomColumn, GraphTableModel, VcsLogGraphTable)
        // used by the Forgejo Actions column. Not on the compile classpath by default.
        bundledModule("intellij.platform.vcs.log.impl")

        // Git remote -> owner/repo resolution for the Forgejo Actions column.
        bundledPlugin("Git4Idea")

        // GitHub-style CI status icons (CIBuildStatusIcons), shared by the collaboration plugins.
        bundledModule("intellij.platform.collaborationTools")

        testFramework(TestFrameworkType.Platform)
    }
}
