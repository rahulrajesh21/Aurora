#!/usr/bin/env bash
# Render start script - ensures yt-dlp is in PATH

# Add common Python binary locations to PATH
export PATH="/opt/render/project/.venv/bin:/opt/render/project/python/bin:$HOME/.local/bin:/usr/local/bin:$PATH"

# Verify yt-dlp is available
if command -v yt-dlp &> /dev/null; then
    echo "yt-dlp found at: $(which yt-dlp)"
    echo "yt-dlp version: $(yt-dlp --version)"
else
    echo "WARNING: yt-dlp not found in PATH"
    echo "PATH: $PATH"
fi

# Start the Node.js application
exec node dist/index.js
