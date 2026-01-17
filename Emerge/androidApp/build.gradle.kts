plugins {
    id("com.android.application")
    kotlin("android")
}

// Workaround for Windows file locking on AGP intermediates (R.jar).
// Moving build output away from `androidApp/build` avoids clashes with tools that may scan/hold that folder.
buildDir = file("$rootDir/.build/androidApp")

android {
    namespace = "org.emerge.androidapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.emerge.androidapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
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
}

