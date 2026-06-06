plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val glassdisplayReleaseStoreFile = providers.gradleProperty("glassdisplayReleaseStoreFile").orNull
val glassdisplayReleaseStoreType = providers.gradleProperty("glassdisplayReleaseStoreType").orNull
val glassdisplayReleaseStorePassword = providers.gradleProperty("glassdisplayReleaseStorePassword").orNull
val glassdisplayReleaseKeyAlias = providers.gradleProperty("glassdisplayReleaseKeyAlias").orNull
val glassdisplayReleaseKeyPassword = providers.gradleProperty("glassdisplayReleaseKeyPassword").orNull
val hasGlassdisplayReleaseSigning = listOf(
    glassdisplayReleaseStoreFile,
    glassdisplayReleaseStorePassword,
    glassdisplayReleaseKeyAlias,
    glassdisplayReleaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "bio.aq.glassdisplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "bio.aq.glassdisplay"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasGlassdisplayReleaseSigning) {
            create("release") {
                storeFile = file(glassdisplayReleaseStoreFile!!)
                if (!glassdisplayReleaseStoreType.isNullOrBlank()) {
                    storeType = glassdisplayReleaseStoreType
                }
                storePassword = glassdisplayReleaseStorePassword
                keyAlias = glassdisplayReleaseKeyAlias
                keyPassword = glassdisplayReleaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasGlassdisplayReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
