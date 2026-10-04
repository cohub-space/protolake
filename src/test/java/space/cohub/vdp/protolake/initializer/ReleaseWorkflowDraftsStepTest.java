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
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the release-PR lookups of the scaffolded {@code release.yml}'s
 * release-please job: the hold step, which drafts every ready release PR
 * before release-please can move it, and the drafts step, which builds the
 * regenerate-release-branch matrix from each release PR release-please opened
 * or moved in the run (the action's {@code prs} output) and every other open
 * draft release PR, once each.
 *
 * <p>Both list every open PR and keep the release PRs in jq. Given
 * {@code --label}, gh answers from the search index, which shows a PR, its
 * label and its draft state only a moment after they change:
 * cohub-protolake#271 stayed a draft with stale BUILD files because a label
 * list ran 0.3 s after release-please opened it.
 *
 * <p>Each step's script runs under {@code bash -e}, as GitHub runs it, with a
 * stub gh. The stub answers a plain list from the open PRs as they are and a
 * {@code --label} list from a separate search-index fixture that can lag; it
 * projects {@code --json} fields and applies gh's default limit of 30, as gh
 * does. Skipped when bash or jq is not on the PATH.
 */
class ReleaseWorkflowDraftsStepTest {

    private static final String TEMPLATE = "/templates/release-please/release.yml";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern OUTPUT_EXPRESSION =
            Pattern.compile("\\$\\{\\{\\s*steps\\.release\\.outputs\\.([a-z_]+)\\s*}}");
    private static final String REPOSITORY = "cohub-space/a-lake";
    private static final String LABEL = "autorelease: pending";
    private static final String BRANCH = "release-please--branches--main";
    private static final String AUTHZ = "release-please--branches--main--components--authz";
    private static final String BOT = "release-please--branches--main--components--bot";

    /**
     * Answers {@code gh pr list} from GH_OPEN_PRS, or from GH_LABEL_SEARCH when
     * given {@code --label}, newest first; records every call, and accepts
     * {@code gh pr ready}.
     */
    private static final String STUB_GH = """
            #!/bin/bash
            printf '%s\\n' "$*" >> "$GH_CALLS"
            case "$1 $2" in
              "pr list") ;;
              "pr ready") exit 0 ;;
              *) echo "stub gh: unexpected call: $*" >&2; exit 2 ;;
            esac
            shift 2
            prs=$GH_OPEN_PRS; fields=; expr=; limit=30
            while [ $# -gt 0 ]; do
              case "$1" in
                -R) ;;
                --state) [ "$2" = open ] || { echo "stub gh: unexpected state $2" >&2; exit 2; } ;;
                --label) [ "$2" = "autorelease: pending" ] || { echo "stub gh: unexpected label $2" >&2; exit 2; }
                         prs=$GH_LABEL_SEARCH ;;
                -L|--limit) limit=$2 ;;
                --json) fields=$2 ;;
                --jq) expr=$2 ;;
                *) echo "stub gh: unexpected argument $1" >&2; exit 2 ;;
              esac
              shift 2
            done
            printf '%s' "$prs" | jq -c --arg fields "$fields" --argjson limit "$limit" '
              ($fields | split(",")) as $keep
              | .[:$limit] | map(with_entries(select(.key as $k | any($keep[]; . == $k))))' \\
              | jq -rc "$expr"
            """;

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireTools() {
        assumeTrue(available("bash", "--version"), "bash not available on PATH");
        assumeTrue(available("jq", "--version"), "jq not available on PATH");
    }

    /** The #271 race: no list shows the PR release-please just opened. */
    @Test
    void aPrOnlyReleasePleaseReported_isRegenerated() throws Exception {
        StepRun run = runStep(tempDir, "drafts", array(), reported(releasePleasePr(271, BRANCH)));

        assertThat(run.matrix()).isEqualTo(entries(271, BRANCH));
        assertThat(tempDir.resolve("pwned")).as("the PR body reaches the script as data").doesNotExist();
    }

    /** The hold step drafted #12 before release-please moved it. */
    @Test
    void aMovedPrAListStillShowsReady_isRegenerated() throws Exception {
        StepRun run = runStep(tempDir, "drafts", array(releasePr(12, false, BRANCH)),
                reported(releasePleasePr(12, BRANCH)));

        assertThat(run.matrix()).isEqualTo(entries(12, BRANCH));
    }

    @Test
    void olderDraftsJoinTheReportedPrs_onceEach_otherPrsStayOut() throws Exception {
        String vdp = "release-please--branches--main--components--vdp";
        String iam = "release-please--branches--main--components--iam";
        StepRun run = runStep(tempDir, "drafts",
                array(featurePr(40, true),
                        releasePr(30, true, vdp),
                        // left a draft by an earlier failed run
                        releasePr(25, true, AUTHZ),
                        // readied again by the hold step: release-please left it alone
                        releasePr(26, false, BOT)),
                reported(releasePleasePr(30, vdp), releasePleasePr(31, iam)));

        assertThat(run.matrix()).isEqualTo(entries(25, AUTHZ, 30, vdp, 31, iam));
    }

    @Test
    void nothingReportedAndNoDraftReleasePr_leavesTheMatrixEmpty() throws Exception {
        StepRun run = runStep(tempDir, "drafts",
                array(featurePr(27, true), releasePr(26, false, BRANCH)),
                Map.of("prs_created", "false"));

        assertThat(run.matrix()).isEqualTo(JSON.createArrayNode());
    }

    @Test
    void noPullRequestOutputsAtAll_leaveTheMatrixEmpty() throws Exception {
        StepRun run = runStep(tempDir, "drafts", array(), Map.of());

        assertThat(run.matrix()).isEqualTo(JSON.createArrayNode());
    }

    @Test
    void aReportedPrTheOutputsCannotName_failsTheStep() throws Exception {
        StepRun missing = runStep(tempDir.resolve("missing"), "drafts", array(),
                Map.of("prs_created", "true"));
        assertThat(missing.exitCode()).as("step output:\n%s", missing.log()).isNotZero();
        assertThat(missing.written()).isEmpty();

        ObjectNode nameless = releasePleasePr(40, BRANCH);
        nameless.remove("headBranchName");
        StepRun unnamed = runStep(tempDir.resolve("nameless"), "drafts", array(), reported(nameless));
        assertThat(unnamed.exitCode()).as("step output:\n%s", unnamed.log()).isNotZero();
        assertThat(unnamed.written()).isEmpty();
    }

    @Test
    void draftsList_readsTheOpenPrsNotTheSearchIndex() throws Exception {
        StepRun run = runStep(tempDir, "drafts",
                array(featurePr(60, true),
                        // a draft release PR whose label the search index has not caught up with
                        releasePr(50, true, AUTHZ),
                        // readied again a moment ago; the search index still shows it a draft
                        releasePr(26, false, BOT)),
                Map.of("prs_created", "false"),
                array(releasePr(26, true, BOT)));

        assertThat(run.matrix()).isEqualTo(entries(50, AUTHZ));
        assertThat(run.listCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).doesNotContain("--label"));
    }

    /**
     * The regenerate job marked #12 ready a moment ago; the search index still
     * shows it a draft. #14 is still a draft; #13 is not a release PR.
     */
    @Test
    void holdStep_holdsAPrMarkedReadyOnlyOnAFreshLookup() throws Exception {
        StepRun run = runStep(tempDir, "hold",
                array(featurePr(13, false), releasePr(12, false, BRANCH), releasePr(14, true, AUTHZ)),
                Map.of(),
                array(releasePr(12, true, BRANCH), releasePr(14, true, AUTHZ)));

        assertThat(run.output("held")).isEqualTo(held(12));
        assertThat(run.calls()).filteredOn(call -> call.startsWith("pr ready"))
                .containsExactly("pr ready --undo 12 -R " + REPOSITORY);
        assertThat(run.listCalls()).isNotEmpty().allSatisfy(call -> assertThat(call).doesNotContain("--label"));
    }

    /** gh lists the newest first; forty newer PRs push the release PRs past 30. */
    @Test
    void bothLists_reachPastGhsDefaultLimit() throws Exception {
        ArrayNode openPrs = JSON.createArrayNode();
        IntStream.iterate(140, n -> n > 100, n -> n - 1).forEach(n -> openPrs.add(featurePr(n, false)));
        openPrs.add(releasePr(12, false, BRANCH));
        openPrs.add(releasePr(14, true, AUTHZ));

        StepRun hold = runStep(tempDir.resolve("hold"), "hold", openPrs, Map.of());
        StepRun drafts = runStep(tempDir.resolve("drafts"), "drafts", openPrs,
                Map.of("prs_created", "false"));

        assertThat(hold.output("held")).isEqualTo(held(12));
        assertThat(drafts.matrix()).isEqualTo(entries(14, AUTHZ));
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
        pr.putArray("labels").add(LABEL);
        pr.putArray("files");
        return pr;
    }

    private static Map<String, String> reported(ObjectNode... prs) {
        return Map.of("prs_created", "true", "prs", array(prs).toString());
    }

    /** An open PR with every field the steps ask gh for. */
    private static ObjectNode openPr(int number, boolean draft, String branch, String label) {
        ObjectNode pr = JSON.createObjectNode();
        pr.put("number", number);
        pr.put("isDraft", draft);
        pr.put("headRefName", branch);
        pr.put("headRefOid", "%040x".formatted(number));
        pr.putArray("labels").addObject().put("name", label).put("color", "ededed");
        return pr;
    }

    private static ObjectNode releasePr(int number, boolean draft, String branch) {
        return openPr(number, draft, branch, LABEL);
    }

    private static ObjectNode featurePr(int number, boolean draft) {
        return openPr(number, draft, "feat/change-" + number, "enhancement");
    }

    private static String held(int number) {
        return number + ":" + "%040x".formatted(number);
    }

    private static ArrayNode array(ObjectNode... nodes) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode node : nodes) {
            array.add(node);
        }
        return array;
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

    /** Runs one step with a search index that has caught up with the open PRs. */
    private StepRun runStep(Path dir, String stepId, ArrayNode openPrs, Map<String, String> outputs)
            throws Exception {
        ArrayNode caughtUp = JSON.createArrayNode();
        for (JsonNode pr : openPrs) {
            boolean release = StreamSupport.stream(pr.path("labels").spliterator(), false)
                    .anyMatch(label -> LABEL.equals(label.path("name").asText()));
            if (release) {
                caughtUp.add(pr);
            }
        }
        return runStep(dir, stepId, openPrs, outputs, caughtUp);
    }

    /**
     * Runs one step of the release-please job: {@code openPrs} is what gh's
     * plain list returns, newest first, and {@code labelSearch} what a
     * {@code --label} list returns from the search index.
     */
    private StepRun runStep(Path dir, String stepId, ArrayNode openPrs, Map<String, String> outputs,
            ArrayNode labelSearch) throws Exception {
        JsonNode step = jobStep(stepId);
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
        env.put("GITHUB_REPOSITORY", REPOSITORY);
        env.put("GITHUB_OUTPUT", output.toString());
        env.put("GH_CALLS", calls.toString());
        env.put("GH_OPEN_PRS", openPrs.toString());
        env.put("GH_LABEL_SEARCH", labelSearch.toString());
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
        List<Map.Entry<String, String>> written = Files.readAllLines(output).stream()
                .map(line -> line.split("=", 2))
                .map(parts -> Map.entry(parts[0], parts.length > 1 ? parts[1] : ""))
                .toList();
        return new StepRun(process.exitValue(), log, written, Files.readAllLines(calls));
    }

    private JsonNode jobStep(String stepId) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(TEMPLATE)) {
            assertThat(in).as("template %s", TEMPLATE).isNotNull();
            JsonNode workflow = new ObjectMapper(new YAMLFactory()).readTree(in);
            return StreamSupport.stream(workflow.at("/jobs/release-please/steps").spliterator(), false)
                    .filter(s -> stepId.equals(s.path("id").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + stepId + " step in " + TEMPLATE));
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

    private record StepRun(int exitCode, String log, List<Map.Entry<String, String>> written,
            List<String> calls) {

        /** The step's single value for an output, once it exited 0. */
        String output(String name) {
            assertThat(exitCode).as("step output:\n%s", log).isZero();
            List<String> values = written.stream()
                    .filter(line -> line.getKey().equals(name))
                    .map(Map.Entry::getValue)
                    .toList();
            assertThat(values).as("%s outputs", name).hasSize(1);
            return values.get(0);
        }

        /** The single {@code prs} output the regenerate job's matrix reads. */
        JsonNode matrix() throws IOException {
            return JSON.readTree(output("prs"));
        }

        List<String> listCalls() {
            return calls.stream().filter(call -> call.startsWith("pr list")).toList();
        }
    }
}
