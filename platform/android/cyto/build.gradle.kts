plugins {
    id("com.android.application")
    kotlin("android")
}

buildDir = file("$rootDir/.build/android-cyto")

android {
    namespace = "org.emerge.cyto"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.emerge.cyto"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
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
            assets.srcDir("$rootDir/assets")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(project(":apps:cyto"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
    implementation(project(":engine:sim:sync"))
}
