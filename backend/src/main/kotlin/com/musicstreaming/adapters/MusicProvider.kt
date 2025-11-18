package com.musicstreaming.adapters

import com.musicstreaming.models.ProviderType
import com.musicstreaming.models.StreamInfo
import com.musicstreaming.models.Track

/**
 * Interface for music provider adapters (Spotify, YouTube, etc.)
 * Defines the contract for searching and retrieving track information
 */
interface MusicProvider {
    /**
     * The type of provider this adapter represents
     */
    val providerType: ProviderType
    
    /**
     * Search for tracks using a query string
     * @param query The search query
     * @param limit Maximum number of results to return
     * @return List of tracks matching the search query
     */
    suspend fun search(query: String, limit: Int = 10): List<Track>
    
    /**
     * Get detailed information about a specific track
     * @param trackId The provider-specific track identifier
     * @return Track details or null if not found
     */
    suspend fun getTrack(trackId: String): Track?
    
    /**
     * Get the stream URL for a specific track
     * @param trackId The provider-specific track identifier
     * @return StreamInfo containing the stream URL and metadata
     */
    suspend fun getStreamUrl(trackId: String): StreamInfo
    
    /**
     * Check if the provider is currently available and authenticated
     * @return true if the provider is ready to use
     */
    suspend fun isAvailable(): Boolean
}
