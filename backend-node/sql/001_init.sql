PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS rooms (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  host_id TEXT NOT NULL,
  host_name TEXT NOT NULL,
  visibility TEXT NOT NULL,
  max_members INTEGER NOT NULL,
  passcode TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS room_members (
  id TEXT PRIMARY KEY,
  room_id TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  display_name TEXT NOT NULL,
  joined_at INTEGER NOT NULL,
  last_active_at INTEGER NOT NULL,
  is_host INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_room_members_room_id ON room_members(room_id);

CREATE TABLE IF NOT EXISTS room_invites (
  code TEXT PRIMARY KEY,
  room_id TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  created_by_member_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  max_uses INTEGER NOT NULL,
  uses INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_room_invites_room_id ON room_invites(room_id);
