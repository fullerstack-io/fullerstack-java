package io.fullerstack.kafka.runtime.cache;

import io.humainary.substrates.ext.serventis.ext.Caches;
import io.humainary.substrates.ext.serventis.ext.Caches.Cache;
import io.humainary.substrates.api.Substrates.*;

import java.util.function.BiConsumer;

import io.humainary.substrates.api.Substrates;
import static io.humainary.substrates.api.Substrates.*;

/**
 * Circuit for cache interaction signal monitoring (RC5 Serventis API).
 * <p>
 * Provides infrastructure for cache instrumentation using {@link Cache} instruments
 * that emit lookup/hit/miss/store/evict/expire/remove signals for cache behavior monitoring.
 * <p>
 * <b>RC5 Instrument Pattern</b>:
 * <ul>
 *   <li>Uses {@link Cache} instrument with method calls (hit(), miss(), store(), evict(), etc.)</li>
 *   <li>Signals are {@link Caches.Sign} enums representing cache operations</li>
 *   <li>Subject context in Channel, not in signals</li>
 *   <li>NO manual Signal construction needed - instruments handle it</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * CacheFlowCircuit circuit = new CacheFlowCircuit();
 * Cache userSessionCache = circuit.cacheFor("user.sessions");
 *
 * // Cache lookup operation
 * userSessionCache.lookup();
 * String session = getFromCache(userId);
 * if (session != null) {
 *     userSessionCache.hit();      // Cache hit
 * } else {
 *     userSessionCache.miss();     // Cache miss
 *     session = loadFromDb(userId);
 *     userSessionCache.store();    // Store in cache
 * }
 *
 * // Cache capacity management
 * if (cacheIsFull) {
 *     userSessionCache.evict();    // LRU eviction
 * }
 *
 * // TTL expiration
 * if (entryExpired) {
 *     userSessionCache.expire();   // TTL expiration
 * }
 *
 * circuit.close();
 * }</pre>
 *
 * <h3>Cache Lifecycle Signals:</h3>
 * <ul>
 *   <li><b>LOOKUP</b> - Attempt to retrieve entry from cache</li>
 *   <li><b>HIT</b> - Lookup succeeded, entry found</li>
 *   <li><b>MISS</b> - Lookup failed, entry not found</li>
 *   <li><b>STORE</b> - Entry added or updated in cache</li>
 *   <li><b>EVICT</b> - Automatic removal due to capacity/policy (LRU, LFU)</li>
 *   <li><b>EXPIRE</b> - Automatic removal due to TTL</li>
 *   <li><b>REMOVE</b> - Explicit invalidation/removal</li>
 * </ul>
 *
 * @see Caches
 * @see Cache
 */
public class CacheFlowCircuit implements AutoCloseable {

  
  private final Circuit circuit;
  private final Conduit < Cache, Caches.Sign > conduit;

  /**
   * Creates a new cache flow circuit.
   * <p>
   * Initializes circuit "cache.flow" with a conduit using {@link Caches#composer}.
   */
  public CacheFlowCircuit () {
    // Create circuit using static Cortex methods
    this.circuit = Substrates.cortex().circuit ( Substrates.cortex().name ( "cache.flow" ) );

    // Create conduit with Caches composer (returns Cache instruments)
    this.conduit = circuit.conduit (
      Substrates.cortex().name ( "cache-monitoring" ),
      Caches::composer
    );
  }

  /**
   * Get Cache instrument for specific cache entity.
   *
   * @param entityName cache identifier (e.g., "user.sessions", "product.catalog")
   * @return Cache instrument for emitting cache signals via method calls
   */
  public Cache cacheFor ( String entityName ) {
    return conduit.percept( Substrates.cortex().name ( entityName ) );
  }

  /**
   * Subscribe to cache signals for observability.
   *
   * @param name       subscriber name
   * @param subscriber subscriber function receiving Subject and Registrar
   */
  public void subscribe ( String name, BiConsumer < Subject < Channel < Caches.Sign > >, Registrar < Caches.Sign > > subscriber ) {
    conduit.subscribe ( Substrates.cortex().subscriber ( Substrates.cortex().name ( name ), subscriber ) );
  }

  /**
   * Closes the circuit and releases resources.
   */
  @Override
  public void close () {
    circuit.close ();
  }
}
