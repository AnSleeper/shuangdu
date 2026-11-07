package com.shuangdu.reader

import android.app.Application
import android.content.Context
import com.shuangdu.reader.data.BookRepository
import com.shuangdu.reader.data.LibraryStore
import com.shuangdu.reader.data.SettingsStore
import com.shuangdu.reader.tts.FishAudioClient
import com.shuangdu.reader.tts.FishKeyStore
import com.shuangdu.reader.tts.TtsSettingsStore
import com.shuangdu.reader.update.UpdateChecker

class SdApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = SettingsStore(appContext)
    val library = LibraryStore(appContext)
    val repository = BookRepository(appContext, library, settings)
    val updater = UpdateChecker(appContext)
    val fishKey = FishKeyStore(appContext)
    val ttsSettings = TtsSettingsStore(appContext)
    val fishClient = FishAudioClient(fishKey)
    val ttsConverter by lazy { com.shuangdu.reader.tts.TtsConverter(repository, fishClient, fishKey, ttsSettings) }
    val ttsPlayer by lazy { com.shuangdu.reader.tts.TtsPlayer(appContext) }
}
