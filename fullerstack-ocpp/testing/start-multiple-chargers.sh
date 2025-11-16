#!/bin/bash

##############################################################################
# Multi-Charger Load Balancing Test Script
#
# Starts multiple OCPP 1.6 chargers using Virtual Charge Point simulator
# to test load balancing, grid capacity management, and adaptive coordination.
#
# Prerequisites:
#   1. OCPP Central System running on ws://localhost:8080
#   2. VCP simulator installed (npm install in ocpp-virtual-charge-point/)
#
# Usage:
#   ./start-multiple-chargers.sh [num_chargers] [vcp_directory]
#
# Examples:
#   ./start-multiple-chargers.sh 5
#   ./start-multiple-chargers.sh 10 ~/ocpp-virtual-charge-point
##############################################################################

set -e  # Exit on error

# Configuration
NUM_CHARGERS=${1:-5}  # Default: 5 chargers
VCP_DIR=${2:-"$HOME/ocpp-virtual-charge-point"}
CENTRAL_SYSTEM_URL="ws://localhost:8080"
LOG_DIR="./logs"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Validate VCP directory
if [ ! -d "$VCP_DIR" ]; then
    echo -e "${RED}ERROR: VCP directory not found: $VCP_DIR${NC}"
    echo "Please clone the simulator:"
    echo "  git clone https://github.com/solidstudiosh/ocpp-virtual-charge-point.git"
    exit 1
fi

if [ ! -f "$VCP_DIR/index_16.ts" ]; then
    echo -e "${RED}ERROR: VCP simulator files not found in $VCP_DIR${NC}"
    exit 1
fi

# Check if npm is installed
if ! command -v npm &> /dev/null; then
    echo -e "${RED}ERROR: npm not found. Please install Node.js${NC}"
    exit 1
fi

# Create log directory
mkdir -p "$LOG_DIR"

echo -e "${GREEN}=====================================================${NC}"
echo -e "${GREEN}  Multi-Charger Load Balancing Test${NC}"
echo -e "${GREEN}=====================================================${NC}"
echo ""
echo "Configuration:"
echo "  Number of chargers: $NUM_CHARGERS"
echo "  Central System:     $CENTRAL_SYSTEM_URL"
echo "  VCP Directory:      $VCP_DIR"
echo "  Log Directory:      $LOG_DIR"
echo ""

# Check if Central System is running
echo -n "Checking Central System availability... "
if curl -s -f http://localhost:9090/api/health > /dev/null 2>&1; then
    echo -e "${GREEN}OK${NC}"
else
    echo -e "${YELLOW}WARNING: Central System may not be running${NC}"
    echo "Please start it with:"
    echo "  mvn exec:java -Dexec.mainClass=\"io.fullerstack.ocpp.example.OcppDemo\""
    echo ""
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

echo ""
echo -e "${GREEN}Starting $NUM_CHARGERS chargers...${NC}"
echo ""

# Array to store PIDs
declare -a PIDS

# Start chargers
for i in $(seq 1 $NUM_CHARGERS); do
    CHARGER_ID=$(printf "CP%03d" $i)
    LOG_FILE="$LOG_DIR/charger-$CHARGER_ID.log"

    echo -n "  Starting $CHARGER_ID... "

    # Start charger in background
    (
        cd "$VCP_DIR"
        export OCPP_ENDPOINT="$CENTRAL_SYSTEM_URL/$CHARGER_ID"
        export CHARGE_POINT_ID="$CHARGER_ID"
        npx tsx index_16.ts > "$LOG_FILE" 2>&1
    ) &

    PID=$!
    PIDS+=($PID)

    echo -e "${GREEN}OK${NC} (PID: $PID, Log: $LOG_FILE)"

    # Stagger connections to avoid overwhelming the server
    if [ $i -lt $NUM_CHARGERS ]; then
        sleep 2
    fi
done

echo ""
echo -e "${GREEN}=====================================================${NC}"
echo -e "${GREEN}  All $NUM_CHARGERS chargers started successfully!${NC}"
echo -e "${GREEN}=====================================================${NC}"
echo ""
echo "PIDs: ${PIDS[@]}"
echo ""
echo -e "${YELLOW}What to expect:${NC}"
echo "  1. Each charger connects via WebSocket"
echo "  2. Central System logs show MONITOR signals (UP, STABLE)"
echo "  3. If total power > 100kW, LoadBalancingAgent rebalances"
echo "  4. SetChargingProfile commands sent to reduce power"
echo ""
echo -e "${YELLOW}Monitoring:${NC}"
echo "  • Central System logs: Terminal running OcppDemo"
echo "  • Charger logs:         $LOG_DIR/charger-*.log"
echo "  • Health API:           curl http://localhost:9090/api/health"
echo ""
echo -e "${YELLOW}To stop all chargers:${NC}"
echo "  kill ${PIDS[@]}"
echo ""
echo "  Or use: ./stop-chargers.sh"
echo ""

# Save PIDs to file for easy cleanup
echo "${PIDS[@]}" > "$LOG_DIR/charger-pids.txt"

# Wait for user to stop (optional)
if [ "${3:-}" = "--wait" ]; then
    echo "Press Ctrl+C to stop all chargers..."
    trap "kill ${PIDS[@]} 2>/dev/null; exit 0" INT TERM
    wait
fi
