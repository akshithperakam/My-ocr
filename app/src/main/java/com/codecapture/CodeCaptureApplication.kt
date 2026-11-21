package com.codecapture

import android.app.Application
import com.codecapture.di.AppContainer

class CodeCaptureApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
