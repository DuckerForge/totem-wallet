import java.util.Properties

plugins {
    id("com.android.application") version "8.10.0"
    kotlin("android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

android {
    namespace = "com.clearsign.app"
    compileSdk = 36

    // Secrets stay in local.properties (never committed). Blank → public RPC nodes.
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) }
    }
    val heliusRpcUrl = localProps.getProperty("clearsign.heliusRpcUrl", "")
    // Wallet that receives SKR for premium themes. Blank → purchases disabled in the UI.
    val skrTreasury = localProps.getProperty("clearsign.skrTreasury", "")

    defaultConfig {
        applicationId = "com.clearsign.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "HELIUS_RPC_URL", "\"$heliusRpcUrl\"")
        buildConfigField("String", "SKR_TREASURY", "\"$skrTreasury\"")
    }

    // Debug-key signing for release too, so a shrunken (R8) APK can be sideloaded on
    // the Seeker without a keystore ceremony. Replace with a real key for the store.
    signingConfigs { getByName("debug") }

    buildTypes {
        release {
            // R8: ~24 MB of Compose/material dex → a few MB; faster cold start too.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

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

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

dependencies {
    // The pure clear-signing engine. The app supplies its ports.
    implementation(project(":core"))

    // Solana Mobile: Seed Vault (TEE signer) — real on-device signing.
    implementation("com.solanamobile:seedvault-wallet-sdk:0.4.0")
    // Mobile Wallet Adapter: the wallet endpoint dApps connect to.
    // 2.1.1 (not 2.2.0, which needs compileSdk 37, unavailable here).
    implementation("com.solanamobile:mobile-wallet-adapter-walletlib:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // QR: scan a recipient (camera) and render your own address.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core-splashscreen:1.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real JSON in JVM tests (android.jar ships stubs)
    implementation("androidx.compose.ui:ui-tooling-preview")
}
