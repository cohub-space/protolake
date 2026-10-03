package space.cohub.vdp.protolake.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.AnnotationsProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.cohub.api.v1.ExposureProto;
import space.cohub.api.v1.MethodExposure;
import space.cohub.api.v1.ServiceExposure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE;
import static com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the API spec tool (templates/tools/api_specs/) as the api_specs aspect
 * does: on a descriptor set, here built with protobuf-java from the same
 * options lakes write, against fake plugins. The fakes answer with what the
 * real plugins wrote for demo.proto (src/test/resources/apispec/, from
 * protoc-gen-openapiv2 2.31.0 and protoc-gen-connect-openapi 0.28.0), less the
 * operations of methods whose binding the request no longer carries, as the
 * real generator omits them; the descriptors here mirror that file, so the
 * tool's rewrites and checks run on real plugin output. One test runs the real
 * protoc-gen-openapiv2 when {@code PROTOC_GEN_OPENAPIV2} names it.
 *
 * <p>Skipped when {@code python3} is not on the PATH (the tool is stdlib-only).
 */
class ApiSpecToolTest {

    private static final String TEMPLATE_DIR = "templates/tools/api_specs/";
    private static final String OPENAPIV2 = "protoc-gen-openapiv2";
    private static final String CONNECT_OPENAPI = "protoc-gen-connect-openapi";
    private static final String DEMO = "demo/v1/demo.proto";
    private static final ObjectMapper JSON = new ObjectMapper();

    // Indexes of ThingService's methods the tests change.
    private static final int GET_THING = 0;
    private static final int WATCH_THINGS = 1;
    private static final int COLLABORATE = 2;
    private static final int UPLOAD_CHUNKS = 3;

    @TempDir
    Path tempDir;

    private Path tool;
    private Path pluginState;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(pythonAvailable(), "python3 not available on PATH");
        Path tools = Files.createDirectories(tempDir.resolve("tools/api_specs"));
        for (String name : List.of("api_specs_generated.py", "asyncapi_generated.py",
                "protodesc_generated.py")) {
            Files.writeString(tools.resolve(name), resource(TEMPLATE_DIR + name));
        }
        tool = tools.resolve("api_specs_generated.py");
        pluginState = Files.createDirectories(tempDir.resolve("plugins"));
        writeFakePlugin(OPENAPIV2, "Version 2.31.0, commit 1dbf071, built at 2026-09-25",
                Map.of("demo.swagger.json", resource("apispec/protoc-gen-openapiv2.demo.swagger.json")));
        writeFakePlugin(CONNECT_OPENAPI, "protoc-gen-connect-openapi v0.28.0 (ea12c19)",
                Map.of("demo.asyncapi.json", resource("apispec/protoc-gen-connect-openapi.demo.asyncapi.json"),
                        "demo/v1/demo.openapi.json", "{}"));
    }

    @Test
    void checks_websocketOnUnaryRpc_failsAtTheOption() throws Exception {
        ServiceDescriptorProto.Builder service = demoService();
        service.setMethod(GET_THING, service.getMethod(GET_THING).toBuilder()
                .setOptions(service.getMethod(GET_THING).getOptions().toBuilder()
                        .setExtension(ExposureProto.exposure,
                                MethodExposure.newBuilder().setWebsocket(true).build())));
        // GetThing's websocket option, at line 11, column 5 (0-based span 10:4).
        SourceCodeInfo locations = SourceCodeInfo.newBuilder()
                .addLocation(location(List.of(6, 0, 2, GET_THING), 8, 2))
                .addLocation(location(List.of(6, 0, 2, GET_THING, 4, 51701), 10, 4))
                .build();

        Run run = runTool(demoFile(service, locations));

        assertThat(run.errors()).singleElement().satisfies(error -> {
            assertThat(error.path("rpc").asText()).isEqualTo("demo.v1.ThingService.GetThing");
            assertThat(error.path("file").asText()).isEqualTo(DEMO);
            assertThat(error.path("line").asInt()).isEqualTo(11);
            assertThat(error.path("column").asInt()).isEqualTo(5);
            assertThat(error.path("message").asText())
                    .contains("unary RPC", "(cohub.api.v1.exposure).websocket", "Remove the option");
        });
        assertThat(run.specs()).isEmpty();
        assertThat(specFiles()).isEmpty();
        assertThat(calls(OPENAPIV2)).isEmpty();
    }

    @Test
    void checks_websocketOnServerStreamingRpc_fails() throws Exception {
        ServiceDescriptorProto.Builder service = demoService();
        service.setMethod(WATCH_THINGS, service.getMethod(WATCH_THINGS).toBuilder()
                .setOptions(service.getMethod(WATCH_THINGS).getOptions().toBuilder()
                        .setExtension(ExposureProto.exposure,
                                MethodExposure.newBuilder().setWebsocket(true).build())));

        Run run = runTool(demoFile(service, null));

        assertThat(run.errors()).singleElement().satisfies(error -> {
            assertThat(error.path("rpc").asText()).isEqualTo("demo.v1.ThingService.WatchThings");
            assertThat(error.path("message").asText()).contains("server-streaming RPC");
        });
        assertThat(specFiles()).isEmpty();
    }

    @Test
    void checks_httpBindingOnClientAndBidiStreamingRpcs_warns() throws Exception {
        ServiceDescriptorProto.Builder service = demoService();
        for (int index : List.of(COLLABORATE, UPLOAD_CHUNKS)) {
            service.setMethod(index, service.getMethod(index).toBuilder()
                    .setOptions(service.getMethod(index).getOptions().toBuilder()
                            .setExtension(AnnotationsProto.http,
                                    HttpRule.newBuilder().setPost("/v1/stream" + index).setBody("*").build())));
        }

        Run run = runTool(demoFile(service, null));

        assertThat(run.errors()).isEmpty();
        assertThat(run.warnings()).extracting(w -> w.path("rpc").asText())
                .containsExactly("demo.v1.ThingService.Collaborate", "demo.v1.ThingService.UploadChunks");
        assertThat(run.warnings()).extracting(w -> w.path("message").asText())
                .anySatisfy(m -> assertThat(m).contains("bidi-streaming RPC", "google.api.http",
                        "leaves the binding out"))
                .anySatisfy(m -> assertThat(m).contains("client-streaming RPC"));
        // A warning does not stop the specs.
        assertThat(run.specs()).containsExactly("demo.openapi.json", "demo.asyncapi.json");
        // REST cannot carry a request stream, so the generator never sees those bindings.
        assertThat(boundMethods(lastRequest(OPENAPIV2)))
                .containsExactlyInAnyOrder("GetThing", "WatchThings", "ExportThing");
    }

    @Test
    void openapi_bindingsOnlyOnRequestStreams_writesNoOpenApi() throws Exception {
        ServiceDescriptorProto.Builder service = demoService();
        for (int i = 0; i < service.getMethodCount(); i++) {
            service.setMethod(i, service.getMethod(i).toBuilder().clearOptions());
        }
        service.setMethod(UPLOAD_CHUNKS, service.getMethod(UPLOAD_CHUNKS).toBuilder()
                .setOptions(http(HttpRule.newBuilder().setPost("/v1/{name=things/*}:upload").setBody("*").build())));

        Run run = runTool(demoFile(service, null));

        assertThat(run.errors()).isEmpty();
        assertThat(run.warnings()).singleElement()
                .satisfies(w -> assertThat(w.path("rpc").asText()).isEqualTo("demo.v1.ThingService.UploadChunks"));
        assertThat(specFiles()).isEmpty();
        assertThat(calls(OPENAPIV2)).isEmpty();
    }

    @Test
    void openapi_realPlugin_leavesOutARequestStreamBindingWithAPathParameter() throws Exception {
        // protoc-gen-openapiv2 refuses a path parameter on a client stream; a
        // WARNING must not turn into a failed build.
        String real = System.getenv("PROTOC_GEN_OPENAPIV2");
        assumeTrue(real != null && Files.isExecutable(Path.of(real)), "PROTOC_GEN_OPENAPIV2 names no plugin");
        ServiceDescriptorProto.Builder service = demoService();
        service.setMethod(UPLOAD_CHUNKS, service.getMethod(UPLOAD_CHUNKS).toBuilder()
                .setOptions(http(HttpRule.newBuilder().setPost("/v1/{name=things/*}:upload").setBody("*").build())));
        Files.delete(pluginState.resolve(OPENAPIV2));
        Files.createSymbolicLink(pluginState.resolve(OPENAPIV2), Path.of(real));

        Run run = runTool(demoFile(service, null));

        assertThat(run.errors()).isEmpty();
        assertThat(run.warnings()).extracting(w -> w.path("rpc").asText())
                .containsExactly("demo.v1.ThingService.UploadChunks");
        assertThat(openApi().path("paths").properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("/v1/{name}", "/v1/things:watch", "/v1/{name}:export");
        assertThat(openApi().at("/paths/~1v1~1things:watch/get/responses/200/x-stream-framing").asText())
                .isEqualTo("newline-delimited");
    }

    @Test
    void bundleWithoutBindingsOrWebSocketRpcs_writesNoSpecsAndRunsNoPlugin() throws Exception {
        ServiceDescriptorProto.Builder service = demoService();
        for (int i = 0; i < service.getMethodCount(); i++) {
            service.setMethod(i, service.getMethod(i).toBuilder().clearOptions());
        }

        Run run = runTool(demoFile(service, null));

        assertThat(run.findings()).isEmpty();
        assertThat(run.specs()).isEmpty();
        assertThat(specFiles()).isEmpty();
        assertThat(calls(OPENAPIV2)).isEmpty();
        assertThat(calls(CONNECT_OPENAPI)).isEmpty();
    }

    @Test
    void openapi_serverStreamingResponse_isNewlineDelimitedBareMessages() throws Exception {
        Run run = runTool(demoFile(demoService(), null));

        JsonNode response = openApi().at("/paths/~1v1~1things:watch/get/responses/200");
        // grpc-gateway's {"result": ..., "error": ...} wrapper is gone: each line is a bare Thing.
        assertThat(response.path("schema").path("$ref").asText()).isEqualTo("#/definitions/v1Thing");
        assertThat(response.path("schema").has("properties")).isFalse();
        assertThat(response.path("x-stream-framing").asText()).isEqualTo("newline-delimited");
        assertThat(response.path("description").asText()).contains("newline-delimited JSON");
        assertThat(run.errors()).isEmpty();
    }

    @Test
    void openapi_keepsUnaryAndCustomBindingsAsWritten() throws Exception {
        runTool(demoFile(demoService(), null));

        JsonNode paths = openApi().path("paths");
        assertThat(paths.at("/~1v1~1{name}/get/operationId").asText()).isEqualTo("ThingService_GetThing");
        assertThat(paths.at("/~1v1~1{name}/get/responses/200/schema/$ref").asText())
                .isEqualTo("#/definitions/v1Thing");
        // ExportThing is custom: its author serves it, and it stays in the spec.
        assertThat(paths.at("/~1v1~1{name}:export/post/operationId").asText())
                .isEqualTo("ThingService_ExportThing");
    }

    @Test
    void openapi_requestNamesGoImportPathsOnlyForFilesWithoutGoPackage() throws Exception {
        runTool(demoFile(demoService(), null));

        JsonNode call = calls(OPENAPIV2).get(0);
        List<String> parameter = List.of(call.path("parameter").asText().split(","));
        assertThat(parameter).contains("allow_merge=true", "merge_file_name=demo", "output_format=json",
                "Mdemo/v1/demo.proto=apispec/demo/v1",
                "Mcohub/api/v1/exposure.proto=apispec/cohub/api/v1");
        // googleapis and descriptor.proto set go_package themselves.
        assertThat(parameter).noneMatch(p -> p.startsWith("Mgoogle/"));
        assertThat(call.path("files")).extracting(JsonNode::asText).containsExactly(DEMO);
    }

    @Test
    void openapi_titledAndVersionedAfterTheBundle() throws Exception {
        runTool(demoFile(demoService(), null));

        assertThat(openApi().path("info").path("title").asText()).isEqualTo("demo");
        assertThat(openApi().path("info").path("version").asText()).isEqualTo("1.4.0");
    }

    @Test
    void openapi_streamInAnUnknownShape_failsInsteadOfPublishing() throws Exception {
        // A future plugin that frames streams some other way must not be published unrewritten.
        String swagger = resource("apispec/protoc-gen-openapiv2.demo.swagger.json")
                .replace("Stream result of v1Thing", "Streamed v1Thing");
        writeFakePlugin(OPENAPIV2, "Version 2.31.0", Map.of("demo.swagger.json", swagger));

        Run run = runTool(demoFile(demoService(), null));

        assertThat(run.errors()).singleElement().satisfies(error -> assertThat(error.path("message").asText())
                .contains("not publishable", "ThingService_WatchThings", "not rewritten"));
        assertThat(specFiles()).isEmpty();
    }

    @Test
    void openapi_pluginAtAnotherVersion_fails() throws Exception {
        writeFakePlugin(OPENAPIV2, "Version 2.30.0, commit abc",
                Map.of("demo.swagger.json", resource("apispec/protoc-gen-openapiv2.demo.swagger.json")));

        Run run = runTool(demoFile(demoService(), null));

        assertThat(run.errors()).singleElement().satisfies(error -> assertThat(error.path("message").asText())
                .contains("version 2.30.0", "expects 2.31.0"));
    }

    @Test
    void asyncapi_keepsOnlyWebSocketRpcs_framedAsTheBridgeFramesThem() throws Exception {
        Run run = runTool(demoFile(demoService(), null));

        assertThat(run.root().path("asyncapi_generator").asText()).isEqualTo("protoc-gen-connect-openapi 0.28.0");
        JsonNode doc = asyncApi();
        assertThat(doc.path("asyncapi").asText()).isEqualTo("3.1.0");
        assertThat(doc.path("info").path("version").asText()).isEqualTo("1.4.0");
        // The plugin also wrote WatchThings, a server stream; only the marked RPCs stay.
        assertThat(doc.path("channels").properties()).extracting(Map.Entry::getKey)
                .containsExactly("demo-v1-ThingService-Collaborate", "demo-v1-ThingService-UploadChunks");
        JsonNode channel = doc.path("channels").path("demo-v1-ThingService-Collaborate");
        assertThat(channel.path("address").asText()).isEqualTo("/ws/demo.v1.ThingService/Collaborate");
        JsonNode messages = channel.path("messages");
        assertThat(messages.at("/request/payload/properties/data/$ref").asText())
                .isEqualTo("#/components/schemas/demo.v1.CollaborateRequest");
        assertThat(messages.at("/response/payload/properties/data/$ref").asText())
                .isEqualTo("#/components/schemas/demo.v1.CollaborateResponse");
        assertThat(messages.at("/end/$ref").asText()).isEqualTo("#/components/messages/End");
        assertThat(messages.at("/status/$ref").asText()).isEqualTo("#/components/messages/Status");
        assertThat(doc.at("/components/messages/End/payload/properties/end/const").asBoolean()).isTrue();
        assertThat(doc.at("/components/messages/Status/payload/properties/status/$ref").asText())
                .isEqualTo("#/components/schemas/google.rpc.Status");
        assertThat(doc.at("/operations/demo-v1-ThingService-Collaborate_send/action").asText()).isEqualTo("send");
        assertThat(doc.at("/operations/demo-v1-ThingService-Collaborate_receive/messages/1/$ref").asText())
                .isEqualTo("#/channels/demo-v1-ThingService-Collaborate/messages/status");
        // Schemas are the plugin's, pruned to what the kept channels reach.
        assertThat(doc.path("components").path("schemas").properties()).extracting(Map.Entry::getKey)
                .contains("demo.v1.CollaborateRequest", "demo.v1.Thing", "demo.v1.Kind", "google.rpc.Status")
                .doesNotContain("demo.v1.WatchThingsRequest", "demo.v1.PingResponse");
        assertThat(doc.at("/components/schemas/demo.v1.CollaborateRequest/unevaluatedProperties").isBoolean())
                .as("the plugin's schema, not the built-in one").isTrue();
        assertThat(run.warnings()).isEmpty();
    }

    @Test
    void customRpcs_stayInBothSpecs() throws Exception {
        // Every RPC custom by the service's default, and the WebSocket RPC custom on its own too.
        ServiceDescriptorProto.Builder service = demoService();
        service.setOptions(DescriptorProtos.ServiceOptions.newBuilder()
                .setExtension(ExposureProto.serviceExposure, ServiceExposure.newBuilder().setCustom(true).build()));
        service.setMethod(COLLABORATE, service.getMethod(COLLABORATE).toBuilder()
                .setOptions(MethodOptions.newBuilder().setExtension(ExposureProto.exposure,
                        MethodExposure.newBuilder().setWebsocket(true).setCustom(true).build())));

        Run run = runTool(demoFile(service, null));

        assertThat(run.findings()).isEmpty();
        assertThat(openApi().path("paths").properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("/v1/{name}", "/v1/things:watch", "/v1/{name}:export");
        assertThat(asyncApi().path("channels").properties()).extracting(Map.Entry::getKey)
                .containsExactly("demo-v1-ThingService-Collaborate", "demo-v1-ThingService-UploadChunks");
    }

    @Test
    void openapi_customServerStream_keepsTheGeneratorsShapeAndSaysItsAuthorDefined() throws Exception {
        // The author serves WatchThings' REST binding; the transcoder's framing is not theirs to follow.
        ServiceDescriptorProto.Builder service = demoService();
        service.setMethod(WATCH_THINGS, service.getMethod(WATCH_THINGS).toBuilder()
                .setOptions(service.getMethod(WATCH_THINGS).getOptions().toBuilder()
                        .setExtension(ExposureProto.exposure, MethodExposure.newBuilder().setCustom(true).build())));

        Run run = runTool(demoFile(service, null));

        assertThat(run.findings()).isEmpty();
        JsonNode response = openApi().at("/paths/~1v1~1things:watch/get/responses/200");
        assertThat(response.path("x-stream-framing").asText()).isEqualTo("author-defined");
        assertThat(response.at("/schema/properties/result/$ref").asText()).isEqualTo("#/definitions/v1Thing");
        assertThat(response.at("/schema/properties/error/$ref").asText()).isEqualTo("#/definitions/rpcStatus");
        // A second generator run, without WatchThings' binding, is how the operation was told apart.
        assertThat(calls(OPENAPIV2)).hasSize(2);
        assertThat(boundMethods(lastRequest(OPENAPIV2))).doesNotContain("WatchThings");
    }

    @Test
    void asyncapi_channelDeclaresTheHandshakeAndTheCloseCodes() throws Exception {
        runTool(demoFile(demoService(), null));

        JsonNode channel = asyncApi().path("channels").path("demo-v1-ThingService-UploadChunks");
        JsonNode ws = channel.path("bindings").path("ws");
        assertThat(ws.path("method").asText()).isEqualTo("GET");
        assertThat(ws.at("/headers/required/0").asText()).isEqualTo("Sec-WebSocket-Protocol");
        assertThat(ws.at("/headers/properties/Sec-WebSocket-Protocol/description").asText())
                .contains("cohub.ws.v1.json", "cohub.ws.v1.proto", "cohub.ws.v1.bearer.");
        assertThat(channel.path("description").asText())
                .startsWith("Uploads chunks of a thing.")
                .contains("| 1000 |", "4012 UNIMPLEMENTED", "| 4100 |", "| 1009 |", "| 1002 |");
        assertThat(asyncApi().at("/info/description").asText())
                .contains("{\"data\": <message>}", "{\"end\": true}", "0x00", "0x01", "0x02");
    }

    @Test
    void asyncapi_pluginOutputMissingAChannel_fallsBackToTheBuiltInGenerator() throws Exception {
        String asyncapi = resource("apispec/protoc-gen-connect-openapi.demo.asyncapi.json")
                .replace("/ws/demo.v1.ThingService/UploadChunks", "/ws/elsewhere/UploadChunks");
        writeFakePlugin(CONNECT_OPENAPI, "protoc-gen-connect-openapi v0.28.0",
                Map.of("demo.asyncapi.json", asyncapi));

        Run run = runTool(demoFile(demoService(), null));

        assertThat(run.root().path("asyncapi_generator").asText()).isEqualTo("built-in");
        assertThat(run.warnings()).singleElement().satisfies(warning -> assertThat(warning.path("message").asText())
                .contains("did not pass protolake's check", "/ws/demo.v1.ThingService/UploadChunks",
                        "built-in generator"));
        // The same document shape, from the descriptors.
        JsonNode doc = asyncApi();
        assertThat(doc.path("channels").properties()).extracting(Map.Entry::getKey)
                .containsExactly("demo-v1-ThingService-Collaborate", "demo-v1-ThingService-UploadChunks");
        assertThat(doc.at("/components/schemas/demo.v1.CollaborateRequest/unevaluatedProperties").isMissingNode())
                .isTrue();
        assertThat(run.errors()).isEmpty();
    }

    @Test
    void asyncapi_pluginMissing_fallsBackToTheBuiltInGenerator() throws Exception {
        Files.delete(pluginState.resolve(CONNECT_OPENAPI));

        Run run = runTool(demoFile(demoService(), null));

        assertThat(run.root().path("asyncapi_generator").asText()).isEqualTo("built-in");
        assertThat(run.warnings()).singleElement()
                .satisfies(warning -> assertThat(warning.path("message").asText()).contains("not installed"));
        assertThat(run.specs()).containsExactly("demo.openapi.json", "demo.asyncapi.json");
    }

    @Test
    void asyncapi_builtInGenerator_describesMessagesInProto3Json() throws Exception {
        Files.delete(pluginState.resolve(CONNECT_OPENAPI));

        runTool(demoFile(demoService(), null));

        JsonNode schemas = asyncApi().path("components").path("schemas");
        JsonNode thing = schemas.path("demo.v1.Thing").path("properties");
        assertThat(thing.at("/displayName/type").asText()).isEqualTo("string");
        // 64-bit integers are strings in proto3 JSON.
        assertThat(thing.at("/editCount/type").asText()).isEqualTo("string");
        assertThat(thing.at("/editCount/format").asText()).isEqualTo("int64");
        // A described reference sits in allOf: keywords beside a $ref are ignored.
        assertThat(thing.at("/kind/allOf/0/$ref").asText()).isEqualTo("#/components/schemas/demo.v1.Kind");
        assertThat(thing.at("/kind/description").asText()).isEqualTo("Its kind.");
        assertThat(thing.at("/tags/type").asText()).isEqualTo("object");
        assertThat(thing.at("/tags/additionalProperties/type").asText()).isEqualTo("string");
        assertThat(schemas.at("/demo.v1.Kind/enum")).extracting(JsonNode::asText)
                .containsExactly("KIND_UNSPECIFIED", "KIND_SMALL", "KIND_LARGE");
        assertThat(schemas.at("/demo.v1.CollaborateResponse/properties/editors/type").asText())
                .isEqualTo("array");
        assertThat(schemas.at("/demo.v1.UploadChunksRequest/properties/chunk/format").asText())
                .isEqualTo("byte");
        assertThat(schemas.has("demo.v1.Thing.TagsEntry")).as("map entries are inlined").isFalse();
    }

    @Test
    void asyncapi_builtInGenerator_forbidsSettingTwoFieldsOfAOneof() throws Exception {
        // proto3 JSON parsers reject a CollaborateRequest that sets insertText and deleteCount.
        Files.delete(pluginState.resolve(CONNECT_OPENAPI));

        runTool(demoFile(demoService(), null));

        JsonNode request = asyncApi().at("/components/schemas/demo.v1.CollaborateRequest");
        assertThat(request.at("/not/anyOf")).hasSize(1);
        assertThat(acceptedByNot(request, Set.of("name", "insertText", "deleteCount"))).isFalse();
        assertThat(acceptedByNot(request, Set.of("name", "insertText"))).isTrue();
        assertThat(acceptedByNot(request, Set.of("name", "deleteCount"))).isTrue();
        // Thing's proto3 optional note is a oneof of one: nothing to forbid.
        assertThat(asyncApi().at("/components/schemas/demo.v1.Thing").has("not")).isFalse();
    }

    @Test
    void asyncapi_channelKeysStayDistinctForNamesThatDifferOnlyInDotsAndUnderscores() throws Exception {
        // acme.foo_bar.v1.Chat.Stream and acme.foo.bar.v1.Chat.Stream once shared a key.
        Run run = runTool(chatBundle("acme/foo_bar/v1/chat.proto", "acme.foo_bar.v1",
                "acme/foo/bar/v1/chat.proto", "acme.foo.bar.v1"), "acme");

        assertThat(run.errors()).isEmpty();
        JsonNode channels = JSON.readTree(tempDir.resolve("demo_api_specs/demo.asyncapi.json").toFile())
                .path("channels");
        assertThat(channels.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("acme-foo_bar-v1-Chat-Stream", "acme-foo-bar-v1-Chat-Stream");
        assertThat(channels.path("acme-foo_bar-v1-Chat-Stream").path("address").asText())
                .isEqualTo("/ws/acme.foo_bar.v1.Chat/Stream");
        assertThat(channels.path("acme-foo-bar-v1-Chat-Stream").path("address").asText())
                .isEqualTo("/ws/acme.foo.bar.v1.Chat/Stream");
    }

    @Test
    void asyncapi_rpcsSharingAChannelKey_failInsteadOfOverwritingEachOther() throws Exception {
        // Two files declaring the same RPC: a set protoc would refuse, and the one way to collide.
        Run run = runTool(chatBundle("acme/a/v1/chat.proto", "acme.v1", "acme/b/v1/chat.proto", "acme.v1"), "acme");

        assertThat(run.errors()).singleElement().satisfies(error -> assertThat(error.path("message").asText())
                .contains("share the channel key acme-v1-Chat-Stream"));
        assertThat(specFiles()).isEmpty();
    }

    @Test
    void optionReusingCohubsExtensionNumber_isNotTheWebSocketOption() throws Exception {
        // Another organization's method option at 51701; the set carries no cohub declaration.
        FileDescriptorProto acme = FileDescriptorProto.newBuilder()
                .setName("acme/v1/opts.proto").setPackage("acme.v1").setSyntax("proto3")
                .addDependency("google/protobuf/descriptor.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("Opt")
                        .addField(field("websocket", 1, FieldDescriptorProto.Type.TYPE_BOOL)))
                .addExtension(FieldDescriptorProto.newBuilder().setName("opt").setNumber(51701)
                        .setLabel(LABEL_OPTIONAL).setType(TYPE_MESSAGE).setTypeName(".acme.v1.Opt")
                        .setExtendee(".google.protobuf.MethodOptions").setJsonName("opt"))
                .build();
        // websocket=true on the unary GetThing, encoded at 51701 as cohub's option would be.
        MethodOptions options = MethodOptions.newBuilder()
                .setUnknownFields(com.google.protobuf.UnknownFieldSet.newBuilder()
                        .addField(51701, com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                .addLengthDelimited(MethodExposure.newBuilder().setWebsocket(true).build()
                                        .toByteString())
                                .build())
                        .build())
                .build();
        ServiceDescriptorProto.Builder service = ServiceDescriptorProto.newBuilder().setName("AcmeService")
                .addMethod(method("Get", ".demo.v1.GetThingRequest", ".demo.v1.Thing", false, false, options));
        FileDescriptorProto demo = demoFile(demoService(), null).toBuilder()
                .clearService().addService(service)
                .clearDependency().addDependency("acme/v1/opts.proto")
                .build();

        Run run = runTool(FileDescriptorSet.newBuilder()
                .addFile(DescriptorProtos.getDescriptor().toProto())
                .addFile(acme)
                .addFile(demo)
                .build());

        assertThat(run.findings()).isEmpty();
        assertThat(run.specs()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // The demo bundle: demo.proto in src/test/resources/apispec, as descriptors
    // -------------------------------------------------------------------------

    private static ServiceDescriptorProto.Builder demoService() {
        return ServiceDescriptorProto.newBuilder()
                .setName("ThingService")
                .addMethod(method("GetThing", ".demo.v1.GetThingRequest", ".demo.v1.Thing", false, false,
                        http(HttpRule.newBuilder().setGet("/v1/{name=things/*}").build())))
                .addMethod(method("WatchThings", ".demo.v1.WatchThingsRequest", ".demo.v1.Thing", false, true,
                        http(HttpRule.newBuilder().setGet("/v1/things:watch").build())))
                .addMethod(method("Collaborate", ".demo.v1.CollaborateRequest", ".demo.v1.CollaborateResponse",
                        true, true, websocket()))
                .addMethod(method("UploadChunks", ".demo.v1.UploadChunksRequest",
                        ".demo.v1.UploadChunksResponse", true, false, websocket()))
                .addMethod(method("ExportThing", ".demo.v1.ExportThingRequest", ".demo.v1.ExportThingResponse",
                        false, false, http(HttpRule.newBuilder()
                                .setPost("/v1/{name=things/*}:export").setBody("*").build()).toBuilder()
                                .setExtension(ExposureProto.exposure,
                                        MethodExposure.newBuilder().setCustom(true).build())
                                .build()))
                .addMethod(method("Ping", ".demo.v1.PingRequest", ".demo.v1.PingResponse", false, false,
                        MethodOptions.getDefaultInstance()));
    }

    private static FileDescriptorProto demoFile(ServiceDescriptorProto.Builder service, SourceCodeInfo locations) {
        DescriptorProto tagsEntry = DescriptorProto.newBuilder().setName("TagsEntry")
                .addField(field("key", 1, TYPE_STRING))
                .addField(field("value", 2, TYPE_STRING))
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .build();
        DescriptorProto thing = DescriptorProto.newBuilder().setName("Thing")
                .addField(field("name", 1, TYPE_STRING))
                .addField(field("display_name", 2, TYPE_STRING))
                .addField(field("edit_count", 3, TYPE_INT64))
                .addField(field("kind", 4, TYPE_ENUM).toBuilder().setTypeName(".demo.v1.Kind"))
                .addField(field("tags", 5, TYPE_MESSAGE).toBuilder().setLabel(LABEL_REPEATED)
                        .setTypeName(".demo.v1.Thing.TagsEntry"))
                .addField(field("note", 6, TYPE_STRING).toBuilder().setProto3Optional(true).setOneofIndex(0))
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("_note"))
                .addNestedType(tagsEntry)
                .build();
        FileDescriptorProto.Builder file = FileDescriptorProto.newBuilder()
                .setName(DEMO).setPackage("demo.v1").setSyntax("proto3")
                .addDependency("cohub/api/v1/exposure.proto")
                .addDependency("google/api/annotations.proto")
                .addMessageType(thing)
                .addEnumType(EnumDescriptorProto.newBuilder().setName("Kind")
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("KIND_UNSPECIFIED").setNumber(0))
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("KIND_SMALL").setNumber(1))
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("KIND_LARGE").setNumber(2)))
                .addMessageType(message("GetThingRequest", field("name", 1, TYPE_STRING)))
                .addMessageType(message("WatchThingsRequest", field("filter", 1, TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder().setName("CollaborateRequest")
                        .addField(field("name", 1, TYPE_STRING))
                        .addField(field("insert_text", 2, TYPE_STRING).toBuilder().setOneofIndex(0))
                        .addField(field("delete_count", 3, TYPE_INT32).toBuilder().setOneofIndex(0))
                        .addOneofDecl(OneofDescriptorProto.newBuilder().setName("edit")))
                .addMessageType(message("CollaborateResponse",
                        field("thing", 1, TYPE_MESSAGE).toBuilder().setTypeName(".demo.v1.Thing").build(),
                        field("editors", 2, TYPE_STRING).toBuilder().setLabel(LABEL_REPEATED).build()))
                .addMessageType(message("UploadChunksRequest", field("chunk", 1, TYPE_BYTES)))
                .addMessageType(message("UploadChunksResponse", field("size", 1, TYPE_INT64)))
                .addMessageType(message("ExportThingRequest", field("name", 1, TYPE_STRING),
                        field("format", 2, TYPE_STRING)))
                .addMessageType(message("ExportThingResponse", field("uri", 1, TYPE_STRING)))
                .addMessageType(message("PingRequest"))
                .addMessageType(message("PingResponse", field("pong_only_message", 1, TYPE_STRING)))
                .addService(service);
        // Comments: a method's, which the plugin turned into its channel's description,
        // and a field's, for the built-in generator's descriptions.
        SourceCodeInfo.Builder info = locations == null ? SourceCodeInfo.newBuilder() : locations.toBuilder();
        info.addLocation(location(List.of(6, 0, 2, UPLOAD_CHUNKS), 20, 2).toBuilder()
                .setLeadingComments(" Uploads chunks of a thing.\n"));
        info.addLocation(location(List.of(4, 0, 2, 3), 30, 2).toBuilder()
                .setLeadingComments(" Its kind.\n"));
        return file.setSourceCodeInfo(info).build();
    }

    private static FileDescriptorSet demoSet(FileDescriptorProto demo) {
        return FileDescriptorSet.newBuilder()
                .addFile(DescriptorProtos.getDescriptor().toProto())
                .addFile(HttpProto.getDescriptor().toProto())
                .addFile(AnnotationsProto.getDescriptor().toProto())
                .addFile(ExposureProto.getDescriptor().toProto())
                .addFile(demo)
                .build();
    }

    private static MethodDescriptorProto method(String name, String input, String output,
            boolean clientStreaming, boolean serverStreaming, MethodOptions options) {
        return MethodDescriptorProto.newBuilder().setName(name).setInputType(input).setOutputType(output)
                .setClientStreaming(clientStreaming).setServerStreaming(serverStreaming)
                .setOptions(options).build();
    }

    private static MethodOptions http(HttpRule rule) {
        return MethodOptions.newBuilder().setExtension(AnnotationsProto.http, rule).build();
    }

    private static MethodOptions websocket() {
        return MethodOptions.newBuilder().setExtension(ExposureProto.exposure,
                MethodExposure.newBuilder().setWebsocket(true).build()).build();
    }

    private static DescriptorProto message(String name, FieldDescriptorProto... fields) {
        return DescriptorProto.newBuilder().setName(name).addAllField(List.of(fields)).build();
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                .setLabel(LABEL_OPTIONAL).setJsonName(jsonName(name)).build();
    }

    private static String jsonName(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private static SourceCodeInfo.Location location(List<Integer> path, int line, int column) {
        return SourceCodeInfo.Location.newBuilder().addAllPath(path)
                .addSpan(line).addSpan(column).addSpan(column + 10).build();
    }

    // -------------------------------------------------------------------------
    // Running the tool
    // -------------------------------------------------------------------------

    private record Run(JsonNode root) {
        List<JsonNode> findings() {
            List<JsonNode> out = new ArrayList<>();
            root.path("findings").forEach(out::add);
            return out;
        }

        List<JsonNode> errors() {
            return findings().stream().filter(f -> "ERROR".equals(f.path("severity").asText())).toList();
        }

        List<JsonNode> warnings() {
            return findings().stream().filter(f -> "WARNING".equals(f.path("severity").asText())).toList();
        }

        List<String> specs() {
            List<String> out = new ArrayList<>();
            root.path("specs").forEach(s -> out.add(s.asText()));
            return out;
        }
    }

    private Run runTool(FileDescriptorProto demo) throws Exception {
        return runTool(demoSet(demo));
    }

    private Run runTool(FileDescriptorSet set) throws Exception {
        return runTool(set, "demo");
    }

    private Run runTool(FileDescriptorSet set, String packagePath) throws Exception {
        Path descriptor = tempDir.resolve("demo_api_descriptor.pb");
        Files.write(descriptor, set.toByteArray());
        Path findings = tempDir.resolve("demo_api_findings.json");
        List<String> command = List.of(python3(), tool.toString(),
                "--bundle", "demo", "--package", packagePath, "--bundle-version", "1.4.0",
                "--descriptor-set", descriptor.toString(),
                "--out-dir", tempDir.resolve("demo_api_specs").toString(),
                "--findings", findings.toString(),
                "--openapiv2", pluginState.resolve(OPENAPIV2).toString(),
                "--openapiv2-version", "2.31.0",
                "--openapiv2-sha256", "0000," + sha256(pluginState.resolve(OPENAPIV2)),
                "--connect-openapi", pluginState.resolve(CONNECT_OPENAPI).toString(),
                "--connect-openapi-version", "0.28.0",
                "--connect-openapi-sha256", sha256(pluginState.resolve(CONNECT_OPENAPI)) + ",0000");
        Process process = new ProcessBuilder(command).directory(tempDir.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("tool timed out:\n%s", output).isTrue();
        assertThat(process.exitValue()).as("tool output:\n%s", output).isZero();
        return new Run(JSON.readTree(findings.toFile()));
    }

    private List<String> specFiles() throws IOException {
        Path dir = tempDir.resolve("demo_api_specs");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private JsonNode openApi() throws IOException {
        return JSON.readTree(tempDir.resolve("demo_api_specs/demo.openapi.json").toFile());
    }

    private JsonNode asyncApi() throws IOException {
        return JSON.readTree(tempDir.resolve("demo_api_specs/demo.asyncapi.json").toFile());
    }

    /**
     * A protoc plugin stand-in: answers --version with the given line and every
     * request with the given files, logging each request's parameter and files
     * and keeping its bytes. Like the real generator, it leaves out of a
     * swagger document the operations of methods the request carries no
     * google.api.http binding for.
     */
    private void writeFakePlugin(String name, String version, Map<String, String> outputs) throws Exception {
        Files.writeString(pluginState.resolve(name + ".version"), version);
        Files.writeString(pluginState.resolve(name + ".outputs.json"), JSON.writeValueAsString(outputs));
        Path script = pluginState.resolve(name);
        Files.writeString(script, """
                #!%s
                import json, os, sys
                sys.dont_write_bytecode = True
                sys.path.insert(0, %s)
                import protodesc_generated as pd
                state = os.path.dirname(os.path.abspath(__file__))
                name = os.path.basename(__file__)
                if sys.argv[1:] == ["--version"]:
                    print(open(os.path.join(state, name + ".version")).read())
                    sys.exit(0)
                request = sys.stdin.buffer.read()
                files, parameter, unbound = [], "", set()
                for number, _, value in pd.iter_fields(request):
                    if number == 1:
                        files.append(value.decode())
                    elif number == 2:
                        parameter = value.decode()
                    elif number == 15:
                        proto, _ = pd.parse_file(value)
                        for service in proto.services:
                            for method in service.methods:
                                if not any(n == 72295728 for n, _, _ in pd.iter_fields(method.options)):
                                    unbound.add(service.name + "_" + method.name)
                calls = os.path.join(state, name + ".calls")
                count = sum(1 for _ in open(calls)) if os.path.exists(calls) else 0
                with open(os.path.join(state, "%%s.request-%%d.bin" %% (name, count)), "wb") as out:
                    out.write(request)
                with open(calls, "a") as log:
                    log.write(json.dumps({"files": files, "parameter": parameter}) + "\\n")
                response = b""
                for file_name, content in json.load(open(os.path.join(state, name + ".outputs.json"))).items():
                    if file_name.endswith(".swagger.json"):
                        doc = json.loads(content)
                        for path in list(doc.get("paths", {})):
                            item = doc["paths"][path]
                            for verb in list(item):
                                if isinstance(item[verb], dict) and item[verb].get("operationId") in unbound:
                                    del item[verb]
                            if not item:
                                del doc["paths"][path]
                        content = json.dumps(doc)
                    response += pd.encode_bytes_field(15, pd.encode_bytes_field(1, file_name.encode())
                                                      + pd.encode_bytes_field(15, content.encode()))
                sys.stdout.buffer.write(response)
                """.formatted(python3(), JSON.writeValueAsString(tool.getParent().toString())));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private List<JsonNode> calls(String plugin) throws IOException {
        Path log = pluginState.resolve(plugin + ".calls");
        List<JsonNode> out = new ArrayList<>();
        if (Files.exists(log)) {
            for (String line : Files.readAllLines(log)) {
                out.add(JSON.readTree(line));
            }
        }
        return out;
    }

    /** The plugin's most recent request, its extensions readable. */
    private CodeGeneratorRequest lastRequest(String plugin) throws IOException {
        int count = calls(plugin).size();
        assertThat(count).as("%s was called", plugin).isPositive();
        ExtensionRegistry registry = ExtensionRegistry.newInstance();
        AnnotationsProto.registerAllExtensions(registry);
        ExposureProto.registerAllExtensions(registry);
        return CodeGeneratorRequest.parseFrom(
                Files.readAllBytes(pluginState.resolve(plugin + ".request-" + (count - 1) + ".bin")), registry);
    }

    /** The demo file's methods that carry a google.api.http binding in a request. */
    private static List<String> boundMethods(CodeGeneratorRequest request) {
        return request.getProtoFileList().stream()
                .filter(file -> file.getName().equals(DEMO))
                .flatMap(file -> file.getServiceList().stream())
                .flatMap(service -> service.getMethodList().stream())
                .filter(method -> method.getOptions().hasExtension(AnnotationsProto.http))
                .map(MethodDescriptorProto::getName)
                .toList();
    }

    /** Whether a payload with these keys passes the schema's "not anyOf required" constraint. */
    private static boolean acceptedByNot(JsonNode schema, Set<String> keys) {
        for (JsonNode branch : schema.at("/not/anyOf")) {
            boolean allPresent = true;
            for (JsonNode required : branch.path("required")) {
                allPresent &= keys.contains(required.asText());
            }
            if (allPresent) {
                return false;
            }
        }
        return true;
    }

    /** Two files, each declaring a WebSocket bidi RPC Chat.Stream in its package. */
    private static FileDescriptorSet chatBundle(String firstFile, String firstPackage,
                                                String secondFile, String secondPackage) {
        FileDescriptorSet.Builder set = FileDescriptorSet.newBuilder()
                .addFile(DescriptorProtos.getDescriptor().toProto())
                .addFile(ExposureProto.getDescriptor().toProto());
        for (String[] file : new String[][] {{firstFile, firstPackage}, {secondFile, secondPackage}}) {
            set.addFile(FileDescriptorProto.newBuilder()
                    .setName(file[0]).setPackage(file[1]).setSyntax("proto3")
                    .addDependency("cohub/api/v1/exposure.proto")
                    .addMessageType(message("Line", field("text", 1, TYPE_STRING)))
                    .addService(ServiceDescriptorProto.newBuilder().setName("Chat")
                            .addMethod(method("Stream", "." + file[1] + ".Line", "." + file[1] + ".Line",
                                    true, true, websocket()))));
        }
        return set.build();
    }

    private static String sha256(Path file) throws Exception {
        if (!Files.exists(file)) {
            return "0000";
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = ApiSpecToolTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String python3() throws Exception {
        Process process = new ProcessBuilder("python3", "-c", "import sys; print(sys.executable)")
                .redirectErrorStream(true).start();
        String path = new String(process.getInputStream().readAllBytes()).trim();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        return path;
    }

    private static boolean pythonAvailable() {
        try {
            Process process = new ProcessBuilder("python3", "--version").start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
