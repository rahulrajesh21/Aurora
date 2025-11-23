# ✅ Aurora Backend - Ready for Render Deployment

Your backend is now ready to deploy to Render with Docker! 🐳

## What's Been Added

### Docker Configuration
- ✅ **Dockerfile** - Multi-stage build with Node 22 Alpine
- ✅ **docker-compose.yml** - Local testing setup
- ✅ **.dockerignore** - Optimized build context
- ✅ **.env.example** - Environment variable template
- ✅ **render-docker.yaml** - Render Blueprint configuration

### Documentation
- ✅ **RENDER_DEPLOY.md** - Complete Render deployment guide
- ✅ **DOCKER_DEPLOY.md** - Railway, Fly.io, DigitalOcean guides
- ✅ **DEPLOY.md** - Platform comparison and quick start
- ✅ **test-docker.sh** - Local testing script

### Features Included
- ✅ yt-dlp pre-installed for YouTube streaming
- ✅ ffmpeg for audio processing
- ✅ Health check endpoint monitoring
- ✅ Non-root user for security
- ✅ WebSocket support
- ✅ Auto-deploy on git push

## Quick Deploy to Render (5 Minutes)

### Option 1: Manual Setup (Recommended)

1. **Go to Render**: https://dashboard.render.com

2. **Create Web Service**:
   - Click "New +" → "Web Service"
   - Connect GitHub → Select Aurora repo
   - Root directory: `backend-node`
   - Environment: **Docker**

3. **Add Environment Variables**:
   ```
   NODE_ENV=production
   PORT=8080
   TURSO_DATABASE_URL=libsql://your-db.turso.io
   TURSO_AUTH_TOKEN=your-token-here
   ```

4. **Deploy**: Click "Create Web Service"

5. **Wait**: ~3-5 minutes for build

6. **Get URL**: `https://aurora-backend.onrender.com`

[Full guide →](./RENDER_DEPLOY.md)

### Option 2: Blueprint (Automated)

1. **Go to Blueprints**: https://dashboard.render.com/blueprints

2. **New Blueprint Instance**:
   - Connect GitHub repo
   - Select `backend-node/render-docker.yaml`
   - Fill in environment variables
   - Click "Apply"

3. **Done**: Render creates everything automatically

## Test Locally First (Recommended)

```bash
cd backend-node

# Copy environment template
cp .env.example .env

# Edit .env with your credentials
nano .env  # or use VS Code

# Start with Docker
docker-compose up -d

# Test endpoints
curl http://localhost:8080/health
curl "http://localhost:8080/api/tracks/search?query=test"

# View logs
docker-compose logs -f

# Stop when done
docker-compose down
```

## After Deployment

### Update Android App

Edit `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        buildConfigField("String", "BACKEND_BASE_URL", "\"https://aurora-backend.onrender.com\"")
        buildConfigField("String", "BACKEND_WS_URL", "\"wss://aurora-backend.onrender.com\"")
    }
}
```

Rebuild:
```bash
cd /Users/rahulrajeshkumar/Aurora
./gradlew clean assembleDebug
```

## Important Notes

### ⚠️ YouTube Streaming on Render

Render's free tier **may have YouTube streaming issues** due to IP blocking. If playback doesn't work:

**Solution**: Deploy to Railway instead (better YouTube compatibility)

```bash
# Railway deployment is just as easy:
# 1. Go to https://railway.app
# 2. Connect GitHub repo
# 3. Set root directory: backend-node
# 4. Add environment variables
# 5. Deploy!
```

See [DOCKER_DEPLOY.md](./DOCKER_DEPLOY.md#deploy-to-railway-recommended) for full Railway guide.

### 💰 Free Tier Limits

Render Free Tier:
- ✅ 750 hours/month
- ⚠️ Spins down after 15 minutes inactivity
- ⚠️ Cold start: ~30-60 seconds

Upgrade to Starter ($7/month) for:
- ✅ Always-on (no cold starts)
- ✅ More resources (1GB RAM)
- ✅ Priority support

## Troubleshooting

### Build Fails
```bash
# Test build locally
cd backend-node
docker build -t aurora-test .
```

### Health Check Fails
- Verify `/health` endpoint exists
- Check environment variables are set
- View logs in Render dashboard

### YouTube Playback Fails
- Try Railway or Fly.io instead
- See DOCKER_DEPLOY.md for alternatives

## Platform Comparison

| Feature | Render | Railway | Fly.io |
|---------|--------|---------|--------|
| Free Tier | 750h/month | $5 credit | Limited |
| YouTube Streaming | ⚠️ May fail | ✅ Works | ✅ Works |
| Setup | ⭐ Easy | ⭐ Easy | ⭐⭐ Medium |
| Always-on | $7/mo | $5-10/mo | $3-5/mo |

**Recommendation**: Start with Render (free), switch to Railway if YouTube fails.

## Documentation

- **[RENDER_DEPLOY.md](./RENDER_DEPLOY.md)** - Complete Render guide
- **[DOCKER_DEPLOY.md](./DOCKER_DEPLOY.md)** - Railway, Fly.io, DigitalOcean
- **[DEPLOY.md](./DEPLOY.md)** - Platform comparison

## Support

- **Render**: https://render.com/docs
- **Railway**: https://railway.app/help
- **Fly.io**: https://fly.io/docs

---

## Next Steps

1. ✅ Test locally with Docker (optional but recommended)
2. ✅ Deploy to Render (or Railway)
3. ✅ Update Android app URL
4. ✅ Test end-to-end
5. ✅ Monitor logs for any issues

**You're all set!** 🚀

Choose your platform and follow the corresponding guide. Good luck! 🎵
