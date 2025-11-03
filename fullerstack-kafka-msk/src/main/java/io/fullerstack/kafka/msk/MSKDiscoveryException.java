package io.fullerstack.kafka.msk;

/**
 * Exception thrown when MSK cluster discovery fails.
 */
public class MSKDiscoveryException extends RuntimeException {

    public MSKDiscoveryException(String message) {
        super(message);
    }

    public MSKDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
