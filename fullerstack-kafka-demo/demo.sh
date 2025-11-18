#!/bin/bash

# Kafka Observability Demo - Distributed Coordination
# Demonstrates Promise Theory + Speech Act Theory

set -e

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARENT_DIR="$(dirname "$DEMO_DIR")"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo ""
echo "╔══════════════════════════════════════════════════════════════════╗"
echo "║                                                                  ║"
echo "║     Kafka Observability Demo - Distributed Coordination         ║"
echo "║     Promise Theory + Speech Act Theory                          ║"
echo "║                                                                  ║"
echo "╚══════════════════════════════════════════════════════════════════╝"
echo ""

# Check if Kafka is running
if ! nc -z localhost 9092 2>/dev/null; then
    echo -e "${RED}❌ Kafka is not running on localhost:9092${NC}"
    echo ""
    echo "Start Kafka first:"
    echo "  cd docker && docker-compose up -d"
    echo ""
    exit 1
fi

echo -e "${GREEN}✅ Kafka is running on localhost:9092${NC}"
echo ""

# Build the project
echo -e "${BLUE}📦 Building project...${NC}"
cd "$PARENT_DIR"
mvn clean install -Dmaven.test.skip=true -pl fullerstack-kafka-producer,fullerstack-kafka-coordination,fullerstack-kafka-demo -am
echo -e "${GREEN}✅ Build complete${NC}"
echo ""

# Create Kafka topics
echo -e "${BLUE}📊 Creating Kafka topics...${NC}"
docker exec kafka-broker-1 kafka-topics --bootstrap-server localhost:9092 \
    --create --if-not-exists \
    --topic observability.speech-acts \
    --partitions 3 \
    --replication-factor 1 2>/dev/null || true

docker exec kafka-broker-1 kafka-topics --bootstrap-server localhost:9092 \
    --create --if-not-exists \
    --topic observability.responses \
    --partitions 3 \
    --replication-factor 1 2>/dev/null || true

echo -e "${GREEN}✅ Topics created${NC}"
echo ""

# Function to run central platform
run_central() {
    echo -e "${BLUE}🌐 Starting Central Platform...${NC}"
    cd "$DEMO_DIR"

    java --enable-preview \
        -cp target/kafka-observability-demo.jar \
        io.fullerstack.kafka.demo.central.CentralPlatformApplication
}

# Function to run sidecar
run_sidecar() {
    echo -e "${BLUE}📡 Starting Producer Sidecar...${NC}"
    cd "$DEMO_DIR"

    export SIDECAR_ID="producer-sidecar-1"
    export KAFKA_BOOTSTRAP="localhost:9092"
    export REQUEST_TOPIC="observability.speech-acts"
    export RESPONSE_TOPIC="observability.responses"

    java --enable-preview \
        -cp target/kafka-observability-demo.jar \
        io.fullerstack.kafka.demo.sidecar.ProducerSidecarApplication
}

# Parse command line arguments
case "${1:-both}" in
    central)
        run_central
        ;;
    sidecar)
        run_sidecar
        ;;
    both)
        echo -e "${YELLOW}Starting both central and sidecar...${NC}"
        echo -e "${YELLOW}Recommendation: Run in separate terminals:${NC}"
        echo ""
        echo "  Terminal 1: ./demo.sh central"
        echo "  Terminal 2: ./demo.sh sidecar"
        echo ""
        echo -e "${RED}Press Ctrl+C to continue with combined mode (harder to read logs)${NC}"
        sleep 5

        # Start central in background
        run_central > /tmp/central-platform.log 2>&1 &
        CENTRAL_PID=$!
        echo -e "${GREEN}✅ Central Platform started (PID: $CENTRAL_PID, logs: /tmp/central-platform.log)${NC}"
        sleep 3

        # Start sidecar in foreground
        run_sidecar

        # Cleanup on exit
        kill $CENTRAL_PID 2>/dev/null || true
        ;;
    *)
        echo "Usage: $0 {central|sidecar|both}"
        echo ""
        echo "  central  - Run only central platform"
        echo "  sidecar  - Run only producer sidecar"
        echo "  both     - Run both (combined logs, harder to read)"
        echo ""
        echo "Recommended: Run in separate terminals for cleaner logs"
        exit 1
        ;;
esac
