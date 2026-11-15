import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Private signing configuration lives outside the repository.
val releaseSigningFile = file(providers.gradleProperty("stopcarSigningFile")
    .getOrElse("${gradle.gradleUserHomeDir}/stopcar-release.properties"))
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
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
    // Device tests must carry the same signature as the installed app:
    // -PcarewayTestBuildType=release runs them against the release-signed build.
    testBuildType = providers.gradleProperty("carewayTestBuildType").getOrElse("debug")
    defaultConfig {
        applicationId = "com.example.careway"
        minSdk = 28
        targetSdk = 37
        versionCode = 12
        versionName = "3.6.0"
        testInstrumentationRunner = "com.example.careway.NativeTestRunner"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (releaseSigningFile.isFile) create("release") {
            storeFile = file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        // Demo tools: the scenario page, the simulated vehicle and the mode buttons.
        // The release app ships without them; -PcarewayDemoTools=true keeps them for device tests.
        val releaseDemoTools = providers.gradleProperty("carewayDemoTools").getOrElse("false").toBoolean()
        getByName("debug") { buildConfigField("boolean", "DEMO_TOOLS", "true") }
        getByName("release") {
            buildConfigField("boolean", "DEMO_TOOLS", releaseDemoTools.toString())
            isDebuggable = false
            isMinifyEnabled = false
            if (releaseSigningFile.isFile) signingConfig = signingConfigs.getByName("release")
        }
    }
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
