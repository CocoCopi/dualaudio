plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val appVersionName = providers.environmentVariable("DUALAUDIO_VERSION_NAME").orElse("1.0.0").get()
val appVersionCode = providers.environmentVariable("DUALAUDIO_VERSION_CODE").orElse("1").get().toInt()
val releaseKeystore = providers.environmentVariable("DUALAUDIO_KEYSTORE_FILE").orNull

android {
    namespace = "com.cococopi.dualaudio"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.cococopi.dualaudio"
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Shizuku user services are started by class name in a separate app_process.
        // Keep the class name stable in release builds.
        buildConfigField("String", "ROUTING_SERVICE_CLASS", "\"com.cococopi.dualaudio.routing.AudioRoutingUserService\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("DUALAUDIO_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("DUALAUDIO_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("DUALAUDIO_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
