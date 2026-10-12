plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// CI sets GITHUB_RUN_NUMBER, so each build gets a higher versionCode and installs over the previous one.
val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.enweilo.fujirecipes"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.enweilo.fujirecipes"
        minSdk = 26
        targetSdk = 37
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A fixed key committed to the repo: every build is signed the same way, so updates install over the
    // old version and keep the recipes. Fine for a personal tool; do not reuse it for anything else.
    signingConfigs {
        create("personal") {
            storeFile = file("fuji-recipes.jks")
            storePassword = "fujirecipes"
            keyAlias = "fuji"
            keyPassword = "fujirecipes"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            signingConfig = signingConfigs.getByName("personal")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
