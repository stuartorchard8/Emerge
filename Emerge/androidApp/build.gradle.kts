plugins {
    id("com.android.application")
    kotlin("android")
}

// Workaround for Windows file locking on AGP intermediates (R.jar).
// Some Windows setups (AV/indexers) can hold `R.jar` open between builds; to avoid "Couldn't delete ... R.jar",
// use a fresh build directory per invocation so AGP doesn't need to delete previous outputs.
buildDir = file("$rootDir/.build/androidApp-${System.currentTimeMillis()}")

android {
    namespace = "org.emerge.androidapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.emerge.androidapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":sim-core"))
    implementation(project(":sim-sync"))
    implementation(project(":net-loopback"))
    implementation(project(":net-tcp"))
}

