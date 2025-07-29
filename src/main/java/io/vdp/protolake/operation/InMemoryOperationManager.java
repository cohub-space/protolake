package io.vdp.protolake.operation;

import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.rpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * In-memory storage and management for long-running operations.
 * 
 * <p>This manager provides thread-safe operation state management with TTL-based cleanup.
 * It enforces resource-level concurrency control, ensuring only one active operation
 * per resource at a time.</p>
 * 
 * <p>Operations are automatically cleaned up after the configured TTL (default 10 minutes)
 * once they reach a terminal state (COMPLETED, FAILED, CANCELLED).</p>
 *
 * TODO: A bunch of build related operation logic is still here in the generic operation manager.
 * TODO: move it out in the future.
 */
@ApplicationScoped
public class InMemoryOperationManager {
    private static final Logger LOG = Logger.getLogger(InMemoryOperationManager.class);
    
    @ConfigProperty(name = "protolake.operation.ttl-minutes", defaultValue = "10")
    int ttlMinutes;
    
    // Main storage for operations - stores the metadata directly
    private final ConcurrentHashMap<String, BuildOperationMetadata> operations = new ConcurrentHashMap<>();
    
    // Tracks cancellation tokens for active operations
    private final ConcurrentHashMap<String, CancellationToken> cancellationTokens = new ConcurrentHashMap<>();
    
    // Tracks completion time for TTL cleanup
    private final ConcurrentHashMap<String, Instant> completionTimes = new ConcurrentHashMap<>();
    
    // Stores the final response for completed operations
    private final ConcurrentHashMap<String, Any> operationResponses = new ConcurrentHashMap<>();
    
    // Index for active operations by resource name for concurrency control
    private final ConcurrentHashMap<String, String> activeOperationsByResource = new ConcurrentHashMap<>();
    
    // Scheduled executor for TTL cleanup
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "operation-cleanup");
            t.setDaemon(true);
            return t;
        }
    );
    
    public InMemoryOperationManager() {
        // Schedule cleanup every minute
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredOperations, 1, 1, TimeUnit.MINUTES);
    }
    
    /**
     * Creates a new operation or returns ABORTED if resource is busy.
     * 
     * @param operationName the full operation name (e.g., "lakes/my-lake/operations/uuid")
     * @param resourceName the resource name (e.g., "lakes/my-lake/bundles/my-bundle")
     * @param metadata the initial BuildOperationMetadata
     * @param cancellationToken token for cancellation
     * @return the created operation
     */
    public Operation createOperation(String operationName, String resourceName, 
                                   BuildOperationMetadata metadata, CancellationToken cancellationToken) {
        // Check for existing active operation on this resource
        String existingOpId = activeOperationsByResource.get(resourceName);
        if (existingOpId != null && isOperationActive(existingOpId)) {
            // Return ABORTED operation
            return Operation.newBuilder()
                .setName(operationName)
                .setDone(true)
                .setError(Status.newBuilder()
                    .setCode(io.grpc.Status.Code.ABORTED.value())
                    .setMessage("Resource " + resourceName + 
                               " already has an active build operation: " + existingOpId)
                    .build())
                .build();
        }
        
        // Store the operation
        operations.put(operationName, metadata);
        cancellationTokens.put(operationName, cancellationToken);
        activeOperationsByResource.put(resourceName, operationName);
        
        LOG.infof("Created operation %s for resource %s", operationName, resourceName);
        
        // Return the initial operation
        return Operation.newBuilder()
            .setName(operationName)
            .setMetadata(Any.pack(metadata))
            .setDone(false)
            .build();
    }
    
    /**
     * Updates the metadata for an operation.
     * 
     * @param operationName the operation name
     * @param metadata the updated metadata
     */
    public void updateMetadata(String operationName, BuildOperationMetadata metadata) {
        operations.put(operationName, metadata);
    }
    
    /**
     * Gets an operation by name.
     * 
     * @param operationName the operation name
     * @return the operation wrapped in Optional, empty if not found
     */
    public Optional<Operation> getOperation(String operationName) {
        BuildOperationMetadata metadata = operations.get(operationName);
        if (metadata == null) {
            return Optional.empty();
        }
        
        // Check if operation is complete
        boolean isDone = isOperationDone(metadata);
        Operation.Builder builder = Operation.newBuilder()
            .setName(operationName)
            .setMetadata(Any.pack(metadata))
            .setDone(isDone);
        
        if (isDone) {
            // If done, check if it failed or succeeded
            if (metadata.getCurrentPhase() == OperationPhase.FAILED) {
                String errorMessage = getOperationError(metadata);
                builder.setError(Status.newBuilder()
                    .setCode(io.grpc.Status.Code.INTERNAL.value())
                    .setMessage(errorMessage)
                    .build());
            } else if (metadata.getCurrentPhase() == OperationPhase.CANCELLED) {
                builder.setError(Status.newBuilder()
                    .setCode(io.grpc.Status.Code.CANCELLED.value())
                    .setMessage("Operation cancelled by user")
                    .build());
            } else {
                // Success - get the stored response
                Any response = operationResponses.get(operationName);
                if (response != null) {
                    builder.setResponse(response);
                }
            }
        }
        
        return Optional.of(builder.build());
    }
    
    /**
     * Lists operations with optional filtering.
     * 
     * @param filter optional filter (e.g., "resource=lakes/my-lake")
     * @param pageSize maximum results
     * @param pageToken pagination token
     * @return list of operations
     */
    public List<Operation> listOperations(String filter, int pageSize, String pageToken) {
        // TODO: Simple implementation - production would need proper filtering/pagination
        return operations.entrySet().stream()
            .map(entry -> getOperation(entry.getKey()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .limit(pageSize > 0 ? pageSize : Integer.MAX_VALUE)
            .collect(Collectors.toList());
    }
    
    /**
     * Cancels an operation.
     * 
     * @param operationName the operation name
     * @return true if cancelled, false if not found or already done
     */
    public boolean cancelOperation(String operationName) {
        BuildOperationMetadata metadata = operations.get(operationName);
        CancellationToken token = cancellationTokens.get(operationName);
        
        if (metadata != null && token != null && !isOperationDone(metadata)) {
            // Cancel the token
            token.cancel();
            
            // Update metadata to cancelled state
            BuildOperationMetadata cancelled = metadata.toBuilder()
                .setCurrentPhase(OperationPhase.CANCELLED)
                .build();
            operations.put(operationName, cancelled);
            
            // Mark as complete
            markOperationComplete(operationName, extractResourceName(metadata));
            
            LOG.infof("Cancelled operation %s", operationName);
            return true;
        }
        return false;
    }
    
    /**
     * Completes an operation successfully.
     * 
     * @param operationName the operation name
     * @param response the final response (e.g., BuildResponse)
     */
    public void completeOperation(String operationName, com.google.protobuf.Message response) {
        BuildOperationMetadata metadata = operations.get(operationName);
        if (metadata != null) {
            // Update to completed state
            BuildOperationMetadata completed = metadata.toBuilder()
                .setCurrentPhase(OperationPhase.COMPLETED)
                .build();
            operations.put(operationName, completed);
            
            // Store the response
            operationResponses.put(operationName, Any.pack(response));
            
            // Mark as complete
            markOperationComplete(operationName, extractResourceName(metadata));
        }
    }
    
    /**
     * Fails an operation.
     * 
     * @param operationName the operation name
     * @param error the error message
     */
    public void failOperation(String operationName, String error) {
        BuildOperationMetadata metadata = operations.get(operationName);
        if (metadata != null) {
            // Update to failed state
            BuildOperationMetadata failed = metadata.toBuilder()
                .setCurrentPhase(OperationPhase.FAILED)
                .build();
            operations.put(operationName, failed);
            
            // Mark as complete
            markOperationComplete(operationName, extractResourceName(metadata));
        }
    }
    
    /**
     * Gets the metadata for an operation directly.
     * Used by BuildOrchestrator to retrieve current state.
     *
     * TODO: we shouldn't be returning null here, rather return optional or throw exception.
     * @param operationName the operation name
     * @return the operation metadata, or null if not found
     */
    public BuildOperationMetadata get(String operationName) {
        return operations.get(operationName);
    }
    
    /**
     * Deletes an operation from storage.
     * 
     * @param operationName the operation name
     * @return true if deleted, false if not found
     */
    public boolean deleteOperation(String operationName) {
        BuildOperationMetadata metadata = operations.remove(operationName);
        cancellationTokens.remove(operationName);
        completionTimes.remove(operationName);
        operationResponses.remove(operationName);
        
        if (metadata != null) {
            String resourceName = extractResourceName(metadata);
            activeOperationsByResource.remove(resourceName, operationName);
            LOG.infof("Deleted operation %s", operationName);
            return true;
        }
        return false;
    }
    
    // Helper methods
    
    private boolean isOperationActive(String operationName) {
        BuildOperationMetadata metadata = operations.get(operationName);
        return metadata != null && !isOperationDone(metadata);
    }
    
    private boolean isOperationDone(BuildOperationMetadata metadata) {
        OperationPhase phase = metadata.getCurrentPhase();
        return phase == OperationPhase.COMPLETED || 
               phase == OperationPhase.FAILED || 
               phase == OperationPhase.CANCELLED;
    }
    
    private String getOperationError(BuildOperationMetadata metadata) {
        // Check phase statuses for errors
        PhaseStatuses phases = metadata.getPhaseStatuses();
        if (phases.hasGazelle() && phases.getGazelle().getStatus() == PhaseStatus.Status.FAILED) {
            return phases.getGazelle().getErrorMessage();
        }
        if (phases.hasValidation() && phases.getValidation().getStatus() == PhaseStatus.Status.FAILED) {
            return phases.getValidation().getErrorMessage();
        }
        if (phases.hasBuild() && phases.getBuild().getStatus() == PhaseStatus.Status.FAILED) {
            return phases.getBuild().getErrorMessage();
        }
        
        // Check target builds for errors
        for (TargetBuildInfo target : metadata.getTargetBuildsMap().values()) {
            if (target.getStatus() == TargetBuildInfo.Status.FAILED && !target.getErrorMessage().isEmpty()) {
                return target.getErrorMessage();
            }
        }
        
        return "Build failed";
    }
    

    
    private String extractResourceName(BuildOperationMetadata metadata) {
        // Extract resource name from requested target
        // For "//...", resource is the lake
        // For "//bundles/foo:all", resource is the bundle
        String target = metadata.getRequestedTarget();
        if (target.equals("//...")) {
            // Lake-level build - extract from operation name
            // This is a bit hacky but works for now
            return "lakes/unknown";
        } else if (target.startsWith("//bundles/")) {
            // Bundle-level build
            String bundlePath = target.substring("//bundles/".length());
            int colonIndex = bundlePath.indexOf(':');
            if (colonIndex > 0) {
                String bundleName = bundlePath.substring(0, colonIndex);
                return "lakes/unknown/bundles/" + bundleName;
            }
        }
        return "unknown";
    }
    
    private void markOperationComplete(String operationName, String resourceName) {
        completionTimes.put(operationName, Instant.now());
        activeOperationsByResource.remove(resourceName, operationName);
    }
    
    /**
     * Cleans up expired operations based on TTL.
     */
    private void cleanupExpiredOperations() {
        Instant cutoff = Instant.now().minusSeconds(ttlMinutes * 60L);
        
        List<String> toRemove = completionTimes.entrySet().stream()
            .filter(e -> e.getValue().isBefore(cutoff))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        
        for (String operationName : toRemove) {
            deleteOperation(operationName);
        }
        
        if (!toRemove.isEmpty()) {
            LOG.debugf("Cleaned up %d expired operations", toRemove.size());
        }
    }
    
    @PreDestroy
    void shutdown() {
        LOG.info("Shutting down operation manager");
        
        // Cancel all running operations
        operations.entrySet().stream()
            .filter(e -> !isOperationDone(e.getValue()))
            .forEach(e -> {
                cancelOperation(e.getKey());
                LOG.infof("Cancelled running operation %s during shutdown", e.getKey());
            });
        
        // Shutdown cleanup executor
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
