package com.earshot.app.push

import com.earshot.app.EarshotApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives Firebase messages (phase 10). Only does anything once Firebase is set up (see Push). */
class EarshotMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Push.upload(application as EarshotApp, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "new_episode") Push.onNewEpisode(application as EarshotApp, message.data)
    }
}
