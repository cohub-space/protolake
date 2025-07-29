# Proto Lake

Proto Lake is a centralized protocol buffer management system that provides branch-aware artifact publishing for local development. It acts as the source of truth for all protobuf definitions and automatically builds language-specific packages (JAR, wheel, npm) that services can consume.

## Overview

Proto Lake solves the challenge of sharing Protocol Buffer definitions across services by providing:

- **Centralized Storage**: Single source of truth for all company protos in dedicated git repositories (lakes)
- **Automatic Dependency Management**: Using Bazel and Gazelle for build file generation
- **Multi-Language Support**: Generates packages for Java, Python, and JavaScript/TypeScript
- **Git-Based Workflow**: Optional branch-aware development for advanced version control
- **Automated Publishing**: To local Maven, PyPI, and NPM repositories
- **Version Management**: Automatic versioning with optional branch suffixes

## Quick Start

### Prerequisites

- Docker and Docker Compose
- Git
- Access to the protolake-gazelle repository (public)

### Running Proto Lake

1. Clone this repository:
```bash
git clone https://github.com/Cohub-Space/Protolake.git
cd Protolake
```

2. Build and start the service:
```bash
docker-compose up -d
```

3. Verify the service is running:
```bash
curl http://localhost:8080/q/health
```

### Creating Your First Lake

1. Create a lake using the gRPC API:
```bash
grpcurl -plaintext -d '{
  "lake": {
    "name": "my-lake",
    "display_name": "My Proto Lake",
    "description": "My first proto lake"
  }
}' localhost:9090 protolake.v1.LakeService/CreateLake
```

2. Navigate to the created lake:
```bash
cd test-lake-output/my-lake
```

3. Add your proto files and run Bazel to build:
```bash
bazel build //...
```

## Configuration

### Using GitHub Repository (Production)

By default, Proto Lake is configured to use the protolake-gazelle extension from GitHub. This is configured in `docker-compose.yml`:

```yaml
environment:
  - PROTOLAKE_GAZELLE_GIT_URL=https://github.com/cohub-space/protolake-gazelle.git
  - PROTOLAKE_GAZELLE_GIT_COMMIT=main  # Use specific commit SHA for stability
```

### Local Development Mode

For local development of protolake-gazelle, you can switch to using a local path:

1. Comment out the Git configuration and uncomment the local path in `docker-compose.yml`:
```yaml
environment:
  # - PROTOLAKE_GAZELLE_GIT_URL=https://github.com/cohub-space/protolake-gazelle.git
  # - PROTOLAKE_GAZELLE_GIT_COMMIT=main
  - PROTOLAKE_GAZELLE_SOURCE_PATH=../../../protolake-gazelle
```

2. Add the volume mount back:
```yaml
volumes:
  - ../protolake-gazelle:/protolake-gazelle
```

3. Restart the service:
```bash
docker-compose down
docker-compose up -d
```

## Architecture

Proto Lake consists of several key components:

- **Lake Service**: Manages lake (repository) lifecycle
- **Bundle Service**: Manages proto bundles within lakes
- **Build Orchestrator**: Handles the build pipeline using Bazel
- **Storage Service**: Manages metadata and filesystem operations
- **Template Engine**: Generates Bazel and configuration files

## Development

### Building from Source

```bash
./mvnw clean package
```

### Running Tests

```bash
# Unit tests
./mvnw test

# Integration tests
./test_protolake.sh
```

### Project Structure

```
protolake/
├── src/main/java/          # Java source code
├── src/main/proto/         # Proto definitions for the service
├── src/main/resources/     # Templates and resources
├── test-protos/           # Example proto files for testing
├── docker-compose.yml     # Docker composition
├── Dockerfile             # Container definition
└── pom.xml               # Maven configuration
```

## API Reference

Proto Lake provides gRPC APIs for managing lakes and bundles:

### Lake Service
- `CreateLake` - Create a new proto lake
- `GetLake` - Retrieve lake details
- `ListLakes` - List all lakes
- `UpdateLake` - Update lake configuration
- `DeleteLake` - Delete a lake
- `BuildLake` - Build all bundles in a lake

### Bundle Service
- `CreateBundle` - Create a new bundle in a lake
- `GetBundle` - Retrieve bundle details
- `ListBundles` - List bundles in a lake
- `UpdateBundle` - Update bundle configuration
- `DeleteBundle` - Delete a bundle
- `BuildBundle` - Build a specific bundle

## Contributing

This is a private repository. For access or questions, please contact the repository owner.

## License

Proprietary - All rights reserved.

## Related Projects

- [protolake-gazelle](https://github.com/cohub-space/protolake-gazelle) - Gazelle extension for Proto Lake
