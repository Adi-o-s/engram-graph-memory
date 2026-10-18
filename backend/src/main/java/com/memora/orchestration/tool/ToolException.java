package com.memora.orchestration.tool;

/** A tool call that did not succeed. The kind decides whether the engine may retry the step. */
public class ToolException extends RuntimeException {

    public enum Kind {
        /** Transient: the step may be retried (bounded by its retry policy). */
        RETRYABLE,
        /** Retrying cannot help: bad arguments, missing data, a failed rule. */
        PERMANENT,
        /** The agent is not allowed to make this call. Never retried. */
        DENIED
    }

    private final Kind kind;

    public ToolException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static ToolException permanent(String message) {
        return new ToolException(Kind.PERMANENT, message);
    }

    public static ToolException denied(String message) {
        return new ToolException(Kind.DENIED, message);
    }

    public static ToolException retryable(String message) {
        return new ToolException(Kind.RETRYABLE, message);
    }
}
