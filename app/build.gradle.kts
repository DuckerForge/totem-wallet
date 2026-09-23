import java.util.Properties
import java.io.File

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
    // A second, separate key for the Seeker crowd scan. Deliberately not the same one:
    // the scan reads thousands of wallets on a schedule, and if it ever burns through its
    // month the agent must keep trading as if nothing happened. Blank → the feature is off.
    val scanRpcUrl = localProps.getProperty("clearsign.scanRpcUrl", "")
    // The other RPC pool keys. Blank = provider absent. Never committed.
    val alchemyRpcUrl = localProps.getProperty("clearsign.alchemyRpcUrl", "")
    val chainstackRpcUrl = localProps.getProperty("clearsign.chainstackRpcUrl", "")
    val rpcfastRpcUrl = localProps.getProperty("clearsign.rpcfastRpcUrl", "")
    val drpcRpcUrl = localProps.getProperty("clearsign.drpcRpcUrl", "")
    // Where the one central scanner publishes what the Seeker crowd is buying.
    // Every phone reads this file; none of them scans. Blank → each phone falls
    // back to scanning for itself, which is fine for one user and absurd for many.
    val crowdUrl = localProps.getProperty("clearsign.crowdUrl", "")
    // With the service, the phone reads the node through it and the key stays on the
    // service, out of the APK. An own key only matters for a build without the service, a one-person build.
    val scanUrl = if (crowdUrl.isNotBlank()) crowdUrl.trimEnd('/') + "/?rpc=1" else scanRpcUrl
    // The shared archive, read without a key. Blank: go through the service.
    val archiveUrl = localProps.getProperty("clearsign.archiveUrl", "")
    // Wallet that receives SKR for premium themes. Blank → purchases disabled in the UI.
    val skrTreasury = localProps.getProperty("clearsign.skrTreasury", "")
    // RocketX partner key (free, from app.rocketx.exchange/partner): the bridge. Blank → the bridge is off.
    val rocketxKey = localProps.getProperty("clearsign.rocketxKey", "")
    // Jupiter referral account (made once at referral.jup.ag with the treasury wallet): the fee on Ultra swaps. Blank → no fee.
    val jupReferral = localProps.getProperty("clearsign.jupReferral", "")

    defaultConfig {
        applicationId = "com.clearsign.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"
        buildConfigField("String", "HELIUS_RPC_URL", "\"$heliusRpcUrl\"")
        buildConfigField("String", "SCAN_RPC_URL", "\"$scanUrl\"")
        buildConfigField("String", "ALCHEMY_RPC_URL", "\"$alchemyRpcUrl\"")
        buildConfigField("String", "CHAINSTACK_RPC_URL", "\"$chainstackRpcUrl\"")
        buildConfigField("String", "RPCFAST_RPC_URL", "\"$rpcfastRpcUrl\"")
        buildConfigField("String", "DRPC_RPC_URL", "\"$drpcRpcUrl\"")
        buildConfigField("String", "CROWD_URL", "\"$crowdUrl\"")
        buildConfigField("String", "ARCHIVE_URL", "\"$archiveUrl\"")
        buildConfigField("String", "SKR_TREASURY", "\"$skrTreasury\"")
        buildConfigField("String", "ROCKETX_KEY", "\"$rocketxKey\"")
        buildConfigField("String", "JUP_REFERRAL", "\"$jupReferral\"")
    }

    // The store wants one key for the life of the app: the first upload decides it, every
    // update must match. It lives outside the repo, named in local.properties, never committed
    // or printed. Without those four lines release falls back to the debug key, which still sideloads.
    val storeFilePath = localProps.getProperty("clearsign.storeFile", "")
    val hasRealKey = storeFilePath.isNotBlank() && File(storeFilePath).exists()
    signingConfigs {
        getByName("debug")
        if (hasRealKey) create("release") {
            storeFile = File(storeFilePath)
            storePassword = localProps.getProperty("clearsign.storePassword", "")
            keyAlias = localProps.getProperty("clearsign.keyAlias", "")
            keyPassword = localProps.getProperty("clearsign.keyPassword", "")
        }
    }

    buildTypes {
        release {
            // R8: ~24 MB of Compose/material dex → a few MB; faster cold start too.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasRealKey) "release" else "debug")
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
    implementation("io.coil-kt:coil-compose:2.7.0")   // token logos
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")   // home-screen Wallet Health widget
    // Ed25519 in software, for the agent envelope: a key we must be able to hand
    // over cannot live in the Seed Vault, which never exports anything.
    implementation("net.i2p.crypto:eddsa:0.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real JSON in JVM tests (android.jar ships stubs)
    implementation("androidx.compose.ui:ui-tooling-preview")
}

// Reports of what the Compose compiler could and could not skip: read them
// in app/build/compose_reports after assembleRelease. HaloRow, HoldingRow,
// CoinRow and PriceChart must come out "restartable skippable".
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
}
