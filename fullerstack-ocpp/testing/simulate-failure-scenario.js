#!/usr/bin/env node

/**
 * Failure Scenario Simulator for OCPP Testing
 *
 * Simulates various failure conditions to test adaptive agent responses:
 * - GroundFailure → ChargerDisableAgent should auto-disable
 * - OverCurrent → ChargerHealthReporter should assess CRITICAL
 * - Connector errors → TransactionStopAgent should stop transaction
 *
 * Usage:
 *   node simulate-failure-scenario.js <scenario> <chargerId>
 *
 * Scenarios:
 *   ground-failure    - Simulate ground fault error
 *   over-current      - Simulate overcurrent protection triggered
 *   over-temperature  - Simulate thermal shutdown
 *   connector-lock    - Simulate connector lock failure
 *
 * Examples:
 *   node simulate-failure-scenario.js ground-failure CP001
 *   node simulate-failure-scenario.js over-current CP002
 */

const WebSocket = require('ws');

// Configuration
const CENTRAL_SYSTEM_URL = process.env.OCPP_ENDPOINT || 'ws://localhost:8080';
const SCENARIO = process.argv[2];
const CHARGER_ID = process.argv[3] || 'CP001';

// OCPP Error Codes (OCPP 1.6 spec)
const ERROR_CODES = {
  'ground-failure': 'GroundFailure',
  'over-current': 'OverCurrentFailure',
  'over-temperature': 'OverTemperature',
  'connector-lock': 'ConnectorLockFailure',
  'power-meter': 'PowerMeterFailure',
  'weak-signal': 'WeakSignal'
};

// Validate input
if (!SCENARIO || !ERROR_CODES[SCENARIO]) {
  console.error('❌ Invalid scenario. Available scenarios:');
  Object.keys(ERROR_CODES).forEach(s => console.error(`  - ${s}`));
  process.exit(1);
}

const errorCode = ERROR_CODES[SCENARIO];
const endpoint = `${CENTRAL_SYSTEM_URL}/${CHARGER_ID}`;

console.log('==================================================');
console.log('  OCPP Failure Scenario Simulator');
console.log('==================================================');
console.log('');
console.log(`Scenario:       ${SCENARIO}`);
console.log(`Error Code:     ${errorCode}`);
console.log(`Charger ID:     ${CHARGER_ID}`);
console.log(`Central System: ${endpoint}`);
console.log('');
console.log('Expected Adaptive Response:');

// Describe expected agent behavior
switch (SCENARIO) {
  case 'ground-failure':
    console.log('  1. OcppMessageObserver emits DEFECTIVE signal');
    console.log('  2. ChargerHealthReporter assesses CRITICAL urgency');
    console.log('  3. ChargerDisableAgent autonomously disables charger');
    console.log('  4. ChangeAvailability(Inoperative) command sent');
    console.log('  5. CommandVerificationObserver verifies execution');
    break;
  case 'over-current':
    console.log('  1. Monitor signal: DEGRADED → DEFECTIVE (if persistent)');
    console.log('  2. Reporter: WARNING → CRITICAL');
    console.log('  3. Agent may reduce power or disable charger');
    break;
  case 'over-temperature':
    console.log('  1. Thermal monitor: DEGRADED → DEFECTIVE');
    console.log('  2. Reporter: CRITICAL (safety hazard)');
    console.log('  3. Agent: Immediate disable (prevent fire)');
    break;
  case 'connector-lock':
    console.log('  1. Connector monitor: DEFECTIVE');
    console.log('  2. If transaction active: TransactionStopAgent acts');
    console.log('  3. RemoteStopTransaction sent');
    break;
}

console.log('');
console.log('Connecting to Central System...');

// Connect WebSocket
const ws = new WebSocket(endpoint, ['ocpp1.6']);

let messageId = 1;

ws.on('open', () => {
  console.log('✓ Connected to Central System');
  console.log('');

  // Step 1: Send BootNotification
  console.log('[1/3] Sending BootNotification...');
  const bootNotification = [
    2,  // CALL
    String(messageId++),
    'BootNotification',
    {
      chargePointVendor: 'FailureSimulator',
      chargePointModel: 'TestSim-1.0',
      chargePointSerialNumber: CHARGER_ID,
      firmwareVersion: '1.0.0'
    }
  ];
  ws.send(JSON.stringify(bootNotification));

  // Step 2: Wait for response, then send failure
  setTimeout(() => {
    console.log(`[2/3] Simulating ${SCENARIO} (${errorCode})...`);

    const statusNotification = [
      2,  // CALL
      String(messageId++),
      'StatusNotification',
      {
        connectorId: 1,
        errorCode: errorCode,
        status: 'Faulted',
        timestamp: new Date().toISOString(),
        info: `Simulated ${SCENARIO} for testing`
      }
    ];
    ws.send(JSON.stringify(statusNotification));

    console.log('✓ Failure signal sent');
    console.log('');
    console.log('🔍 Watch Central System logs for adaptive response...');
    console.log('');

  }, 2000);

  // Step 3: Send periodic heartbeats to keep connection alive
  const heartbeatInterval = setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) {
      const heartbeat = [
        2,  // CALL
        String(messageId++),
        'Heartbeat',
        {}
      ];
      ws.send(JSON.stringify(heartbeat));
    }
  }, 30000);  // Every 30 seconds

  // Keep running for 2 minutes to observe response
  setTimeout(() => {
    console.log('[3/3] Test complete. Disconnecting...');
    clearInterval(heartbeatInterval);
    ws.close();
    process.exit(0);
  }, 120000);  // 2 minutes
});

ws.on('message', (data) => {
  try {
    const message = JSON.parse(data.toString());
    const messageType = message[0];
    const messageTypeStr = messageType === 3 ? 'CALLRESULT' :
                          messageType === 4 ? 'CALLERROR' : 'CALL';
    const action = message[2] || message[1];

    console.log(`← Received ${messageTypeStr}: ${action}`);

    // Check for ChangeAvailability command (expected response)
    if (messageType === 2 && message[2] === 'ChangeAvailability') {
      console.log('');
      console.log('🎉 SUCCESS: ChargerDisableAgent sent ChangeAvailability!');
      console.log('   Adaptive response working correctly.');
      console.log('');

      // Send acceptance response
      const response = [
        3,  // CALLRESULT
        message[1],  // Same message ID
        {
          status: 'Accepted'
        }
      ];
      ws.send(JSON.stringify(response));
      console.log('→ Sent acceptance: ChangeAvailability accepted');
    }

    // Auto-respond to other commands
    if (messageType === 2) {
      const response = [
        3,  // CALLRESULT
        message[1],  // Same message ID
        {}
      ];
      ws.send(JSON.stringify(response));
    }

  } catch (err) {
    console.error('Error parsing message:', err.message);
  }
});

ws.on('error', (err) => {
  console.error('❌ WebSocket error:', err.message);
  console.error('');
  console.error('Is the Central System running?');
  console.error('  mvn exec:java -Dexec.mainClass="io.fullerstack.ocpp.example.OcppDemo"');
  process.exit(1);
});

ws.on('close', () => {
  console.log('Disconnected from Central System');
});

// Handle Ctrl+C
process.on('SIGINT', () => {
  console.log('');
  console.log('Stopping...');
  ws.close();
  process.exit(0);
});
