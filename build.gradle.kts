import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins").map { it.split(',') },
        )
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // untilBuild intentionally omitted -> open-ended forward compatibility
        }

        // Feed the CHANGELOG into the plugin's <change-notes>, which becomes the
        // "What's new" section on the JetBrains Marketplace. Renders the section
        // matching the current pluginVersion (falling back to [Unreleased] before
        // the changelog is finalized) as HTML, dropping the version header and any
        // empty sections. NOTE: this is baked into plugin.xml at build time, so a
        // Marketplace upload always reflects the CHANGELOG as of that build.
        //
        // The changelog extension is bound to a local val so the map {} lambda
        // closes over it rather than over `project` — capturing the project would
        // break Gradle's configuration cache (which is enabled in gradle.properties).
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
    // signing { ... } and publishing { ... } configured later for Marketplace publishing.
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(17)
        vendor = JvmVendorSpec.JETBRAINS
    }
}
