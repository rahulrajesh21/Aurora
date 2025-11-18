package com.musicstreaming.models

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val tracks: List<Track>,
    val query: String,
    val providers: List<ProviderType>
) {
    init {
        require(query.isNotBlank()) { "Search query cannot be blank" }
    }
}
