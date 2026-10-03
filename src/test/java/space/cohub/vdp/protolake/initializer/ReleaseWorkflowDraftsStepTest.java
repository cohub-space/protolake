package space.cohub.vdp.protolake.initializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the drafts step of the scaffolded {@code release.yml}, which builds the
 * regenerate-release-branch matrix: it carries each release PR release-please
 * opened or moved in the run, read from the action's {@code prs} output, and
 * every other open draft release PR, once each. The label list alone misses
 * the run's own PR: gh answers it from the search index, which shows a PR
 * release-please has just opened, and its label, only a moment later
 * (cohub-protolake#271 stayed a draft with stale BUILD files that way).
 *
 * <p>The step's script runs under {@code bash -e}, as GitHub runs it, with a
 * stub gh that answers the label list from a fixture. Skipped when bash or jq
 * is not on the PATH.
 */
class ReleaseWorkflowDraftsStepTest {

    private static final String TEMPLATE = "/templates/release-please/release.yml";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern OUTPUT_EXPRESSION =
            Pattern.compile("\\$\\{\\{\\s*steps\\.release\\.outputs\\.([a-z_]+)\\s*}}");
    private static final String BRANCH = "release-please--branches--main";

    /**
     * Answers {@code gh pr list ... --json ... --jq <expr>} as gh does: the
     * fixture is what the label and state filters return; strings print raw.
     */
    private static final String STUB_GH = """
            #!/bin/bash
            printf '%s\\n' "$*" >> "$GH_CALLS"
            [ "$1 $2" = "pr list" ] || { echo "stub gh: unexpected call: $*" >&2; exit 2; }
            expr=
            while [ $# -gt 0 ]; do
              if [ "$1" = "--jq" ]; then expr=$2; shift; fi
              shift
            done
            printf '%s' "$GH_LIST_FIXTURE" | jq -rc "$expr"
            """;

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireTools() {
        assumeTrue(available("bash", "--version"), "bash not available on PATH");
        assumeTrue(available("jq", "--version"), "jq not available on PATH");
    }

    /** The #271 race: the label list does not show the PR release-please just opened. */
    @Test
    void aPrOnlyReleasePleaseReported_isRegenerated() throws Exception {
        StepRun run = runStep(tempDir, reported(releasePleasePr(271, BRANCH)), "[]");

        assertThat(run.matrix()).isEqualTo(entries(271, BRANCH));
        assertThat(tempDir.resolve("pwned")).as("the PR body reaches the script as data").doesNotExist();
        assertThat(run.ghCalls()).isNotEmpty().allSatisfy(call -> assertThat(call)
                .contains("--label autorelease: pending").contains("--state open"));
    }

    /** The hold step drafted #12 before release-please moved it; the list's index still shows it ready. */
    @Test
    void aMovedPrTheListStillShowsReady_isRegenerated() throws Exception {
        StepRun run = runStep(tempDir, reported(releasePleasePr(12, BRANCH)),
                list(listed(12, false, BRANCH)));

        assertThat(run.matrix()).isEqualTo(entries(12, BRANCH));
    }

    @Test
    void olderDraftsJoinTheReportedPrs_onceEach_readyOnesStayOut() throws Exception {
        String vdp = "release-please--branches--main--components--vdp";
        String iam = "release-please--branches--main--components--iam";
        String authz = "release-please--branches--main--components--authz";
        String bot = "release-please--branches--main--components--bot";
        StepRun run = runStep(tempDir,
                reported(releasePleasePr(30, vdp), releasePleasePr(31, iam)),
                list(listed(30, true, vdp),
                        // left a draft by an earlier failed run
                        listed(25, true, authz),
                        // readied again by the hold step: release-please left it alone
                        listed(26, false, bot)));

        assertThat(run.matrix()).isEqualTo(entries(25, authz, 30, vdp, 31, iam));
    }

    @Test
    void nothingReportedAndNoDraft_leavesTheMatrixEmpty() throws Exception {
        StepRun run = runStep(tempDir, Map.of("prs_created", "false"),
                list(listed(26, false, BRANCH)));

        assertThat(run.matrix()).isEqualTo(JSON.createArrayNode());
    }

    @Test
    void noPullRequestOutputsAtAll_leaveTheMatrixEmpty() throws Exception {
        StepRun run = runStep(tempDir, Map.of(), "[]");

        assertThat(run.matrix()).isEqualTo(JSON.createArrayNode());
    }

    @Test
    void aReportedPrTheOutputsCannotName_failsTheStep() throws Exception {
        StepRun missing = runStep(Files.createDirectories(tempDir.resolve("missing")),
                Map.of("prs_created", "true"), "[]");
        assertThat(missing.exitCode()).as("step output:\n%s", missing.log()).isNotZero();
        assertThat(missing.prs()).isEmpty();

        ObjectNode nameless = releasePleasePr(40, BRANCH);
        nameless.remove("headBranchName");
        StepRun unnamed = runStep(Files.createDirectories(tempDir.resolve("nameless")),
                reported(nameless), "[]");
        assertThat(unnamed.exitCode()).as("step output:\n%s", unnamed.log()).isNotZero();
        assertThat(unnamed.prs()).isEmpty();
    }

    /** The PullRequest object release-please-action reports in {@code prs}. */
    private static ObjectNode releasePleasePr(int number, String branch) {
        ObjectNode pr = JSON.createObjectNode();
        pr.put("headBranchName", branch);
        pr.put("baseBranchName", "main");
        pr.put("number", number);
        pr.put("title", "chore(main): release main");
        // The body is user-controlled text: it must reach the script as data.
        pr.put("body", ":robot: I have created a release *beep* *boop*\n---\n"
                + "## 2.66.0 \"quoted\" 'single' $(touch pwned) `touch pwned`\n");
        pr.putArray("labels").add("autorelease: pending");
        pr.putArray("files");
        return pr;
    }

    private static Map<String, String> reported(ObjectNode... prs) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode pr : prs) {
            array.add(pr);
        }
        return Map.of("prs_created", "true", "prs", array.toString());
    }

    private static ObjectNode listed(int number, boolean draft, String branch) {
        ObjectNode pr = JSON.createObjectNode();
        pr.put("number", number);
        pr.put("isDraft", draft);
        pr.put("headRefName", branch);
        return pr;
    }

    private static String list(ObjectNode... prs) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode pr : prs) {
            array.add(pr);
        }
        return array.toString();
    }

    /** Matrix entries from (number, branch) pairs. */
    private static ArrayNode entries(Object... numberBranchPairs) {
        ArrayNode array = JSON.createArrayNode();
        for (int i = 0; i < numberBranchPairs.length; i += 2) {
            ObjectNode entry = array.addObject();
            entry.put("number", (Integer) numberBranchPairs[i]);
            entry.put("headRefName", (String) numberBranchPairs[i + 1]);
        }
        return array;
    }

    /** Runs the step with release-please's outputs and the label list's answer. */
    private StepRun runStep(Path dir, Map<String, String> outputs, String labelList) throws Exception {
        JsonNode step = draftsStep();
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path gh = bin.resolve("gh");
        Files.writeString(gh, STUB_GH);
        Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path script = dir.resolve("step.sh");
        Files.writeString(script, step.get("run").asText());
        Path output = Files.createFile(dir.resolve("output"));
        Path calls = Files.createFile(dir.resolve("gh-calls"));

        ProcessBuilder builder = new ProcessBuilder("bash", "-e", script.toString())
                .directory(dir.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", bin + ":" + System.getenv("PATH"));
        env.put("GITHUB_REPOSITORY", "cohub-space/a-lake");
        env.put("GITHUB_OUTPUT", output.toString());
        env.put("GH_CALLS", calls.toString());
        env.put("GH_LIST_FIXTURE", labelList);
        for (Map.Entry<String, JsonNode> var : step.path("env").properties()) {
            Matcher expression = OUTPUT_EXPRESSION.matcher(var.getValue().asText().strip());
            assertThat(expression.matches())
                    .as("the test does not model %s: %s", var.getKey(), var.getValue().asText())
                    .isTrue();
            // An output the action did not set reads as an empty string.
            env.put(var.getKey(), outputs.getOrDefault(expression.group(1), ""));
        }

        Process process = builder.start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        List<String> prs = Files.readAllLines(output).stream()
                .filter(line -> line.startsWith("prs="))
                .map(line -> line.substring("prs=".length()))
                .toList();
        return new StepRun(process.exitValue(), log, prs, Files.readAllLines(calls));
    }

    private JsonNode draftsStep() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(TEMPLATE)) {
            assertThat(in).as("template %s", TEMPLATE).isNotNull();
            JsonNode workflow = new ObjectMapper(new YAMLFactory()).readTree(in);
            return StreamSupport.stream(workflow.at("/jobs/release-please/steps").spliterator(), false)
                    .filter(s -> "drafts".equals(s.path("id").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no drafts step in " + TEMPLATE));
        }
    }

    private static boolean available(String command, String versionFlag) {
        try {
            Process process = new ProcessBuilder(command, versionFlag).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private record StepRun(int exitCode, String log, List<String> prs, List<String> ghCalls) {

        /** The single {@code prs} output the regenerate job's matrix reads. */
        JsonNode matrix() throws IOException {
            assertThat(exitCode).as("step output:\n%s", log).isZero();
            assertThat(prs).as("prs outputs").hasSize(1);
            return JSON.readTree(prs.get(0));
        }
    }
}
