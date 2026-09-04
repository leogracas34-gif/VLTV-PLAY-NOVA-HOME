package com.vltv.play

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Receiver removido — DownloadHelper agora usa Media3/ExoPlayer,
        // que não depende de BroadcastReceiver.
    }
}
