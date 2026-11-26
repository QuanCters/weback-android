package com.weback.robot

import android.app.Application
import com.weback.robot.core.AppContainer
import com.weback.robot.core.DefaultAppContainer

class WeBackApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(context = applicationContext)
    }

    internal fun replaceContainerForTesting(testContainer: AppContainer) {
        if (::container.isInitialized) {
            container.close()
        }
        container = testContainer
    }

    override fun onTerminate() {
        if (::container.isInitialized) {
            container.close()
        }
        super.onTerminate()
    }
}
