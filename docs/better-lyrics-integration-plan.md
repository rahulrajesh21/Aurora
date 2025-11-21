# Better Lyrics Parity Plan (No Caching)

This document outlines how to reproduce the Better Lyrics reference flow inside the Aurora stack (Kotlin Android client + Node backend) **without** implementing caching. Each section lists concrete, sequential tasks to reach feature parity.

---

## 1. Goals & Non-Goals
1. Deliver lyric experiences identical to Better Lyrics: detection, fetching, music-video adjustments, DOM/UX parity.
2. Keep Kotlin frontend and Node backend in sync via existing sockets/APIs.
3. Exclude any transient or persistent caching layers for lyrics or metadata.
4. Ensure architecture remains extensible for later caching toggle (feature-flagged).

Non-goals: UI theming beyond parity, provider expansion beyond the current Better Lyrics set, performance optimizations outside of precise sync.

---

## 2. High-Level Architecture
1. **Kotlin Player Instrumentation**: mirror Better Lyrics' 20 ms polling using Android `Handler`/`Coroutine` loop tied to ExoPlayer events.
2. **Playback Telemetry Channel**: stream structured events to backend via existing playback socket (`AuroraServiceLocator.createPlaybackSocket`).
3. **Lyrics Orchestrator Service (Node)**: new module that consumes telemetry, orchestrates provider calls, applies segment maps, and serves typed lyric payloads back over websockets/REST.
4. **Provider Layer**: plug-in architecture replicating Better Lyrics `sourceMap` pattern, minus caching, returning `LyricSourceResult` objects with line/word timings.
5. **Sync Engine (Frontend)**: Kotlin-side lyric renderer using RecyclerView + animations mirroring `animationEngine.ts`, translating CSS timing offsets into Android animation parameters.

---

## 3. Detailed Implementation Steps

### 3.1 Kotlin Frontend Tasks
1. **Player Telemetry Collector**
   - [ ] Create `PlayerTelemetryManager` tied to `PlayerActivity` lifecycle.
   - [ ] Sample ExoPlayer every 20 ms (use `Choreographer` or `Handler.postDelayed`).
   - [ ] Capture: track ID, title, artist, duration, `player.currentPosition`, `player.isPlaying`, buffered ratios, and video layout metadata (from UI state or backend-supplied flags).
   - [ ] Extrapolate precise current time using `lastPosition` + `SystemClock.elapsedRealtime()` delta.
2. **Event Dispatch to Backend**
   - [ ] Extend playback socket protocol with `player_tick` messages mirroring Better Lyrics `blyrics-send-player-time` payload (JSON fields: `currentTime`, `trackId`, `song`, `artist`, `duration`, `isPlaying`, `isBuffering`, `contentRect`, `browserTime`).
   - [ ] Send tick events only when track metadata or timestamps change beyond epsilon to limit traffic (no caching, but allow debouncing).
   - [ ] On new track detection, emit `player_metadata_changed` event to trigger backend lyric refresh.
3. **Lyric Rendering Layer**
   - [ ] Build `LyricsView` (RecyclerView + custom `LyricLineView`).
   - [ ] Translate Better Lyrics CSS offsets (`--blyrics-richsync-timing-offset`, etc.) to constants with remote config override.
   - [ ] Implement click-to-seek handlers that call `ExoPlayer.seekTo` with line start time.
   - [ ] Manage scroll behavior similar to `animationEngine`: keep active line centered, pause auto-scroll on user interaction.

### 3.2 Backend Node Tasks
1. **API Surface Definition**
   - [ ] Add REST `POST /api/rooms/:roomId/lyrics/fetch` and websocket event `lyrics:update` mirroring Better Lyrics responses.
   - [ ] Define TypeScript interfaces replicating `Lyric`, `LyricPart`, `SegmentMap`, `LyricSourceResult`. Ensure Kotlin models share schema via OpenAPI or shared JSON schema.
2. **Telemetry Consumer**
   - [ ] Enhance `PlaybackSocket` server to ingest `player_tick` events, maintain per-room `PlayerTelemetryState` (latest metadata, timestamps, isMusicVideo flag).
   - [ ] Detect song changes and trigger lyric orchestrator (no cache lookup).
3. **Segment Map Acquisition**
   - [ ] Implement `SegmentMapService` that asks YouTube Music (via existing adapters) or other metadata sources for counterpart audio IDs and `segmentMap` (fields: `primaryVideoStartTimeMilliseconds`, `counterpartVideoStartTimeMilliseconds`, `durationMilliseconds`).
   - [ ] Store current segment map in-memory alongside session (no persistence).
4. **Lyrics Orchestrator**
   - [ ] Port Better Lyrics `createLyrics` logic into `LyricsService.createLyrics(roomId, TrackMetadata)` minus caching branches.
   - [ ] Steps:
     1. Validate metadata (non-empty song/artist/id).
     2. Determine if track is music video via telemetry (content rect non-zero or backend flag).
     3. If music video and `segmentMap` present, swap to counterpart audio ID for provider lookup (keep map for adjustments).
     4. Build `ProviderParameters` with TTL-less abort signal (tie to request scope).
     5. Iterate provider priority list (`bLyrics-richsynced`, `musixmatch-richsync`, `yt-captions`, ...). For each:
        - Ensure provider populates `LyricSourceResult` with `source`, `language`, `lyrics[]`.
        - Perform optional validation vs. YouTube official lyrics (if accessible) using string-similarity threshold 0.5.
     6. If provider returns lyrics, proceed; else try next provider until exhausted (then emit failure event).
5. **Provider Implementations**
   - [ ] Mirror Better Lyrics provider classes within `backend-node/src/services/lyrics/providers/`.
   - [ ] Reuse existing HTTP clients/adapters for LRCLib, Musixmatch, YouTube captions (respect rate limits, add env credentials).
   - [ ] Each provider should be abortable via `AbortController`/`Promise.race` to keep end-to-end latency low.
6. **Segment Map Application**
   - [ ] After obtaining lyrics (if not already music-video synced), adjust each line and part using the same algorithm: find relevant segment, compute `offset = primary - counterpart`, add offset to `startTimeMs` for line, parts, and timed romanization.
   - [ ] Ensure algorithm handles gaps: keep last matching offset when lyric lies beyond defined segments.
7. **Response Formatting & Delivery**
   - [ ] Package `LyricSourceResultWithMeta` (lyrics, source, language, syncType, timing offsets) and push via websocket `lyrics:update` + store in `RoomSession` state for REST retrieval.
   - [ ] Include `metadataVersion` to help frontend discard stale payloads.
   - [ ] On failure, emit structured error payload with `reason` so frontend can show fallback message.

### 3.3 Shared Data Contracts
1. **Kotlin Models**: Define `LyricLine`, `LyricPart`, `LyricPayload` data classes matching backend schema.
2. **Timestamp Precision**: Use `Long` milliseconds for transport, convert to `Float` seconds for UI calculations.
3. **Segment Map DTO**: Provide optional `segmentMap` in responses for debugging and future analytics.

### 3.4 Testing & Validation Tasks
1. **Unit Tests (Backend)**
   - [ ] Cover segment-offset logic, provider selection order, string-similarity rejection, and abort handling.
2. **Integration Tests**
   - [ ] Simulate telemetry + lyric fetch flow end-to-end using mocked providers.
3. **Frontend UI Tests**
   - [ ] Snapshot tests for lyric rendering, instrumentation tests verifying scroll/seek behavior, and pause/resume sync accuracy.
4. **Manual QA Checklist**
   - [ ] Audio track → lyrics synced
   - [ ] Music video with longer intro → verify segment map corrections
   - [ ] Provider fallback order (simulate provider outages)
   - [ ] Socket reconnect/resubscribe ensures lyrics rehydrate without cache

---

## 4. Rollout Strategy
1. Feature-flag entire lyric experience (`better_lyrics_enabled`) server- and client-side.
2. Deploy backend first with dormant endpoints; ensure no caching dependencies.
3. Ship Kotlin client with hidden UI toggle for internal QA.
4. Gradually enable for beta rooms, monitor telemetry (latency, provider success rates).
5. Collect feedback, then roll out broadly.

---

## 5. Future Enhancements (Post-Plan)
1. Add optional caching (transient + persistent) once parity is proven.
2. Introduce configurable provider order per region.
3. Offer offline lyric packages for known tracks.
4. Expose lyric editing tools similar to Better Lyrics editor.
