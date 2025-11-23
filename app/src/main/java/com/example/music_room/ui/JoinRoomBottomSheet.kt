package com.example.music_room.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.example.music_room.R
import com.example.music_room.data.remote.model.JoinRoomResponseDto
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class JoinRoomBottomSheet(
    context: Context,
    roomName: String,
    private val onJoinRequested: (passcode: String?, (JoinRoomResponseDto) -> Unit, (Throwable) -> Unit) -> Unit,
    private val onCancelled: () -> Unit
) : BottomSheetDialog(context) {

    private val digits = arrayOfNulls<android.widget.EditText>(4)
    private val errorText: TextView

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_join_room, null)
        setContentView(view)

        setCancelable(false)
        setCanceledOnTouchOutside(false)
        behavior.isDraggable = false
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true

        view.findViewById<TextView>(R.id.joinRoomTitle).text = context.getString(R.string.join_room_title, roomName)
        
        digits[0] = view.findViewById(R.id.digit1)
        digits[1] = view.findViewById(R.id.digit2)
        digits[2] = view.findViewById(R.id.digit3)
        digits[3] = view.findViewById(R.id.digit4)
        
        errorText = view.findViewById(R.id.joinErrorText)
        val cancelButton: Button = view.findViewById(R.id.cancelJoinButton)

        setupDigitInputs()

        cancelButton.setOnClickListener {
            dismiss()
            onCancelled()
        }
    }

    private fun setupDigitInputs() {
        for (i in digits.indices) {
            val editText = digits[i] ?: continue
            
            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    errorText.visibility = View.GONE
                    resetErrorState()
                }
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (s != null && s.length == 1) {
                        if (i < digits.size - 1) {
                            digits[i + 1]?.requestFocus()
                        } else {
                            // Last digit entered, try to join
                            attemptJoin()
                        }
                    }
                }
            })

            editText.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                    if (editText.text.isEmpty() && i > 0) {
                        digits[i - 1]?.requestFocus()
                        digits[i - 1]?.text?.clear()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
        
        // Focus first digit initially
        digits[0]?.requestFocus()
    }

    private fun attemptJoin() {
        val passcode = StringBuilder()
        for (digit in digits) {
            passcode.append(digit?.text ?: "")
        }

        if (passcode.length == 4) {
            onJoinRequested(passcode.toString(), { _ ->
                dismiss()
            }, { error ->
                showError(error)
            })
        }
    }

    private fun showError(error: Throwable? = null) {
        val message = when {
            error?.message?.contains("passcode", ignoreCase = true) == true ->
                context.getString(R.string.room_join_invalid_passcode)
            error?.message?.isNotBlank() == true ->
                error.message
            else ->
                context.getString(R.string.room_join_failed)
        }
        errorText.text = message
        errorText.visibility = View.VISIBLE
        
        // Highlight boxes in red (using selected state as defined in drawable)
        digits.forEach { 
            it?.isSelected = true 
            // Shake animation
            val shake = android.animation.ObjectAnimator.ofFloat(it, "translationX", 0f, 25f, -25f, 25f, -25f, 15f, -15f, 6f, -6f, 0f)
            shake.duration = 500
            shake.start()
        }
        
        // Clear inputs after a short delay or let user clear? 
        // Apple style usually shakes and clears. Let's clear for now.
        digits[0]?.postDelayed({
            digits.forEach { 
                it?.text?.clear() 
                it?.isSelected = false
            }
            digits[0]?.requestFocus()
        }, 500)
    }
    
    private fun resetErrorState() {
        digits.forEach { it?.isSelected = false }
    }
}
