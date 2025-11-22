package com.example.music_room.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.doOnTextChanged
import com.example.music_room.R
import com.example.music_room.data.remote.model.TrackDto
import com.example.music_room.utils.displayTitle
import com.example.music_room.utils.sanitizeArtistLabel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import coil.load

class AddSongBottomSheet(
    context: Context,
    private val onSearch: (String, (List<TrackDto>) -> Unit, (Throwable) -> Unit) -> Unit,
    private val onPlayNow: (TrackDto) -> Unit,
    private val onAddToQueue: (TrackDto) -> Unit
) : BottomSheetDialog(context) {

    private val searchInput: TextInputEditText
    private val emptyState: TextView
    private val resultsRecyclerView: RecyclerView
    private val progressBar: ProgressBar
    private val adapter = SearchResultsAdapter(onPlayNow, onAddToQueue)
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingQuery: String? = null
    private val searchRunnable = Runnable {
        val query = pendingQuery ?: return@Runnable
        performSearch(query)
    }

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_add_song, null)
        setContentView(view)

        searchInput = view.findViewById(R.id.searchInput)
        emptyState = view.findViewById(R.id.searchEmptyState)
        resultsRecyclerView = view.findViewById(R.id.searchResultsRecyclerView)
        progressBar = view.findViewById(R.id.searchProgress)

        resultsRecyclerView.layoutManager = LinearLayoutManager(context)
        resultsRecyclerView.adapter = adapter
        emptyState.visibility = View.VISIBLE
        emptyState.text = context.getString(R.string.search_empty_hint)

        searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = v.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    searchHandler.removeCallbacks(searchRunnable)
                    pendingQuery = null
                    performSearch(query)
                }
                true
            } else {
                false
            }
        }

        searchInput.doOnTextChanged { text, _, _, _ ->
            val query = text?.toString()?.trim().orEmpty()
            searchHandler.removeCallbacks(searchRunnable)
            pendingQuery = null
            if (query.length < 3) {
                adapter.submitList(emptyList())
                emptyState.visibility = View.VISIBLE
                emptyState.text = context.getString(R.string.search_empty_hint)
                progressBar.visibility = View.GONE
            } else {
                pendingQuery = query
                progressBar.visibility = View.VISIBLE
                searchHandler.postDelayed(searchRunnable, 400)
            }
        }

        setOnDismissListener {
            searchHandler.removeCallbacks(searchRunnable)
        }
    }

    private fun performSearch(query: String) {
        emptyState.visibility = View.GONE
        adapter.submitList(emptyList())
        progressBar.visibility = View.VISIBLE
        onSearch(query, { results ->
            progressBar.visibility = View.GONE
            val sanitized = results.filter { it.id.isNotBlank() && it.title.isNotBlank() }
            adapter.submitList(sanitized)
            emptyState.visibility = if (sanitized.isEmpty()) View.VISIBLE else View.GONE
            if (sanitized.isEmpty()) {
                emptyState.text = context.getString(R.string.no_results_for, query)
            }
        }, {
            emptyState.visibility = View.VISIBLE
            emptyState.text = it.message ?: context.getString(R.string.loading)
            progressBar.visibility = View.GONE
        })
    }

    private class SearchResultsAdapter(
        private val onPlayNow: (TrackDto) -> Unit,
        private val onAddToQueue: (TrackDto) -> Unit
    ) : RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

        private val items = mutableListOf<TrackDto>()

        fun submitList(tracks: List<TrackDto>) {
            items.clear()
            items.addAll(tracks)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val track = items[position]
            holder.bind(track)
            holder.onPlay = { onPlayNow(track) }
            holder.onAdd = { onAddToQueue(track) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.trackTitle)
            private val subtitle: TextView = view.findViewById(R.id.trackSubtitle)
            private val thumb: ImageView = view.findViewById(R.id.trackThumb)
            private val playButton: ImageView = view.findViewById(R.id.playNowButton)
            private val addButton: ImageView = view.findViewById(R.id.addButton)
            var onPlay: (() -> Unit)? = null
            var onAdd: (() -> Unit)? = null

            fun bind(track: TrackDto) {
                title.text = track.displayTitle()
                val duration = formatDuration(track.durationSeconds)
                val artistLabel = track.artist.sanitizeArtistLabel().ifBlank { track.artist }
                subtitle.text = listOfNotNull(artistLabel, duration).joinToString(" • ")
                if (!track.thumbnailUrl.isNullOrBlank()) {
                    thumb.load(track.thumbnailUrl) {
                        placeholder(R.drawable.album_placeholder)
                        error(R.drawable.album_placeholder)
                    }
                } else {
                    thumb.setImageResource(R.drawable.album_placeholder)
                }
                playButton.setOnClickListener { onPlay?.invoke() }
                addButton.setOnClickListener { onAdd?.invoke() }
            }

            private fun formatDuration(seconds: Int): String? {
                if (seconds <= 0) return null
                val minutes = seconds / 60
                val remaining = seconds % 60
                return String.format("%d:%02d", minutes, remaining)
            }
        }
    }
}
