package io.fullerstack.kafka.producer.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for KafkaCentralCommunicator using Testcontainers.
 */
@Testcontainers
class KafkaCentralCommunicatorTest {

    private static final String TEST_TOPIC = "test-speech-acts";

    @Container
    private static final KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    private KafkaCentralCommunicator communicator;
    private KafkaConsumer<String, String> testConsumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        String bootstrapServers = kafka.getBootstrapServers();
        communicator = new KafkaCentralCommunicator(bootstrapServers, TEST_TOPIC);
        objectMapper = new ObjectMapper();

        // Create test consumer to verify messages
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        testConsumer = new KafkaConsumer<>(props);
        testConsumer.subscribe(List.of(TEST_TOPIC));
    }

    @AfterEach
    void tearDown() {
        if (communicator != null) {
            communicator.close();
        }
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    @Test
    void shouldSendDirectiveMessage() throws Exception {
        // Given
        DirectiveMessage message = new DirectiveMessage(
            "producer-1",
            "throttle-agent",
            "SCALE_RESOURCES",
            "Buffer exhausted despite throttling",
            List.of("scale replicas", "expand disk"),
            "IMMEDIATE",
            System.currentTimeMillis()
        );

        // When
        communicator.sendDirective(message);

        // Then - consume and verify
        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        ConsumerRecord<String, String> record = records.iterator().next();
        assertThat(record.key()).isEqualTo("producer-1");

        Map<String, Object> payload = objectMapper.readValue(record.value(), Map.class);
        assertThat(payload)
            .containsEntry("speechAct", "REQUEST")
            .containsEntry("source", "producer-1")
            .containsEntry("contextAgent", "throttle-agent")
            .containsEntry("requestType", "SCALE_RESOURCES")
            .containsEntry("description", "Buffer exhausted despite throttling")
            .containsEntry("urgency", "IMMEDIATE")
            .containsKey("suggestedActions")
            .containsKey("timestamp");

        @SuppressWarnings("unchecked")
        List<String> actions = (List<String>) payload.get("suggestedActions");
        assertThat(actions).containsExactly("scale replicas", "expand disk");
    }

    @Test
    void shouldSendInformMessage() throws Exception {
        // Given
        InformMessage message = new InformMessage(
            "producer-1",
            "throttle-agent",
            "Self-regulation successful - buffer pressure resolved",
            System.currentTimeMillis()
        );

        // When
        communicator.sendInform(message);

        // Then - consume and verify
        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(10));
        assertThat(records).isNotEmpty();

        ConsumerRecord<String, String> record = records.iterator().next();
        assertThat(record.key()).isEqualTo("producer-1");

        Map<String, Object> payload = objectMapper.readValue(record.value(), Map.class);
        assertThat(payload)
            .containsEntry("speechAct", "REPORT")
            .containsEntry("source", "producer-1")
            .containsEntry("contextAgent", "throttle-agent")
            .containsEntry("information", "Self-regulation successful - buffer pressure resolved")
            .containsKey("timestamp");
    }

    @Test
    void shouldHandleMultipleMessages() throws Exception {
        // Given
        DirectiveMessage directive = new DirectiveMessage(
            "producer-1", "agent-1", "SCALE_RESOURCES", "Help needed",
            List.of("scale"), "IMMEDIATE", System.currentTimeMillis()
        );
        InformMessage inform = new InformMessage(
            "producer-1", "agent-1", "Status update", System.currentTimeMillis()
        );

        // When
        communicator.sendDirective(directive);
        communicator.sendInform(inform);

        // Then
        ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofSeconds(10));
        assertThat(records).hasSize(2);

        int requestCount = 0;
        int reportCount = 0;

        for (ConsumerRecord<String, String> record : records) {
            Map<String, Object> payload = objectMapper.readValue(record.value(), Map.class);
            String speechAct = (String) payload.get("speechAct");

            if ("REQUEST".equals(speechAct)) {
                requestCount++;
            } else if ("REPORT".equals(speechAct)) {
                reportCount++;
            }
        }

        assertThat(requestCount).isEqualTo(1);
        assertThat(reportCount).isEqualTo(1);
    }
}
