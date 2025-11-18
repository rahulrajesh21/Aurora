package com.example.music_room.data.socket

import com.example.music_room.data.remote.model.PlaybackStateDto
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
    private var webSocket: WebSocket? = null

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

    fun disconnect() {
        webSocket?.close(1000, "User closed")
        webSocket = null
    }
}
