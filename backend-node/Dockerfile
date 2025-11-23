# Multi-stage build for Aurora backend
FROM node:22-slim AS builder

# Install build dependencies
RUN apt-get update && \
    apt-get install -y python3 python3-pip make g++ git && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy package files
COPY package*.json ./
COPY tsconfig.json ./

# Install dependencies
RUN npm ci

# Copy source code
COPY src ./src

# Build TypeScript
RUN npm run build

# Production stage
FROM node:22-slim

# Install runtime dependencies
RUN apt-get update && \
    apt-get install -y \
    python3 \
    python3-pip \
    ffmpeg \
    ca-certificates \
    curl \
    git && \
    rm -rf /var/lib/apt/lists/*

# Install yt-dlp and pot provider plugin (Python)
RUN pip3 install --no-cache-dir --break-system-packages yt-dlp bgutil-ytdlp-pot-provider

# Install pot provider server (Node.js) from source
WORKDIR /opt
RUN git clone https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git
WORKDIR /opt/bgutil-ytdlp-pot-provider/server
RUN npm install && npx tsc

# Create app directory
WORKDIR /app

# Copy package files and install production dependencies
COPY package*.json ./
RUN npm ci --omit=dev

# Copy built files from builder
COPY --from=builder /app/dist ./dist

# Create non-root user with home directory for cache
RUN groupadd -r nodejs && useradd -r -g nodejs -m -d /home/nodejs nodejs && \
    chown -R nodejs:nodejs /app && \
    chown -R nodejs:nodejs /opt/bgutil-ytdlp-pot-provider

# Switch to non-root user
USER nodejs

# Set environment
ENV NODE_ENV=production
ENV PORT=8080
ENV HOME=/home/nodejs

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD node -e "require('http').get('http://localhost:8080/health', (r) => {process.exit(r.statusCode === 200 ? 0 : 1)})"

# Start application
CMD ["node", "dist/index.js"]
