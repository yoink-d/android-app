package com.maynkudu.yoinkd

import android.app.Application
import com.maynkudu.yoinkd.app.AppContainer

class YoinkApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}
