plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

android {
    namespace = "com.lakshaysethi.victronbleexporter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lakshaysethi.victronbleexporter"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.2.26"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        val localProperties = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use {
            localProperties.load(it)
        }
        val logServerBase = (localProperties.getProperty("logServerBaseUrl") ?: "")
            .trim()
            .trimEnd('/')
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "LOG_SERVER_BASE", "\"$logServerBase\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // BLE - using platform BluetoothLeScanner (no extra dep)
    // implementation("androidx.bluetooth:bluetooth:1.0.0-alpha04")

    // For simple HTTP server (NanoHTTPD - tiny and perfect)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // For encrypted prefs (future)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}