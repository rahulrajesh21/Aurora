#!/usr/bin/env bash
# Render build script - installs dependencies including yt-dlp

set -e  # Exit on error

echo "Installing npm dependencies..."
npm install

echo "Installing yt-dlp..."
# Install yt-dlp using pip (Python package manager)
pip install -U yt-dlp

echo "Building TypeScript..."
npm run build

echo "Build complete!"
