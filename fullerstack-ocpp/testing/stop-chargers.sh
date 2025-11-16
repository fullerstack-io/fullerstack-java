#!/bin/bash

##############################################################################
# Stop all running charger simulators
#
# Kills all charger processes started by start-multiple-chargers.sh
##############################################################################

LOG_DIR="./logs"
PID_FILE="$LOG_DIR/charger-pids.txt"

if [ ! -f "$PID_FILE" ]; then
    echo "No PID file found. Chargers may not be running."
    echo "Trying to kill all npx processes anyway..."
    pkill -f "npx tsx index_16.ts" || echo "No charger processes found"
    exit 0
fi

PIDS=$(cat "$PID_FILE")

echo "Stopping chargers (PIDs: $PIDS)..."

for PID in $PIDS; do
    if kill -0 $PID 2>/dev/null; then
        echo "  Stopping PID $PID..."
        kill $PID 2>/dev/null || true
    else
        echo "  PID $PID already stopped"
    fi
done

# Clean up
rm -f "$PID_FILE"

echo "All chargers stopped."
