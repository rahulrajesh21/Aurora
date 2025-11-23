# Deploy Aurora Backend to Render with Docker

This guide shows you how to deploy the Aurora backend to Render using Docker, which provides better compatibility for YouTube streaming.

## Prerequisites

- GitHub account with Aurora repository pushed
- Render account (free): https://render.com
- Turso database credentials ready

## Deployment Steps

### 1. Push Docker Files to GitHub

Make sure all Docker-related files are committed:

```bash
cd /Users/rahulrajeshkumar/Aurora/backend-node
git add Dockerfile docker-compose.yml .dockerignore render-docker.yaml
git commit -m "Add Docker deployment configuration for Render"
git push origin main
```

### 2. Create Web Service on Render

1. Go to https://dashboard.render.com
2. Click **"New +"** → **"Web Service"**
3. Click **"Build and deploy from a Git repository"**
4. Click **"Connect account"** to link your GitHub
5. Find and select your **Aurora** repository

### 3. Configure the Service

Fill in the configuration:

**Basic Settings:**
- **Name**: `aurora-backend`
- **Region**: Oregon (US West) or closest to you
- **Branch**: `main`
- **Root Directory**: `backend-node`

**Build Settings:**
- **Environment**: Select **"Docker"**
- **Dockerfile Path**: `./Dockerfile`
- **Docker Build Context Directory**: `./`

**Instance Settings:**
- **Instance Type**: Free

### 4. Add Environment Variables

Scroll to **Environment Variables** section and add:

| Key | Value | Notes |
|-----|-------|-------|
| `NODE_ENV` | `production` | Required |
| `PORT` | `8080` | Required |
| `TURSO_DATABASE_URL` | `libsql://your-db.turso.io` | Get from Turso dashboard |
| `TURSO_AUTH_TOKEN` | `your-token-here` | Get from Turso dashboard |

**To get your Turso credentials:**
```bash
# If you have turso CLI installed
turso db show aurora-rooms

# Otherwise, get from: https://turso.tech/app
```

**Optional environment variables:**
- `YOUTUBE_API_KEY` - If you have a YouTube Data API key
- `OPENROUTER_API_KEY` - For lyrics metadata normalization

### 5. Configure Advanced Settings (Optional)

Click **"Advanced"** to expand:

**Health Check Path:** `/health`

**Docker Command:** Leave empty (uses Dockerfile CMD)

### 6. Deploy

1. Click **"Create Web Service"**
2. Render will:
   - Clone your repository
   - Build the Docker image
   - Start the container
   - Assign a URL

**Build time**: ~3-5 minutes for first deployment

### 7. Verify Deployment

Once deployed, your backend will be at: `https://aurora-backend.onrender.com`

Test it:
```bash
# Health check
curl https://aurora-backend.onrender.com/health

# Search test
curl "https://aurora-backend.onrender.com/api/tracks/search?query=test"
```

### 8. Update Android App

Edit `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        // ... other configs
        
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

## Render Features

✅ **Docker Support** - Better compatibility for yt-dlp and YouTube streaming
✅ **WebSocket Support** - Full real-time functionality  
✅ **Auto-deploy** - Deploys automatically on git push to main
✅ **HTTPS/SSL** - Free SSL certificate included
✅ **Health Checks** - Automatic monitoring of `/health` endpoint
✅ **Logs** - Real-time logs in dashboard

## Free Plan Limitations

⚠️ **Spins down after 15 minutes of inactivity**
- First request after spin-down takes ~30-60 seconds
- Subsequent requests are fast
- Upgrade to paid plan ($7/month) for always-on

⚠️ **750 hours/month free**
- Enough for development and testing
- Resets on the 1st of each month

⚠️ **Limited resources**
- 512 MB RAM
- 0.5 CPU cores
- Usually sufficient for Aurora backend

## Monitoring Your Deployment

### View Logs

1. Go to your service in Render dashboard
2. Click **"Logs"** tab
3. See real-time logs from your container

Or use Render CLI:
```bash
# Install Render CLI
npm install -g render-cli

# View logs
render logs aurora-backend
```

### Check Metrics

1. Click **"Metrics"** tab
2. View:
   - CPU usage
   - Memory usage
   - Request count
   - Response times

### Monitor Health

Render automatically checks `/health` every 30 seconds:
- **Healthy**: Green indicator
- **Unhealthy**: Red indicator, auto-restart triggered

## Troubleshooting

### Build Fails

**Check build logs** in Render dashboard:
1. Click on your service
2. Go to **"Events"** tab
3. Click on failed deployment
4. Review build logs

**Common issues:**
- Missing Dockerfile - ensure it's in `backend-node/` directory
- Docker context path wrong - should be `./`
- Out of memory - try reducing dependencies

**Solution:**
```bash
# Test build locally first
cd backend-node
docker build -t aurora-test .

# If successful, commit and push
git push origin main
```

### Service Crashes on Startup

**Check runtime logs:**
1. Go to **"Logs"** tab
2. Look for error messages

**Common causes:**
- Missing environment variables
- Database connection failed
- Port binding issue

**Fix missing env vars:**
1. Go to **"Environment"** tab
2. Verify `TURSO_DATABASE_URL` and `TURSO_AUTH_TOKEN` are set
3. Click **"Save Changes"**
4. Render will auto-redeploy

### Health Check Failing

**Symptoms:** Service shows as unhealthy or keeps restarting

**Debug:**
```bash
# Test health endpoint
curl https://aurora-backend.onrender.com/health

# Should return 200 OK
```

**Common fixes:**
1. Ensure `/health` route exists in your Express app
2. Check if app is listening on port 8080
3. Verify `PORT` environment variable is set to `8080`

### YouTube Streaming Issues

**Note:** Even with Docker, Render's IP ranges might still be blocked by YouTube for yt-dlp stream extraction.

**Test streaming:**
```bash
curl "https://aurora-backend.onrender.com/api/tracks/TRACK_ID/stream-url"
```

**If still blocked:**
- Consider Railway or Fly.io (see DOCKER_DEPLOY.md)
- Both have better YouTube compatibility
- Similar pricing and features

### Slow Cold Starts

**Issue:** First request after inactivity takes 30-60 seconds

**Why:** Render spins down free tier services after 15 minutes

**Solutions:**
1. **Upgrade to paid plan** ($7/month) - stays always-on
2. **Use a pinger service** (free tier hack):
   - https://uptimerobot.com (free)
   - https://cron-job.org (free)
   - Ping your `/health` endpoint every 10 minutes
3. **Accept the trade-off** - fine for development

## Automatic Deployments

Render automatically deploys when you push to GitHub:

```bash
# Make changes to your code
git add .
git commit -m "Update feature"
git push origin main

# Render detects the push and deploys automatically
```

**To disable auto-deploy:**
1. Go to service **"Settings"**
2. Scroll to **"Build & Deploy"**
3. Toggle **"Auto-Deploy"** to off

## Manual Deployments

Deploy without pushing to GitHub:

1. Go to your service dashboard
2. Click **"Manual Deploy"**
3. Select branch to deploy
4. Click **"Deploy"**

Or use Render CLI:
```bash
render deploy aurora-backend
```

## Updating Environment Variables

1. Go to **"Environment"** tab
2. Edit or add variables
3. Click **"Save Changes"**
4. Render automatically redeploys with new variables

**Important:** Changes to environment variables trigger a redeploy!

## Custom Domain (Optional)

Add your own domain:

1. Go to **"Settings"** tab
2. Scroll to **"Custom Domains"**
3. Click **"Add Custom Domain"**
4. Enter your domain (e.g., `api.aurora.com`)
5. Follow DNS configuration instructions
6. Wait for SSL certificate (automatic)

## Upgrading to Paid Plan

Benefits of upgrading ($7/month):

✅ Always-on (no cold starts)
✅ More resources (1 GB RAM, 1 CPU)
✅ Priority support
✅ Better performance

**To upgrade:**
1. Go to service **"Settings"**
2. Scroll to **"Instance Type"**
3. Select **"Starter"** plan
4. Confirm payment method

## Comparison: Render vs Alternatives

| Feature | Render (Docker) | Railway | Fly.io |
|---------|----------------|---------|--------|
| Free Tier | 750h/month | $5 credit | Limited free |
| Always-on (Free) | ❌ Spins down | ❌ Uses credit | ❌ Limited |
| Auto-deploy | ✅ Yes | ✅ Yes | ✅ Yes |
| Docker Support | ✅ Yes | ✅ Yes | ✅ Yes |
| WebSocket | ✅ Yes | ✅ Yes | ✅ Yes |
| YouTube Streaming | ⚠️ May block | ✅ Works | ✅ Works |
| Paid Price | $7/month | ~$5-10/month | ~$3-5/month |
| Setup Difficulty | Easy | Easiest | Moderate |

**Recommendation:**
- **Render**: Good for getting started, easy setup
- **Railway**: Best for production, most reliable YouTube streaming
- **Fly.io**: Best for global distribution, technical users

## Next Steps

1. ✅ Deploy to Render
2. ✅ Test health endpoint
3. ✅ Test search functionality
4. ✅ Test music playback
5. ⚠️ If YouTube streaming fails, try Railway (see DOCKER_DEPLOY.md)
6. ✅ Update Android app with Render URL
7. ✅ Test end-to-end with Android app

## Support

- **Render Docs**: https://render.com/docs
- **Render Community**: https://community.render.com
- **Status Page**: https://status.render.com

## Alternative: Use Railway Instead

If you encounter YouTube blocking on Render, Railway is a better alternative:

```bash
# See full guide
cat DOCKER_DEPLOY.md

# Quick Railway setup:
# 1. Go to https://railway.app
# 2. Connect GitHub repo
# 3. Select Aurora repository
# 4. Set root directory to backend-node
# 5. Add environment variables
# 6. Deploy!
```

Railway typically has better YouTube streaming compatibility.
