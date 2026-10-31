import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)                    // Room's annotation processor
}

android {
    namespace = "com.chronos"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.chronos"
        minSdk = 26                            // java.time without desugaring
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two editions (31 Oct 2026). "personal" is the app on your phone, as it was: same package, same data.
    // "public" is the one to give to others: its own package, so it installs beside yours with its own
    // empty data, and no goal graph — each person makes theirs from their own goals.
    flavorDimensions += "edition"
    productFlavors {
        create("personal") {
            dimension = "edition"
            applicationId = "com.chronos"
            buildConfigField("boolean", "PUBLIC_EDITION", "false")
        }
        create("public") {
            dimension = "edition"
            applicationId = "com.chronos.planner"
            versionNameSuffix = "-public"
            buildConfigField("boolean", "PUBLIC_EDITION", "true")
        }
    }

    // The release key lives outside the repository: keystore.properties (never committed) names it.
    val signing = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (signing != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        // The engine uses records and switch expressions.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // The Anthropic SDK's HTTP libraries each carry the same licence notices.
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                                    "META-INF/INDEX.LIST")
    }
}

// Room writes the schema JSON here so future migrations can be diffed.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":navigator"))   // goal graph, estimate, forecast, allocate: plain JVM
    implementation(libs.androidx.room.runtime)
    implementation(libs.anthropic.java)          // Navigator LLM layer: called only when a trigger fires
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)      // the optional app lock: the phone's own fingerprint or screen lock  // weekly calibration, calendar sync

    testImplementation(libs.junit)              // the engine harness runs here, no emulator
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
