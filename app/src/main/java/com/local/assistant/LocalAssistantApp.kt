package com.local.assistant

import android.app.Application
import com.local.assistant.di.AppContainer

class LocalAssistantApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
