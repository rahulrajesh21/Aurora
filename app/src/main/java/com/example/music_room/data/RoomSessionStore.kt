package com.example.music_room.data

import android.content.Context
import java.util.UUID

data class RoomSession(
    val memberId: String,
    val sessionToken: String,
    val savedAt: Long
)

object RoomSessionStore {
    private const val PREFS_NAME = "aurora_room_sessions"
    private const val KEY_MEMBER_PREFIX = "member_"
    private const val KEY_TOKEN_PREFIX = "token_"
    private const val KEY_TIMESTAMP_PREFIX = "ts_"
    private val processToken: String = UUID.randomUUID().toString()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveMemberId(context: Context, roomId: String, memberId: String) {
        prefs(context).edit()
            .putString(KEY_MEMBER_PREFIX + roomId, memberId)
            .putString(KEY_TOKEN_PREFIX + roomId, processToken)
            .putLong(KEY_TIMESTAMP_PREFIX + roomId, System.currentTimeMillis())
            .apply()
    }

    fun getSession(context: Context, roomId: String): RoomSession? {
        val storedMember = prefs(context).getString(KEY_MEMBER_PREFIX + roomId, null) ?: return null
        val token = prefs(context).getString(KEY_TOKEN_PREFIX + roomId, null) ?: ""
        val timestamp = prefs(context).getLong(KEY_TIMESTAMP_PREFIX + roomId, 0L)
        return RoomSession(storedMember, token, timestamp)
    }

    fun isSameProcess(session: RoomSession): Boolean {
        return session.sessionToken == processToken
    }

    fun clearMemberId(context: Context, roomId: String) {
        prefs(context).edit()
            .remove(KEY_MEMBER_PREFIX + roomId)
            .remove(KEY_TOKEN_PREFIX + roomId)
            .remove(KEY_TIMESTAMP_PREFIX + roomId)
            .apply()
    }
}
