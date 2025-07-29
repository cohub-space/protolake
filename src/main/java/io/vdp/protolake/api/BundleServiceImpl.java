package io.vdp.protolake.api;

import com.google.longrunning.Operation;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.vdp.protolake.initializer.BundleInitializer;
import io.vdp.protolake.operation.CancellationToken;
import io.vdp.protolake.operation.InMemoryOperationManager;
import io.vdp.protolake.pipeline.BuildOrchestrator;
import io.vdp.protolake.storage.BundleDiscoveryService;
import io.vdp.protolake.storage.StorageService;
import io.vdp.protolake.validator.BundleValidator;
import io.vdp.protolake.util.BundleUtil;
import io.vdp.protolake.util.LakeUtil;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import protolake.v1.*;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * gRPC service implementation for Bundle management.
 * 
 * A Bundle represents a logical grouping of protos that get built into 
 * language-specific packages (JAR, wheel, npm).
 */
@GrpcService
public class BundleServiceImpl extends BundleServiceGrpc.BundleServiceImplBase {
    private static final Logger LOG = Logger.getLogger(BundleServiceImpl.class);

    @Inject
    StorageService storageService;

    @Inject
    BundleInitializer bundleInitializer;

    @Inject
    BuildOrchestrator buildOrchestrator;
    
    @Inject
    InMemoryOperationManager operationManager;
    
    @Inject
    BundleValidator bundleValidator;

    @Inject
    BundleDiscoveryService bundleDiscoveryService;
    
    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    /**
     * Creates a new bundle within a lake.
     */
    @Override
    public void createBundle(CreateBundleRequest request, StreamObserver<protolake.v1.Bundle> responseObserver) {
        try {
            String lakeId = LakeUtil.extractLakeId(request.getParent());
            String bundleId = BundleUtil.extractBundleId(request.getBundle().getName());

            LOG.infof("Creating bundle %s in lake %s", bundleId, lakeId);

            // Validate lake exists
            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException();
            }

            // Check if bundle already exists
            Optional<protolake.v1.Bundle> existing = storageService.getBundle(lakeId, bundleId);
            if (existing.isPresent()) {
                throw Status.ALREADY_EXISTS
                    .withDescription("Bundle already exists: " + bundleId)
                    .asRuntimeException();
            }

            // Initialize bundle (create bundle.yaml, directory structure)
            protolake.v1.Bundle bundle = bundleInitializer.initializeBundle(
                lake.get(),
                bundleId,
                request.getBundle().getDisplayName(),
                request.getBundle().getDescription(),
                request.getBundle().getBundlePrefix(),
                request.getBundle().getConfig()
            );
            
            // Note: Validation is now handled in the storage layer
            // Save bundle to database for discovery
            storageService.createBundle(bundle);

            responseObserver.onNext(bundle);
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to create bundle: %s", request.getBundle().getName());
            responseObserver.onError(e);
        }
    }

    /**
     * Retrieves bundle details.
     */
    @Override
    public void getBundle(GetBundleRequest request, StreamObserver<protolake.v1.Bundle> responseObserver) {
        try {
            String lakeId = BundleUtil.extractLakeIdFromBundle(request.getName());
            String bundleId = BundleUtil.extractBundleId(request.getName());

            Optional<protolake.v1.Bundle> bundle = storageService.getBundle(lakeId, bundleId);
            if (bundle.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Bundle not found: " + request.getName())
                    .asRuntimeException();
            }

            responseObserver.onNext(bundle.get());
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to get bundle: %s", request.getName());
            responseObserver.onError(e);
        }
    }

    /**
     * Lists bundles in a lake.
     * Uses dynamic discovery to find bundles from the filesystem.
     */
    @Override
    public void listBundles(ListBundlesRequest request, StreamObserver<ListBundlesResponse> responseObserver) {
        try {
            String lakeId = LakeUtil.extractLakeId(request.getParent());

            // Use discovery instead of persistent storage
            List<protolake.v1.Bundle> bundles = storageService.listBundles(lakeId);

            // Apply pagination
            int startIndex = request.getPageSize() > 0 && !request.getPageToken().isEmpty()
                ? Integer.parseInt(request.getPageToken()) : 0;
            int endIndex = request.getPageSize() > 0
                ? Math.min(startIndex + request.getPageSize(), bundles.size())
                : bundles.size();

            List<protolake.v1.Bundle> pagedBundles = bundles.subList(startIndex, endIndex);

            ListBundlesResponse.Builder response = ListBundlesResponse.newBuilder()
                .addAllBundles(pagedBundles);

            if (endIndex < bundles.size()) {
                response.setNextPageToken(String.valueOf(endIndex));
            }

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to list bundles");
            responseObserver.onError(e);
        }
    }

    /**
     * Updates bundle configuration by modifying bundle.yaml.
     */
    @Override
    public void updateBundle(UpdateBundleRequest request, StreamObserver<protolake.v1.Bundle> responseObserver) {
        try {
            String lakeId = BundleUtil.extractLakeIdFromBundle(request.getBundle().getName());
            String bundleId = BundleUtil.extractBundleId(request.getBundle().getName());

            Optional<protolake.v1.Bundle> existing = storageService.getBundle(lakeId, bundleId);
            if (existing.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Bundle not found: " + request.getBundle().getName())
                    .asRuntimeException();
            }

            // Build updated bundle with new values
            protolake.v1.Bundle.Builder bundleBuilder = existing.get().toBuilder()
                .setDisplayName(request.getBundle().getDisplayName())
                .setDescription(request.getBundle().getDescription())
                .setConfig(request.getBundle().getConfig())
                .setBundlePrefix(request.getBundle().getBundlePrefix())
                .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()));
            
            protolake.v1.Bundle updatedBundle = bundleBuilder.build();
            
            // Note: Validation is now handled in the storage layer
            
            // Update the bundle.yaml file
            // TODO: Implement updating bundle.yaml file based on the new configuration
            // TODO: we might as well just re-initialize the bundle by deleting the old one
            // For now, this requires manual update of the bundle.yaml file
            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isPresent()) {
                Path bundleYamlPath = BundleUtil.getBundleYamlPath(lake.get(), updatedBundle, basePath);
                LOG.warnf("Bundle configuration update not fully implemented. Please manually update %s", 
                         bundleYamlPath);
            }
            
            // Save updated bundle to database
            updatedBundle = storageService.updateBundle(updatedBundle);
            
            // TODO: Run gazelle to update BUILD files if needed
            
            responseObserver.onNext(updatedBundle);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update bundle: %s", request.getBundle().getName());
            responseObserver.onError(e);
        }
    }

    /**
     * Deletes a bundle from the lake.
     * Can optionally delete the entire directory or just bundle.yaml.
     */
    @Override
    public void deleteBundle(DeleteBundleRequest request, StreamObserver<Empty> responseObserver) {
        try {
            String lakeId = BundleUtil.extractLakeIdFromBundle(request.getName());
            String bundleId = BundleUtil.extractBundleId(request.getName());

            Optional<protolake.v1.Bundle> bundle = storageService.getBundle(lakeId, bundleId);
            if (bundle.isEmpty()) {
                // If bundle isn't found, treat it as already deleted
                LOG.infof("Bundle not found, treating as already deleted: %s", request.getName());
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
                return;
            }

            // Get the lake to calculate bundle path
            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException();
            }

            Path bundleDir = BundleUtil.calculateBundlePath(lake.get(), bundle.get(), basePath);
            
            if (request.getDeleteFilesystem()) {
                // Delete the entire bundle directory
                if (bundleDir.toFile().exists()) {
                    deleteRecursively(bundleDir);
                    LOG.infof("Deleted entire bundle directory: %s", bundleDir);
                }
            } else {
                // Delete only bundle.yaml
                Path bundleYaml = bundleDir.resolve("bundle.yaml");
                Files.deleteIfExists(bundleYaml);
                LOG.infof("Deleted bundle.yaml: %s", bundleYaml);
            }
            
            // Delete from database
            storageService.deleteBundle(lakeId, bundleId);
            
            // TODO: Run gazelle to update BUILD files
            
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete bundle: %s", request.getName());
            responseObserver.onError(e);
        }
    }
    
    /**
     * Recursively deletes a directory and all its contents.
     */
    private void deleteRecursively(Path path) throws IOException {
        if (path.toFile().isDirectory()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path entry : stream) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    /**
     * Builds a specific bundle.
     * Returns a long-running operation for progress tracking.
     */
    @Override
    public void buildBundle(BuildBundleRequest request, StreamObserver<Operation> responseObserver) {
        try {
            String lakeId = BundleUtil.extractLakeIdFromBundle(request.getName());
            String bundleId = BundleUtil.extractBundleId(request.getName());

            Optional<protolake.v1.Bundle> bundle = storageService.getBundle(lakeId, bundleId);
            if (bundle.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Bundle not found: " + request.getName())
                    .asRuntimeException();
            }

            // Get the lake for this bundle
            protolake.v1.Lake lake = storageService.getLake(lakeId)
                .orElseThrow(() -> Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException());

            // Create unique operation ID and resource-scoped name
            String operationId = UUID.randomUUID().toString();
            String operationName = String.format("lakes/%s/bundles/%s/operations/%s", 
                lakeId, bundleId, operationId);
            String resourceName = String.format("lakes/%s/bundles/%s", lakeId, bundleId);
            
            // Get the workspace-relative path for the bundle
            String targetPath = BundleUtil.getWorkspaceRelativePath(lake, bundle.get());
            
            // Create initial metadata for bundle build
            BuildOperationMetadata metadata = BuildOperationMetadata.newBuilder()
                    .setResourceName(resourceName)
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
                .setLake(lake)
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
            
            // Start build asynchronously for the specific bundle target
            BuildPipelineConfig pipelineConfig = request.hasPipelineConfig() 
                ? request.getPipelineConfig() 
                : BuildOrchestrator.getDefaultPipelineConfig();
            buildOrchestrator.buildTargetAsync(lake, targetPath, pipelineConfig, operationName, cancellationToken);

            responseObserver.onNext(operation);
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to build bundle");
            responseObserver.onError(e);
        }
    }

    /**
     * Gets the latest build for a bundle.
     * Used by package managers for LOCAL_LATEST resolution.
     */
    @Override
    public void getLatestBuild(GetLatestBuildRequest request, StreamObserver<protolake.v1.Build> responseObserver) {
        try {
            String lakeId = BundleUtil.extractLakeIdFromBundle(request.getParent());
            String bundleId = BundleUtil.extractBundleId(request.getParent());

            // Get current git branch if not specified
            String branch = request.getBranch().isEmpty() 
                ? buildOrchestrator.getCurrentBranch(lakeId)
                : request.getBranch();

            Optional<TargetBuildInfo> latestBuild = storageService.getLatestTargetBuild(
                lakeId,
                bundleId,
                branch,
                request.getLanguage()
            );

            if (latestBuild.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription(String.format(
                        "No build found for bundle %s on branch %s for language %s",
                        bundleId, branch, request.getLanguage()))
                    .asRuntimeException();
            }

            // Convert TargetBuildInfo to proto Build
            TargetBuildInfo buildInfo = latestBuild.get();
            protolake.v1.Build response = protolake.v1.Build.newBuilder()
                .setId(UUID.randomUUID().toString()) // Generate ID since TargetBuildInfo doesn't have one
                .setBundle(String.format("lakes/%s/bundles/%s", lakeId, bundleId))
                .setVersion(buildInfo.getVersion())
                .setBranch(branch)
                .setStatus(protolake.v1.Build.BuildStatus.valueOf(buildInfo.getStatus().name()))
                .setStartTime(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to get latest build");
            responseObserver.onError(e);
        }
    }
    
    /**
     * Refreshes bundles from the filesystem for a specific lake.
     * Discovers bundles by scanning for bundle.yaml files.
     */
    @Override
    public void refreshBundles(RefreshBundlesRequest request, StreamObserver<RefreshBundlesResponse> responseObserver) {
        try {
            String lakeId = LakeUtil.extractLakeId(request.getParent());
            LOG.infof("Refreshing bundles for lake: %s", lakeId);
            
            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException();
            }
            
            // Use the unified refresh method from LakeDiscoveryService
            int bundleCount = bundleDiscoveryService.refreshBundlesForLake(lake.get());
            
            // Get the updated list of bundles
            List<protolake.v1.Bundle> bundles = storageService.listBundles(lakeId);
            List<String> bundleNames = bundles.stream()
                .map(bundle -> BundleUtil.extractBundleId(bundle.getName()))
                .collect(Collectors.toList());
            
            RefreshBundlesResponse response = RefreshBundlesResponse.newBuilder()
                .setBundlesDiscovered(bundleCount)
                .addAllBundleNames(bundleNames)
                .build();
                
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh bundles");
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription("Failed to refresh bundles: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException()
            );
        }
    }
}
