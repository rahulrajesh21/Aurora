import { PlaybackState } from './PlaybackState';

export enum RoomVisibility {
  PUBLIC = 'PUBLIC',
  PRIVATE = 'PRIVATE',
}

export interface Room {
  id: string;
  name: string;
  hostId: string;
  hostName: string;
  visibility: RoomVisibility;
  maxMembers: number;
  passcode?: string;
  createdAt: number;
  updatedAt: number;
}

export interface RoomMember {
  id: string;
  displayName: string;
  joinedAt: number;
  lastActiveAt: number;
  isHost: boolean;
}

export interface RoomInvite {
  code: string;
  roomId: string;
  createdByMemberId: string;
  createdAt: number;
  expiresAt: number;
  maxUses: number;
  uses: number;
}

export interface RoomSnapshot {
  room: Room;
  memberCount: number;
  isLocked: boolean;
  nowPlaying?: PlaybackState;
}

export interface RoomStateEnvelope {
  roomId: string;
  playback: PlaybackState;
  queueLength: number;
  members: RoomMember[];
  updatedAt: number;
}
