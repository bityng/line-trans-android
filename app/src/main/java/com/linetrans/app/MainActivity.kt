package com.linetrans.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.linetrans.app.data.DocRepository
import com.linetrans.app.data.SettingsRepository
import com.linetrans.app.data.WordbookRepository
import com.linetrans.app.ai.LocalDictionary
import com.linetrans.app.util.SpeechService
import com.linetrans.app.server.WebServerService
import com.linetrans.app.ui.AppRoot
import com.linetrans.app.ui.theme.LineTransTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsRepository.init(applicationContext)
        DocRepository.init(applicationContext)
        WordbookRepository.init(applicationContext)
        LocalDictionary.init(applicationContext)
        SpeechService.init(applicationContext)
        if (SettingsRepository.settings.webServerAutoStart && !WebServerService.isRunning) {
            runCatching { WebServerService.start(applicationContext) }
        }
        setContent {
            LineTransTheme {
                AppRoot()
            }
        }
    }

    /** 退到后台时把还在防抖队列里的文档写入磁盘。 */
    override fun onStop() {
        super.onStop()
        DocRepository.flushAll()
    }
}
