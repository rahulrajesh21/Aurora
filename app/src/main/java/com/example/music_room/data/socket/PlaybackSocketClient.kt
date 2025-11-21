package com.example.music_room.data.socket

import com.example.music_room.data.remote.model.PlaybackStateDto
import com.example.music_room.data.remote.model.LyricsResponseDto
import com.example.music_room.data.remote.model.PlayerTelemetryState
import com.example.music_room.data.remote.model.PlayerTickMessage
import com.example.music_room.data.remote.model.SocketEventMessage
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class PlaybackSocketClient(
    private val okHttpClient: OkHttpClient,
    moshi: Moshi,
    private val socketUrl: String
) {
    private val adapter = moshi.adapter(PlaybackStateDto::class.java)
    private val tickAdapter = moshi.adapter(PlayerTickMessage::class.java)
    private val eventAdapter = moshi.adapter(SocketEventMessage::class.java)
    private val lyricsAdapter = moshi.adapter(LyricsResponseDto::class.java)

    private var webSocket: WebSocket? = null
    private var onLyricsUpdate: ((LyricsResponseDto) -> Unit)? = null

    fun setLyricsListener(listener: (LyricsResponseDto) -> Unit) {
        onLyricsUpdate = listener
    }

    fun connect(
        onState: (PlaybackStateDto) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val request = Request.Builder().url(socketUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                // no-op
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Try generic event first
                try {
                    val event = eventAdapter.fromJson(text)
                    if (event?.type == "lyrics:update" && event.payload != null) {
                        val lyrics = lyricsAdapter.fromJsonValue(event.payload)
                        if (lyrics != null) {
                            onLyricsUpdate?.invoke(lyrics)
                        }
                        return
                    }
                } catch (e: Exception) {
                    // Not an event or parsing failed, continue to PlaybackStateDto
                }

                runCatching {
                    adapter.fromJson(text)
                }.onSuccess { state ->
                    if (state != null) {
                        onState(state)
                    }
                }.onFailure { error ->
                    onError(error)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                onError(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // no-op
            }
        })
    }

    fun sendPing() {
        webSocket?.send("ping")
    }

    fun sendPlayerTick(state: PlayerTelemetryState) {
        val message = PlayerTickMessage(payload = state)
        webSocket?.send(tickAdapter.toJson(message))
    }

    fun disconnect() {
        webSocket?.close(1000, "User closed")
        webSocket = null
    }
}
