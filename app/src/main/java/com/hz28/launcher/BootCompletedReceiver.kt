package com.hz28.workplace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Класс для получения сообщения конца загрузки системы, запускает загрузчик
class BootCompletedReceiver: BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(Intent(context, LaunchActivity::class.java))
    }

}
