# Docker Deployment Guide for Aurora Backend

## Quick Start

### Local Testing with Docker

1. **Setup environment variables:**
```bash
cd backend-node
cp .env.example .env
```

Edit `.env` with your credentials:
```bash
# Required
TURSO_DATABASE_URL=libsql://your-db.turso.io
TURSO_AUTH_TOKEN=your-token

# Optional
YOUTUBE_API_KEY=your-key
```

2. **Start the service:**
```bash
docker-compose up -d
```

3. **Verify it's working:**
```bash
# Check health
curl http://localhost:8080/health

# Test search
curl "http://localhost:8080/api/tracks/search?query=test"

# View logs
docker-compose logs -f
```

4. **Stop when done:**
```bash
docker-compose down
```

## Deploy to Railway (Recommended)

Railway is the easiest platform for Docker deployment with excellent YouTube streaming compatibility.

### Step-by-Step

1. **Sign up at Railway**
   - Go to https://railway.app
   - Sign in with GitHub

2. **Create New Project**
   - Click **"New Project"**
   - Select **"Deploy from GitHub repo"**
   - Choose your Aurora repository
   - Railway automatically detects the Dockerfile

3. **Configure the Service**
   - **Root Directory**: Set to `backend-node`
   - **Start Command**: Auto-detected from Dockerfile
   - Click **"Add variables"** to add environment variables

4. **Add Environment Variables**
   ```
   NODE_ENV=production
   PORT=8080
   TURSO_DATABASE_URL=libsql://your-db.turso.io
   TURSO_AUTH_TOKEN=your-token
   ```

5. **Deploy**
   - Railway deploys automatically
   - Wait 2-3 minutes for build
   - You'll get a URL like: `https://aurora-backend.up.railway.app`

6. **Enable Public Access**
   - Go to **Settings** → **Networking**
   - Click **"Generate Domain"**
   - Your backend is now live!

### Railway Features

- ✅ $5 free credit (enough for ~1 month)
- ✅ Auto-deploy on git push
- ✅ Free SSL certificates
- ✅ WebSocket support
- ✅ Works with YouTube streaming
- ✅ Easy environment variable management
- ✅ View logs in real-time

### Cost After Free Credit

- ~$5-10/month for small apps
- Pay only for what you use
- Can pause service when not needed

## Deploy to Fly.io (Advanced)

Fly.io provides global distribution with edge computing.

### Prerequisites

Install Fly CLI:
```bash
# macOS
brew install flyctl

# Linux/WSL
curl -L https://fly.io/install.sh | sh

# Windows
powershell -Command "iwr https://fly.io/install.ps1 -useb | iex"
```

### Deployment Steps

1. **Login to Fly**
```bash
fly auth login
```

2. **Initialize your app**
```bash
cd backend-node
fly launch
```

You'll be prompted:
- **App name**: `aurora-backend` (or choose your own)
- **Region**: Choose closest to your users
- **Postgres database**: No (we use Turso)
- **Deploy now**: No (set secrets first)

3. **Set environment variables**
```bash
fly secrets set TURSO_DATABASE_URL="libsql://your-db.turso.io"
fly secrets set TURSO_AUTH_TOKEN="your-token"
fly secrets set NODE_ENV="production"
```

4. **Configure the app**

Fly created `fly.toml`. Update if needed:
```toml
app = "aurora-backend"
primary_region = "ord"  # or your chosen region

[build]
  dockerfile = "Dockerfile"

[env]
  PORT = "8080"
  NODE_ENV = "production"

[[services]]
  internal_port = 8080
  protocol = "tcp"

  [[services.ports]]
    handlers = ["http"]
    port = 80

  [[services.ports]]
    handlers = ["tls", "http"]
    port = 443

  [[services.http_checks]]
    interval = "30s"
    timeout = "5s"
    grace_period = "10s"
    method = "GET"
    path = "/health"
```

5. **Deploy**
```bash
fly deploy
```

6. **Access your app**
```bash
fly open
# Or visit: https://aurora-backend.fly.dev
```

### Fly.io Features

- ✅ Free tier: 3 shared VMs, 160GB bandwidth/month
- ✅ Global edge deployment
- ✅ Built-in load balancing
- ✅ Auto-scaling
- ✅ Works with YouTube streaming

### Fly.io Commands

```bash
# View logs
fly logs

# Check status
fly status

# Scale (if needed)
fly scale count 2

# SSH into container
fly ssh console

# View secrets
fly secrets list

# Destroy app (when done)
fly apps destroy aurora-backend
```

## Deploy to DigitalOcean App Platform

DigitalOcean provides managed Docker hosting.

### Deployment Steps

1. **Sign up at DigitalOcean**
   - Go to https://cloud.digitalocean.com
   - Create account (get $200 credit for 60 days)

2. **Create App**
   - Click **"Create"** → **"Apps"**
   - Connect your GitHub repository
   - Select the Aurora repository

3. **Configure App**
   - **Source Directory**: `backend-node`
   - **Dockerfile Location**: `backend-node/Dockerfile`
   - **HTTP Port**: 8080
   - **Plan**: Basic ($5/month or free trial)

4. **Add Environment Variables**
   - Go to **"Environment Variables"** section
   - Add your variables (TURSO_DATABASE_URL, etc.)

5. **Deploy**
   - Click **"Create Resources"**
   - Wait for deployment
   - You'll get a URL like: `https://aurora-backend-xxxxx.ondigitalocean.app`

### DigitalOcean Features

- ✅ $200 free credit (60 days)
- ✅ Then $5/month for basic tier
- ✅ Easy scaling
- ✅ CDN included
- ✅ Good for production apps

## Update Android App

After deployment, update your Android app configuration:

### Edit `app/build.gradle.kts`

```kotlin
android {
    defaultConfig {
        // ... other configs
        
        // Replace with your deployed URL
        buildConfigField("String", "BACKEND_BASE_URL", "\"https://aurora-backend.up.railway.app\"")
        buildConfigField("String", "BACKEND_WS_URL", "\"wss://aurora-backend.up.railway.app\"")
    }
}
```

### Rebuild the app

```bash
cd /Users/rahulrajeshkumar/Aurora
./gradlew clean assembleDebug
```

### Install on your device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

### Docker Build Fails Locally

**Issue**: Build fails with dependency errors

**Solution**:
```bash
# Clear Docker cache
docker system prune -a
docker-compose build --no-cache
```

### Container Exits Immediately

**Issue**: Container starts then stops

**Solution**:
```bash
# Check logs for errors
docker-compose logs

# Common issues:
# 1. Missing environment variables
# 2. Port already in use
# 3. Database connection failed
```

### YouTube Streaming Still Fails

**Issue**: Deployed app can search but not play music

**Diagnosis**:
```bash
# Test from deployed server
curl "https://your-app.railway.app/api/tracks/oyDsagB3TRQ/stream-url"
```

**Solutions**:
1. Try a different platform (Railway vs Fly.io)
2. Check yt-dlp is installed: `docker exec -it aurora-backend which yt-dlp`
3. Add proxy/VPN to Docker container (advanced)

### Port Conflicts

**Issue**: Port 8080 already in use locally

**Solution**:
```bash
# Find what's using the port
lsof -i :8080

# Option 1: Kill the process
kill -9 <PID>

# Option 2: Change port in docker-compose.yml
ports:
  - "8081:8080"  # Use 8081 externally
```

### Environment Variables Not Working

**Issue**: App can't connect to database

**Check**:
```bash
# View environment inside container
docker exec -it aurora-backend env | grep TURSO

# If empty, check:
# 1. .env file exists
# 2. Variables in docker-compose.yml
# 3. No typos in variable names
```

## Performance Optimization

### Production Best Practices

1. **Enable healthchecks** (already in Dockerfile)
2. **Set resource limits** (in docker-compose.yml)
3. **Use multi-stage builds** (already implemented)
4. **Run as non-root user** (already implemented)

### Scaling Tips

**Horizontal Scaling** (multiple instances):
- Railway: Increase replicas in dashboard
- Fly.io: `fly scale count 3`
- DigitalOcean: Adjust in App settings

**Vertical Scaling** (more resources):
```yaml
# In docker-compose.yml
deploy:
  resources:
    limits:
      cpus: '2.0'
      memory: 2G
```

## Monitoring

### Railway Monitoring

- **Logs**: Click on service → **Logs** tab
- **Metrics**: View CPU, memory, network in **Metrics** tab
- **Deployments**: See build history in **Deployments**

### Fly.io Monitoring

```bash
# Real-time logs
fly logs -a aurora-backend

# App status
fly status -a aurora-backend

# Metrics
fly dashboard
```

### Health Checks

All platforms automatically monitor `/health` endpoint:
- **Response**: 200 OK = healthy
- **Timeout**: 10s
- **Interval**: Every 30s

## Next Steps

1. ✅ **Test locally** with `docker-compose up`
2. ✅ **Deploy to Railway** (easiest)
3. ✅ **Update Android app** with new URL
4. ✅ **Test music playback** from Android
5. ✅ **Monitor logs** for any issues

## Support

**Railway**: https://railway.app/help
**Fly.io**: https://fly.io/docs
**DigitalOcean**: https://docs.digitalocean.com/products/app-platform/
