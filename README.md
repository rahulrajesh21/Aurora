# 🎵 Aurora Music Player

A modern Android music player app with a stunning gradient UI and glassmorphism design.

## ✨ Features

### 🎵 Music Playback
- **Background Playback** - Continue listening even when the app is closed
- **Media Controls** - Control playback from notification panel and lock screen
- **Real-time Sync** - Multi-device synchronization through WebSocket connections
- **Queue Management** - Add, remove, and reorder tracks in the playback queue

### 🎨 Design & UI
- **Beautiful Gradient Background** - Multi-layered radial gradients creating an aurora effect
- **Glassmorphism Design** - Semi-transparent cards with backdrop blur effects
- **Modern UI/UX** - Clean, intuitive interface with custom color palette
- **Room-based Music Sharing** - Create and join music rooms with friends

### 🔍 Discovery
- **YouTube Integration** - Search and stream music from YouTube
- **Album Gallery** - Browse albums with circular artwork and smooth animations
- **Real-time Search** - Fast music discovery with instant results

## 🎨 Designsanldansdn

- **Color Palette:**
  - Primary Purple: `#DEBDFF`
  - Accent Green: `#E1FFBA`
  - Dark Background: Gradient from `#0D0F23` to `#05060F`
  - Semi-transparent cards with glassmorphism effect

- **UI Components:**
  - Custom gradient background with radial color blobs
  - Translucent album cards with rounded corners
  - Waveform visualization for playing tracks
  - Circular album artwork with play buttons

## 🏗️ Architecture

### Background Playback System
Aurora implements a robust background playback system using modern Android architecture:

- **MediaSessionService** - Handles background music playback with system integration
- **ExoPlayer** - High-performance media player with adaptive streaming
- **Media3 Session API** - Modern media session management for Android 13+
- **Foreground Service** - Ensures uninterrupted playback with proper notification handling
- **WebSocket Integration** - Real-time synchronization between app UI and background service

### Backend Services
- **Node.js Backend** - RESTful API with WebSocket support
- **Room Management** - Multi-user music rooms with real-time state synchronization  
- **Queue Management** - Persistent playback queues with cross-device sync
- **YouTube Integration** - Stream audio directly from YouTube with `yt-dlp` extraction

## 🛠️ Tech Stack

### Android App
- **Language:** Kotlin
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 36
- **Architecture:** MVVM with background services
- **UI:** Material Design 3 with custom glassmorphism effects
- **Media:** ExoPlayer with Media3 Session API
- **Build System:** Gradle with Kotlin DSL

### Backend
- **Runtime:** Node.js with TypeScript
- **Framework:** Express.js with WebSocket support
- **Audio Processing:** YouTube integration with stream extraction
- **Storage:** LibSQL/SQLite for room management

## 📱 Screenshots

*Coming soon*

## 🚀 Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- JDK 11 or higher
- Android SDK 24+

### Installation

1. Clone the repository
```bash
git clone https://github.com/yourusername/aurora-music-player.git
```

2. Open the project in Android Studio

3. Sync Gradle files

4. Run the app on an emulator or physical device

## 📦 Project Structure

```
app/
├── src/main/
│   ├── java/com/example/music_room/
│   │   ├── MainActivity.kt
│   │   ├── Album.kt
│   │   └── AlbumAdapter.kt
│   ├── res/
│   │   ├── drawable/          # Vector drawables and gradients
│   │   ├── layout/            # XML layouts
│   │   └── values/            # Colors, themes, strings
│   └── AndroidManifest.xml
```

## 🎯 Roadmap

- [ ] Add music playback functionality
- [ ] Implement search feature
- [ ] Add favorites/playlist management
- [ ] Integrate with music streaming APIs
- [ ] Add animations and transitions
- [ ] Dark/Light theme toggle
- [ ] Offline music support

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 👨‍💻 Author

Created with ❤️ by [Your Name]

## 🙏 Acknowledgments

- Design inspiration from modern music streaming apps
- Material Design 3 guidelines
- Android community

---

⭐ Star this repo if you find it helpful!
