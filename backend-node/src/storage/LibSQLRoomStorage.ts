import { Client, createClient } from '@libsql/client';

import { Room, RoomInvite, RoomMember } from '../models/Room';
import { RoomStorage } from './RoomStorage';

export interface LibSQLRoomStorageOptions {
  url: string;
  authToken?: string;
}

export class LibSQLRoomStorage implements RoomStorage {
  private readonly ready: Promise<void>;

  constructor(private readonly client: Client) {
    this.ready = this.client.execute('PRAGMA foreign_keys = ON').then(() => undefined);
  }

  static fromOptions(options: LibSQLRoomStorageOptions): LibSQLRoomStorage {
    return new LibSQLRoomStorage(createClient({ url: options.url, authToken: options.authToken }));
  }

  async listRooms(): Promise<Room[]> {
    await this.ready;
    const result = await this.client.execute({
      sql: `SELECT id, name, host_id, host_name, visibility, max_members, description, passcode, created_at, updated_at FROM rooms ORDER BY created_at DESC`,
    });
    return result.rows.map((row) => this.mapRoom(row));
  }

  async getRoom(roomId: string): Promise<Room | null> {
    await this.ready;
    const result = await this.client.execute({
      sql: `SELECT id, name, host_id, host_name, visibility, max_members, description, passcode, created_at, updated_at FROM rooms WHERE id = ? LIMIT 1`,
      args: [roomId],
    });
    const row = result.rows[0];
    return row ? this.mapRoom(row) : null;
  }

  async saveRoom(room: Room): Promise<void> {
    await this.ready;
    await this.client.execute({
      sql: `INSERT INTO rooms (id, name, host_id, host_name, visibility, max_members, description, passcode, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              name = excluded.name,
              host_id = excluded.host_id,
              host_name = excluded.host_name,
              visibility = excluded.visibility,
              max_members = excluded.max_members,
              description = excluded.description,
              passcode = excluded.passcode,
              created_at = excluded.created_at,
              updated_at = excluded.updated_at`,
      args: [
        room.id,
        room.name,
        room.hostId,
        room.hostName,
        room.visibility,
        room.maxMembers,
        room.description ?? null,
        room.passcode ?? null,
        room.createdAt,
        room.updatedAt,
      ],
    });
  }

  async deleteRoom(roomId: string): Promise<void> {
    await this.ready;
    await this.client.execute({ sql: 'DELETE FROM rooms WHERE id = ?', args: [roomId] });
  }

  async getMembers(roomId: string): Promise<RoomMember[]> {
    await this.ready;
    const result = await this.client.execute({
      sql: `SELECT id, display_name, joined_at, last_active_at, is_host
            FROM room_members WHERE room_id = ? ORDER BY joined_at ASC`,
      args: [roomId],
    });
    return result.rows.map((row) => this.mapMember(row));
  }

  async saveMembers(roomId: string, members: RoomMember[]): Promise<void> {
    await this.ready;
    const tx = await this.client.transaction('write');
    try {
      await tx.execute({ sql: 'DELETE FROM room_members WHERE room_id = ?', args: [roomId] });
      for (const member of members) {
        await tx.execute({
          sql: `INSERT INTO room_members (id, room_id, display_name, joined_at, last_active_at, is_host)
                 VALUES (?, ?, ?, ?, ?, ?)`,
          args: [
            member.id,
            roomId,
            member.displayName,
            member.joinedAt,
            member.lastActiveAt,
            member.isHost ? 1 : 0,
          ],
        });
      }
      await tx.commit();
    } catch (error) {
      await tx.rollback();
      throw error;
    }
  }

  async getInvites(roomId: string): Promise<RoomInvite[]> {
    await this.ready;
    const result = await this.client.execute({
      sql: `SELECT code, room_id, created_by_member_id, created_at, expires_at, max_uses, uses
            FROM room_invites WHERE room_id = ? ORDER BY created_at DESC`,
      args: [roomId],
    });
    return result.rows.map((row) => this.mapInvite(row));
  }

  async saveInvites(roomId: string, invites: RoomInvite[]): Promise<void> {
    await this.ready;
    const tx = await this.client.transaction('write');
    try {
      await tx.execute({ sql: 'DELETE FROM room_invites WHERE room_id = ?', args: [roomId] });
      for (const invite of invites) {
        await tx.execute({
          sql: `INSERT INTO room_invites (code, room_id, created_by_member_id, created_at, expires_at, max_uses, uses)
                 VALUES (?, ?, ?, ?, ?, ?, ?)`,
          args: [
            invite.code,
            invite.roomId ?? roomId,
            invite.createdByMemberId,
            invite.createdAt,
            invite.expiresAt,
            invite.maxUses,
            invite.uses,
          ],
        });
      }
      await tx.commit();
    } catch (error) {
      await tx.rollback();
      throw error;
    }
  }

  private mapRoom(row: Record<string, unknown>): Room {
    return {
      id: String(row.id),
      name: String(row.name),
      hostId: String(row.host_id),
      hostName: String(row.host_name),
      visibility: String(row.visibility) as Room['visibility'],
      maxMembers: Number(row.max_members),
      description: row.description == null ? undefined : String(row.description),
      passcode: row.passcode == null ? undefined : String(row.passcode),
      createdAt: Number(row.created_at),
      updatedAt: Number(row.updated_at),
    };
  }

  private mapMember(row: Record<string, unknown>): RoomMember {
    return {
      id: String(row.id),
      displayName: String(row.display_name),
      joinedAt: Number(row.joined_at),
      lastActiveAt: Number(row.last_active_at),
      isHost: Number(row.is_host) === 1,
    };
  }

  private mapInvite(row: Record<string, unknown>): RoomInvite {
    return {
      code: String(row.code),
      roomId: String(row.room_id),
      createdByMemberId: String(row.created_by_member_id),
      createdAt: Number(row.created_at),
      expiresAt: Number(row.expires_at),
      maxUses: Number(row.max_uses),
      uses: Number(row.uses),
    };
  }
}
