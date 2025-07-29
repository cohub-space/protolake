package io.vdp.protolake.api;

import com.google.longrunning.*;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.vdp.protolake.operation.InMemoryOperationManager;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Implementation of the standard Google Long Running Operations service.
 * 
 * <p>This service provides a unified interface for managing operations from
 * both LakeService and BundleService. It follows the AIP-151 specification
 * for long-running operations.</p>
 * 
 * <p>Operation names follow the pattern:
 * <ul>
 *   <li>Lake operations: {@code lakes/{lake}/operations/{uuid}}</li>
 *   <li>Bundle operations: {@code lakes/{lake}/bundles/{bundle}/operations/{uuid}}</li>
 * </ul>
 * </p>
 */
@GrpcService
public class OperationsServiceImpl extends OperationsGrpc.OperationsImplBase {
    private static final Logger LOG = Logger.getLogger(OperationsServiceImpl.class);
    
    @Inject
    InMemoryOperationManager operationManager;
    
    /**
     * Gets the latest state of a long-running operation.
     * 
     * <p>Clients can use this method to poll the operation result by repeatedly
     * calling it until the operation is done.</p>
     */
    @Override
    public void getOperation(GetOperationRequest request, StreamObserver<Operation> responseObserver) {
        try {
            String operationName = request.getName();
            LOG.debugf("Getting operation: %s", operationName);
            
            Optional<Operation> operation = operationManager.getOperation(operationName);
            if (!operation.isPresent()) {
                throw Status.NOT_FOUND
                    .withDescription("Operation not found: " + operationName)
                    .asRuntimeException();
            }
            
            responseObserver.onNext(operation.get());
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to get operation");
            responseObserver.onError(e);
        }
    }
    
    /**
     * Lists operations that match the specified filter.
     * 
     * <p>Useful for finding all operations for a specific resource or
     * operations in a particular state.</p>
     */
    @Override
    public void listOperations(ListOperationsRequest request, StreamObserver<ListOperationsResponse> responseObserver) {
        try {
            LOG.debugf("Listing operations with filter: %s", request.getFilter());
            
            List<Operation> operations = operationManager.listOperations(
                request.getFilter(),
                request.getPageSize(),
                request.getPageToken()
            );
            
            ListOperationsResponse response = ListOperationsResponse.newBuilder()
                .addAllOperations(operations)
                .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to list operations");
            responseObserver.onError(e);
        }
    }
    
    /**
     * Starts asynchronous cancellation on a long-running operation.
     * 
     * <p>The server makes a best effort to cancel the operation, but success is not
     * guaranteed. Clients can use GetOperation to check whether the cancellation
     * succeeded.</p>
     */
    @Override
    public void cancelOperation(CancelOperationRequest request, StreamObserver<Empty> responseObserver) {
        try {
            String operationName = request.getName();
            LOG.infof("Cancelling operation: %s", operationName);
            
            boolean cancelled = operationManager.cancelOperation(operationName);
            if (!cancelled) {
                LOG.warnf("Operation not found or already done: %s", operationName);
            }
            
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to cancel operation");
            responseObserver.onError(e);
        }
    }
    
    /**
     * Deletes a long-running operation.
     * 
     * <p>This method indicates that the client is no longer interested in the
     * operation result. It does not cancel the operation.</p>
     */
    @Override
    public void deleteOperation(DeleteOperationRequest request, StreamObserver<Empty> responseObserver) {
        try {
            String operationName = request.getName();
            LOG.infof("Deleting operation: %s", operationName);
            
            boolean deleted = operationManager.deleteOperation(operationName);
            if (!deleted) {
                throw Status.NOT_FOUND
                    .withDescription("Operation not found: " + operationName)
                    .asRuntimeException();
            }
            
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete operation");
            responseObserver.onError(e);
        }
    }
    
    /**
     * Waits until the specified long-running operation is done.
     * 
     * <p>Not implemented in this version - clients should use GetOperation polling.</p>
     */
    @Override
    public void waitOperation(WaitOperationRequest request, StreamObserver<Operation> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED
            .withDescription("WaitOperation is not implemented. Use GetOperation for polling.")
            .asRuntimeException());
    }
}
