package com.clearsign.app

import android.app.Application
import android.content.res.Configuration

/**
 * Runs first in the process. The app language is per-app, and Context-less code reads it from
 * [AppLocale]. Setting it only in `Themes.load` missed services: a worker agent answered in
 * Italian under an English app. Set here, it holds from process start and on every config change.
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
