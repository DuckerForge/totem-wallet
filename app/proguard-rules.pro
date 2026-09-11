# Solana Mobile SDKs: keep public API surface used via Intents/content providers + JSON-RPC.
-keep class com.solanamobile.seedvault.** { *; }
-keep class com.solana.mobilewalletadapter.** { *; }
-dontwarn com.solana.mobilewalletadapter.**
# Kotlin coroutines / reflection-free; keep BuildConfig for the RPC url.
-keep class com.clearsign.app.BuildConfig { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
