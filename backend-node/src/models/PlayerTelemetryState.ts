export interface PlayerTelemetryState {
    currentTime: number;
    trackId: string;
    song: string;
    artist: string;
    duration: number;
    isPlaying: boolean;
    isBuffering: boolean;
    contentRect?: {
        left: number;
        top: number;
        width: number;
        height: number;
    };
    browserTime: number;
    timestamp: number; // Server time when received
}
