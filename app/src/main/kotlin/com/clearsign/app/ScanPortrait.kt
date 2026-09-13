package com.clearsign.app

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The scanner, upright.
 *
 * zxing's own capture activity follows the sensor, which on this phone means it
 * opens sideways — you end up tilting your head to read a QR. This subclass
 * exists only so the manifest can pin it to portrait, like every other screen
 * in the app.
 */
class ScanPortraitActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // The layout is ours (res/layout/zxing_capture_activity.xml) purely so there
        // is a visible way out; the scanning itself is still the library's.
        findViewById<android.widget.ImageView>(R.id.apex_scan_close)?.setOnClickListener { finish() }
    }
}
