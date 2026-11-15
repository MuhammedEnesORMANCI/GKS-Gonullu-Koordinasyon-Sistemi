package org.gks.android

import android.app.Application

class GksApplication : Application() {

	lateinit var container: AppContainer
		private set

	override fun onCreate() {
		super.onCreate()
		container = AppContainer(this)
	}
}
