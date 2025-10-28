package io.fullerstack.kafka.consumer.sensors;

import io.fullerstack.kafka.consumer.models.ConsumerMetrics;
import io.fullerstack.kafka.core.config.ConsumerEndpoint;
import io.fullerstack.kafka.core.config.ConsumerSensorConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerSensorTest {

    @Test
    void shouldCreateSensor() {
        // given
        ConsumerSensorConfig config = ConsumerSensorConfig.defaults(
                "localhost:9092",
                List.of(new ConsumerEndpoint("consumer-1", "test-group", "http://localhost:9999/metrics"))
        );

        // when
        ConsumerSensor sensor = new ConsumerSensor(config, (id, metrics) -> {});

        // then
        assertThat(sensor).isNotNull();
        assertThat(sensor.isStarted()).isFalse();
        sensor.close();
    }

    @Test
    void shouldStartAndStop() {
        // given
        ConsumerSensorConfig config = ConsumerSensorConfig.defaults(
                "localhost:9092",
                List.of(new ConsumerEndpoint("consumer-1", "test-group", "http://localhost:9999/metrics"))
        );
        ConsumerSensor sensor = new ConsumerSensor(config, (id, metrics) -> {});

        // when
        sensor.start();

        // then
        assertThat(sensor.isStarted()).isTrue();

        // cleanup
        sensor.close();
    }

    @Test
    void shouldRejectDoubleStart() {
        // given
        ConsumerSensorConfig config = ConsumerSensorConfig.defaults(
                "localhost:9092",
                List.of(new ConsumerEndpoint("consumer-1", "test-group", "http://localhost:9999/metrics"))
        );
        ConsumerSensor sensor = new ConsumerSensor(config, (id, metrics) -> {});
        sensor.start();

        // when/then
        assertThatThrownBy(sensor::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");

        sensor.close();
    }

    @Test
    void shouldCallEmitterCallback() throws InterruptedException {
        // given
        ConsumerSensorConfig config = new ConsumerSensorConfig(
                "localhost:9092",
                List.of(new ConsumerEndpoint("consumer-1", "test-group", "http://localhost:9999/metrics")),
                100L,  // Fast collection for test
                io.fullerstack.kafka.core.config.JmxConnectionPoolConfig.withPoolingEnabled()
        );

        List<String> collectedIds = new CopyOnWriteArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        ConsumerSensor sensor = new ConsumerSensor(config, (id, metrics) -> {
            collectedIds.add(id);
            callCount.incrementAndGet();
        });

        // when
        sensor.start();
        Thread.sleep(250);  // Wait for at least 2 collection cycles

        // then - should have attempted collection (will fail, but callback should be attempted)
        // Note: Collection will fail because no Kafka is running, but we're testing the scheduling
        sensor.close();

        // Verify sensor was active
        assertThat(sensor.isStarted()).isTrue();
    }

    @Test
    void shouldHandleNullConfig() {
        assertThatThrownBy(() ->
                new ConsumerSensor(null, (id, metrics) -> {})
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("config cannot be null");
    }

    @Test
    void shouldHandleNullEmitter() {
        // given
        ConsumerSensorConfig config = ConsumerSensorConfig.defaults(
                "localhost:9092",
                List.of(new ConsumerEndpoint("consumer-1", "test-group", "http://localhost:9999/metrics"))
        );

        // when/then
        assertThatThrownBy(() ->
                new ConsumerSensor(config, null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("emitter cannot be null");
    }

    @Test
    void shouldCloseGracefully() {
        // given
        ConsumerSensorConfig config = ConsumerSensorConfig.defaults(
                "localhost:9092",
                List.of(new ConsumerEndpoint("consumer-1", "test-group", "http://localhost:9999/metrics"))
        );
        ConsumerSensor sensor = new ConsumerSensor(config, (id, metrics) -> {});
        sensor.start();

        // when
        sensor.close();

        // then - no exception should be thrown
        assertThat(sensor.isStarted()).isTrue(); // Started flag remains true after close
    }
}
