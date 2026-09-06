plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.boyoffi9.matrixrainview"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        targetSdk = 34
    }

    lint {
        targetSdk = 34
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Matches Kotlin 1.9.24 per the Compose Compiler <-> Kotlin
        // compatibility map. Bump this if the root Kotlin version changes.
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // Compose interop. Note: this pulls Compose UI into the library for
    // ALL consumers, even ones using the plain XML View. If that's ever
    // a concern, split this into a separate `matrixrainview-compose`
    // module that depends on this one.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                
                groupId = "com.github.boy-offi9-inc"
                artifactId = "matrix-rain-view"
                version = "1.0.1"
            }
        }
    }
}
