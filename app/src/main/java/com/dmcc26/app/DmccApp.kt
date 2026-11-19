package com.dmcc26.app

import android.app.Application
import com.dmcc26.app.data.NoticeWorker
import com.dmcc26.app.data.Notifier

class DmccApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        if (BuildConfig.FIREBASE_READY) runCatching { NoticeWorker.schedule(this) }
    }
}
