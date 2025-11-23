# Deploy Aurora Backend

Quick deployment guides for the Aurora backend.

## Choose Your Platform

### 🚀 [Railway (Recommended)](./DOCKER_DEPLOY.md#deploy-to-railway-recommended)
- **Best for**: Production use, reliable YouTube streaming
- **Pricing**: $5 free credit, then ~$5-10/month
- **Setup time**: 5 minutes
- **Auto-deploy**: ✅ Yes
- **Docker**: ✅ Native support

### 🐳 [Render with Docker](./RENDER_DEPLOY.md)
- **Best for**: Getting started, easy setup
- **Pricing**: Free (750h/month), then $7/month
- **Setup time**: 5 minutes
- **Auto-deploy**: ✅ Yes
- **Note**: May have YouTube streaming issues

### ✈️ [Fly.io](./DOCKER_DEPLOY.md#deploy-to-flyio-advanced)
- **Best for**: Global distribution, technical users
- **Pricing**: Free tier, then ~$3-5/month
- **Setup time**: 10 minutes
- **Docker**: ✅ Native support

### 💻 [Local Docker](./DOCKER_DEPLOY.md#local-docker-testing)
- **Best for**: Development, testing
- **Pricing**: Free
- **Setup time**: 2 minutes
- **Requirements**: Docker installed

## Quick Start

### Local Testing (Docker)

```bash
cd backend-node
cp .env.example .env
# Edit .env with your credentials
docker-compose up -d
```

### Deploy to Railway (Fastest)

1. Go to https://railway.app
2. Click "New Project" → "Deploy from GitHub repo"
3. Select Aurora repository
4. Set root directory: `backend-node`
5. Add environment variables
6. Deploy!

[Full Railway guide →](./DOCKER_DEPLOY.md#deploy-to-railway-recommended)

### Deploy to Render

1. Go to https://dashboard.render.com
2. Click "New +" → "Web Service"
3. Connect GitHub → Select Aurora repo
4. Set root directory: `backend-node`
5. Environment: **Docker**
6. Add environment variables
7. Deploy!

[Full Render guide →](./RENDER_DEPLOY.md)

## Prerequisites
- Environment variables ready (Turso DB credentials, API keys)
- GitHub repository with code pushed
- For local testing: Docker and Docker Compose installed

## Environment Variables

Create a `.env` file (copy from `.env.example`):

```bash
# Required
TURSO_DATABASE_URL=libsql://your-db.turso.io
TURSO_AUTH_TOKEN=your-token-here

# Optional
YOUTUBE_API_KEY=your-youtube-api-key
OPENROUTER_API_KEY=your-openrouter-key
```

## Documentation

- **[DOCKER_DEPLOY.md](./DOCKER_DEPLOY.md)** - Complete Docker deployment guide
  - Local testing with Docker Compose
  - Railway deployment (recommended)
  - Fly.io deployment
  - DigitalOcean deployment
  
- **[RENDER_DEPLOY.md](./RENDER_DEPLOY.md)** - Render-specific deployment
  - Docker-based Render deployment
  - Environment configuration
  - Troubleshooting guide

## Platform Comparison

| Feature | Railway | Render | Fly.io | Local |
|---------|---------|--------|--------|-------|
| Free Tier | $5 credit | 750h/month | Limited | Unlimited |
| YouTube Streaming | ✅ Works | ⚠️ May fail | ✅ Works | ✅ Works |
| Setup Difficulty | ⭐ Easy | ⭐ Easy | ⭐⭐ Medium | ⭐ Easy |
| Auto-deploy | ✅ | ✅ | ✅ | ❌ |
| WebSocket | ✅ | ✅ | ✅ | ✅ |
| Always-on | $$ | $7/mo | $$ | Manual |

## Troubleshooting

### YouTube Streaming Blocked

**Problem**: Search works but music playback fails

**Solution**: Use Railway or Fly.io instead of Render. See [DOCKER_DEPLOY.md](./DOCKER_DEPLOY.md)

### Build Failures

```bash
# Test build locally
cd backend-node
docker build -t aurora-test .

# Check logs
docker logs <container-id>
```

### Database Connection Issues

Verify environment variables:
- `TURSO_DATABASE_URL` is correct
- `TURSO_AUTH_TOKEN` is set
- Turso database exists and is accessible

### Port Conflicts (Local)

```bash
# Find what's using port 8080
lsof -i :8080

# Change port in docker-compose.yml
ports:
  - "8081:8080"
```

## Next Steps After Deployment

1. ✅ Test `/health` endpoint
2. ✅ Test `/api/tracks/search?query=test`
3. ✅ Update Android app `build.gradle.kts` with deployment URL
4. ✅ Rebuild Android app: `./gradlew assembleDebug`
5. ✅ Test end-to-end music playback

## Support

- **Railway**: https://railway.app/help
- **Render**: https://render.com/docs
- **Fly.io**: https://fly.io/docs
- **Docker**: https://docs.docker.com

---

**Need help?** Check the detailed guides:
- [DOCKER_DEPLOY.md](./DOCKER_DEPLOY.md) - Full Docker deployment
- [RENDER_DEPLOY.md](./RENDER_DEPLOY.md) - Render deployment

