// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"

    // Declare plugin versions once (applied in modules).
    id("com.android.application") version "8.12.0" apply false
    id("com.android.library") version "8.12.0" apply false
    kotlin("android") version "2.1.10" apply false
    kotlin("multiplatform") version "2.1.10" apply false
}

// Include subprojects in the build.
// If there are changes in only one of the projects, Gradle will rebuild only the one that has changed.
// Learn more about structuring projects with Gradle - https://docs.gradle.org/8.7/userguide/multi_project_builds.html
include(":platform:desktop-app")
include(":platform:android-app")
include(":platform:web-app")

include(":engine:sim:core")
include(":engine:sim:sync")
include(":engine:sim:codecs:ecs")

include(":engine:render:torus")

include(":engine:net:api")
include(":engine:net:transports:loopback")
include(":engine:net:transports:tcp")
include(":engine:net:transports:websocket")

include(":demos:scavengers")
include(":demos:drockets")

rootProject.name = "Emerge"
