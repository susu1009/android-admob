plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.banv.admob"
    compileSdk = 35

    defaultConfig {
        // Google Mobile Ads 24+ requires API 23.
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    resourcePrefix = "admob_"

    testOptions { unitTests.isReturnDefaultValues = true }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin { explicitApi() }

dependencies {
    // api: apps bind native ads / read AdValue with GMA types, and need the SDK on their classpath.
    api(libs.play.services.ads)
    api(libs.ump)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // ComponentDialog: loading dialogs host Compose/ViewTree owners without extra setup.
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "admob"
            afterEvaluate { from(components["release"]) }
        }
    }
}
