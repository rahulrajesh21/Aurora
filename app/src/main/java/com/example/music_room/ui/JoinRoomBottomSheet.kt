package com.example.music_room.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.example.music_room.R
import com.example.music_room.data.remote.model.JoinRoomResponseDto
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText

class JoinRoomBottomSheet(
    context: Context,
    roomName: String,
    private val onJoinRequested: (passcode: String?, inviteCode: String?, (JoinRoomResponseDto) -> Unit, (Throwable) -> Unit) -> Unit,
    private val onCancelled: () -> Unit
) : BottomSheetDialog(context) {

    private val passcodeInput: TextInputEditText
    private val inviteCodeInput: TextInputEditText
    private val errorText: TextView

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_join_room, null)
        setContentView(view)

        view.findViewById<TextView>(R.id.joinRoomTitle).text = context.getString(R.string.join_room_title, roomName)
        passcodeInput = view.findViewById(R.id.passcodeInput)
        inviteCodeInput = view.findViewById(R.id.inviteCodeInput)
        errorText = view.findViewById(R.id.joinErrorText)
        val joinButton: Button = view.findViewById(R.id.joinRoomButton)
        val cancelButton: Button = view.findViewById(R.id.cancelJoinButton)

        joinButton.setOnClickListener {
            val passcode = passcodeInput.text?.toString()?.trim().orEmpty().ifEmpty { null }
            val inviteCode = inviteCodeInput.text?.toString()?.trim().orEmpty().ifEmpty { null }
            errorText.text = ""
            errorText.visibility = View.GONE
            onJoinRequested(passcode, inviteCode, { _ ->
                dismiss()
            }, { error ->
                val message = error.message ?: context.getString(R.string.room_join_failed)
                errorText.text = message
                errorText.visibility = View.VISIBLE
            })
        }

        cancelButton.setOnClickListener {
            dismiss()
            onCancelled()
        }
    }
}
