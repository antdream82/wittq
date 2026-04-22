import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = Properties().apply {
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        keystoreFile.inputStream().use { load(it) }
    }
}

val versionCodeValue = providers.gradleProperty("VERSION_CODE").orNull?.toIntOrNull()
    ?: providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull()
    ?: 1

fun Properties.text(key: String): String? = getProperty(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.fortq.wittq"
    compileSdk {
        version = release(36)
    }

    val hasReleaseSigning =
        keystoreProperties.text("storeFile") != null &&
            keystoreProperties.text("storePassword") != null &&
            keystoreProperties.text("keyAlias") != null &&
            keystoreProperties.text("keyPassword") != null

    defaultConfig {
        applicationId = "com.fortq.wittq"
        minSdk = 28
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.text("storeFile")!!)
                storePassword = keystoreProperties.text("storePassword")!!
                keyAlias = keystoreProperties.text("keyAlias")!!
                keyPassword = keystoreProperties.text("keyPassword")!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    buildFeatures {
        compose = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // Retrofit & Gson
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    // Coroutines (비동기 처리를 위함)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.glance:glance-appwidget:1.1.0")
}
