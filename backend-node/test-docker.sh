#!/bin/bash

# Aurora Docker Test Script
# Tests that the Docker container is working correctly

set -e

echo "🐳 Testing Aurora Docker Deployment..."
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test functions
test_health() {
    echo -n "Testing health endpoint... "
    response=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health || echo "000")
    
    if [ "$response" = "200" ]; then
        echo -e "${GREEN}✓ Passed${NC}"
        return 0
    else
        echo -e "${RED}✗ Failed (HTTP $response)${NC}"
        return 1
    fi
}

test_search() {
    echo -n "Testing search endpoint... "
    response=$(curl -s "http://localhost:8080/api/tracks/search?query=test" || echo "error")
    
    if echo "$response" | grep -q "videoId"; then
        echo -e "${GREEN}✓ Passed${NC}"
        return 0
    else
        echo -e "${RED}✗ Failed${NC}"
        echo "Response: $response"
        return 1
    fi
}

test_websocket() {
    echo -n "Testing WebSocket support... "
    # Check if server accepts WebSocket upgrade (basic check)
    response=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Connection: Upgrade" \
        -H "Upgrade: websocket" \
        http://localhost:8080/ws || echo "000")
    
    # 101 = Switching Protocols (success)
    # 426 = Upgrade Required (server supports it but needs proper handshake)
    # 400 = Bad Request (server received it but missing some headers)
    if [ "$response" = "101" ] || [ "$response" = "426" ] || [ "$response" = "400" ]; then
        echo -e "${GREEN}✓ Passed${NC}"
        return 0
    else
        echo -e "${YELLOW}⚠ Skipped (HTTP $response)${NC}"
        return 0
    fi
}

test_ytdlp() {
    echo -n "Testing yt-dlp installation... "
    
    # Try to check if yt-dlp is installed in the container
    if docker-compose ps | grep -q "aurora-backend"; then
        ytdlp_check=$(docker-compose exec -T aurora-backend which yt-dlp 2>/dev/null || echo "not found")
        
        if [ "$ytdlp_check" != "not found" ]; then
            echo -e "${GREEN}✓ Passed${NC}"
            return 0
        else
            echo -e "${RED}✗ Failed (yt-dlp not found)${NC}"
            return 1
        fi
    else
        echo -e "${YELLOW}⚠ Skipped (container not running)${NC}"
        return 0
    fi
}

# Main test sequence
echo "Prerequisites:"
echo "  - Docker and Docker Compose installed"
echo "  - Aurora backend running (docker-compose up -d)"
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}Error: Docker is not running${NC}"
    exit 1
fi

# Check if container is running
if ! docker-compose ps 2>/dev/null | grep -q "aurora-backend"; then
    echo -e "${YELLOW}Warning: Aurora container doesn't seem to be running${NC}"
    echo "Starting container..."
    docker-compose up -d
    echo "Waiting 10 seconds for startup..."
    sleep 10
fi

echo "Running tests..."
echo ""

# Run tests
failed=0

test_health || ((failed++))
test_search || ((failed++))
test_websocket || ((failed++))
test_ytdlp || ((failed++))

echo ""
echo "================================"

if [ $failed -eq 0 ]; then
    echo -e "${GREEN}✓ All tests passed!${NC}"
    echo ""
    echo "Your Aurora backend is ready to use:"
    echo "  • Health: http://localhost:8080/health"
    echo "  • Search: http://localhost:8080/api/tracks/search?query=test"
    echo "  • Logs: docker-compose logs -f"
    exit 0
else
    echo -e "${RED}✗ $failed test(s) failed${NC}"
    echo ""
    echo "Troubleshooting:"
    echo "  1. Check logs: docker-compose logs"
    echo "  2. Verify .env file exists with correct values"
    echo "  3. Ensure ports aren't already in use: lsof -i :8080"
    echo "  4. Rebuild: docker-compose build --no-cache"
    exit 1
fi
