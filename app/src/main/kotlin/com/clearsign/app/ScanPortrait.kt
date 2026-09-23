package com.clearsign.app

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The scanner, upright. zxing's capture activity follows the sensor and opens sideways on
 * this phone; this subclass exists so the manifest can pin it to portrait like every other screen.
 */
class ScanPortraitActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // The layout is ours (res/layout/zxing_capture_activity.xml) purely so there
        // is a visible way out; the scanning itself is still the library's.
        findViewById<android.widget.ImageView>(R.id.apex_scan_close)?.setOnClickListener { finish() }
    }
}
