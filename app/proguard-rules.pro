# Solana Mobile SDKs: keep public API surface used via Intents/content providers + JSON-RPC.
-keep class com.solanamobile.seedvault.** { *; }
-keep class com.solana.mobilewalletadapter.** { *; }
-dontwarn com.solana.mobilewalletadapter.**
# Kotlin coroutines / reflection-free; keep BuildConfig for the RPC url.
-keep class com.clearsign.app.BuildConfig { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Il firmatario della paghetta. Le classi qui sono referenziate direttamente nel
# codice, quindi R8 le terrebbe comunque: la regola e' cintura e bretelle, e c'e'
# perche' questa libreria firma spese vere e un fallimento in release si vedrebbe
# solo quando qualcuno prova a spendere. Non costa niente tenerla.
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# Il lettore QR. Qui il rischio e' reale e non teorico: `zxing_capture_activity.xml`
# nomina `DecoratedBarcodeView` come stringa, e una vista dentro un layout la
# costruisce il sistema per nome. Rinominata da R8, l'inquadratura di un QR
# esplode in release e compila benissimo in debug.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.journeyapps.barcodescanner.**
