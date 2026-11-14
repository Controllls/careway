plugins {
    alias(libs.plugins.android.application)
}

// This project's AGP/Kotlin combination loses unchanged top-level declarations on incremental
// recompilation (top-level declarations). Compile this small module in full so normal edits build reliably.
afterEvaluate {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        incremental = false
    }
}

android {
    namespace = "com.example.careway"
    compileSdk { version = release(37) }
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "com.example.careway"
        minSdk = 28
        targetSdk = 37
        versionCode = 5
        versionName = "3.1.0"
        testInstrumentationRunner = "com.example.careway.NativeTestRunner"
    }
    buildFeatures { buildConfig = true }
    lint {
        // AGP 9.4.1's WebView detector crashes in Kotlin UAST (Unexpected owner function: null).
        // This module has no WebView. Keep all other lint checks and abortOnError enabled.
        disable += "WebViewApiAvailability"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    testImplementation("junit:junit:4.13.2")
}
