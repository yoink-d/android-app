package com.maynkudu.yoinkd

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maynkudu.yoinkd.app.AppRoot
import com.maynkudu.yoinkd.app.AppViewModel
import com.maynkudu.yoinkd.app.incomingRequest

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels {
        viewModelFactory { initializer { AppViewModel((application as YoinkApplication).container) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (savedInstanceState == null) model.request.value = incomingRequest(intent)
        setContent { AppRoot(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingRequest(intent)?.let { model.request.value = it }
    }
}
