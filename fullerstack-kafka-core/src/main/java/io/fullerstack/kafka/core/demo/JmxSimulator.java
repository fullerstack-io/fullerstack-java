package io.fullerstack.kafka.core.demo;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simulates JMX MBeans for Kafka producer, consumer, and broker metrics.
 * <p>
 * Provides realistic metric values that evolve over time based on simulated cluster state.
 * The real Observer classes (ProducerBufferMonitor, ProducerSendObserver, etc.) will
 * query these simulated MBeans just like they would query real Kafka JMX endpoints.
 */
public class JmxSimulator implements AutoCloseable {

    private final MBeanServer mbeanServer;
    private final KafkaClusterSimulator cluster;
    private final Random random = new Random();

    // Registered MBeans (for cleanup)
    private final Map<ObjectName, DynamicMBean> registeredBeans = new ConcurrentHashMap<>();

    public JmxSimulator(KafkaClusterSimulator cluster) {
        this.mbeanServer = ManagementFactory.getPlatformMBeanServer();
        this.cluster = cluster;
    }

    /**
     * Registers simulated JMX MBeans for producers, consumers, and brokers.
     */
    public void registerAll() throws Exception {
        System.out.println("\n🔌 Registering simulated JMX MBeans...");

        // Register producer MBeans
        registerProducerMBean("producer-1");
        registerProducerMBean("producer-2");
        registerProducerMBean("producer-3");
        registerProducerMBean("producer-4");

        // Register broker MBeans
        registerBrokerMBean("broker-1");
        registerBrokerMBean("broker-2");

        System.out.println("✅ JMX MBeans registered");
    }

    /**
     * Registers producer metrics MBean.
     */
    private void registerProducerMBean(String producerId) throws Exception {
        ObjectName objectName = new ObjectName(
            String.format("kafka.producer:type=producer-metrics,client-id=%s", producerId)
        );

        ProducerMetricsMBean mbean = new ProducerMetricsMBean(cluster, producerId);
        registerMBean(objectName, mbean);
        System.out.printf("   ✓ Registered: %s%n", objectName);
    }

    /**
     * Registers broker metrics MBean.
     */
    private void registerBrokerMBean(String brokerId) throws Exception {
        // JVM metrics
        ObjectName jvmName = new ObjectName(
            String.format("kafka.server:type=app-info,broker-id=%s", brokerId)
        );
        BrokerJvmMetricsMBean jvmMBean = new BrokerJvmMetricsMBean(cluster, brokerId);
        registerMBean(jvmName, jvmMBean);
        System.out.printf("   ✓ Registered: %s%n", jvmName);
    }

    private void registerMBean(ObjectName objectName, DynamicMBean mbean) throws Exception {
        mbeanServer.registerMBean(mbean, objectName);
        registeredBeans.put(objectName, mbean);
    }

    @Override
    public void close() {
        System.out.println("\n🧹 Unregistering JMX MBeans...");
        for (ObjectName objectName : registeredBeans.keySet()) {
            try {
                mbeanServer.unregisterMBean(objectName);
                System.out.printf("   ✓ Unregistered: %s%n", objectName);
            } catch (Exception e) {
                System.err.printf("   ✗ Failed to unregister %s: %s%n", objectName, e.getMessage());
            }
        }
        registeredBeans.clear();
    }

    // ========================================
    // Producer Metrics MBean
    // ========================================

    /**
     * Simulated producer metrics matching kafka.producer:type=producer-metrics MBean.
     */
    private class ProducerMetricsMBean extends BaseMBean {
        private final String producerId;
        private final AtomicLong sendTotal = new AtomicLong(0);
        private final AtomicLong errorTotal = new AtomicLong(0);
        private final AtomicLong retryTotal = new AtomicLong(0);
        private final AtomicLong exhaustedTotal = new AtomicLong(0);

        public ProducerMetricsMBean(KafkaClusterSimulator cluster, String producerId) {
            this.producerId = producerId;
        }

        @Override
        protected Object getAttributeValue(String attributeName) {
            ProducerSimulator producer = cluster.getProducer(producerId);
            if (producer == null) {
                return 0;
            }

            return switch (attributeName) {
                // Buffer metrics
                case "buffer-available-bytes" -> {
                    long bufferSize = 32 * 1024 * 1024L; // 32MB
                    double utilization = producer.isThrottled() ? 0.40 : 0.75;
                    yield (long)(bufferSize * (1.0 - utilization));
                }
                case "buffer-total-bytes" -> 32 * 1024 * 1024L; // 32MB
                case "buffer-exhausted-total" -> exhaustedTotal.get();
                case "batch-size-avg" -> producer.getCurrentRate() * 1000.0; // bytes
                case "records-per-request-avg" -> producer.getCurrentRate() / 10.0;

                // Send metrics
                case "record-send-rate" -> (double) producer.getCurrentRate();
                case "record-send-total" -> sendTotal.addAndGet(producer.getCurrentRate());
                case "record-error-rate" -> producer.isCircuitOpen() ? 10.0 : (producer.isThrottled() ? 0.5 : 0.0);
                case "record-retry-rate" -> producer.isThrottled() ? 2.0 : 0.0;
                case "request-latency-avg" -> {
                    if (producer.isCircuitOpen()) yield 500.0; // High latency
                    if (producer.isThrottled()) yield 80.0;
                    yield 35.0 + (random.nextDouble() * 20.0); // 35-55ms
                }

                // Connection metrics
                case "connection-count" -> producer.isCircuitOpen() ? 0 : 3;
                case "connection-creation-rate" -> producer.isCircuitOpen() ? 0.5 : 0.01;
                case "io-wait-time-ns-avg" -> {
                    if (producer.isCircuitOpen()) yield 50_000_000.0; // 50ms
                    yield 5_000_000.0 + (random.nextDouble() * 2_000_000.0); // 5-7ms
                }

                default -> 0;
            };
        }

        @Override
        protected MBeanAttributeInfo[] createAttributeInfo() {
            return new MBeanAttributeInfo[]{
                attr("buffer-available-bytes", "long"),
                attr("buffer-total-bytes", "long"),
                attr("buffer-exhausted-total", "long"),
                attr("batch-size-avg", "double"),
                attr("records-per-request-avg", "double"),
                attr("record-send-rate", "double"),
                attr("record-send-total", "long"),
                attr("record-error-rate", "double"),
                attr("record-retry-rate", "double"),
                attr("request-latency-avg", "double"),
                attr("connection-count", "int"),
                attr("connection-creation-rate", "double"),
                attr("io-wait-time-ns-avg", "double")
            };
        }
    }

    // ========================================
    // Broker JVM Metrics MBean
    // ========================================

    /**
     * Simulated broker JVM metrics.
     */
    private class BrokerJvmMetricsMBean extends BaseMBean {
        private final String brokerId;

        public BrokerJvmMetricsMBean(KafkaClusterSimulator cluster, String brokerId) {
            this.brokerId = brokerId;
        }

        @Override
        protected Object getAttributeValue(String attributeName) {
            BrokerSimulator broker = cluster.getBroker(brokerId);
            if (broker == null) {
                return 0;
            }

            return switch (attributeName) {
                case "heap-memory-used" -> {
                    long maxHeap = 2L * 1024 * 1024 * 1024; // 2GB
                    yield (long)(maxHeap * broker.getMemoryUsage());
                }
                case "heap-memory-max" -> 2L * 1024 * 1024 * 1024; // 2GB
                case "non-heap-memory-used" -> 256 * 1024 * 1024L; // 256MB
                case "gc-collection-count" -> (long)(Math.random() * 100);
                case "gc-collection-time" -> (long)(Math.random() * 1000);
                default -> 0;
            };
        }

        @Override
        protected MBeanAttributeInfo[] createAttributeInfo() {
            return new MBeanAttributeInfo[]{
                attr("heap-memory-used", "long"),
                attr("heap-memory-max", "long"),
                attr("non-heap-memory-used", "long"),
                attr("gc-collection-count", "long"),
                attr("gc-collection-time", "long")
            };
        }
    }

    // ========================================
    // Base MBean Implementation
    // ========================================

    /**
     * Base class for simulated DynamicMBeans.
     */
    private abstract static class BaseMBean implements DynamicMBean {

        protected abstract Object getAttributeValue(String attributeName);
        protected abstract MBeanAttributeInfo[] createAttributeInfo();

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            Object value = getAttributeValue(attribute);
            if (value == null) {
                throw new AttributeNotFoundException("Attribute " + attribute + " not found");
            }
            return value;
        }

        @Override
        public void setAttribute(Attribute attribute) {
            // Read-only MBean
            throw new UnsupportedOperationException("Attributes are read-only");
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList list = new AttributeList();
            for (String attr : attributes) {
                try {
                    list.add(new Attribute(attr, getAttribute(attr)));
                } catch (AttributeNotFoundException e) {
                    // Skip missing attributes
                }
            }
            return list;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList(); // Read-only
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) {
            throw new UnsupportedOperationException("Operations not supported");
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return new MBeanInfo(
                getClass().getName(),
                "Simulated Kafka MBean",
                createAttributeInfo(),
                null, // constructors
                null, // operations
                null  // notifications
            );
        }

        protected MBeanAttributeInfo attr(String name, String type) {
            String className = switch (type) {
                case "int" -> "java.lang.Integer";
                case "long" -> "java.lang.Long";
                case "double" -> "java.lang.Double";
                case "boolean" -> "java.lang.Boolean";
                default -> "java.lang.String";
            };

            return new MBeanAttributeInfo(
                name,
                className,
                name,
                true,  // readable
                false, // writable
                false  // is getter
            );
        }
    }
}
