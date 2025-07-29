package io.vdp.protolake.api;

import com.google.longrunning.Operation;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.vdp.protolake.initializer.LakeInitializer;
import io.vdp.protolake.operation.CancellationToken;
import io.vdp.protolake.operation.InMemoryOperationManager;
import io.vdp.protolake.pipeline.BuildOrchestrator;
import io.vdp.protolake.storage.StorageService;
import io.vdp.protolake.storage.LakeDiscoveryService;
import io.vdp.protolake.util.LakeUtil;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * gRPC service implementation for Lake management.
 * 
 * A Lake represents a git repository containing proto bundles.
 * This service handles CRUD operations on lakes and orchestrates lake-wide builds.
 */
@GrpcService
public class LakeServiceImpl extends LakeServiceGrpc.LakeServiceImplBase {
    private static final Logger LOG = Logger.getLogger(LakeServiceImpl.class);

    @Inject
    StorageService storageService;

    @Inject
    LakeInitializer lakeInitializer;

    @Inject
    BuildOrchestrator buildOrchestrator;
    
    @Inject
    InMemoryOperationManager operationManager;
    
    @Inject
    LakeDiscoveryService lakeDiscoveryService;
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    /**
     * Creates a new lake (git repository) for storing proto definitions.
     */
    @Override
    public void createLake(CreateLakeRequest request, StreamObserver<protolake.v1.Lake> responseObserver) {
        try {
            LOG.infof("Creating lake: %s", request.getLake().getName());

            // Validate request
            if (request.getLake().getName().isEmpty()) {
                throw Status.INVALID_ARGUMENT
                    .withDescription("Lake name is required")
                    .asRuntimeException();
            }

            String lakeId = LakeUtil.extractLakeId(request.getLake().getName());

            // Check if lake already exists
            Optional<protolake.v1.Lake> existing = storageService.getLake(lakeId);
            if (existing.isPresent()) {
                throw Status.ALREADY_EXISTS
                    .withDescription("Lake already exists: " + lakeId)
                    .asRuntimeException();
            }

            // Initialize the lake (create git repo, bazel workspace, etc.)
            protolake.v1.Lake lake = lakeInitializer.initializeLake(
                lakeId,
                request.getLake().getDisplayName(),
                request.getLake().getDescription(),
                request.getLake().getConfig(),
                request.getLake().getLakePrefix()
            );
            
            // Note: Validation is now handled in the storage layer
            // Store lake metadata
            lake = storageService.createLake(lake);

            responseObserver.onNext(lake);
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to create lake: %s", request.getLake().getName());
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription("Failed to create lake: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException()
            );
        }
    }

    /**
     * Retrieves lake details by name.
     */
    @Override
    public void getLake(GetLakeRequest request, StreamObserver<protolake.v1.Lake> responseObserver) {
        try {
            String lakeId = LakeUtil.extractLakeId(request.getName());
            
            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException();
            }

            responseObserver.onNext(lake.get());
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to get lake: %s", request.getName());
            responseObserver.onError(e);
        }
    }

    /**
     * Lists all available lakes.
     */
    @Override
    public void listLakes(ListLakesRequest request, StreamObserver<ListLakesResponse> responseObserver) {
        try {
            List<protolake.v1.Lake> lakes = storageService.listLakes();
            
            // Apply pagination if requested
            int startIndex = request.getPageSize() > 0 && !request.getPageToken().isEmpty() 
                ? Integer.parseInt(request.getPageToken()) : 0;
            int endIndex = request.getPageSize() > 0 
                ? Math.min(startIndex + request.getPageSize(), lakes.size()) 
                : lakes.size();

            List<protolake.v1.Lake> pagedLakes = lakes.subList(startIndex, endIndex);

            ListLakesResponse.Builder response = ListLakesResponse.newBuilder()
                .addAllLakes(pagedLakes);

            // Set next page token if there are more results
            if (endIndex < lakes.size()) {
                response.setNextPageToken(String.valueOf(endIndex));
            }

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to list lakes");
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription("Failed to list lakes: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException()
            );
        }
    }

    /**
     * Updates lake configuration.
     */
    @Override
    public void updateLake(UpdateLakeRequest request, StreamObserver<protolake.v1.Lake> responseObserver) {
        try {
            String lakeId = LakeUtil.extractLakeId(request.getLake().getName());
            
            Optional<protolake.v1.Lake> existing = storageService.getLake(lakeId);
            if (existing.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException();
            }

            // Build updated lake with new values
            protolake.v1.Lake.Builder lakeBuilder = existing.get().toBuilder()
                .setDisplayName(request.getLake().getDisplayName())
                .setDescription(request.getLake().getDescription())
                .setConfig(request.getLake().getConfig())
                .setLakePrefix(request.getLake().getLakePrefix())
                .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()));
            
            protolake.v1.Lake updatedLake = lakeBuilder.build();
            
            // Note: Validation is now handled in the storage layer

            // Save updates
            updatedLake = storageService.updateLake(updatedLake);

            responseObserver.onNext(updatedLake);
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to update lake: %s", request.getLake().getName());
            responseObserver.onError(e);
        }
    }

    /**
     * Deletes a lake from protolake management.
     * Can optionally delete the entire filesystem or just protolake files.
     */
    @Override
    public void deleteLake(DeleteLakeRequest request, StreamObserver<Empty> responseObserver) {
        try {
            String lakeId = LakeUtil.extractLakeId(request.getName());
            
            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException();
            }
            
            // Delete from filesystem if requested
            if (request.getDeleteFilesystem()) {
                LOG.infof("Deleting entire lake directory: %s", 
                    LakeUtil.getLocalPath(lake.get(), basePath));
                lakeInitializer.deleteLakeDirectory(lake.get());
            } else {
                LOG.infof("Deleting only protolake files for lake: %s", lakeId);
                lakeInitializer.deleteProtolakeFiles(lake.get());
            }
            
            // Delete from database
            storageService.deleteLake(lakeId);

            LOG.infof("Deleted lake: %s (filesystem=%s)", lakeId, request.getDeleteFilesystem());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete lake: %s", request.getName());
            responseObserver.onError(e);
        }
    }

    /**
     * Builds all bundles in the lake.
     * Returns a long-running operation for progress tracking.
     */
    @Override
    public void buildLake(BuildLakeRequest request, StreamObserver<Operation> responseObserver) {
        try {
            String lakeId = LakeUtil.extractLakeId(request.getName());
            
            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException();
            }

            // Create unique operation ID and resource-scoped name
            String operationId = UUID.randomUUID().toString();
            String operationName = "lakes/" + lakeId + "/operations/" + operationId;
            String resourceName = "lakes/" + lakeId;
            
            // Get the relative path of the lake from base workspace
            String targetPath = LakeUtil.getRelativePath(lake.get());
            
            // Create initial metadata for lake build
            BuildOperationMetadata metadata = BuildOperationMetadata.newBuilder()
                .setRequestedTarget(targetPath)
                .setBranch(buildOrchestrator.getCurrentBranch(lakeId))
                .setStartTime(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .setCurrentPhase(OperationPhase.INITIALIZING)
                .setPhaseStatuses(PhaseStatuses.newBuilder()
                    .setGazelle(PhaseStatus.newBuilder().setStatus(PhaseStatus.Status.NOT_STARTED).build())
                    .setValidation(PhaseStatus.newBuilder().setStatus(PhaseStatus.Status.NOT_STARTED).build())
                    .setBuild(PhaseStatus.newBuilder().setStatus(PhaseStatus.Status.NOT_STARTED).build())
                    .build())
                .setLake(lake.get())
                .build();
            
            // Create cancellation token
            CancellationToken cancellationToken = new CancellationToken();
            
            // Create operation (may return ABORTED if resource is busy)
            Operation operation = operationManager.createOperation(
                operationName, resourceName, metadata, cancellationToken);
            
            // Check if operation was aborted due to existing operation
            if (operation.getDone() && operation.hasError() && 
                operation.getError().getCode() == io.grpc.Status.Code.ABORTED.value()) {
                responseObserver.onNext(operation);
                responseObserver.onCompleted();
                return;
            }

            // Start build asynchronously with the lake's relative path
            BuildPipelineConfig pipelineConfig = request.hasPipelineConfig() 
                ? request.getPipelineConfig() 
                : BuildOrchestrator.getDefaultPipelineConfig();
            buildOrchestrator.buildTargetAsync(lake.get(), targetPath, pipelineConfig, operationName, cancellationToken);

            responseObserver.onNext(operation);
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to build lake: %s", request.getName());
            responseObserver.onError(e);
        }
    }
    
    /**
     * Refreshes lakes from the filesystem.
     * Discovers lakes and bundles by scanning for lake.yaml and bundle.yaml files.
     */
    @Override
    public void refreshLakes(RefreshLakesRequest request, StreamObserver<RefreshLakesResponse> responseObserver) {
        try {
            LOG.infof("Refreshing lakes from filesystem");
            
            // Determine path to scan
            java.nio.file.Path scanPath;
            if (!request.getPath().isEmpty()) {
                scanPath = java.nio.file.Paths.get(request.getPath());
            } else {
                scanPath = java.nio.file.Paths.get(basePath);
            }
            
            // Use the unified refresh method from LakeDiscoveryService
            int lakeCount = lakeDiscoveryService.refreshLakes(scanPath);
            
            // Get the updated list of lakes for the response
            List<protolake.v1.Lake> lakes = storageService.listLakes();
            List<String> lakeIds = lakes.stream()
                .map(lake -> LakeUtil.extractLakeId(lake.getName()))
                .collect(Collectors.toList());
            
            // Count total bundles across all lakes
            int totalBundles = 0;
            for (protolake.v1.Lake lake : lakes) {
                String lakeId = LakeUtil.extractLakeId(lake.getName());
                totalBundles += storageService.listBundles(lakeId).size();
            }
            
            RefreshLakesResponse response = RefreshLakesResponse.newBuilder()
                .setLakesDiscovered(lakeCount)
                .setBundlesDiscovered(totalBundles)
                .addAllLakeNames(lakeIds)
                .build();
                
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh lakes");
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription("Failed to refresh lakes: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException()
            );
        }
    }
}
