package com.example.music_room.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.music_room.databinding.BottomSheetMenuBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.example.music_room.R

class BottomSheetMenu(
    private val onCreateRoom: () -> Unit,
    private val onBrowseRooms: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMenuBinding? = null
    private val binding get() = _binding!!

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.actionCreateRoom.setOnClickListener {
            dismiss()
            onCreateRoom()
        }

        binding.actionBrowseRooms.setOnClickListener {
            dismiss()
            onBrowseRooms()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
