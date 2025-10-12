package io.fullerstack.substrates.container;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.CortexRuntime;
import io.fullerstack.substrates.name.NameImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ContainerImpl verifying collection management behavior.
 *
 * <p>Based on Humainary article: https://humainary.io/blog/observability-x-containers/
 * "A Container is a collection of Conduits of the same emittance data type"
 */
class ContainerImplTest {
    private Cortex cortex;
    private Circuit circuit;

    @AfterEach
    void cleanup() {
        if (circuit != null) {
            try {
                circuit.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Test
    void shouldCreateContainerViaCircuit() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Container<Pool<Pipe<String>>, Source<String>> container =
            circuit.container(cortex.name("test"), Composer.pipe());

        assertThat((Object) container).isNotNull();
        assertThat((Object) container.subject()).isNotNull();
        assertThat(container.subject().type()).isEqualTo(Subject.Type.CONTAINER);
    }

    @Test
    void shouldCacheContainerBySameName() {
        // Test that Circuit caches Containers by name (like Clock and Conduit)
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Name containerName = cortex.name("metrics");

        // Call container() twice with the same name
        Container<Pool<Pipe<Long>>, Source<Long>> container1 =
            circuit.container(containerName, Composer.pipe());
        Container<Pool<Pipe<Long>>, Source<Long>> container2 =
            circuit.container(containerName, Composer.pipe());

        // Should return the SAME Container instance (cached)
        assertThat((Object) container1).isSameAs(container2);
    }

    @Test
    void shouldCreateDifferentContainersForDifferentNames() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        // Create containers with different names
        Container<Pool<Pipe<String>>, Source<String>> orders =
            circuit.container(cortex.name("orders"), Composer.pipe());
        Container<Pool<Pipe<String>>, Source<String>> trades =
            circuit.container(cortex.name("trades"), Composer.pipe());

        // Different names should create different Container instances
        assertThat((Object) orders).isNotSameAs(trades);
    }

    @Test
    void shouldCacheContainerWithDifferentComposers() {
        // Like Conduit, Container should be cached by (Name, Composer type)
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Name containerName = cortex.name("data");

        // Same name, different composers
        Container<Pool<Pipe<Long>>, Source<Long>> pipeContainer =
            circuit.container(containerName, Composer.pipe());
        Container<Pool<Channel<Long>>, Source<Long>> channelContainer =
            circuit.container(containerName, Composer.channel());

        // Different composers should create different Container instances
        assertThat((Object) pipeContainer).isNotSameAs(channelContainer);

        // Calling again with same name + same composer should return cached instance
        Container<Pool<Pipe<Long>>, Source<Long>> pipeContainer2 =
            circuit.container(containerName, Composer.pipe());
        assertThat((Object) pipeContainer).isSameAs(pipeContainer2);
    }

    @Test
    void shouldCreateSeparateConduitsPerName() {
        // This is the KEY test for the article's requirement
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Container<Pool<Pipe<Long>>, Source<Long>> container =
            circuit.container(cortex.name("metrics"), Composer.pipe());

        // Get Pools for different names
        Pool<Pipe<Long>> applPool = container.get(cortex.name("APPL"));
        Pool<Pipe<Long>> msftPool = container.get(cortex.name("MSFT"));

        // These should be DIFFERENT Conduits (not the same object)
        assertThat((Object) applPool).isNotNull();
        assertThat((Object) msftPool).isNotNull();
        assertThat((Object) applPool).isNotSameAs(msftPool);
    }

    @Test
    void shouldCacheSameConduitForSameName() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Container<Pool<Pipe<String>>, Source<String>> container =
            circuit.container(cortex.name("cache-test"), Composer.pipe());

        // Get same name twice
        Pool<Pipe<String>> pool1 = container.get(cortex.name("APPL"));
        Pool<Pipe<String>> pool2 = container.get(cortex.name("APPL"));

        // Should be same Conduit (cached)
        assertThat((Object) pool1).isSameAs(pool2);
    }

    @Test
    void shouldSupportNestedAccessPattern() {
        // Article's example pattern:
        // container.get(name("APPL")).get(BUY).emit(order)
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Container<Pool<Pipe<String>>, Source<String>> container =
            circuit.container(cortex.name("orders"), Composer.pipe());

        // Get Conduit for APPL
        Pool<Pipe<String>> applConduit = container.get(cortex.name("APPL"));

        // Get BUY Pipe from APPL Conduit
        Pipe<String> buyPipe = applConduit.get(cortex.name("BUY"));

        // Emit order (should not throw)
        assertThat((Object) buyPipe).isNotNull();
        buyPipe.emit("Order{symbol=APPL, action=BUY, quantity=100}");
    }

    @Test
    void shouldSupportMultipleConduitsWithEmissions() throws Exception {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Container<Pool<Pipe<String>>, Source<String>> container =
            circuit.container(cortex.name("stocks"), Composer.pipe());

        // APPL trades
        container.get(cortex.name("APPL")).get(cortex.name("BUY")).emit("APPL-BUY-100");
        container.get(cortex.name("APPL")).get(cortex.name("SELL")).emit("APPL-SELL-50");

        // MSFT trades (different Conduit)
        container.get(cortex.name("MSFT")).get(cortex.name("BUY")).emit("MSFT-BUY-200");
        container.get(cortex.name("MSFT")).get(cortex.name("SELL")).emit("MSFT-SELL-100");

        // Verify we have 2 separate Conduits
        ContainerImpl<Pipe<String>, String> containerImpl = (ContainerImpl<Pipe<String>, String>) container;
        assertThat(containerImpl.getConduits()).hasSize(2);
        assertThat(containerImpl.getConduits()).containsKeys(
            cortex.name("APPL"),
            cortex.name("MSFT")
        );
    }

    @Test
    void shouldProvideSourceForSubscriptions() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Container<Pool<Pipe<Integer>>, Source<Integer>> container =
            circuit.container(cortex.name("sensors"), Composer.pipe());

        Source<Source<Integer>> containerSource = container.source();

        assertThat((Object) containerSource).isNotNull();
        assertThat((Object) containerSource.subject()).isNotNull();
    }

    @Test
    void shouldUseHierarchicalNamesForConduits() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Container<Pool<Pipe<String>>, Source<String>> container =
            circuit.container(cortex.name("parent"), Composer.pipe());

        // Access creates Conduit with hierarchical name
        container.get(cortex.name("child"));

        // Verify Conduit was created
        ContainerImpl<Pipe<String>, String> containerImpl = (ContainerImpl<Pipe<String>, String>) container;
        assertThat(containerImpl.getConduits()).hasSize(1);
    }

    @Test
    void shouldRequireNonNullName() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Container<Pool<Pipe<String>>, Source<String>> container =
            circuit.container(cortex.name("test"), Composer.pipe());

        Name nullName = null;
        assertThatThrownBy(() -> container.get(nullName))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Conduit name cannot be null");
    }

    @Test
    void shouldCloseAllManagedConduits() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        Container<Pool<Pipe<String>>, Source<String>> container =
            circuit.container(cortex.name("closeable"), Composer.pipe());

        // Create multiple Conduits
        container.get(cortex.name("conduit1"));
        container.get(cortex.name("conduit2"));
        container.get(cortex.name("conduit3"));

        // Close container (should close all Conduits)
        container.close();

        // Verify all Conduits were cleared
        ContainerImpl<Pipe<String>, String> containerImpl = (ContainerImpl<Pipe<String>, String>) container;
        assertThat(containerImpl.getConduits()).isEmpty();
    }

    @Test
    void shouldHandleDifferentEmissionTypes() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        // String container
        Container<Pool<Pipe<String>>, Source<String>> stringContainer =
            circuit.container(cortex.name("strings"), Composer.pipe());

        // Long container
        Container<Pool<Pipe<Long>>, Source<Long>> longContainer =
            circuit.container(cortex.name("longs"), Composer.pipe());

        // Both should work independently
        stringContainer.get(cortex.name("A")).get(cortex.name("1")).emit("test");
        longContainer.get(cortex.name("B")).get(cortex.name("2")).emit(42L);

        // Verify separate containers
        assertThat((Object) stringContainer).isNotSameAs(longContainer);
    }

    @Test
    void shouldSupportArticleStockTradingExample() {
        // This test directly implements the article's stock trading example
        cortex = new CortexRuntime();
        circuit = cortex.circuit();

        // Create orders container
        Container<Pool<Pipe<String>>, Source<String>> container =
            circuit.container(cortex.name("ORDERS"), Composer.pipe());

        // Article's pattern: container.get(name("APPL")).get(BUY).emit(order)
        Pool<Pipe<String>> applConduit = container.get(cortex.name("APPL"));
        Pipe<String> buyPipe = applConduit.get(cortex.name("BUY"));
        buyPipe.emit("Order{symbol=APPL, quantity=200, price=1000000}");

        // Different stock - different Conduit
        Pool<Pipe<String>> msftConduit = container.get(cortex.name("MSFT"));
        assertThat((Object) msftConduit).isNotSameAs(applConduit);

        Pipe<String> sellPipe = msftConduit.get(cortex.name("SELL"));
        sellPipe.emit("Order{symbol=MSFT, quantity=100, price=500000}");

        // Verify both Conduits exist
        ContainerImpl<Pipe<String>, String> containerImpl = (ContainerImpl<Pipe<String>, String>) container;
        assertThat(containerImpl.getConduits()).hasSize(2);
    }
}
