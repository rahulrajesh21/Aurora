export class RoomError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'RoomError';
  }
}

export class RoomNotFoundError extends RoomError {
  constructor(roomId: string) {
    super(`Room ${roomId} was not found`);
    this.name = 'RoomNotFoundError';
  }
}

export class RoomAccessError extends RoomError {
  constructor(message: string) {
    super(message);
    this.name = 'RoomAccessError';
  }
}

export class RoomCapacityError extends RoomError {
  constructor(maxMembers: number) {
    super(`Room is full. Max members: ${maxMembers}`);
    this.name = 'RoomCapacityError';
  }
}

export class RoomInviteError extends RoomError {
  constructor(message: string) {
    super(message);
    this.name = 'RoomInviteError';
  }
}
