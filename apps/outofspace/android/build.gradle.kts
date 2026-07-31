// :apps:outofspace:android — the phone host. An Activity + a GLSurfaceView around :apps:outofspace:core.
//
// Build a debug APK with:   ./gradlew :apps:outofspace:android:assembleDebug
// Install it with:          adb install -r .build/outofspace-android/outputs/apk/debug/*.apk
plugins {
    id("com.android.application")
    kotlin("android")
}

buildDir = file("$rootDir/.build/outofspace-android")

android {
    namespace = "org.emerge.outofspace"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.emerge.outofspace"
        minSdk = 26
        targetSdk = 36
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

    sourceSets {
        getByName("main") {
            // Shared asset folder at the repo root (fonts, textures) — the UI text renderer needs it.
            assets.srcDir("$rootDir/assets")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(project(":apps:outofspace:core"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
}
