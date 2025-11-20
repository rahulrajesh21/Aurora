import { loadConfig, RoomStorageDriver } from '../config/appConfig';
import { LibSQLRoomStorage } from '../storage/LibSQLRoomStorage';
import { RoomStorage } from '../storage/RoomStorage';

async function main(): Promise<void> {
  const config = loadConfig();
  const storage = createStorage(config.rooms);

  const rooms = await storage.listRooms();
  if (rooms.length === 0) {
    console.log('No rooms found. Nothing to delete.');
    return;
  }

  for (const room of rooms) {
    await storage.deleteRoom(room.id);
  }

  console.log(`Deleted ${rooms.length} room(s).`);
}

function createStorage(config: ReturnType<typeof loadConfig>['rooms']): RoomStorage {
  if (config.storageDriver === RoomStorageDriver.LIBSQL) {
    if (!config.libsql) {
      throw new Error('LibSQL storage driver selected but no connection details were provided.');
    }
    return LibSQLRoomStorage.fromOptions(config.libsql);
  }
  throw new Error(`Unsupported storage driver: ${config.storageDriver}. Only LibSQL is supported.`);
}

main().catch((error) => {
  console.error('Failed to remove rooms', error);
  process.exitCode = 1;
});
