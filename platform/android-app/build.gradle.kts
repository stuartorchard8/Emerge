plugins {
    id("com.android.application")
    kotlin("android")
}

// Build dir strategy (Windows-friendly):
// - Default: stable build dir for good caching and predictable disk usage.
// - If you hit Windows file locks (AV/indexers holding AGP intermediates like R.jar), set:
//   `-Pemerge.uniqueBuildDir=true`
//   (and consider excluding `.build/` from real-time scanning).
val uniqueBuildDir: Boolean = (findProperty("emerge.uniqueBuildDir") as String?)?.toBoolean() ?: false
val buildDirName = if (uniqueBuildDir) "android-app-${System.currentTimeMillis()}" else "android-app"
buildDir = file("$rootDir/.build/$buildDirName")

android {
    namespace = "org.emerge.androidapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.emerge.androidapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
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
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(project(":demos:physics"))
    implementation(project(":engine:sim:core"))
    implementation(project(":engine:sim:sync"))
    implementation(project(":engine:sim:codecs:physics"))
    implementation(project(":engine:net:transports:loopback"))
    implementation(project(":engine:net:transports:tcp"))
}

