package io.vdp.protolake.operation;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe cancellation token for cooperative cancellation of long-running operations.
 * 
 * <p>This token allows operations to be cancelled gracefully. Runners should check
 * {@link #isCancelled()} at appropriate points (e.g., between phases, between targets)
 * and stop processing if cancellation has been requested.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * CancellationToken token = new CancellationToken();
 * 
 * // In the operation runner
 * if (token.isCancelled()) {
 *     throw new CancellationException("Operation cancelled by user");
 * }
 * 
 * // To cancel
 * token.cancel();
 * }</pre>
 */
public class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Requests cancellation of the operation.
     * 
     * <p>This method is idempotent - calling it multiple times has no additional effect.</p>
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Checks if cancellation has been requested.
     * 
     * @return true if cancellation was requested, false otherwise
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Throws a CancellationException if cancellation has been requested.
     * 
     * <p>This is a convenience method for runners that want to check and throw in one call.</p>
     * 
     * @throws CancellationException if cancellation was requested
     */
    public void throwIfCancelled() throws CancellationException {
        if (isCancelled()) {
            throw new CancellationException("Operation cancelled");
        }
    }

    /**
     * Exception thrown when an operation is cancelled.
     */
    public static class CancellationException extends Exception {
        public CancellationException(String message) {
            super(message);
        }
    }
}
