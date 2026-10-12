package com.enweilo.fujirecipes

import android.app.Application

class FujiApp : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = AppController(this)
    }
}
