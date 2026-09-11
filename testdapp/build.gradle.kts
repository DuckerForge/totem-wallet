plugins {
    id("com.android.application") version "8.10.0"
    kotlin("android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

android {
    namespace = "com.clearsign.tester"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clearsign.tester"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

dependencies {
    // MWA dApp side (initiates the association with a wallet like ClearSign).
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
