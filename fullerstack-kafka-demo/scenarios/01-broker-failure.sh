#!/bin/bash
# Chaos Scenario 1: Broker Failure
#
# Simulates broker-1 crashing, triggering:
# - Layer 1 (OBSERVE): Probe detects DISCONNECT
# - Layer 2 (ORIENT): Monitor assesses DEGRADED cluster health
# - Layer 3 (DECIDE): Reporter signals CRITICAL urgency
# - Layer 4 (ACT): Agent/Actor handles failover

set -e

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  CHAOS SCENARIO 1: Broker Failure"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "📊 Step 1: Check initial cluster health"
docker ps --filter "name=kafka-demo-broker" --format "{{.Names}}: {{.Status}}"
echo

sleep 2

echo "🔥 Step 2: Kill broker-1"
docker stop kafka-demo-broker-1
echo "  ✅ Broker-1 stopped"
echo

sleep 5

echo "👀 Step 3: Observe signal flow (check demo logs)"
echo "  Expected observations:"
echo "  - [OBSERVE] Probe: DISCONNECT (broker-1 heartbeat)"
echo "  - [ORIENT] Monitor: DEGRADED (cluster.health)"
echo "  - [DECIDE] Reporter: CRITICAL (cluster.health)"
echo "  - [ACT] Agent/Actor: Handle failover"
echo

sleep 30

echo "🔄 Step 4: Restart broker-1 (recovery)"
docker start kafka-demo-broker-1
echo "  ✅ Broker-1 restarted"
echo

sleep 10

echo "📊 Step 5: Verify cluster recovered"
docker ps --filter "name=kafka-demo-broker" --format "{{.Names}}: {{.Status}}"
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  ✅ SCENARIO COMPLETE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
