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
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}

afterEvaluate {
    publishing {
        publications {
            release(MavenPublication) {
                from(components.release)
                
                groupId = "com.github.boy-offi9-inc"
                artifactId = "matrix-rain-view"
                version = "1.0.0"
            }
        }
    }
}
