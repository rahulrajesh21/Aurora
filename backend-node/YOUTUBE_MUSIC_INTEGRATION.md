# YouTube Music Clean Metadata Integration

## Overview

Aurora now uses **YouTube Music's internal API** to get clean, structured song metadata - the same approach used by SimpMusic. This provides properly separated song titles, artist names, and album information instead of messy YouTube video titles.

## What Changed

### Before (YouTube Data API v3)
```typescript
// Returns messy titles like:
"The Weeknd - Blinding Lights (Official Audio) [2020]"
```

### After (YouTube Music Internal API)
```typescript
// Returns clean, separated metadata:
{
  title: "Blinding Lights",      // Clean title
  artist: "The Weeknd",           // Separated artist
  album: "After Hours"            // Album info
}
```

## Technical Implementation

### New Provider: `YouTubeMusicScraperProvider`

Located at: `src/adapters/YouTubeMusicScraperProvider.ts`

**Key Features:**
- ✅ No API key required (uses public YouTube Music endpoints)
- ✅ Clean metadata extraction from structured responses
- ✅ Separate title, artist, and album fields
- ✅ Better thumbnail quality
- ✅ Direct audio stream access

**How It Works:**

YouTube Music's web app uses an internal API that returns structured data in columns:

```typescript
{
  flexColumns: [
    { text: { runs: [{ text: "Blinding Lights" }] } },  // Column 0: Title
    { text: { runs: [{ text: "The Weeknd" }] } },       // Column 1: Artist
    { text: { runs: [{ text: "After Hours" }] } }       // Column 2: Album
  ],
  fixedColumns: [
    { text: { runs: [{ text: "3:20" }] } }              // Duration
  ]
}
```

The scraper parses these columns to extract clean metadata.

### API Endpoints Used

1. **Search**: `https://music.youtube.com/youtubei/v1/search`
   - Filter: Songs only (`params: 'EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D'`)
   - Returns structured song results

2. **Player**: `https://music.youtube.com/youtubei/v1/player`
   - Gets track details and stream URLs
   - Provides clean metadata from `videoDetails`

### Client Context

The scraper mimics the YouTube Music web client:

```typescript
{
  client: {
    clientName: 'WEB_REMIX',
    clientVersion: '1.20231211.01.00',
    hl: 'en',
    gl: 'US'
  }
}
```

## Configuration

### Automatic Selection

The system automatically chooses the best provider:

```typescript
// In ServiceContainer.ts
const ytProvider = this.config.youtube.apiKey 
  ? new YouTubeMusicProvider(this.config.youtube.apiKey)  // If API key exists
  : new YouTubeMusicScraperProvider();                     // Otherwise use scraper
```

### Recommended Setup

**No API key needed!** The YouTube Music scraper works without configuration:

```env
# .env - No YouTube API key required
# YOUTUBE_API_KEY=  <-- Can be left empty
```

### If You Want to Use Regular YouTube API

Add your API key to `.env`:

```env
YOUTUBE_API_KEY=your_api_key_here
```

The system will automatically use it.

## Benefits

### 1. Clean Song Titles
- **Before**: "Artist - Song (Official Video) [Year]"
- **After**: "Song"

### 2. Separated Artist Names
- **Before**: Mixed in title
- **After**: Clean artist field with multiple artists properly formatted

### 3. No API Quota Limits
- YouTube Data API v3: 10,000 requests/day limit
- YouTube Music Scraper: No quota (uses public endpoints)

### 4. Better Metadata Quality
- Album information included
- Higher quality thumbnails
- More accurate duration

### 5. Direct Audio Streams
- Access to adaptive audio formats
- Better quality audio selection
- No yt-dlp dependency for streaming

## Code Example

### Search Request

```typescript
// POST https://music.youtube.com/youtubei/v1/search
{
  "context": {
    "client": {
      "clientName": "WEB_REMIX",
      "clientVersion": "1.20231211.01.00"
    }
  },
  "query": "blinding lights",
  "params": "EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D"  // Filter: Songs
}
```

### Search Response Parsing

```typescript
private parseSearchItem(item: MusicResponsiveListItem): Track | null {
  const flexColumns = renderer.flexColumns || [];
  
  // Column 0: Clean title
  const title = flexColumns[0]?.text?.runs?.[0]?.text;
  
  // Column 1: Artists (separated, can be multiple)
  const artistRuns = flexColumns[1]?.text?.runs || [];
  const artists = artistRuns
    .filter((_, i) => i % 2 === 0)  // Every other run is an artist
    .map(run => run.text);
  
  // Column 2: Album (if available)
  const album = flexColumns[2]?.text?.runs?.[0]?.text;
  
  return {
    id: videoId,
    title,              // "Blinding Lights"
    artist: artists.join(', '),  // "The Weeknd"
    // ... other fields
  };
}
```

## Comparison with SimpMusic

This implementation mirrors SimpMusic's approach:

**SimpMusic (Kotlin):**
```kotlin
// From NextPage.kt
fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer): SongItem? {
    return SongItem(
        title = renderer.flexColumns
                    .firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text?.runs?.firstOrNull()?.text,
        artists = artistRuns.map { Artist(name = it.text, id = ...) },
        // ...
    )
}
```

**Aurora (TypeScript):**
```typescript
// From YouTubeMusicScraperProvider.ts
private parseSearchItem(item: MusicResponsiveListItem): Track | null {
    const title = flexColumns[0]?.text?.runs?.[0]?.text;
    const artists = artistRuns.map(run => run.text);
    // ...
}
```

Both extract metadata from the same structured response format.

## Testing

### Test Search

```bash
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -d '{"query": "blinding lights"}'
```

Expected response:
```json
{
  "tracks": [
    {
      "id": "4NRXx6U8ABQ",
      "title": "Blinding Lights",
      "artist": "The Weeknd",
      "durationSeconds": 200,
      "provider": "YOUTUBE",
      "thumbnailUrl": "https://...",
      "externalUrl": "https://music.youtube.com/watch?v=4NRXx6U8ABQ"
    }
  ]
}
```

### Compare with Old Provider

If you have an API key, you can A/B test:

```typescript
// Test both providers
const oldProvider = new YouTubeMusicProvider(apiKey);
const newProvider = new YouTubeMusicScraperProvider();

const oldResults = await oldProvider.search('blinding lights');
const newResults = await newProvider.search('blinding lights');

// Old: "The Weeknd - Blinding Lights (Official Audio)"
// New: "Blinding Lights"
```

## Troubleshooting

### Issue: "Failed to parse search response"

**Cause**: YouTube Music API structure changed  
**Solution**: Update the parsing logic in `parseSearchItem()`

### Issue: "No audio stream found"

**Cause**: Stream extraction failed  
**Solution**: Check `extractBestAudioStream()` logic

### Issue: Search returns no results

**Cause**: API endpoint blocked or rate limited  
**Solution**: 
1. Check network connectivity
2. Verify user agent headers
3. Try with different search queries

## Future Enhancements

- [ ] Add support for YouTube Music playlists
- [ ] Implement artist page browsing
- [ ] Add album browsing
- [ ] Support for YouTube Music charts
- [ ] Cache parsed metadata
- [ ] Add more robust error handling
- [ ] Support for signature decryption (if needed)

## Credits

Implementation inspired by [SimpMusic](https://github.com/maxrave-dev/SimpMusic) - A FOSS YouTube Music client for Android.

Special thanks to:
- **maxrave-dev** for SimpMusic's YouTube Music scraper
- **z-huang/InnerTune** for the original YouTube Music API reverse engineering

## License

MIT License (same as Aurora)
