// Convention plugin for Kotlin Multiplatform modules.
package buildsrc.convention

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("multiplatform")
}

kotlin {
    // Intentionally do not hard-pin a toolchain version.
    // This keeps the project easy to build on fresh machines without extra JDK installs.

    // Silence the KT-61573 Beta notice for expect/actual classes — we use them deliberately
    // (AtomicRef/ReentrantLock/ParallelExecutor/GPU) and the pattern is stable for our purposes.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
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

