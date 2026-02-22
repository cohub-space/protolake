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

    @ConfigProperty(name = "protolake.storage.base-path")
    String basePath;

    @Override
    public void createBundle(CreateBundleRequest request, StreamObserver<protolake.v1.Bundle> responseObserver) {
        try {
            String lakeId = LakeUtil.extractLakeId(request.getParent());
            String bundleId = BundleUtil.extractBundleId(request.getBundle().getName());

            LOG.infof("Creating bundle %s in lake %s", bundleId, lakeId);

            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException();
            }

            Optional<protolake.v1.Bundle> existing = storageService.getBundle(lakeId, bundleId);
            if (existing.isPresent()) {
                throw Status.ALREADY_EXISTS
                    .withDescription("Bundle already exists: " + bundleId)
                    .asRuntimeException();
            }

            protolake.v1.Bundle bundle = bundleInitializer.initializeBundle(
                lake.get(),
                bundleId,
                request.getBundle().getDisplayName(),
                request.getBundle().getDescription(),
                request.getBundle().getBundlePrefix(),
                request.getBundle().getConfig()
            );

            storageService.createBundle(bundle);

            responseObserver.onNext(bundle);
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to create bundle: %s", request.getBundle().getName());
            responseObserver.onError(e);
        }
    }

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

    @Override
    public void listBundles(ListBundlesRequest request, StreamObserver<ListBundlesResponse> responseObserver) {
        try {
            String lakeId = LakeUtil.extractLakeId(request.getParent());

            List<protolake.v1.Bundle> bundles = storageService.listBundles(lakeId);

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

            protolake.v1.Bundle.Builder bundleBuilder = existing.get().toBuilder()
                .setDisplayName(request.getBundle().getDisplayName())
                .setDescription(request.getBundle().getDescription())
                .setConfig(request.getBundle().getConfig())
                .setBundlePrefix(request.getBundle().getBundlePrefix())
                .setUpdateTime(LakeUtil.toProtoTimestamp(Instant.now()));

            protolake.v1.Bundle updatedBundle = bundleBuilder.build();

            // Persist bundle.yaml update
            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isPresent()) {
                try {
                    bundleInitializer.updateBundleYaml(lake.get(), updatedBundle);
                } catch (Exception e) {
                    LOG.warnf("Failed to update bundle.yaml for %s: %s", bundleId, e.getMessage());
                }
            }

            updatedBundle = storageService.updateBundle(updatedBundle);

            responseObserver.onNext(updatedBundle);
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to update bundle: %s", request.getBundle().getName());
            responseObserver.onError(e);
        }
    }

    @Override
    public void deleteBundle(DeleteBundleRequest request, StreamObserver<Empty> responseObserver) {
        try {
            String lakeId = BundleUtil.extractLakeIdFromBundle(request.getName());
            String bundleId = BundleUtil.extractBundleId(request.getName());

            Optional<protolake.v1.Bundle> bundle = storageService.getBundle(lakeId, bundleId);
            if (bundle.isEmpty()) {
                LOG.infof("Bundle not found, treating as already deleted: %s", request.getName());
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
                return;
            }

            Optional<protolake.v1.Lake> lake = storageService.getLake(lakeId);
            if (lake.isEmpty()) {
                throw Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException();
            }

            Path bundleDir = BundleUtil.calculateBundlePath(lake.get(), bundle.get(), basePath);

            if (request.getDeleteFilesystem()) {
                if (bundleDir.toFile().exists()) {
                    deleteRecursively(bundleDir);
                    LOG.infof("Deleted entire bundle directory: %s", bundleDir);
                }
            } else {
                Path bundleYaml = bundleDir.resolve("bundle.yaml");
                Files.deleteIfExists(bundleYaml);
                LOG.infof("Deleted bundle.yaml: %s", bundleYaml);
            }

            storageService.deleteBundle(lakeId, bundleId);

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete bundle: %s", request.getName());
            responseObserver.onError(e);
        }
    }

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

            protolake.v1.Lake lake = storageService.getLake(lakeId)
                .orElseThrow(() -> Status.NOT_FOUND
                    .withDescription("Lake not found: " + lakeId)
                    .asRuntimeException());

            String operationId = UUID.randomUUID().toString();
            String operationName = String.format("lakes/%s/bundles/%s/operations/%s",
                lakeId, bundleId, operationId);
            String resourceName = String.format("lakes/%s/bundles/%s", lakeId, bundleId);

            String targetPath = BundleUtil.getWorkspaceRelativePath(lake, bundle.get());

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
                    .setPublish(PhaseStatus.newBuilder().setStatus(PhaseStatus.Status.NOT_STARTED).build())
                    .build())
                .setLake(lake)
                .build();

            CancellationToken cancellationToken = new CancellationToken();

            Operation operation = operationManager.createOperation(
                operationName, resourceName, metadata, cancellationToken);

            if (operation.getDone() && operation.hasError() &&
                operation.getError().getCode() == io.grpc.Status.Code.ABORTED.value()) {
                responseObserver.onNext(operation);
                responseObserver.onCompleted();
                return;
            }

            buildOrchestrator.buildTargetAsync(lake, targetPath,
                request.getSkipValidation(), request.getInstallLocal(),
                operationName, cancellationToken);

            responseObserver.onNext(operation);
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to build bundle");
            responseObserver.onError(e);
        }
    }
}
