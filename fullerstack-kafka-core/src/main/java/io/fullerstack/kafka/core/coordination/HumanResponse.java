package io.fullerstack.kafka.core.coordination;

import java.util.Objects;

/**
 * Represents a human's response to a decision request.
 * <p>
 * Used in SUPERVISED and ADVISORY modes where human can:
 * <ul>
 *   <li><b>APPROVE</b> - Allow action to proceed</li>
 *   <li><b>VETO</b> - Cancel action with reason</li>
 *   <li><b>PAUSE</b> - Hold action for investigation</li>
 *   <li><b>OVERRIDE</b> - Execute different action instead</li>
 *   <li><b>TIMEOUT</b> - No response within window (auto-execute in ADVISORY mode)</li>
 * </ul>
 */
public class HumanResponse {

    public enum Type {
        APPROVED,   // Human approved action
        VETOED,     // Human denied action
        PAUSED,     // Human wants to investigate first
        OVERRIDE,   // Human wants different action
        TIMEOUT     // Human didn't respond (auto-execute)
    }

    private final Type type;
    private final String userId;
    private final String reason;  // For VETO or OVERRIDE

    private HumanResponse(Type type, String userId, String reason) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.userId = userId;  // May be null for TIMEOUT
        this.reason = reason;  // May be null for APPROVED
    }

    /**
     * Human approved the action.
     */
    public static HumanResponse approved(String userId) {
        return new HumanResponse(Type.APPROVED, userId, null);
    }

    /**
     * Human vetoed (denied) the action.
     */
    public static HumanResponse vetoed(String userId, String reason) {
        return new HumanResponse(Type.VETOED, userId, reason);
    }

    /**
     * Human wants to pause and investigate.
     */
    public static HumanResponse paused(String userId) {
        return new HumanResponse(Type.PAUSED, userId, null);
    }

    /**
     * Human wants to execute a different action.
     */
    public static HumanResponse override(String userId, String alternativeAction) {
        return new HumanResponse(Type.OVERRIDE, userId, alternativeAction);
    }

    /**
     * Human didn't respond within timeout window.
     */
    public static HumanResponse timeout() {
        return new HumanResponse(Type.TIMEOUT, null, "No response within timeout");
    }

    public Type type() {
        return type;
    }

    public String userId() {
        return userId;
    }

    public String reason() {
        return reason;
    }

    public boolean approved() {
        return type == Type.APPROVED || type == Type.TIMEOUT;  // Timeout = implicit approval in ADVISORY mode
    }

    public boolean vetoed() {
        return type == Type.VETOED;
    }

    public boolean paused() {
        return type == Type.PAUSED;
    }

    public boolean override() {
        return type == Type.OVERRIDE;
    }

    @Override
    public String toString() {
        return String.format("HumanResponse{type=%s, user=%s, reason=%s}", type, userId, reason);
    }
}
