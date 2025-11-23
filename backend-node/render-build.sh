#!/usr/bin/env bash
# Render build script - installs dependencies including yt-dlp

set -e  # Exit on error

echo "Installing npm dependencies..."
npm install

echo "Installing yt-dlp..."
# Try multiple methods to install yt-dlp
if command -v pip3 &> /dev/null; then
    pip3 install -U yt-dlp
elif command -v pip &> /dev/null; then
    pip install -U yt-dlp
else
    # Download yt-dlp binary directly
    echo "pip not found, downloading yt-dlp binary..."
    curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp || \
    curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o ./yt-dlp
    chmod a+rx ./yt-dlp 2>/dev/null || chmod a+rx /usr/local/bin/yt-dlp
    export PATH="$PWD:$PATH"
fi

echo "Verifying yt-dlp installation..."
yt-dlp --version || echo "Warning: yt-dlp not found in PATH"

echo "Building TypeScript..."
npm run build

echo "Build complete!"
