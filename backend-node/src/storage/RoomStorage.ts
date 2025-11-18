import { Room, RoomInvite, RoomMember } from '../models/Room';

export interface RoomStorage {
  listRooms(): Promise<Room[]>;
  getRoom(roomId: string): Promise<Room | null>;
  saveRoom(room: Room): Promise<void>;
  deleteRoom(roomId: string): Promise<void>;

  getMembers(roomId: string): Promise<RoomMember[]>;
  saveMembers(roomId: string, members: RoomMember[]): Promise<void>;

  getInvites(roomId: string): Promise<RoomInvite[]>;
  saveInvites(roomId: string, invites: RoomInvite[]): Promise<void>;
}
