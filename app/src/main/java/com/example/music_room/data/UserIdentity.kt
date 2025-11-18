package com.example.music_room.data

import android.content.Context
import java.util.UUID

object UserIdentity {
    private const val PREFS_NAME = "aurora_prefs"
    private const val KEY_DISPLAY_NAME = "display_name"

    fun getDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_DISPLAY_NAME, null)
        if (!cached.isNullOrEmpty()) {
            return cached
        }
        val generated = "Listener-" + UUID.randomUUID().toString().take(6)
        prefs.edit().putString(KEY_DISPLAY_NAME, generated).apply()
        return generated
    }
}
