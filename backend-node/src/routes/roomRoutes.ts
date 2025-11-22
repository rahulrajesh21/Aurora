import { Request, Response, Router } from 'express';

import { RoomVisibility } from '../models/Room';
import { RoomAccessError, RoomCapacityError, RoomInviteError, RoomNotFoundError } from '../models/RoomError';
import { RoomManager } from '../services/RoomManager';
import { createError } from './types';

interface CreateRoomRequest {
  name?: string;
  hostName?: string;
  description?: string;
  visibility?: RoomVisibility;
  passcode?: string;
}

interface JoinRoomRequest {
  displayName?: string;
  passcode?: string;
  inviteCode?: string;
}

interface CreateInviteRequest {
  requestedBy?: string;
  maxUses?: number;
  ttlSeconds?: number;
}

interface LeaveRoomRequest {
  memberId?: string;
}

interface HeartbeatRequest {
  memberId?: string;
}

interface DeleteRoomRequest {
  memberId?: string;
}

export function createRoomRoutes(roomManager: RoomManager): Router {
  const router = Router();

  router.get('/api/rooms', async (_req: Request, res: Response) => {
    const rooms = await roomManager.listRooms();
    res.json(rooms);
  });

  router.post('/api/rooms', async (req: Request, res: Response) => {
    const body = req.body as CreateRoomRequest;
    try {
      const result = await roomManager.createRoom({
        name: body.name ?? '',
        hostName: body.hostName ?? '',
        description: body.description,
        visibility: body.visibility,
        passcode: body.passcode,
      });
      res.status(201).json(result);
    } catch (error) {
      handleRoomError(res, error);
    }
  });

  router.post('/api/rooms/:roomId/join', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as JoinRoomRequest;
    try {
      const member = await roomManager.joinRoom({
        roomId,
        displayName: body.displayName ?? '',
        passcode: body.passcode,
        inviteCode: body.inviteCode,
      });
      res.status(200).json({ member });
    } catch (error) {
      handleRoomError(res, error);
    }
  });

  router.post('/api/rooms/:roomId/leave', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as LeaveRoomRequest;
    if (!body.memberId) {
      res.status(400).json(createError('INVALID_REQUEST', 'memberId is required'));
      return;
    }
    try {
      await roomManager.leaveRoom(roomId, body.memberId);
      res.status(204).send();
    } catch (error) {
      handleRoomError(res, error);
    }
  });

  router.post('/api/rooms/:roomId/heartbeat', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as HeartbeatRequest;
    if (!body.memberId) {
      res.status(400).json(createError('INVALID_REQUEST', 'memberId is required'));
      return;
    }
    try {
      await roomManager.recordHeartbeat(roomId, body.memberId);
      res.status(204).send();
    } catch (error) {
      handleRoomError(res, error);
    }
  });

  router.delete('/api/rooms/:roomId', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as DeleteRoomRequest;
    if (!body.memberId) {
      res.status(400).json(createError('INVALID_REQUEST', 'memberId is required'));
      return;
    }
    try {
      await roomManager.deleteRoom(roomId, body.memberId);
      res.status(204).send();
    } catch (error) {
      handleRoomError(res, error);
    }
  });

  router.get('/api/rooms/:roomId/members', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    try {
      const members = await roomManager.getRoomMembers(roomId);
      res.json({ members });
    } catch (error) {
      handleRoomError(res, error);
    }
  });

  router.get('/api/rooms/:roomId/invites', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    try {
      const invites = await roomManager.getInvites(roomId);
      res.json({ invites });
    } catch (error) {
      handleRoomError(res, error);
    }
  });

  router.post('/api/rooms/:roomId/invites', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as CreateInviteRequest;
    try {
      const invite = await roomManager.createInvite({
        roomId,
        requestedBy: body.requestedBy ?? '',
        maxUses: body.maxUses,
        ttlSeconds: body.ttlSeconds,
      });
      res.status(201).json({ invite });
    } catch (error) {
      handleRoomError(res, error);
    }
  });

  return router;
}

function handleRoomError(res: Response, error: unknown): void {
  if (error instanceof RoomNotFoundError) {
    res.status(404).json(createError('ROOM_NOT_FOUND', error.message));
    return;
  }
  if (error instanceof RoomCapacityError) {
    res.status(409).json(createError('ROOM_FULL', error.message));
    return;
  }
  if (error instanceof RoomAccessError) {
    res.status(403).json(createError('ROOM_ACCESS_DENIED', error.message));
    return;
  }
  if (error instanceof RoomInviteError) {
    res.status(400).json(createError('ROOM_INVITE_ERROR', error.message));
    return;
  }
  res.status(500).json(createError('ROOM_ERROR', 'Unexpected room error'));
}
