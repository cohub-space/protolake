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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
 * or moved in the run (the action's {@code prs} output, of which only numbers
 * and head branches reach the environment) and every other open draft release
 * PR, once each, and puts a reported PR that is ready back to draft.
 *
 * <p>Both page through every open PR with the REST API and keep the release
 * PRs in jq. Given {@code --label}, gh answers from the search index, which
 * shows a PR, its label and its draft state only a moment after they change:
 * cohub-protolake#271 stayed a draft with stale BUILD files because a label
 * list ran 0.3 s after release-please opened it.
 *
 * <p>Each step's script runs under the command GitHub runs for its declared
 * shell, with the env GitHub evaluates for it and a stub gh. The stub answers
 * {@code gh api --paginate} a page of 100 PRs at a time, applying
 * {@code --jq} to each page as gh does; for older copies of the workflow it
 * also answers {@code gh pr list}, from the open PRs as they are or, given
 * {@code --label}, from a search-index fixture that can lag, with gh's field
 * projection and default limit of 30. Skipped when bash or jq is not on the
 * PATH. The cohub-protolake repository runs the same cases against its own
 * {@code release.yml} in {@code scripts/test_release_drafts.py}.
 */
class ReleaseWorkflowDraftsStepTest {

    private static final String TEMPLATE = "/templates/release-please/release.yml";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPOSITORY = "cohub-space/a-lake";
    private static final String LABEL = "autorelease: pending";
    private static final String BRANCH = "release-please--branches--main";
    private static final String AUTHZ = "release-please--branches--main--components--authz";
    private static final String BOT = "release-please--branches--main--components--bot";
    private static final String VDP = "release-please--branches--main--components--vdp";
    /** The head repository of a PR whose fork was deleted: the REST API reports it as null. */
    private static final String DELETED_FORK = "";
    /** Linux's MAX_ARG_STRLEN: execve refuses an environment entry this long. */
    private static final int MAX_ENV_ENTRY = 131072;

    private static final Pattern STEP_OUTPUT =
            Pattern.compile("\\$\\{\\{\\s*steps\\.release\\.outputs\\.([a-z_]+)\\s*}}");
    private static final Pattern OUTPUT_FIELD = Pattern.compile(
            "\\$\\{\\{\\s*toJSON\\(fromJSON\\(steps\\.release\\.outputs\\.([a-z_]+) \\|\\| '\\[\\]'\\)"
                    + "\\.\\*\\.([A-Za-z_]+)\\)\\s*}}");
    private static final String NUMBERS =
            "${{ toJSON(fromJSON(steps.release.outputs.prs || '[]').*.number) }}";
    private static final String BRANCHES =
            "${{ toJSON(fromJSON(steps.release.outputs.prs || '[]').*.headBranchName) }}";
    /** What GitHub runs for a step's shell (docs: "Supported shells"); empty is unspecified. */
    private static final Map<String, List<String>> SHELLS = Map.of(
            "", List.of("bash", "-e"),
            "bash", List.of("bash", "--noprofile", "--norc", "-eo", "pipefail"));

    private static final String STUB_GH = """
            #!/bin/bash
            printf '%s\\n' "$*" >> "$GH_CALLS"
            fail() { echo "stub gh: $*" >&2; exit 2; }
            # Applies --jq as gh does: strings raw, everything else as compact JSON.
            emit() { jq -rc "$expr"; }
            case "$1 $2" in
              "pr ready") exit 0 ;;
              "pr list"|api\\ *) [ -z "$GH_FAIL" ] || fail "the lookup failed" ;;
            esac
            case "$1 $2" in
              "pr list")
                shift 2
                prs=$GH_FIXTURES/pr-list.json; fields=; expr=; limit=30
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -R) [ "$2" = "$GITHUB_REPOSITORY" ] || fail "unexpected repository $2" ;;
                    --state) [ "$2" = open ] || fail "unexpected state $2" ;;
                    --label) [ "$2" = "autorelease: pending" ] || fail "unexpected label $2"
                             prs=$GH_FIXTURES/label-search.json ;;
                    -L|--limit) limit=$2 ;;
                    --json) fields=$2 ;;
                    --jq) expr=$2 ;;
                    *) fail "unexpected argument $1" ;;
                  esac
                  shift 2
                done
                jq -c --arg fields "$fields" --argjson limit "$limit" '
                  ($fields | split(",")) as $keep
                  | .[:$limit] | map(with_entries(select(.key as $k | any($keep[]; . == $k))))' "$prs" | emit ;;
              api\\ *)
                shift
                paginate=false; path=; expr=
                while [ $# -gt 0 ]; do
                  case "$1" in
                    --paginate) paginate=true; shift ;;
                    --jq) expr=$2; shift 2 ;;
                    -*) fail "unexpected argument $1" ;;
                    *) path=$1; shift ;;
                  esac
                done
                [ "$path" = "repos/$GITHUB_REPOSITORY/pulls?state=open&per_page=100" ] || fail "unexpected path $path"
                # A page per 100 PRs, the first one only without --paginate.
                jq -c 'if length == 0 then [] else range(0; length; 100) as $i | .[$i:$i + 100] end' \\
                    "$GH_FIXTURES/pulls.json" | { if $paginate; then cat; else head -1; fi; } |
                  while IFS= read -r page; do printf '%s' "$page" | emit || exit 3; done ;;
              *) fail "unexpected call: $*" ;;
            esac
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
        StepRun run = runStep(tempDir, "drafts", List.of(), reported(releasePleasePr(271, BRANCH, 0)));

        assertThat(run.matrix()).isEqualTo(entries(271, BRANCH));
        assertThat(tempDir.resolve("pwned")).as("the PR body reaches the script as data").doesNotExist();
    }

    /** The hold step drafted #12 before release-please moved it. */
    @Test
    void aMovedPrAListStillShowsReady_isRegenerated() throws Exception {
        StepRun run = runStep(tempDir, "drafts", List.of(releasePr(12, false, BRANCH)),
                reported(releasePleasePr(12, BRANCH, 0)));

        assertThat(run.matrix()).isEqualTo(entries(12, BRANCH));
    }

    /** release-please reopened #12 from a snooze, ready; the hold step never saw it. */
    @Test
    void aReportedPrThatIsReady_goesBackToDraft() throws Exception {
        StepRun run = runStep(tempDir, "drafts",
                List.of(featurePr(13, false), releasePr(12, false, BRANCH), releasePr(30, true, VDP),
                        releasePr(26, false, BOT)),
                reported(releasePleasePr(12, BRANCH, 0), releasePleasePr(30, VDP, 0)));

        assertThat(run.matrix()).isEqualTo(entries(12, BRANCH, 30, VDP));
        assertThat(run.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
    }

    @Test
    void olderDraftsJoinTheReportedPrs_onceEach_otherPrsStayOut() throws Exception {
        String iam = "release-please--branches--main--components--iam";
        StepRun run = runStep(tempDir, "drafts",
                List.of(featurePr(40, true),
                        releasePr(30, true, VDP),
                        // left a draft by an earlier failed run
                        releasePr(25, true, AUTHZ),
                        // readied again by the hold step: release-please left it alone
                        releasePr(26, false, BOT)),
                reported(releasePleasePr(30, VDP, 0), releasePleasePr(31, iam, 0)));

        assertThat(run.matrix()).isEqualTo(entries(25, AUTHZ, 30, VDP, 31, iam));
    }

    @Test
    void nothingReportedAndNoDraftReleasePr_leavesTheMatrixEmpty() throws Exception {
        StepRun run = runStep(tempDir, "drafts",
                List.of(featurePr(27, true), releasePr(26, false, BRANCH)),
                Map.of("prs_created", "false"));

        assertThat(run.matrix()).isEqualTo(JSON.createArrayNode());
        assertThat(run.undone()).isEmpty();
    }

    @Test
    void noPullRequestOutputsAtAll_leaveTheMatrixEmpty() throws Exception {
        StepRun run = runStep(tempDir, "drafts", List.of(), Map.of());

        assertThat(run.matrix()).isEqualTo(JSON.createArrayNode());
    }

    @Test
    void aReportedPrTheOutputsCannotName_failsTheStep() throws Exception {
        StepRun missing = runStep(tempDir.resolve("missing"), "drafts", List.of(),
                Map.of("prs_created", "true"));
        assertThat(missing.exitCode()).as("step output:\n%s", missing.log()).isNotZero();
        assertThat(missing.written()).isEmpty();

        ObjectNode nameless = releasePleasePr(40, BRANCH, 0);
        nameless.remove("headBranchName");
        StepRun unnamed = runStep(tempDir.resolve("nameless"), "drafts", List.of(),
                reported(nameless, releasePleasePr(41, BRANCH, 0)));
        assertThat(unnamed.exitCode()).as("step output:\n%s", unnamed.log()).isNotZero();
        assertThat(unnamed.written()).isEmpty();
    }

    /** release-please reports number 0 for a PR it opened from an empty change set. */
    @Test
    void aReportedPrNumberedZero_failsTheStep() throws Exception {
        StepRun run = runStep(tempDir, "drafts", List.of(), reported(releasePleasePr(0, BRANCH, 0)));

        assertThat(run.exitCode()).as("step output:\n%s", run.log()).isNotZero();
        assertThat(run.written()).isEmpty();
    }

    @Test
    void draftsList_readsTheOpenPrsNotTheSearchIndex() throws Exception {
        StepRun run = runStep(tempDir, "drafts",
                List.of(featurePr(60, true),
                        // a draft release PR whose label the search index has not caught up with
                        releasePr(50, true, AUTHZ),
                        // readied again a moment ago; the search index still shows it a draft
                        releasePr(26, false, BOT)),
                Map.of("prs_created", "false"),
                List.of(releasePr(26, true, BOT)), false);

        assertThat(run.matrix()).isEqualTo(entries(50, AUTHZ));
        assertThat(run.lookups()).isNotEmpty().allSatisfy(call -> assertThat(call).doesNotContain("--label"));
    }

    /**
     * The regenerate job marked #12 ready a moment ago; the search index still
     * shows it a draft. #14 is still a draft; #13 is not a release PR.
     */
    @Test
    void holdStep_holdsAPrMarkedReadyOnlyOnAFreshLookup() throws Exception {
        StepRun run = runStep(tempDir, "hold",
                List.of(featurePr(13, false), releasePr(12, false, BRANCH), releasePr(14, true, AUTHZ)),
                Map.of(),
                List.of(releasePr(12, true, BRANCH), releasePr(14, true, AUTHZ)), false);

        assertThat(run.output("held")).isEqualTo(held(12));
        assertThat(run.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
        assertThat(run.lookups()).isNotEmpty().allSatisfy(call -> assertThat(call).doesNotContain("--label"));
    }

    @Test
    void holdStep_neverHoldsAForkPr() throws Exception {
        StepRun run = runStep(tempDir, "hold",
                List.of(forkPr(70, false, "someone/a-lake"), forkPr(72, false, DELETED_FORK),
                        releasePr(12, false, BRANCH)),
                Map.of());

        assertThat(run.output("held")).isEqualTo(held(12));
        assertThat(run.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
    }

    @Test
    void draftsList_neverRegeneratesAForkPr() throws Exception {
        StepRun run = runStep(tempDir, "drafts",
                List.of(forkPr(71, true, "someone/a-lake"), forkPr(73, true, DELETED_FORK),
                        releasePr(14, true, AUTHZ)),
                Map.of("prs_created", "false"));

        assertThat(run.matrix()).isEqualTo(entries(14, AUTHZ));
    }

    /** Newest first: 1,050 newer PRs put the release PRs past any page or limit. */
    @Test
    void bothLookups_seeEveryOpenPr() throws Exception {
        List<OpenPr> openPrs = new ArrayList<>();
        IntStream.iterate(2100, n -> n > 1050, n -> n - 1).forEach(n -> openPrs.add(featurePr(n, false)));
        openPrs.add(releasePr(12, false, BRANCH));
        openPrs.add(releasePr(14, true, AUTHZ));

        StepRun hold = runStep(tempDir.resolve("hold"), "hold", openPrs, Map.of());
        StepRun drafts = runStep(tempDir.resolve("drafts"), "drafts", openPrs,
                Map.of("prs_created", "false"));

        assertThat(hold.output("held")).isEqualTo(held(12));
        assertThat(drafts.matrix()).isEqualTo(entries(14, AUTHZ));
    }

    /** Separate release PRs with bodies near release-please's 65,536-character cap. */
    @Test
    void largePrBodies_neverReachTheEnvironment() throws Exception {
        Map<String, String> outputs = reported(
                releasePleasePr(31, BRANCH + "--components--c31", 65_000),
                releasePleasePr(32, BRANCH + "--components--c32", 65_000),
                releasePleasePr(33, BRANCH + "--components--c33", 65_000));

        Map<String, String> env = stepEnv("drafts", outputs);
        assertThat(env).allSatisfy((name, value) -> {
            assertThat((name + "=" + value).getBytes(StandardCharsets.UTF_8)).hasSizeLessThan(MAX_ENV_ENTRY);
            assertThat(value).doesNotContain("a changelog line");
        });

        StepRun run = runStep(tempDir, "drafts", List.of(), outputs);
        assertThat(run.matrix()).isEqualTo(entries(31, BRANCH + "--components--c31",
                32, BRANCH + "--components--c32", 33, BRANCH + "--components--c33"));
    }

    @Test
    void draftsStep_takesOnlyNumberAndBranchFromPrs() throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> var : jobStep("drafts").path("env").properties()) {
            env.put(var.getKey(), var.getValue().asText());
        }

        assertThat(env).isEqualTo(Map.of("PRS_CREATED", "${{ steps.release.outputs.prs_created }}",
                "REPORTED_NUMBERS", NUMBERS, "REPORTED_BRANCHES", BRANCHES));
    }

    /** A lookup piped into another command fails the step only under pipefail. */
    @Test
    void aFailedLookup_failsTheStep() throws Exception {
        for (String stepId : List.of("hold", "drafts")) {
            List<OpenPr> openPrs = List.of(releasePr(12, false, BRANCH));
            StepRun run = runStep(tempDir.resolve(stepId), stepId, openPrs, Map.of("prs_created", "false"),
                    caughtUp(openPrs), true);

            assertThat(run.exitCode()).as("%s step output:\n%s", stepId, run.log()).isNotZero();
            assertThat(run.written()).as(stepId).isEmpty();
        }
    }

    @Test
    void releasePleaseJob_runsBashWithPipefail() throws Exception {
        JsonNode job = workflow().path("jobs").path("release-please");

        assertThat(job.path("defaults").path("run").path("shell").asText()).isEqualTo("bash");
        assertThat(job.path("steps")).allSatisfy(step -> assertThat(step.has("shell")).isFalse());
    }

    /** An open PR: its number, draft state, head branch, head repository and label. */
    private record OpenPr(int number, boolean draft, String branch, String label, String repo) {

        String sha() {
            return "%040x".formatted(number);
        }

        /** The PR as {@code gh pr list --json} reports it. */
        ObjectNode listed() {
            ObjectNode pr = JSON.createObjectNode();
            pr.put("number", number);
            pr.put("isDraft", draft);
            pr.put("headRefName", branch);
            pr.put("headRefOid", sha());
            pr.putArray("labels").addObject().put("name", label).put("color", "ededed");
            return pr;
        }

        /** The PR as the REST pulls API reports it, abridged. */
        ObjectNode pull() {
            ObjectNode pr = JSON.createObjectNode();
            pr.put("number", number);
            pr.put("state", "open");
            pr.put("draft", draft);
            pr.put("title", "change " + number);
            pr.put("body", "a change");
            ObjectNode head = pr.putObject("head").put("ref", branch).put("sha", sha())
                    .put("label", "cohub-space:" + branch);
            if (repo.equals(DELETED_FORK)) {
                head.putNull("repo");
            } else {
                head.putObject("repo").put("full_name", repo);
            }
            pr.putObject("base").put("ref", "main");
            pr.putArray("labels").addObject().put("name", label).put("color", "ededed");
            return pr;
        }
    }

    private static OpenPr releasePr(int number, boolean draft, String branch) {
        return new OpenPr(number, draft, branch, LABEL, REPOSITORY);
    }

    private static OpenPr featurePr(int number, boolean draft) {
        return new OpenPr(number, draft, "feat/change-" + number, "enhancement", REPOSITORY);
    }

    /** A fork PR labelled as a release PR, its branch named after the base branch. */
    private static OpenPr forkPr(int number, boolean draft, String repo) {
        return new OpenPr(number, draft, "main", LABEL, repo);
    }

    /** The PullRequest object release-please-action reports in {@code prs}. */
    private static ObjectNode releasePleasePr(int number, String branch, int bodySize) {
        ObjectNode pr = JSON.createObjectNode();
        pr.put("headBranchName", branch);
        pr.put("baseBranchName", "main");
        pr.put("number", number);
        pr.put("title", "chore(main): release main");
        // The body is user-controlled text: it must reach the script as data.
        pr.put("body", ":robot: I have created a release *beep* *boop*\n---\n"
                + "## 2.66.0 \"quoted\" 'single' $(touch pwned) `touch pwned`\n"
                + "* fix: a changelog line\n".repeat(bodySize / 24));
        pr.putArray("labels").add(LABEL);
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

    private static String held(int number) {
        return number + ":" + "%040x".formatted(number);
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

    private static ArrayNode array(List<OpenPr> prs, Function<OpenPr, ObjectNode> shape) {
        ArrayNode array = JSON.createArrayNode();
        prs.forEach(pr -> array.add(shape.apply(pr)));
        return array;
    }

    /** The value GitHub gives a step env expression of the forms the steps use. */
    private static String evaluate(String expression, Map<String, String> outputs) throws IOException {
        String text = expression.strip();
        Matcher output = STEP_OUTPUT.matcher(text);
        if (output.matches()) {
            // An output the action did not set reads as an empty string.
            return outputs.getOrDefault(output.group(1), "");
        }
        Matcher field = OUTPUT_FIELD.matcher(text);
        if (field.matches()) {
            // `a || b` is the first truthy operand; `.*.key` skips an item that
            // lacks the key (actions/runner Index.HandleFilteredArray); toJSON
            // pretty-prints.
            String json = outputs.getOrDefault(field.group(1), "");
            ArrayNode values = JSON.createArrayNode();
            for (JsonNode item : JSON.readTree(json.isEmpty() ? "[]" : json)) {
                if (item.has(field.group(2))) {
                    values.add(item.get(field.group(2)));
                }
            }
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(values);
        }
        throw new AssertionError("the harness does not model " + expression);
    }

    private Map<String, String> stepEnv(String stepId, Map<String, String> outputs) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> var : jobStep(stepId).path("env").properties()) {
            env.put(var.getKey(), evaluate(var.getValue().asText(), outputs));
        }
        return env;
    }

    /** What a {@code --label} list returns from a search index that has caught up. */
    private static List<OpenPr> caughtUp(List<OpenPr> openPrs) {
        return openPrs.stream().filter(pr -> LABEL.equals(pr.label())).toList();
    }

    /** Runs one step with a search index that has caught up with the open PRs. */
    private StepRun runStep(Path dir, String stepId, List<OpenPr> openPrs, Map<String, String> outputs)
            throws Exception {
        return runStep(dir, stepId, openPrs, outputs, caughtUp(openPrs), false);
    }

    /**
     * Runs one step of the release-please job. {@code openPrs} are the open PRs
     * as they are, newest first; {@code labelSearch} is what a {@code --label}
     * list returns from the search index; {@code lookupsFail} makes every gh
     * lookup exit non-zero.
     */
    private StepRun runStep(Path dir, String stepId, List<OpenPr> openPrs, Map<String, String> outputs,
            List<OpenPr> labelSearch, boolean lookupsFail) throws Exception {
        JsonNode job = workflow().path("jobs").path("release-please");
        JsonNode step = jobStep(stepId);
        String shell = step.has("shell") ? step.path("shell").asText()
                : job.path("defaults").path("run").path("shell").asText();
        assertThat(SHELLS).as("the shells the test models").containsKey(shell);

        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path gh = bin.resolve("gh");
        Files.writeString(gh, STUB_GH);
        Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwxr-xr-x"));
        // Fixtures go through files: a thousand PRs outgrow an environment entry.
        Path fixtures = Files.createDirectories(dir.resolve("fixtures"));
        Files.writeString(fixtures.resolve("pr-list.json"), array(openPrs, OpenPr::listed).toString());
        Files.writeString(fixtures.resolve("label-search.json"), array(labelSearch, OpenPr::listed).toString());
        Files.writeString(fixtures.resolve("pulls.json"), array(openPrs, OpenPr::pull).toString());
        Path script = dir.resolve("step.sh");
        Files.writeString(script, step.get("run").asText());
        Path output = Files.createFile(dir.resolve("output"));
        Path calls = Files.createFile(dir.resolve("gh-calls"));

        List<String> command = new ArrayList<>(SHELLS.get(shell));
        command.add(script.toString());
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", bin + ":" + System.getenv("PATH"));
        env.put("GITHUB_REPOSITORY", REPOSITORY);
        env.put("GITHUB_OUTPUT", output.toString());
        env.put("GH_CALLS", calls.toString());
        env.put("GH_FIXTURES", fixtures.toString());
        env.put("GH_FAIL", lookupsFail ? "1" : "");
        Map<String, String> stepEnv = stepEnv(stepId, outputs);
        env.putAll(stepEnv);

        Process process = builder.start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        List<Map.Entry<String, String>> written = Files.readAllLines(output).stream()
                .map(line -> line.split("=", 2))
                .map(parts -> Map.entry(parts[0], parts.length > 1 ? parts[1] : ""))
                .toList();
        return new StepRun(process.exitValue(), log, written, Files.readAllLines(calls), stepEnv);
    }

    private JsonNode workflow() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(TEMPLATE)) {
            assertThat(in).as("template %s", TEMPLATE).isNotNull();
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        }
    }

    private JsonNode jobStep(String stepId) throws IOException {
        return StreamSupport.stream(workflow().at("/jobs/release-please/steps").spliterator(), false)
                .filter(s -> stepId.equals(s.path("id").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + stepId + " step in " + TEMPLATE));
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
            List<String> calls, Map<String, String> env) {

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

        List<String> lookups() {
            return calls.stream().filter(call -> !call.startsWith("pr ready")).toList();
        }

        List<String> undone() {
            return calls.stream().filter(call -> call.startsWith("pr ready --undo")).toList();
        }
    }
}
