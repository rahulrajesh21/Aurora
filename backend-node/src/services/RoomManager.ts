import { randomBytes } from 'node:crypto';

import { Mutex } from 'async-mutex';
import { v4 as uuid } from 'uuid';

import { AppConfig, RoomConfig } from '../config/appConfig';
import { PlaybackState, createEmptyState } from '../models/PlaybackState';
import { Room, RoomInvite, RoomMember, RoomSnapshot, RoomVisibility } from '../models/Room';
import { RoomAccessError, RoomCapacityError, RoomInviteError, RoomNotFoundError } from '../models/RoomError';
import { RoomStorage } from '../storage/RoomStorage';
import { logger } from '../utils/logger';

export interface CreateRoomInput {
  name: string;
  hostName: string;
  description?: string;
  visibility?: RoomVisibility;
  passcode?: string;
}

export interface JoinRoomInput {
  roomId: string;
  displayName: string;
  passcode?: string;
  inviteCode?: string;
}

export interface RoomInviteRequest {
  roomId: string;
  requestedBy: string;
  maxUses?: number;
  ttlSeconds?: number;
}

interface RoomContext {
  state: PlaybackState;
}

export class RoomManager {
  private readonly mutex = new Mutex();
  private readonly rooms = new Map<string, Room>();
  private readonly members = new Map<string, RoomMember[]>();
  private readonly invites = new Map<string, RoomInvite[]>();
  private readonly contexts = new Map<string, RoomContext>();

  constructor(private readonly storage: RoomStorage, private readonly config: RoomConfig) { }

  static create(config: AppConfig, storage: RoomStorage): RoomManager {
    return new RoomManager(storage, config.rooms);
  }

  async init(): Promise<void> {
    const rooms = await this.storage.listRooms();
    for (const room of rooms) {
      this.rooms.set(room.id, room);
      const members = await this.storage.getMembers(room.id);
      this.members.set(room.id, members);
      const invites = await this.storage.getInvites(room.id);
      this.invites.set(room.id, this.filterActiveInvites(invites));
      this.contexts.set(room.id, { state: createEmptyState() });
      await this.pruneInactiveMembers(room.id);
    }
  }

  async createRoom(input: CreateRoomInput): Promise<{ room: Room; host: RoomMember }> {
    const trimmedName = input.name.trim();
    if (!trimmedName) {
      throw new RoomAccessError('Room name is required');
    }
    const hostName = input.hostName.trim();
    if (!hostName) {
      throw new RoomAccessError('Host display name is required');
    }

    return this.mutex.runExclusive(async () => {
      const now = Date.now();
      const room: Room = {
        id: uuid(),
        name: trimmedName,
        hostId: uuid(),
        hostName,
        description: input.description?.trim() || undefined,
        visibility: input.visibility ?? RoomVisibility.PUBLIC,
        maxMembers: this.config.maxMembers,
        passcode: input.passcode?.trim() || undefined,
        createdAt: now,
        updatedAt: now,
      };

      const hostMember: RoomMember = {
        id: room.hostId,
        displayName: hostName,
        joinedAt: now,
        lastActiveAt: now,
        isHost: true,
      };

      this.rooms.set(room.id, room);
      this.members.set(room.id, [hostMember]);
      this.invites.set(room.id, []);
      this.contexts.set(room.id, { state: createEmptyState() });

      await this.storage.saveRoom(room);
      await this.storage.saveMembers(room.id, [hostMember]);
      await this.storage.saveInvites(room.id, []);

      logger.info({ roomId: room.id }, 'Created room');
      return { room, host: hostMember };
    });
  }

  async listRooms(): Promise<RoomSnapshot[]> {
    return this.mutex.runExclusive(async () => {
      for (const roomId of this.rooms.keys()) {
        await this.pruneInactiveMembers(roomId);
      }
      return [...this.rooms.values()].map((room) => ({
        room,
        memberCount: this.members.get(room.id)?.length ?? 0,
        isLocked: room.visibility === RoomVisibility.PRIVATE || Boolean(room.passcode),
        nowPlaying: this.contexts.get(room.id)?.state,
      }));
    });
  }

  async joinRoom(input: JoinRoomInput): Promise<RoomMember> {
    return this.mutex.runExclusive(async () => {
      const room = await this.ensureRoomLoaded(input.roomId);
      await this.pruneInactiveMembers(room.id);
      const members = this.members.get(room.id) ?? [];

      if (members.length >= room.maxMembers) {
        throw new RoomCapacityError(room.maxMembers);
      }

      if (room.passcode) {
        if (!input.passcode || room.passcode !== input.passcode) {
          throw new RoomAccessError('Invalid room passcode');
        }
      }

      // if (room.visibility === RoomVisibility.PRIVATE && !input.inviteCode) {
      //   throw new RoomAccessError('Invite code is required for private rooms');
      // }

      if (input.inviteCode) {
        await this.consumeInvite(room.id, input.inviteCode);
      }

      const displayName = input.displayName.trim();
      if (!displayName) {
        throw new RoomAccessError('Display name is required');
      }

      const now = Date.now();
      const member: RoomMember = {
        id: uuid(),
        displayName,
        joinedAt: now,
        lastActiveAt: now,
        isHost: false,
      };

      members.push(member);
      await this.storage.saveMembers(room.id, members);
      this.members.set(room.id, members);
      logger.info({ roomId: room.id, memberId: member.id }, 'Member joined room');
      return member;
    });
  }

  async leaveRoom(roomId: string, memberId: string): Promise<void> {
    await this.mutex.runExclusive(async () => {
      const room = await this.ensureRoomLoaded(roomId);
      await this.pruneInactiveMembers(room.id);
      const members = this.members.get(room.id) ?? [];
      const index = members.findIndex((member) => member.id === memberId);
      if (index === -1) {
        return;
      }
      const [removed] = members.splice(index, 1);
      if (removed.isHost) {
        await this.ensureHostAssignment(room, members);
      }
      await this.storage.saveMembers(room.id, members);
      this.members.set(room.id, members);
      logger.info({ roomId, memberId }, 'Member left room');
    });
  }

  async createInvite(request: RoomInviteRequest): Promise<RoomInvite> {
    return this.mutex.runExclusive(async () => {
      const room = await this.ensureRoomLoaded(request.roomId);
      const memberList = this.members.get(room.id) ?? [];
      const requester = memberList.find((member) => member.id === request.requestedBy);
      if (!requester) {
        throw new RoomAccessError('Member not found in room');
      }
      if (!requester.isHost) {
        throw new RoomAccessError('Only hosts can create invites');
      }
      const activeInvites = this.filterActiveInvites(this.invites.get(room.id) ?? []);
      if (activeInvites.length >= this.config.invite.maxPending) {
        throw new RoomInviteError('Invite limit reached');
      }
      const ttl = (request.ttlSeconds ?? this.config.invite.ttlSeconds) * 1000;
      const invite: RoomInvite = {
        code: this.generateInviteCode(),
        roomId: room.id,
        createdByMemberId: requester.id,
        createdAt: Date.now(),
        expiresAt: Date.now() + ttl,
        maxUses: request.maxUses ?? 1,
        uses: 0,
      };
      activeInvites.push(invite);
      this.invites.set(room.id, activeInvites);
      await this.storage.saveInvites(room.id, activeInvites);
      return invite;
    });
  }

  async getInvites(roomId: string): Promise<RoomInvite[]> {
    return this.mutex.runExclusive(async () => {
      const active = this.filterActiveInvites(this.invites.get(roomId) ?? []);
      if (active.length !== (this.invites.get(roomId) ?? []).length) {
        this.invites.set(roomId, active);
        await this.storage.saveInvites(roomId, active);
      }
      return active;
    });
  }

  async getRoomMembers(roomId: string): Promise<RoomMember[]> {
    return this.mutex.runExclusive(async () => {
      const room = await this.ensureRoomLoaded(roomId);
      await this.pruneInactiveMembers(room.id);
      return [...(this.members.get(room.id) ?? [])];
    });
  }

  async getRoom(roomId: string): Promise<Room | null> {
    return this.mutex.runExclusive(async () => {
      await this.ensureRoomLoaded(roomId);
      return this.rooms.get(roomId) ?? null;
    });
  }

  async updatePlaybackState(roomId: string, state: PlaybackState): Promise<void> {
    await this.mutex.runExclusive(async () => {
      await this.ensureRoomLoaded(roomId);
      this.contexts.set(roomId, { state });
    });
  }

  async getPlaybackState(roomId: string): Promise<PlaybackState> {
    return this.mutex.runExclusive(async () => {
      await this.ensureRoomLoaded(roomId);
      return this.contexts.get(roomId)?.state ?? createEmptyState();
    });
  }

  async recordHeartbeat(roomId: string, memberId: string): Promise<void> {
    await this.mutex.runExclusive(async () => {
      const room = await this.ensureRoomLoaded(roomId);
      const members = this.members.get(room.id) ?? [];
      const member = members.find((item) => item.id === memberId);
      if (!member) {
        throw new RoomAccessError('Member not found in room');
      }
      member.lastActiveAt = Date.now();
      await this.storage.saveMembers(room.id, members);
      this.members.set(room.id, members);
    });
  }

  async deleteRoom(roomId: string, requesterId: string): Promise<void> {
    await this.mutex.runExclusive(async () => {
      const room = await this.ensureRoomLoaded(roomId);
      if (room.hostId !== requesterId) {
        throw new RoomAccessError('Only the room creator can delete this room');
      }
      await this.storage.deleteRoom(roomId);
      this.rooms.delete(roomId);
      this.members.delete(roomId);
      this.invites.delete(roomId);
      this.contexts.delete(roomId);
      logger.info({ roomId, requesterId }, 'Room deleted');
    });
  }

  private async ensureRoomLoaded(roomId: string): Promise<Room> {
    const cached = this.rooms.get(roomId);
    if (cached) {
      return cached;
    }
    const room = await this.storage.getRoom(roomId);
    if (!room) {
      throw new RoomNotFoundError(roomId);
    }
    const memberList = await this.storage.getMembers(roomId);
    const inviteList = this.filterActiveInvites(await this.storage.getInvites(roomId));
    this.rooms.set(roomId, room);
    this.members.set(roomId, memberList);
    this.invites.set(roomId, inviteList);
    if (!this.contexts.has(roomId)) {
      this.contexts.set(roomId, { state: createEmptyState() });
    }
    await this.pruneInactiveMembers(roomId);
    return room;
  }

  private async consumeInvite(roomId: string, code: string): Promise<void> {
    const inviteList = this.invites.get(roomId) ?? [];
    const invite = inviteList.find((item) => item.code === code);
    if (!invite) {
      throw new RoomInviteError('Invite not found or expired');
    }
    if (invite.expiresAt < Date.now()) {
      throw new RoomInviteError('Invite has expired');
    }
    if (invite.uses >= invite.maxUses) {
      throw new RoomInviteError('Invite has no remaining uses');
    }
    invite.uses += 1;
    if (invite.uses >= invite.maxUses) {
      const index = inviteList.findIndex((item) => item.code === code);
      inviteList.splice(index, 1);
    }
    await this.storage.saveInvites(roomId, inviteList);
    this.invites.set(roomId, inviteList);
  }

  private filterActiveInvites(invites: RoomInvite[]): RoomInvite[] {
    const now = Date.now();
    return invites.filter((invite) => invite.expiresAt > now && invite.uses < invite.maxUses);
  }

  private async pruneInactiveMembers(roomId: string): Promise<void> {
    const timeoutMs = this.config.idleTimeoutMs;
    if (timeoutMs <= 0) {
      return;
    }
    const members = this.members.get(roomId);
    if (!members?.length) {
      return;
    }
    const cutoff = Date.now() - timeoutMs;
    const activeMembers = members.filter((member) => member.lastActiveAt >= cutoff);
    if (activeMembers.length === members.length) {
      return;
    }
    const room = this.rooms.get(roomId);
    if (!room) {
      return;
    }
    await this.ensureHostAssignment(room, activeMembers);
    await this.storage.saveMembers(roomId, activeMembers);
    this.members.set(roomId, activeMembers);
    logger.info({ roomId, removedCount: members.length - activeMembers.length }, 'Pruned inactive room members');
  }

  private async ensureHostAssignment(room: Room, members: RoomMember[]): Promise<void> {
    if (!members.length) {
      return;
    }
    const currentHost = members.find((member) => member.isHost);
    if (currentHost) {
      return;
    }
    const newHost = members[0];
    newHost.isHost = true;
    room.hostId = newHost.id;
    room.hostName = newHost.displayName;
    room.updatedAt = Date.now();
    await this.storage.saveRoom(room);
    this.rooms.set(room.id, room);
    logger.info({ roomId: room.id, memberId: newHost.id }, 'Promoted new host for room');
  }

  private generateInviteCode(): string {
    return randomBytes(4).toString('hex');
  }
}
