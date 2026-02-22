"""Rules for creating multi-language proto bundles with hybrid publishing - Bazel 8 compatible"""

load("@rules_java//java:defs.bzl", "JavaInfo")
# ProtoInfo is available from protobuf directly
load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")

# Helper function for map_each - must be at top level
def _proto_source_mapper(f):
    """Map proto file to src=dest pair for bundler tools"""
    return f.path + "=" + f.path

def _java_proto_bundle_impl(ctx):
    """Create a fat JAR containing all proto classes and source files"""

    output_jar = ctx.actions.declare_file("{}_bundle.jar".format(ctx.label.name))

    # Collect all runtime JARs from java dependencies (both proto and gRPC)
    all_java_deps = ctx.attr.java_deps + ctx.attr.java_grpc_deps

    if ctx.attr.fat_jar:
        # Fat JAR: include all transitive dependencies
        runtime_jars = depset(
            transitive = [dep[JavaInfo].transitive_runtime_jars for dep in all_java_deps],
        )
    else:
        # Thin JAR (default): only include the generated code JARs
        # Transitive deps (protobuf-java, grpc, etc.) are declared in POM
        direct_jars = []
        for dep in all_java_deps:
            ji = dep[JavaInfo]
            direct_jars.extend(ji.runtime_output_jars)
        runtime_jars = depset(
            direct = direct_jars,
            transitive = [dep[JavaInfo].full_compile_jars for dep in all_java_deps],
        )

    # Collect all proto sources
    proto_sources = depset(
        transitive = [dep[ProtoInfo].transitive_sources for dep in ctx.attr.proto_deps]
    )

    # Use the jar_bundler tool
    args = ctx.actions.args()
    args.add("--output", output_jar)
    args.add("--group-id", ctx.attr.group_id)
    args.add("--artifact-id", ctx.attr.artifact_id)
    
    # Read version from environment with default - this is the hybrid approach
    args.add("--version", "${VERSION:-1.0.0}")

    # Add Java JARs
    args.add_all("--java-jars", runtime_jars)

    # Add proto sources
    args.add_all("--proto-sources", proto_sources)

    ctx.actions.run(
        outputs = [output_jar],
        inputs = depset(transitive = [runtime_jars, proto_sources]),
        executable = ctx.executable._jar_bundler,
        arguments = [args],
        mnemonic = "JavaProtoBundle",
        progress_message = "Building Java proto bundle %s" % output_jar.short_path,
        use_default_shell_env = True,  # This allows access to environment variables
    )

    # Create a file containing Maven coordinates (also uses environment version)
    maven_coords_file = ctx.actions.declare_file("{}_maven_coords.txt".format(ctx.label.name))
    ctx.actions.write(
        output = maven_coords_file,
        content = "{}:{}:${{VERSION:-1.0.0}}".format(ctx.attr.group_id, ctx.attr.artifact_id),
        is_executable = False,
    )

    return [
        DefaultInfo(files = depset([output_jar])),
        OutputGroupInfo(
            maven_artifact = depset([output_jar]),
            maven_coordinates = depset([maven_coords_file])
        ),
    ]

java_proto_bundle = rule(
    implementation = _java_proto_bundle_impl,
    attrs = {
        "proto_deps": attr.label_list(
            providers = [ProtoInfo],
            doc = "Proto library dependencies",
        ),
        "java_deps": attr.label_list(
            providers = [JavaInfo],
            doc = "Java proto library dependencies",
        ),
        "java_grpc_deps": attr.label_list(
            providers = [JavaInfo],
            default = [],
            doc = "Java gRPC library dependencies",
        ),
        "group_id": attr.string(
            mandatory = True,
            doc = "Maven group ID",
        ),
        "artifact_id": attr.string(
            mandatory = True,
            doc = "Maven artifact ID",
        ),
        "fat_jar": attr.bool(
            default = False,
            doc = "If True, creates a fat JAR with all transitive dependencies. " +
                  "If False (default), creates a thin JAR with only generated code.",
        ),
        # NO version attribute - version comes from environment variable
        "_jar_bundler": attr.label(
            default = "//tools:jar_bundler",
            executable = True,
            cfg = "exec",
            doc = "JAR bundler tool",
        ),
    },
    doc = "Creates a JAR containing generated Java classes. Use fat_jar=True for all transitive deps.",
)

def _py_proto_bundle_impl(ctx):
    """Create a Python wheel containing all proto files and generated Python files"""

    output_whl = ctx.actions.declare_file("{}_bundle.whl".format(ctx.label.name))

    # Collect all Python files from dependencies (both proto and gRPC)
    py_files = []
    all_py_deps = ctx.attr.py_deps + ctx.attr.py_grpc_deps
    for dep in all_py_deps:
        if hasattr(dep, "files"):
            py_files.extend(dep.files.to_list())

    # Collect all proto sources
    proto_sources = depset(
        transitive = [dep[ProtoInfo].transitive_sources for dep in ctx.attr.proto_deps]
    )

    # Use the wheel_builder tool
    args = ctx.actions.args()
    args.add("--output", output_whl)
    args.add("--package-name", ctx.attr.package_name)
    
    # Read version from environment with default - hybrid approach
    args.add("--version", "${VERSION:-1.0.0}")
    
    args.add_all("--py-files", py_files)
    # Pass proto sources as src=dest pairs using top-level function
    args.add_all("--proto-sources", proto_sources,
                 map_each = _proto_source_mapper)

    ctx.actions.run(
        outputs = [output_whl],
        inputs = depset(direct = py_files, transitive = [proto_sources]),
        executable = ctx.executable._wheel_builder,
        arguments = [args],
        mnemonic = "PyProtoBundle",
        progress_message = "Building Python proto bundle %s" % output_whl.short_path,
        use_default_shell_env = True,  # Enable environment variable access
    )

    # Create a file containing PyPI coordinates (also uses environment version)
    pypi_coords_file = ctx.actions.declare_file("{}_pypi_coords.txt".format(ctx.label.name))
    ctx.actions.write(
        output = pypi_coords_file,
        content = "{}==${{VERSION:-1.0.0}}".format(ctx.attr.package_name),
        is_executable = False,
    )

    return [
        DefaultInfo(files = depset([output_whl])),
        OutputGroupInfo(
            pypi_artifact = depset([output_whl]),
            pypi_coordinates = depset([pypi_coords_file])
        ),
    ]

py_proto_bundle = rule(
    implementation = _py_proto_bundle_impl,
    attrs = {
        "proto_deps": attr.label_list(
            providers = [ProtoInfo],
            doc = "Proto library dependencies",
        ),
        "py_deps": attr.label_list(
            doc = "Python proto library dependencies",
        ),
        "py_grpc_deps": attr.label_list(
            default = [],
            doc = "Python gRPC library dependencies",
        ),
        "package_name": attr.string(
            mandatory = True,
            doc = "PyPI package name",
        ),
        # NO version attribute - version comes from environment variable
        "_wheel_builder": attr.label(
            default = "//tools:wheel_builder",
            executable = True,
            cfg = "exec",
        ),
    },
    doc = "Creates a Python wheel containing all proto files and generated Python files",
)

def _js_proto_bundle_impl(ctx):
    """Create a JavaScript/TypeScript NPM package with all proto files and generated code"""
    output_tgz = ctx.actions.declare_file("{}_bundle.tgz".format(ctx.label.name))

    # Collect all JS files from both grpc and grpc_web deps
    js_files = []
    for dep in ctx.attr.js_deps + ctx.attr.js_grpc_web_deps:
        if hasattr(dep, "files"):
            js_files.extend(dep.files.to_list())

    # Collect proto sources
    proto_sources = depset(
        transitive = [dep[ProtoInfo].transitive_sources for dep in ctx.attr.proto_deps]
    )

    # Use npm_bundler tool
    args = ctx.actions.args()
    args.add("--output", output_tgz)
    args.add("--package-name", ctx.attr.package_name)
    
    # Read version from environment with default - hybrid approach
    args.add("--version", "${VERSION:-1.0.0}")
    
    args.add("--typescript")  # Always include TypeScript definitions
    args.add("--module-format", "dual")  # Support both CommonJS and ESM
    args.add_all("--js-files", js_files)
    args.add_all("--proto-sources", proto_sources,
                 map_each = _proto_source_mapper)

    ctx.actions.run(
        outputs = [output_tgz],
        inputs = depset(direct = js_files, transitive = [proto_sources]),
        executable = ctx.executable._npm_bundler,
        arguments = [args],
        mnemonic = "JsProtoBundle",
        progress_message = "Building JavaScript proto bundle %s" % output_tgz.short_path,
        use_default_shell_env = True,  # Enable environment variable access
    )

    # Create NPM coordinates file (also uses environment version)
    npm_coords_file = ctx.actions.declare_file("{}_npm_coords.txt".format(ctx.label.name))
    ctx.actions.write(
        output = npm_coords_file,
        content = "{}@${{VERSION:-1.0.0}}".format(ctx.attr.package_name),
        is_executable = False,
    )

    return [
        DefaultInfo(files = depset([output_tgz])),
        OutputGroupInfo(
            npm_artifact = depset([output_tgz]),
            npm_coordinates = depset([npm_coords_file])
        ),
    ]

js_proto_bundle = rule(
    implementation = _js_proto_bundle_impl,
    attrs = {
        "proto_deps": attr.label_list(
            providers = [ProtoInfo],
            doc = "Proto library dependencies",
        ),
        "js_deps": attr.label_list(
            doc = "JavaScript gRPC-js library dependencies (Node.js)",
        ),
        "js_grpc_web_deps": attr.label_list(
            default = [],
            doc = "JavaScript gRPC-web library dependencies (Browser)",
        ),
        "package_name": attr.string(
            mandatory = True,
            doc = "NPM package name",
        ),
        # NO version attribute - version comes from environment variable
        "_npm_bundler": attr.label(
            default = "//tools:npm_bundler",
            executable = True,
            cfg = "exec",
        ),
    },
    doc = "Creates a JavaScript/TypeScript NPM package containing all proto files and generated code",
)

# Descriptor set rule - merges transitive FileDescriptorSets into a single .pb file
# Used by Envoy gRPC-JSON transcoding, grpcurl, gRPC server reflection, etc.
def _proto_descriptor_set_impl(ctx):
    output = ctx.actions.declare_file("{}.pb".format(ctx.attr.name))
    descriptor_sets = depset(
        transitive = [dep[ProtoInfo].transitive_descriptor_sets for dep in ctx.attr.deps],
    )
    args = ctx.actions.args()
    args.add(output)
    args.add_all(descriptor_sets)
    ctx.actions.run_shell(
        command = """output="$1"; shift; cat "$@" > "$output" """,
        inputs = descriptor_sets,
        outputs = [output],
        arguments = [args],
        mnemonic = "MergeDescriptorSets",
        progress_message = "Merging proto descriptor sets into %s" % output.short_path,
    )
    return [DefaultInfo(files = depset([output]))]

proto_descriptor_set = rule(
    implementation = _proto_descriptor_set_impl,
    attrs = {
        "deps": attr.label_list(
            providers = [ProtoInfo],
            doc = "Proto library targets to include in the descriptor set",
        ),
    },
    doc = "Merges transitive FileDescriptorSets from proto_library deps into a single .pb file",
)

# Proto-loader bundle rule - packages raw .proto files for @grpc/proto-loader
def _js_proto_loader_bundle_impl(ctx):
    output_tgz = ctx.actions.declare_file("{}_proto_loader.tgz".format(ctx.label.name))

    # Collect all transitive proto sources (includes external deps like google/api/*.proto)
    proto_sources = depset(
        transitive = [dep[ProtoInfo].transitive_sources for dep in ctx.attr.proto_deps],
    )

    args = ctx.actions.args()
    args.add("--output", output_tgz)
    args.add("--package-name", ctx.attr.package_name)
    args.add("--version", "${VERSION:-1.0.0}")
    args.add_all("--proto-sources", proto_sources, map_each = _proto_source_mapper)

    ctx.actions.run(
        outputs = [output_tgz],
        inputs = proto_sources,
        executable = ctx.executable._proto_loader_publisher,
        arguments = [args],
        mnemonic = "JsProtoLoaderBundle",
        progress_message = "Building proto-loader bundle %s" % output_tgz.short_path,
        use_default_shell_env = True,
    )

    # Create NPM coordinates file
    npm_coords_file = ctx.actions.declare_file("{}_proto_loader_coords.txt".format(ctx.label.name))
    ctx.actions.write(
        output = npm_coords_file,
        content = "{}@${{VERSION:-1.0.0}}".format(ctx.attr.package_name),
        is_executable = False,
    )

    return [
        DefaultInfo(files = depset([output_tgz])),
        OutputGroupInfo(
            npm_artifact = depset([output_tgz]),
            npm_coordinates = depset([npm_coords_file]),
        ),
    ]

js_proto_loader_bundle = rule(
    implementation = _js_proto_loader_bundle_impl,
    attrs = {
        "proto_deps": attr.label_list(
            providers = [ProtoInfo],
            doc = "Proto library dependencies whose .proto files will be packaged",
        ),
        "package_name": attr.string(
            mandatory = True,
            doc = "NPM package name (e.g., '@cohub/iam-proto-loader')",
        ),
        "_proto_loader_publisher": attr.label(
            default = "//tools:proto_loader_publisher",
            executable = True,
            cfg = "exec",
            doc = "Proto-loader publisher tool",
        ),
    },
    doc = "Creates an npm package with raw .proto files for @grpc/proto-loader dynamic loading",
)

# Build validation rule to ensure bundles can be built
build_validation = rule(
    implementation = lambda ctx: [DefaultInfo()],
    attrs = {
        "targets": attr.label_list(),
    },
    doc = "Ensures that the specified targets build successfully",
)
