package io.fullerstack.kafka.demo.chaos;

import javax.management.Attribute;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.util.concurrent.CompletableFuture;

/**
 * Chaos controller for demo scenarios using JMX dynamic control.
 * <p>
 * Adjusts producer rate via JMX without restart - instant response!
 */
public class ChaosController {

    private static final String JMX_URL = "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi";
    private static final String MBEAN_NAME = "io.fullerstack.kafka.demo:type=ProducerControl,name=producer-1";

    public static void triggerScenario(String scenarioId) {
        System.out.println("🎭 Triggering scenario: " + scenarioId);

        CompletableFuture.runAsync(() -> {
            try {
                switch (scenarioId) {
                    case "buffer-overflow":
                        setProducerRate(10000); // High rate (10k msg/sec) fills 2MB buffer in ~10 seconds for visible demo
                        break;
                    case "normal-operation":
                        setProducerRate(10); // Low rate is healthy
                        break;
                    default:
                        System.out.println("⚠️  Scenario not implemented: " + scenarioId);
                }
            } catch (Exception e) {
                System.err.println("❌ Error triggering scenario: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Get real message count from producer via JMX.
     */
    public static long getMessageCount() {
        try {
            JMXServiceURL url = new JMXServiceURL(JMX_URL);
            try (JMXConnector jmxc = JMXConnectorFactory.connect(url, null)) {
                MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
                ObjectName mbeanName = new ObjectName(MBEAN_NAME);
                return (Long) mbsc.getAttribute(mbeanName, "MessagesSent");
            }
        } catch (Exception e) {
            return -1; // Error indicator
        }
    }

    /**
     * Get current producer rate via JMX.
     */
    public static int getCurrentRate() {
        try {
            JMXServiceURL url = new JMXServiceURL(JMX_URL);
            try (JMXConnector jmxc = JMXConnectorFactory.connect(url, null)) {
                MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
                ObjectName mbeanName = new ObjectName(MBEAN_NAME);
                return (Integer) mbsc.getAttribute(mbeanName, "Rate");
            }
        } catch (Exception e) {
            return -1; // Error indicator
        }
    }

    /**
     * Get buffer utilization percentage from Kafka producer JMX metrics.
     */
    public static int getBufferUtilization() {
        try {
            JMXServiceURL url = new JMXServiceURL(JMX_URL);
            try (JMXConnector jmxc = JMXConnectorFactory.connect(url, null)) {
                MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
                ObjectName metricsName = new ObjectName("kafka.producer:type=producer-metrics,client-id=producer-1");

                Double availableBytes = (Double) mbsc.getAttribute(metricsName, "buffer-available-bytes");
                Double totalBytes = (Double) mbsc.getAttribute(metricsName, "buffer-total-bytes");

                if (availableBytes != null && totalBytes != null && totalBytes > 0) {
                    double utilization = (1.0 - (availableBytes / totalBytes)) * 100.0;
                    int percent = (int) Math.round(utilization);
                    System.out.println("📊 Buffer JMX: available=" + String.format("%.1f", availableBytes/1024/1024) + "MB, total=" + String.format("%.1f", totalBytes/1024/1024) + "MB, utilization=" + percent + "%");
                    return percent;
                }
                System.err.println("⚠️  Buffer JMX returned null values");
                return -1;
            }
        } catch (Exception e) {
            System.err.println("❌ Buffer JMX error: " + e.getMessage());
            return -1; // Error indicator
        }
    }

    /**
     * Dynamically adjust producer rate via JMX - no restart needed!
     */
    private static void setProducerRate(int newRate) {
        try {
            System.out.println("🔄 Adjusting producer rate to: " + newRate + " msg/sec via JMX");

            // Connect to JMX
            JMXServiceURL url = new JMXServiceURL(JMX_URL);
            try (JMXConnector jmxc = JMXConnectorFactory.connect(url, null)) {
                MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();

                // Set Rate attribute on ProducerControl MBean
                ObjectName mbeanName = new ObjectName(MBEAN_NAME);
                Attribute rateAttr = new Attribute("Rate", newRate);
                mbsc.setAttribute(mbeanName, rateAttr);

                System.out.println("✅ Producer rate adjusted to " + newRate + " msg/sec (INSTANT - no restart!)");
            }

        } catch (Exception e) {
            System.err.println("❌ Failed to adjust producer rate via JMX: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
