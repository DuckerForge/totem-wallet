package com.clearsign.app

import android.app.Application
import android.content.res.Configuration

/**
 * The one thing that runs before anything else in this process.
 *
 * The app's language is per-app, and the code that has no Context asks [AppLocale] for it.
 * That answer was noted in `Themes.load`, which every screen calls, but not every service
 * does: an agent deciding in a worker had never seen it, fell back to the phone's list, and
 * answered in Italian inside an app set to English. Noted here it is true everywhere, from
 * the first line of the process, and again whenever the system changes it under us.
 */
class TotemApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.remember(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.remember(this)
    }
}
