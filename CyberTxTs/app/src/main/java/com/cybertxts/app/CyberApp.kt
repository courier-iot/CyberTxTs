package com.cybertxts.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * The chat session (connections, keys, messages) lives in ONE ChatViewModel owned by the app itself,
 * not by the screen. Closing or rotating the screen no longer drops the room, and the foreground service
 * (SessionService) keeps this process alive in the background.
 */
class CyberApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        Sounds.init(this)
        Notifier.ensureChannels(this)
    }

    fun chatVm(): ChatViewModel =
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[ChatViewModel::class.java]
}
