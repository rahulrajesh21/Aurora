# Deploy Aurora Backend to Render

## Prerequisites
- GitHub account
- Render account (free): https://render.com
- Environment variables ready (Turso DB credentials, API keys)

## Deployment Steps

### 1. Push your code to GitHub
```bash
cd /Users/rahulrajeshkumar/Aurora/backend-node
git add .
git commit -m "Add Render deployment configuration"
git push origin main
```

### 2. Create a new Web Service on Render

1. Go to https://dashboard.render.com
2. Click **"New +"** → **"Web Service"**
3. Connect your GitHub account and select the **Aurora** repository
4. Configure the service:
   - **Name**: `aurora-backend`
   - **Region**: Choose closest to your users (e.g., Oregon, Frankfurt)
   - **Branch**: `main`
   - **Root Directory**: `backend-node`
   - **Runtime**: `Node`
   - **Build Command**: `npm install && npm run build`
   - **Start Command**: `npm start`
   - **Plan**: Free

### 3. Add Environment Variables

In the Render dashboard, go to **Environment** tab and add:

```
NODE_ENV=production
PORT=8080
TURSO_DATABASE_URL=libsql://your-database-url.turso.io
TURSO_AUTH_TOKEN=your-auth-token-here
YOUTUBE_API_KEY=your-youtube-api-key (optional - for metadata)
YOUTUBE_INNERTUBE_API_KEY=your-innertube-key (optional - fallback to default)
OPENROUTER_API_KEY=your-openrouter-key (optional)
```

**To get your values from local .env:**
```bash
cat .env
```

### 4. Deploy

1. Click **"Create Web Service"**
2. Render will automatically build and deploy
3. Wait for deployment to complete (~2-5 minutes)
4. Your backend will be available at: `https://aurora-backend.onrender.com`

### 5. Update Android App

Update the backend URL in your Android app:

In `app/src/main/java/com/example/music_room/data/AuroraServiceLocator.kt`:
```kotlin
private const val BASE_URL = "https://aurora-backend.onrender.com"
```

## Features on Render

✅ **WebSocket Support** - Full real-time functionality
✅ **Auto-deploy** - Deploys automatically on git push
✅ **HTTPS** - Free SSL certificate
✅ **Health Checks** - `/health` endpoint monitoring
✅ **Logs** - View real-time logs in dashboard
✅ **Zero Config** - No Docker or complex setup needed

## Free Plan Limitations

⚠️ **Spins down after 15 minutes of inactivity**
- First request after spin-down takes ~30 seconds
- Upgrade to paid plan ($7/month) for always-on

⚠️ **750 hours/month free**
- Enough for development/testing
- Resets monthly

## Monitoring

- **Logs**: https://dashboard.render.com/web/YOUR_SERVICE/logs
- **Metrics**: https://dashboard.render.com/web/YOUR_SERVICE/metrics
- **Health**: https://aurora-backend.onrender.com/health

## Troubleshooting

### Build fails
- Check that all dependencies are in `package.json`
- Verify TypeScript compiles locally: `npm run build`

### Service crashes
- Check logs in Render dashboard
- Verify environment variables are set correctly
- Make sure `TURSO_DATABASE_URL` and `TURSO_AUTH_TOKEN` are correct

### WebSocket not connecting
- Ensure Android app uses `wss://` (not `ws://`)
- Check CORS settings allow your domain

## Alternative: Use Blueprint (render.yaml)

Instead of manual setup, you can use the included `render.yaml`:

1. Go to https://dashboard.render.com/blueprints
2. Click **"New Blueprint Instance"**
3. Connect your GitHub repo
4. Select `backend-node/render.yaml`
5. Fill in environment variables
6. Click **"Apply"**

This automatically creates the service with all settings configured.
