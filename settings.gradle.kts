pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // Default PREFER_PROJECT lets :core keep its own repositories{} block while
    // :app resolves Android/Compose/Solana artifacts from these.
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ClearSign"

// Pure-Kotlin, device-independent clear-signing engine (unit-tested).
include(":core")

// The Android/Seeker app (Compose + Seed Vault + Mobile Wallet Adapter) that
// supplies the real implementations of :core's ports and runs on the Seeker.
include(":app")

// A test dApp that crafts known transactions (transfers, hidden fees, unlimited
// approvals, …) and sends them to ClearSign over MWA, to verify the receipts.
include(":testdapp")
