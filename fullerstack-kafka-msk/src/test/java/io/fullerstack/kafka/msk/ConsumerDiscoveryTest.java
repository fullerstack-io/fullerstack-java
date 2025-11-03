package io.fullerstack.kafka.msk;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConsumerDiscovery.
 */
@ExtendWith(MockitoExtension.class)
class ConsumerDiscoveryTest {

    @Mock
    private AdminClient adminClient;

    @Mock
    private ListConsumerGroupsResult listConsumerGroupsResult;

    @Mock
    private DescribeConsumerGroupsResult describeConsumerGroupsResult;

    private ConsumerDiscovery discovery;

    @BeforeEach
    void setUp() {
        discovery = new ConsumerDiscovery(adminClient);
    }

    @Test
    void shouldDiscoverConsumerGroups() throws Exception {
        // Given: AdminClient returns 2 consumer groups
        List<ConsumerGroupListing> groupListings = List.of(
            new ConsumerGroupListing("group-1", false),
            new ConsumerGroupListing("group-2", false)
        );

        when(adminClient.listConsumerGroups()).thenReturn(listConsumerGroupsResult);
        when(listConsumerGroupsResult.all()).thenReturn(KafkaFuture.completedFuture(groupListings));

        // When: Discovering consumer groups
        List<String> groups = discovery.discoverConsumerGroups();

        // Then: Both groups discovered
        assertThat(groups).containsExactlyInAnyOrder("group-1", "group-2");

        verify(adminClient).listConsumerGroups();
    }

    @Test
    void shouldHandleEmptyConsumerGroupList() throws Exception {
        // Given: No consumer groups found
        when(adminClient.listConsumerGroups()).thenReturn(listConsumerGroupsResult);
        when(listConsumerGroupsResult.all()).thenReturn(KafkaFuture.completedFuture(List.of()));

        // When: Discovering consumer groups
        List<String> groups = discovery.discoverConsumerGroups();

        // Then: Empty list returned
        assertThat(groups).isEmpty();
    }

    @Test
    void shouldDetectActiveConsumer() throws Exception {
        // Given: Consumer group with active members
        ConsumerGroupDescription groupDescription = new ConsumerGroupDescription(
            "group-1",
            false,
            List.of(
                new MemberDescription(
                    "consumer-1",
                    Optional.of("instance-1"),
                    "client-1",
                    "localhost",
                    new MemberAssignment(java.util.Set.of())
                )
            ),
            "range",
            ConsumerGroupState.STABLE,
            new Node(0, "broker", 9092)
        );

        Map<String, KafkaFuture<ConsumerGroupDescription>> describedGroups = Map.of(
            "group-1", KafkaFuture.completedFuture(groupDescription)
        );

        when(adminClient.describeConsumerGroups(anyList())).thenReturn(describeConsumerGroupsResult);
        when(describeConsumerGroupsResult.describedGroups()).thenReturn(describedGroups);

        // When: Checking if consumer is active
        boolean active = discovery.isConsumerActive("group-1");

        // Then: Consumer is active
        assertThat(active).isTrue();

        verify(adminClient).describeConsumerGroups(List.of("group-1"));
    }

    @Test
    void shouldDetectInactiveConsumer() throws Exception {
        // Given: Consumer group with no members
        ConsumerGroupDescription groupDescription = new ConsumerGroupDescription(
            "group-1",
            false,
            List.of(),  // No members
            "range",
            ConsumerGroupState.EMPTY,
            new Node(0, "broker", 9092)
        );

        Map<String, KafkaFuture<ConsumerGroupDescription>> describedGroups = Map.of(
            "group-1", KafkaFuture.completedFuture(groupDescription)
        );

        when(adminClient.describeConsumerGroups(anyList())).thenReturn(describeConsumerGroupsResult);
        when(describeConsumerGroupsResult.describedGroups()).thenReturn(describedGroups);

        // When: Checking if consumer is active
        boolean active = discovery.isConsumerActive("group-1");

        // Then: Consumer is inactive
        assertThat(active).isFalse();
    }
}
