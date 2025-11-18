#!/bin/bash

# Interactive Kafka Observability Demo
# Allows user to trigger scenarios and see results

set -e

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARENT_DIR="$(dirname "$DEMO_DIR")"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

clear

echo ""
echo "╔══════════════════════════════════════════════════════════════════╗"
echo "║                                                                  ║"
echo "║       Interactive Kafka Observability Demo                      ║"
echo "║       Promise Theory + Speech Act Theory                        ║"
echo "║                                                                  ║"
echo "╚══════════════════════════════════════════════════════════════════╝"
echo ""

# Check if Kafka is running
if ! nc -z localhost 9092 2>/dev/null && ! timeout 1 bash -c 'cat < /dev/null > /dev/tcp/localhost/9092' 2>/dev/null; then
    echo -e "${RED}❌ Kafka is not running on localhost:9092${NC}"
    echo ""
    echo "Start Kafka first:"
    echo "  cd $DEMO_DIR && docker-compose up -d"
    echo ""
    exit 1
fi

echo -e "${GREEN}✅ Kafka is running${NC}"
echo ""

# Build if needed
if [ ! -f "$DEMO_DIR/target/kafka-observability-demo.jar" ]; then
    echo -e "${BLUE}📦 Building demo...${NC}"
    cd "$DEMO_DIR"
    mvn clean package -DskipTests -q
    echo -e "${GREEN}✅ Build complete${NC}"
    echo ""
fi

# Function to show the menu
show_menu() {
    echo ""
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}                    INTERACTIVE DEMO MENU                        ${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "Choose a scenario to run:"
    echo ""
    echo -e "${GREEN}1)${NC} Level 1: Silent Self-Regulation (99% of cases)"
    echo "   Agent fulfills promise → No communication to central"
    echo ""
    echo -e "${YELLOW}2)${NC} Level 2: Notable Event Report (0.9% of cases)"
    echo "   Agent fulfills promise → Sends REPORT to central (audit trail)"
    echo ""
    echo -e "${RED}3)${NC} Level 3: Request Help from Central (0.1% of cases)"
    echo "   Agent breaches promise → Full conversation with central"
    echo ""
    echo -e "${BLUE}4)${NC} Run All Scenarios (Demo Mode)"
    echo ""
    echo -e "${MAGENTA}5)${NC} View Live Kafka Messages"
    echo ""
    echo "0) Exit"
    echo ""
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo ""
}

# Function to run scenario 1
run_scenario_1() {
    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  Scenario 1: Level 1 - Silent Self-Regulation (99%)            ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "What happens:"
    echo "  1. Agent makes a PROMISE to self-regulate the buffer"
    echo "  2. Agent successfully FULFILLS the promise"
    echo "  3. ✅ Success! No network communication needed"
    echo ""
    echo "This is the NORMAL case - agent handles everything locally."
    echo ""
    read -p "Press ENTER to run this scenario..."

    echo ""
    echo -e "${BLUE}Running...${NC}"
    echo ""

    # Create a simple Java program that just does scenario 1
    java --enable-preview -cp "$DEMO_DIR/target/kafka-observability-demo.jar" \
        io.fullerstack.kafka.demo.scenarios.Scenario1Silent

    echo ""
    echo -e "${GREEN}✅ Scenario 1 Complete!${NC}"
    echo ""
    echo "Result: Agent self-regulated successfully with ZERO network traffic."
    echo ""
    read -p "Press ENTER to continue..."
}

# Function to run scenario 2
run_scenario_2() {
    echo ""
    echo -e "${YELLOW}╔══════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${YELLOW}║  Scenario 2: Level 2 - Notable Event Report (0.9%)             ║${NC}"
    echo -e "${YELLOW}╚══════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "What happens:"
    echo "  1. Agent makes a PROMISE to self-regulate"
    echo "  2. Agent FULFILLS the promise"
    echo "  3. 📊 Notable event - sends REPORT to central (audit trail)"
    echo "  4. Central receives REPORT for compliance logging"
    echo ""
    echo "This is for AUDIT purposes - minimal network traffic."
    echo ""
    read -p "Press ENTER to run this scenario..."

    echo ""
    echo -e "${BLUE}Running...${NC}"
    echo ""
    echo "Starting central platform in background..."

    # Start central in background
    java --enable-preview -cp "$DEMO_DIR/target/kafka-observability-demo.jar" \
        io.fullerstack.kafka.demo.central.CentralPlatformApplication \
        > /tmp/central-scenario2.log 2>&1 &
    CENTRAL_PID=$!

    sleep 3
    echo "Central platform running (PID: $CENTRAL_PID)"
    echo ""

    # Run scenario 2
    java --enable-preview -cp "$DEMO_DIR/target/kafka-observability-demo.jar" \
        io.fullerstack.kafka.demo.scenarios.Scenario2Report

    echo ""
    echo -e "${YELLOW}✅ Scenario 2 Complete!${NC}"
    echo ""
    echo "Result: Agent succeeded and sent audit REPORT to central."
    echo ""
    echo "Central platform log:"
    tail -20 /tmp/central-scenario2.log | grep -E "(REPORT|RECEIVED)" || echo "(No REPORT messages yet - central may still be starting)"

    # Cleanup
    kill $CENTRAL_PID 2>/dev/null || true

    echo ""
    read -p "Press ENTER to continue..."
}

# Function to run scenario 3
run_scenario_3() {
    echo ""
    echo -e "${RED}╔══════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║  Scenario 3: Level 3 - Request Help from Central (0.1%)        ║${NC}"
    echo -e "${RED}╚══════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "What happens:"
    echo "  1. Agent makes a PROMISE to self-regulate"
    echo "  2. ❌ Agent BREACHES promise (local throttling failed)"
    echo "  3. 📞 Agent sends REQUEST to central for help"
    echo "  4. Central receives REQUEST"
    echo "  5. Central sends ACKNOWLEDGE"
    echo "  6. Central processes request"
    echo "  7. Central sends PROMISE or DENY"
    echo "  8. Central takes action (if approved)"
    echo "  9. Central sends DELIVER or explains failure"
    echo ""
    echo "This is the FULL CONVERSATION - Speech Act Theory in action!"
    echo ""
    read -p "Press ENTER to run this scenario..."

    echo ""
    echo -e "${BLUE}Starting central platform...${NC}"
    echo ""

    # Start central in background
    java --enable-preview -cp "$DEMO_DIR/target/kafka-observability-demo.jar" \
        io.fullerstack.kafka.demo.central.CentralPlatformApplication \
        > /tmp/central-scenario3.log 2>&1 &
    CENTRAL_PID=$!

    sleep 3
    echo "Central platform running (PID: $CENTRAL_PID)"
    echo ""

    echo -e "${BLUE}Running sidecar...${NC}"
    echo ""

    # Run scenario 3 with live output
    java --enable-preview -cp "$DEMO_DIR/target/kafka-observability-demo.jar" \
        io.fullerstack.kafka.demo.scenarios.Scenario3Request 2>&1 | \
        grep -E "(SIDECAR|Agent|REQUEST|RESPONSE|ACTOR|Speech Act)" || true

    echo ""
    echo -e "${RED}✅ Scenario 3 Complete!${NC}"
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "                    CONVERSATION SUMMARY"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""
    echo "Sidecar:"
    echo "  1. ❌ Agent BREACHED promise (couldn't self-regulate)"
    echo "  2. 📞 Sent REQUEST to central"
    echo ""
    echo "Central:"
    tail -30 /tmp/central-scenario3.log | grep -E "(REQUEST|ACKNOWLEDGE|PROMISE|DENY|DELIVER)" | head -10 || echo "(Processing...)"

    echo ""
    echo "Full central log: /tmp/central-scenario3.log"

    # Cleanup
    kill $CENTRAL_PID 2>/dev/null || true

    echo ""
    read -p "Press ENTER to continue..."
}

# Function to view Kafka messages
view_kafka_messages() {
    echo ""
    echo -e "${MAGENTA}╔══════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${MAGENTA}║                    Live Kafka Messages                           ║${NC}"
    echo -e "${MAGENTA}╚══════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "Choose topic to view:"
    echo ""
    echo "1) observability.speech-acts (Sidecar → Central)"
    echo "2) observability.responses (Central → Sidecar)"
    echo "3) Both (side-by-side)"
    echo "0) Back to menu"
    echo ""
    read -p "Choice: " kafka_choice

    case $kafka_choice in
        1)
            echo ""
            echo "Showing messages from: observability.speech-acts"
            echo "(Press Ctrl+C to stop)"
            echo ""
            docker exec kafka-demo-broker-1 kafka-console-consumer \
                --bootstrap-server localhost:9092 \
                --topic observability.speech-acts \
                --from-beginning \
                --property print.key=true \
                --property key.separator=" => " \
                2>/dev/null || echo "No messages yet or Kafka not accessible"
            ;;
        2)
            echo ""
            echo "Showing messages from: observability.responses"
            echo "(Press Ctrl+C to stop)"
            echo ""
            docker exec kafka-demo-broker-1 kafka-console-consumer \
                --bootstrap-server localhost:9092 \
                --topic observability.responses \
                --from-beginning \
                --property print.key=true \
                --property key.separator=" => " \
                2>/dev/null || echo "No messages yet or Kafka not accessible"
            ;;
        3)
            echo ""
            echo "Showing both topics (last 5 messages each):"
            echo ""
            echo -e "${CYAN}=== Speech Acts (Sidecar → Central) ===${NC}"
            docker exec kafka-demo-broker-1 kafka-console-consumer \
                --bootstrap-server localhost:9092 \
                --topic observability.speech-acts \
                --from-beginning \
                --max-messages 5 \
                --property print.key=true \
                --property key.separator=" => " \
                2>/dev/null || echo "No messages yet"
            echo ""
            echo -e "${CYAN}=== Responses (Central → Sidecar) ===${NC}"
            docker exec kafka-demo-broker-1 kafka-console-consumer \
                --bootstrap-server localhost:9092 \
                --topic observability.responses \
                --from-beginning \
                --max-messages 5 \
                --property print.key=true \
                --property key.separator=" => " \
                2>/dev/null || echo "No messages yet"
            ;;
    esac

    echo ""
    read -p "Press ENTER to continue..."
}

# Main loop
while true; do
    show_menu
    read -p "Enter your choice: " choice

    case $choice in
        1)
            run_scenario_1
            ;;
        2)
            run_scenario_2
            ;;
        3)
            run_scenario_3
            ;;
        4)
            echo ""
            echo "Running all scenarios..."
            run_scenario_1
            run_scenario_2
            run_scenario_3
            ;;
        5)
            view_kafka_messages
            ;;
        0)
            echo ""
            echo "Exiting demo. Goodbye!"
            echo ""
            exit 0
            ;;
        *)
            echo ""
            echo -e "${RED}Invalid choice. Please try again.${NC}"
            sleep 2
            ;;
    esac
done
