// Convention plugin for Kotlin Multiplatform modules.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("multiplatform")
}

kotlin {
    // Intentionally do not hard-pin a toolchain version.
    // This keeps the project easy to build on fresh machines without extra JDK installs.
}

tasks.withType<Test>().configureEach {
    // Configure all JVM test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}

