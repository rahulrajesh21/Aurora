# Aurora Node Backend

TypeScript/Express backend that powers the Aurora streaming app. This implementation now supports multi-room playback scaffolding so every room can own its queue, playback state, and invite flow.

## Getting Started

```bash
npm install
npm run build
npm start
```

Use `npm run dev` for hot reload via `ts-node-dev`.

## Configuration

Runtime settings live in `config/app.config.json` (or environment variables). The new `rooms` block controls per-room behavior:

| Field | Description |
| --- | --- |
| `rooms.storageDriver` | `"file"` (default) keeps JSON persistence, `"libsql"` uses Turso/libSQL. Set via `ROOMS_STORAGE_DRIVER`. |
| `rooms.storagePath` | Directory where room metadata, members, and invite files are persisted. Defaults to `./data/rooms`. |
| `rooms.libsql.url` | Connection string for Turso/libSQL. Mirrors `ROOMS_LIBSQL_URL` / `TURSO_DATABASE_URL`. Required when `storageDriver=libsql`. |
| `rooms.libsql.authToken` | Auth token issued by Turso (`ROOMS_LIBSQL_AUTH_TOKEN` or `TURSO_AUTH_TOKEN`). Optional for local dev. |
| `rooms.maxMembers` | Hard cap on simultaneous members per room. Joining past the limit fails with `ROOM_FULL`. |
| `rooms.idleTimeoutMs` | Milliseconds of inactivity before a member is auto-removed. Heartbeats extend the timer. |
| `rooms.invite.ttlSeconds` | Default invite lifetime in seconds. Expired invites are auto-pruned. |
| `rooms.invite.maxPending` | Maximum active invites per room to avoid spam. |

Remember to set `YOUTUBE_API_KEY` in your environment or the config file before starting the server.

### Migrating rooms to Turso/libSQL

1. Provision a Turso database (`turso db create aurora-rooms`) and grab `TURSO_DATABASE_URL` plus `TURSO_AUTH_TOKEN`.
2. Set the env vars (or config) before starting the server:

```bash
export ROOMS_STORAGE_DRIVER=libsql
export ROOMS_LIBSQL_URL="$TURSO_DATABASE_URL"
export ROOMS_LIBSQL_AUTH_TOKEN="$TURSO_AUTH_TOKEN"
```

3. Apply the schema:

```bash
npm run db:migrate
```

4. Start the backend (`npm run dev` locally or deploy to Vercel). Room data now lives in Turso so Vercel's ephemeral filesystem is no longer a constraint.

## Room API Endpoints

`createRoomRoutes` mounts the following JSON endpoints under `/api/rooms`:

| Method & Path | Description |
| --- | --- |
| `GET /api/rooms` | List all rooms with member counts and latest playback snapshot. |
| `POST /api/rooms` | Create a new room. Body: `{ "name": "", "hostName": "", "visibility": "PUBLIC" | "PRIVATE", "passcode": "optional" }`. |
| `POST /api/rooms/:roomId/join` | Join a room using display name plus optional `passcode`/`inviteCode`. Returns the enrolled member object. |
| `POST /api/rooms/:roomId/leave` | Remove a member from a room. Body: `{ "memberId": "..." }`. Promotes the next member to host if needed. |
| `POST /api/rooms/:roomId/heartbeat` | Update a member's `lastActiveAt` timestamp so idle eviction doesn't remove them. Body: `{ "memberId": "..." }`. |
| `DELETE /api/rooms/:roomId` | Delete a room entirely. Body: `{ "memberId": "hostMemberId" }`. Only the original creator/host can perform this action. |
| `GET /api/rooms/:roomId/members` | Fetch the current roster for a room. |
| `GET /api/rooms/:roomId/invites` | List active invites (expired ones are filtered). |
| `POST /api/rooms/:roomId/invites` | Host-only endpoint to create a shareable invite. Body: `{ "requestedBy": "memberId", "maxUses": 1, "ttlSeconds": 86400 }`. |

All responses follow the existing `createError` helper for error payloads. Room errors map to `ROOM_NOT_FOUND`, `ROOM_FULL`, `ROOM_ACCESS_DENIED`, `ROOM_INVITE_ERROR`, or a generic `ROOM_ERROR`.

## Lyrics API

`POST /api/lyrics` mirrors the Better Lyrics extension pipeline so the frontend can render the exact same synced view the browser add-on does. The request body must provide the bits the extension normally sniffs from YouTube:

```json
{
	"song": "Blinding Lights",
	"artist": "The Weeknd",
	"album": "After Hours",
	"videoId": "fHI8X4OXluQ",
	"durationMs": 200000,
	"audioTrackData": { "captionTracks": [] },
	"youtubeLyricsText": "...optional raw text...",
	"youtubeLyricsSource": "Lyrics provided by YouTube"
}
```

The response contains the canonical lyric structure from Better Lyrics:

```jsonc
{
	"song": "Blinding Lights",
	"artist": "The Weeknd",
	"album": "After Hours",
	"durationMs": 200000,
	"videoId": "fHI8X4OXluQ",
	"source": "boidu.dev",
	"sourceHref": "https://boidu.dev/",
	"lyrics": [
		{
			"startTimeMs": 12345,
			"durationMs": 2200,
			"words": "I've been tryna call",
			"parts": [
				{ "startTimeMs": 12345, "durationMs": 400, "words": "I've" },
				{ "startTimeMs": 12745, "durationMs": 300, "words": "been" }
			],
			"translation": { "lang": "es", "text": "He estado intentando llamar" },
			"romanization": "...optional..."
		}
	]
}
```

Render logic: treat each `lyrics[n]` entry as a line whose clock anchor is `startTimeMs / 1000`. Highlight/scroll when the player position enters the line window, and optionally animate per-word karaoke using `parts`. If `parts` is empty, fall back to line-level syncing exactly like the extension’s `injectLyrics.ts`. Translation and romanization strings (when present) should be displayed beneath the primary line, maintaining order 1) base words, 2) romanized, 3) translated—mirroring Better Lyrics styling.

Providers are tried in the same order as the extension (`bLyrics` → `Musixmatch` placeholder → `YouTube Captions` → `LRCLib`) and cached per `videoId` for one week. Pass through the caption metadata (`audioTrackData.captionTracks`) from the YouTube response if you want the automatic caption fallback to kick in.

## Next Steps

- Wire each room to its own `QueueManager`/`PlaybackEngine` instance so playback is truly isolated per room.
- Bridge room membership with WebSocket channels for presence and now-playing updates.
- Expand integration tests that simulate host & guest flows end-to-end.
