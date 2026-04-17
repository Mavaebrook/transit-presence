plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.handleit.transitpresence"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.handleit.transitpresence"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // GTFS-RT feed URL — override per flavor or BuildConfig field
        buildConfigField("String", "GTFS_RT_VEHICLE_POSITIONS_URL",
            "\"http://gtfsrt.golynx.com/gtfsrt/GTFS_VehiclePositions.pb"")
        buildConfigField("String", "GTFS_RT_TRIP_UPDATES_URL",
            "\"http://gtfsrt.golynx.com/gtfsrt/GTFS_TripUpdates.pb"")
        buildConfigField("String", "GTFS_STATIC_BASE_URL",
            "\"http://gtfsrt.golynx.com/gtfsrt/"")

        // Fusion thresholds (overridable per flavor)
        buildConfigField("float", "ON_BUS_CONFIDENCE_THRESHOLD", "0.85f")
        buildConfigField("float", "STOP_GEOFENCE_RADIUS_METERS", "50.0f")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "MOCK_MODE_DEFAULT", "false")
            applicationIdSuffix = ".debug"
        }
        release {
            buildConfigField("boolean", "MOCK_MODE_DEFAULT", "false")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    productFlavors {
        // Central Florida agencies — extend later
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.13" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // Network
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    // Protobuf for GTFS-RT
    implementation(libs.protobuf.kotlin.lite)

    // Maps
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Serialization & Prefs
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)

    // Logging
    implementation(libs.timber)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}

kapt { correctErrorTypes = true }
