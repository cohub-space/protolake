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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the release-PR handling of the scaffolded {@code release.yml}. The
 * release-please job holds every ready release PR before release-please can
 * move it. Only after release-please succeeded, the drafts step judges every
 * open release PR: one release-please reported in the run needs regenerating
 * outright, any other is judged by its branch tip, never by the PR's recorded
 * head, which lags a push. A regenerated one is ready; any other goes back to
 * draft and into the regenerate-release-branch matrix. After a report
 * {@code REPORTED_PRS} does not trust, every release PR is regenerated and the
 * step fails. The drafts step is the only step of the job that readies a
 * release PR. The regenerate job's commit carries a Regenerated-by trailer,
 * made even when nothing changed. release-please's
 * {@code prs} output, of which only numbers and head branches reach the
 * environment, is only a hint for a PR the paged list missed, which is looked
 * up by number and kept only if it is open on a branch of the repository
 * itself.
 *
 * <p>Both lookups page through every open PR with the REST API and keep the
 * release PRs in jq. Given {@code --label}, gh answers from the search index,
 * which shows a PR, its label and its draft state only a moment after they
 * change: cohub-protolake#271 stayed a draft with stale BUILD files because a
 * label list ran 0.3 s after release-please opened it.
 *
 * <p>A job run runs every step in order, each when its {@code if:} lets GitHub
 * run it, with release-please's outcome and outputs given. Each step's script
 * runs under the command GitHub runs for its declared shell, with the env
 * GitHub evaluates for it and a stub gh over fixture files. The stub answers
 * {@code gh api --paginate} a page of 100 PRs at a time, applying {@code --jq}
 * to each page as gh does, or a 503 once a case's scans run out;
 * {@code gh api .../pulls/N}, {@code .../commits/heads/BRANCH} (the tip) and
 * {@code .../commits/SHA} with one object or a 404, or a 503 where a case says
 * so; and {@code gh pr ready [--undo]} by changing the PR's draft state in
 * every view but the search index, so steps run in sequence see each other's
 * changes. For older copies of the workflow it also answers
 * {@code gh pr view} and {@code gh pr list}, from the open PRs as they are or,
 * given {@code --label}, from a search-index fixture that can lag, with gh's
 * field projection and default limit of 30. Skipped when bash or jq is not on
 * the PATH. The cohub-protolake repository runs the same cases against its own
 * {@code release.yml} in {@code scripts/test_release_drafts.py}.
 */
class ReleaseWorkflowDraftsStepTest {

    private static final String TEMPLATE = "/templates/release-please/release.yml";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPOSITORY = "cohub-space/a-lake";
    private static final String LABEL = "autorelease: pending";
    private static final String TRAILER = "Regenerated-by: release-workflow";
    private static final String BRANCH = "release-please--branches--main";
    private static final String AUTHZ = "release-please--branches--main--components--authz";
    private static final String BOT = "release-please--branches--main--components--bot";
    private static final String VDP = "release-please--branches--main--components--vdp";
    /** The head repository of a PR whose fork was deleted: the REST API reports it as null. */
    private static final String DELETED_FORK = "";
    /** Linux's MAX_ARG_STRLEN: execve refuses an environment entry this long. */
    private static final int MAX_ENV_ENTRY = 131072;

    private static final Pattern STEP_OUTPUT =
            Pattern.compile("\\$\\{\\{\\s*steps\\.([a-z_-]+)\\.outputs\\.([a-z_]+)\\s*}}");
    private static final Pattern OUTPUT_FIELD = Pattern.compile(
            "\\$\\{\\{\\s*toJSON\\(fromJSON\\(steps\\.release\\.outputs\\.([a-z_]+) \\|\\| '\\[\\]'\\)"
                    + "\\.\\*\\.([A-Za-z_]+)\\)\\s*}}");
    private static final String NUMBERS =
            "${{ toJSON(fromJSON(steps.release.outputs.prs || '[]').*.number) }}";
    private static final String BRANCHES =
            "${{ toJSON(fromJSON(steps.release.outputs.prs || '[]').*.headBranchName) }}";
    private static final Map<String, String> REPORT_ENV = Map.of(
            "PRS_CREATED", "${{ steps.release.outputs.prs_created }}",
            "REPORTED_NUMBERS", NUMBERS, "REPORTED_BRANCHES", BRANCHES);
    /** How a step reads release-please's report: through REPORTED_PRS, the one definition of a trusted one. */
    private static final String REPORT_CHECK =
            "reported=$(printf '%s\\n%s' \"$REPORTED_NUMBERS\" \"$REPORTED_BRANCHES\""
            + " | jq -cs --arg created \"$PRS_CREATED\" \"$REPORTED_PRS\")";
    private static final Pattern CONDITION =
            Pattern.compile("steps\\.([a-z_-]+)\\.(outputs\\.[a-z_]+|outcome) (==|!=) '([^']*)'");
    /** What GitHub runs for a step's shell (docs: "Supported shells"); empty is unspecified. */
    private static final Map<String, List<String>> SHELLS = Map.of(
            "", List.of("bash", "-e"),
            "bash", List.of("bash", "--noprofile", "--norc", "-eo", "pipefail"));

    private static final String STUB_GH = """
            #!/bin/bash
            printf '%s\\n' "$*" >> "$GH_CALLS"
            F=$GH_FIXTURES
            fail() { echo "stub gh: $*" >&2; exit 2; }
            unavailable() { echo "gh: Service Unavailable (HTTP 503)" >&2; exit 1; }
            # Applies --jq as gh does: strings raw, everything else as compact JSON.
            emit() { jq -rc "$expr"; }
            # Sets PR $1's draft state to $2 in every view of it but the search index.
            set_draft() {
              for f in pulls lookup; do
                jq -c --argjson n "$1" --argjson d "$2" 'map(if .number == $n then .draft = $d else . end)' \\
                  "$F/$f.json" > "$F/$f.tmp" && mv "$F/$f.tmp" "$F/$f.json"
              done
              jq -c --argjson n "$1" --argjson d "$2" 'map(if .number == $n then .isDraft = $d else . end)' \\
                "$F/pr-list.json" > "$F/pr-list.tmp" && mv "$F/pr-list.tmp" "$F/pr-list.json"
            }
            case "$1 $2" in
              "pr list"|"pr view"|api\\ *) [ -z "$GH_FAIL" ] || fail "the lookup failed" ;;
            esac
            case "$1 $2" in
              "pr ready")
                shift 2
                draft=false; n=
                while [ $# -gt 0 ]; do
                  case "$1" in
                    --undo) draft=true; shift ;;
                    -R) [ "$2" = "$GITHUB_REPOSITORY" ] || fail "unexpected repository $2"; shift 2 ;;
                    -*) fail "unexpected argument $1" ;;
                    *) n=$1; shift ;;
                  esac
                done
                set_draft "$n" "$draft" ;;
              "pr view")
                n=$3; shift 3
                fields=; expr=
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -R) [ "$2" = "$GITHUB_REPOSITORY" ] || fail "unexpected repository $2" ;;
                    --json) fields=$2 ;;
                    --jq) expr=$2 ;;
                    *) fail "unexpected argument $1" ;;
                  esac
                  shift 2
                done
                jq -c --argjson n "$n" --arg fields "$fields" '
                  ($fields | split(",")) as $keep
                  | map(select(.number == $n)) | first // error("no such PR")
                  | .state |= ascii_upcase | .headRefOid = .head.sha | .isDraft = .draft
                  | with_entries(select(.key as $k | any($keep[]; . == $k)))' "$F/lookup.json" | emit ;;
              "pr list")
                shift 2
                prs=$F/pr-list.json; fields=; expr=; limit=30
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -R) [ "$2" = "$GITHUB_REPOSITORY" ] || fail "unexpected repository $2" ;;
                    --state) [ "$2" = open ] || fail "unexpected state $2" ;;
                    --label) [ "$2" = "autorelease: pending" ] || fail "unexpected label $2"
                             prs=$F/label-search.json ;;
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
                case "$path" in
                  "repos/$GITHUB_REPOSITORY/pulls?state=open&per_page=100")
                    if [ -e "$F/scans-left" ]; then
                      left=$(cat "$F/scans-left")
                      [ "$left" -gt 0 ] || unavailable
                      echo $((left - 1)) > "$F/scans-left"
                    fi
                    # A page per 100 PRs, the first one only without --paginate.
                    jq -c 'if length == 0 then [] else range(0; length; 100) as $i | .[$i:$i + 100] end' \\
                        "$F/pulls.json" | { if $paginate; then cat; else head -1; fi; } |
                      while IFS= read -r page; do printf '%s' "$page" | emit || exit 3; done ;;
                  "repos/$GITHUB_REPOSITORY/pulls/"[0-9]*)
                    n=${path##*/}
                    if jq -e --argjson n "$n" 'index($n)' "$F/failing-lookups.json" > /dev/null; then unavailable; fi
                    pr=$(jq -c --argjson n "$n" 'map(select(.number == $n)) | first // empty' "$F/lookup.json")
                    [ -n "$pr" ] || { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
                    printf '%s' "$pr" | emit ;;
                  "repos/$GITHUB_REPOSITORY/commits/heads/"?*)
                    branch=${path#"repos/$GITHUB_REPOSITORY/commits/heads/"}
                    if jq -e --arg b "$branch" 'index($b)' "$F/failing-branches.json" > /dev/null; then unavailable; fi
                    commit=$(jq -c --arg b "$branch" '.[$b] // empty' "$F/branches.json")
                    [ -n "$commit" ] || { echo "gh: No commit found for SHA: heads/$branch (HTTP 422)" >&2; exit 1; }
                    printf '%s' "$commit" | emit ;;
                  "repos/$GITHUB_REPOSITORY/commits/"?*)
                    sha=${path##*/}
                    if jq -e --arg s "$sha" 'index($s)' "$F/failing-commits.json" > /dev/null; then unavailable; fi
                    commit=$(jq -c --arg s "$sha" '.[$s] // empty' "$F/commits.json")
                    [ -n "$commit" ] || { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
                    printf '%s' "$commit" | emit ;;
                  *) fail "unexpected path $path" ;;
                esac ;;
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

    /** The #271 race: the label search does not show the PR yet; the open PRs do. */
    @Test
    void aPrReleasePleaseJustOpened_isRegenerated() throws Exception {
        List<Pr> openPrs = List.of(releasePr(271, true, BRANCH));
        StepRun run = runIn(new Repo(tempDir, openPrs, List.of(), List.of(), false, List.of(), List.of()),
                "drafts", reported(releasePleasePr(271, BRANCH, 0)));

        assertThat(run.matrix()).isEqualTo(entries(271, BRANCH));
        assertThat(tempDir.resolve("pwned")).as("the PR body reaches the script as data").doesNotExist();
    }

    @Test
    void aReportedPrWhoseLabelIsNotVisibleYet_isRegenerated() throws Exception {
        StepRun run = runIn(repo(tempDir, List.of(new Pr(271, true, BRANCH, "", REPOSITORY, "open", false, false))),
                "drafts", reported(releasePleasePr(271, BRANCH, 0)));

        assertThat(run.matrix()).isEqualTo(entries(271, BRANCH));
    }

    @Test
    void aReadyPrReleasePleaseMoved_goesBackToDraftAndIsRegenerated() throws Exception {
        Repo repo = repo(tempDir, List.of(releasePr(12, false, BRANCH)));

        StepRun run = runIn(repo, "drafts", reported(releasePleasePr(12, BRANCH, 0)));

        assertThat(run.matrix()).isEqualTo(entries(12, BRANCH));
        assertThat(run.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
        assertThat(repo.draft(12)).isTrue();
    }

    /** release-please reopened #12 from a snooze, ready; the hold step never saw it. */
    @Test
    void aReportedPrThatIsReady_goesBackToDraft() throws Exception {
        StepRun run = runIn(repo(tempDir,
                        List.of(featurePr(13, false), releasePr(12, false, BRANCH), releasePr(30, true, VDP),
                                regeneratedPr(26, false, BOT))),
                "drafts", reported(releasePleasePr(12, BRANCH, 0), releasePleasePr(30, VDP, 0)));

        assertThat(run.matrix()).isEqualTo(entries(12, BRANCH, 30, VDP));
        assertThat(run.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
    }

    @Test
    void olderDraftsJoinTheReportedPrs_onceEach_otherPrsStayOut() throws Exception {
        String iam = "release-please--branches--main--components--iam";
        StepRun run = runIn(repo(tempDir,
                        List.of(releasePr(31, true, iam),
                                featurePr(40, true),
                                releasePr(30, true, VDP),
                                // left a draft by an earlier failed run, and seen on two pages
                                releasePr(25, true, AUTHZ),
                                releasePr(25, true, AUTHZ),
                                // regenerated and readied by an earlier run
                                regeneratedPr(26, false, BOT))),
                "drafts", reported(releasePleasePr(30, VDP, 0), releasePleasePr(31, iam, 0)));

        assertThat(run.matrix()).isEqualTo(entries(25, AUTHZ, 30, VDP, 31, iam));
    }

    @Test
    void nothingReportedAndNothingToRegenerate_leavesTheMatrixEmpty() throws Exception {
        StepRun run = runIn(repo(tempDir, List.of(featurePr(27, true), regeneratedPr(26, false, BRANCH))),
                "drafts", Map.of("prs_created", "false"));

        assertThat(run.matrix()).isEqualTo(JSON.createArrayNode());
        assertThat(run.undone()).isEmpty();
        assertThat(run.readied()).isEmpty();
    }

    @Test
    void noPullRequestOutputsAtAll_leaveTheMatrixEmpty() throws Exception {
        StepRun run = runIn(repo(tempDir, List.of()), "drafts", Map.of());

        assertThat(run.matrix()).isEqualTo(JSON.createArrayNode());
    }

    @Test
    void aReportedPrTheOutputsCannotName_failsTheStepAfterTheListedPrs() throws Exception {
        StepRun missing = runIn(repo(tempDir.resolve("missing"), List.of(releasePr(14, true, AUTHZ))),
                "drafts", Map.of("prs_created", "true"));
        assertThat(missing.exitCode()).as("step output:\n%s", missing.log()).isNotZero();
        assertThat(missing.writtenMatrix()).isEqualTo(entries(14, AUTHZ));

        ObjectNode nameless = releasePleasePr(40, BRANCH, 0);
        nameless.remove("headBranchName");
        StepRun unnamed = runIn(repo(tempDir.resolve("nameless"), List.of(releasePr(14, true, AUTHZ))),
                "drafts", reported(nameless, releasePleasePr(41, BRANCH, 0)));
        assertThat(unnamed.exitCode()).as("step output:\n%s", unnamed.log()).isNotZero();
        assertThat(unnamed.writtenMatrix()).isEqualTo(entries(14, AUTHZ));
    }

    @Test
    void aReportedPrWithAnEmptyBranch_failsTheStep() throws Exception {
        StepRun run = runIn(repo(tempDir, List.of(releasePr(40, true, BRANCH))),
                "drafts", reported(releasePleasePr(40, "", 0)));

        assertThat(run.exitCode()).as("step output:\n%s", run.log()).isNotZero();
    }

    /** release-please reports number 0 for a PR it opened from an empty change set. */
    @Test
    void aReportedPrNumberedZero_failsTheStep() throws Exception {
        StepRun run = runIn(repo(tempDir, List.of()), "drafts", reported(releasePleasePr(0, BRANCH, 0)));

        assertThat(run.exitCode()).as("step output:\n%s", run.log()).isNotZero();
    }

    @Test
    void draftsList_readsTheOpenPrsNotTheSearchIndex() throws Exception {
        List<Pr> openPrs = List.of(featurePr(60, true),
                // a draft release PR whose label the search index has not caught up with
                releasePr(50, true, AUTHZ),
                // regenerated and readied a moment ago; the search index still shows it a draft
                regeneratedPr(26, false, BOT));
        StepRun run = runIn(new Repo(tempDir, openPrs, List.of(), List.of(releasePr(26, true, BOT)), false,
                List.of(), List.of()), "drafts", Map.of("prs_created", "false"));

        assertThat(run.matrix()).isEqualTo(entries(50, AUTHZ));
        assertThat(run.lookups()).isNotEmpty().allSatisfy(call -> assertThat(call).doesNotContain("--label"));
    }

    /**
     * A PR closed during the paged read moved #12 onto a page already read;
     * release-please reopened #12 from a snooze, ready, and moved it.
     */
    @Test
    void aReportedPrTheListMissed_isLookedUpAndRegenerated() throws Exception {
        List<Pr> openPrs = List.of(releasePr(14, true, AUTHZ));
        StepRun run = runIn(new Repo(tempDir, openPrs, List.of(releasePr(12, false, BRANCH)), caughtUp(openPrs),
                false, List.of(), List.of()), "drafts", reported(releasePleasePr(12, BRANCH, 0)));

        assertThat(run.matrix()).isEqualTo(entries(12, BRANCH, 14, AUTHZ));
        assertThat(run.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
        assertThat(run.annotations("warning")).isEmpty();
    }

    /** release-please matched #75, a ready fork PR, by its branch name; #76 is closed. */
    @Test
    void aReportedPrNotOpenOnThisRepository_isDroppedWithAWarning() throws Exception {
        List<Pr> openPrs = List.of(forkPr(75, false, "someone/a-lake", BRANCH), releasePr(14, true, AUTHZ));
        StepRun run = runIn(new Repo(tempDir, openPrs, List.of(closedPr(76, VDP)), caughtUp(openPrs), false,
                        List.of(), List.of()),
                "drafts", reported(releasePleasePr(75, BRANCH, 0), releasePleasePr(76, VDP, 0)));

        assertThat(run.matrix()).isEqualTo(entries(14, AUTHZ));
        assertThat(run.undone()).isEmpty();
        assertThat(run.annotations("warning")).satisfiesExactly(
                warning -> assertThat(warning).contains("#75"),
                warning -> assertThat(warning).contains("#76"));
    }

    /** #12, reported and missed by the list, cannot be looked up; #20 and #14 were listed. */
    @Test
    void aFailedLookup_stillHandlesTheVerifiedPrsThenFailsTheStep() throws Exception {
        List<Pr> openPrs = List.of(releasePr(20, false, VDP), releasePr(14, true, AUTHZ));
        StepRun run = runIn(new Repo(tempDir, openPrs, List.of(releasePr(12, false, BRANCH)), caughtUp(openPrs),
                false, List.of(12), List.of()), "drafts", reported(releasePleasePr(12, BRANCH, 0)));

        assertThat(run.exitCode()).as("step output:\n%s", run.log()).isNotZero();
        assertThat(run.writtenMatrix()).isEqualTo(entries(14, AUTHZ, 20, VDP));
        assertThat(run.undone()).containsExactly("pr ready --undo 20 -R " + REPOSITORY);
        assertThat(run.annotations("error")).anySatisfy(error -> assertThat(error).contains("#12"));
    }

    @Test
    void anUnreadableBranchTip_countsAsNotRegenerated() throws Exception {
        List<Pr> openPrs = List.of(regeneratedPr(20, false, VDP), releasePr(14, true, AUTHZ));
        StepRun run = runIn(new Repo(tempDir, openPrs, List.of(), caughtUp(openPrs), false, List.of(),
                List.of(20)), "drafts", Map.of("prs_created", "false"));

        assertThat(run.exitCode()).as("step output:\n%s", run.log()).isNotZero();
        assertThat(run.writtenMatrix()).isEqualTo(entries(14, AUTHZ, 20, VDP));
        assertThat(run.undone()).containsExactly("pr ready --undo 20 -R " + REPOSITORY);
    }

    @Test
    void aRetryAfterAFailedLookup_regeneratesThePrRatherThanReadyingIt() throws Exception {
        // Run 1: release-please reopened #12 ready and moved it; the list missed
        // it and its lookup failed.
        List<Pr> openPrs = List.of(releasePr(14, true, AUTHZ));
        StepRun first = runIn(new Repo(tempDir.resolve("first"), openPrs, List.of(releasePr(12, false, BRANCH)),
                caughtUp(openPrs), false, List.of(12), List.of()), "drafts", reported(releasePleasePr(12, BRANCH, 0)));
        assertThat(first.exitCode()).isNotZero();

        // The retry: release-please reports nothing; #14 was regenerated meanwhile.
        Repo retry = repo(tempDir.resolve("retry"),
                List.of(releasePr(12, false, BRANCH), regeneratedPr(14, false, AUTHZ)));
        JobRun job = runJob(retry, Map.of("prs_created", "false"));

        assertThat(job.readied()).containsExactly("pr ready 14 -R " + REPOSITORY);
        assertThat(job.step("drafts").matrix()).isEqualTo(entries(12, BRANCH));
        assertThat(retry.draft(12)).isTrue();
        assertThat(retry.draft(14)).isFalse();
    }

    @Test
    void aPrTheListMissed_isRegeneratedOnTheNextRunWithNothingReported() throws Exception {
        // Run 1: the list missed #12, ready since release-please reopened it, and
        // release-please reported nothing.
        List<Pr> openPrs = List.of(releasePr(14, true, AUTHZ));
        StepRun first = runIn(new Repo(tempDir.resolve("first"), openPrs, List.of(releasePr(12, false, BRANCH)),
                caughtUp(openPrs), false, List.of(), List.of()), "drafts", Map.of("prs_created", "false"));
        assertThat(first.matrix()).isEqualTo(entries(14, AUTHZ));

        Repo next = repo(tempDir.resolve("next"),
                List.of(releasePr(12, false, BRANCH), regeneratedPr(14, false, AUTHZ)));
        JobRun job = runJob(next, Map.of("prs_created", "false"));

        assertThat(job.step("drafts").matrix()).isEqualTo(entries(12, BRANCH));
        assertThat(next.draft(12)).isTrue();
    }

    /** release-please moved #12 a moment ago; neither its recorded head nor its branch reads its push yet. */
    @Test
    void aReportedPrWhoseHeadStillReadsAsTheRegenerateCommit_isRegenerated() throws Exception {
        Repo repo = repo(tempDir, List.of(regeneratedPr(12, false, BRANCH), regeneratedPr(14, false, AUTHZ)));

        StepRun run = runIn(repo, "drafts", reported(releasePleasePr(12, BRANCH, 0)));

        assertThat(run.matrix()).isEqualTo(entries(12, BRANCH));
        assertThat(run.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
        assertThat(repo.draft(12)).isTrue();
        assertThat(repo.draft(14)).isFalse();
    }

    /**
     * release-please moved #12 a moment ago and its branch does not read the
     * push yet; #14, regenerated and not reported, is readied.
     */
    @Test
    void aReportedPr_isNeverReadied_evenIfItsTipReadsAsTheRegenerateCommit() throws Exception {
        Repo repo = repo(tempDir, List.of(regeneratedPr(12, false, BRANCH), regeneratedPr(14, false, AUTHZ)));

        JobRun job = runJob(repo, reported(releasePleasePr(12, BRANCH, 0)));

        assertThat(job.readied()).containsExactly("pr ready 14 -R " + REPOSITORY);
        assertThat(job.step("drafts").matrix()).isEqualTo(entries(12, BRANCH));
        assertThat(repo.draft(12)).isTrue();
        assertThat(repo.draft(14)).isFalse();
    }

    /**
     * The hold step drafts #12; release-please moves and reports it, but its
     * branch still reads as the regenerate commit; then the drafts step's scan
     * fails, so no step after it would put #12 back to draft.
     */
    @Test
    void aReportedPr_staysADraftWhenItsTipReadLagsAndTheDraftsScanFails() throws Exception {
        Repo repo = repo(tempDir, List.of(regeneratedPr(12, false, BRANCH)));
        repo.limitScans(1);

        JobRun job = runJob(repo, reported(releasePleasePr(12, BRANCH, 0)));

        StepRun drafts = job.step("drafts");
        assertThat(drafts.exitCode()).as("step output:\n%s", drafts.log()).isNotZero();
        assertThat(drafts.written()).isEmpty();
        assertThat(job.readied()).isEmpty();
        assertThat(repo.draft(12)).isTrue();
    }

    @Test
    void inTheReleasePleaseJob_onlyTheDraftsStepReadiesAReleasePr() throws Exception {
        List<String> readying = new ArrayList<>();
        for (JsonNode step : workflow().at("/jobs/release-please/steps")) {
            if (Pattern.compile("gh pr ready (?!--undo)").matcher(step.path("run").asText()).find()) {
                readying.add(step.has("id") ? step.path("id").asText() : step.path("name").asText());
            }
        }

        assertThat(readying).containsExactly("drafts");
    }

    @Test
    void draftsStep_trustsAReportByTheOneDefinition() throws Exception {
        String script = jobStep("drafts").path("run").asText();

        assertThat(env(jobStep("drafts"))).isEqualTo(REPORT_ENV);
        assertThat(script.split(Pattern.quote(REPORT_CHECK), -1)).hasSize(2);
        assertThat(script).doesNotContain("transpose");
    }

    @Test
    void aReport_isTrustedOnlyIfItNamesEachPrByWholeNumberAndBranch() throws Exception {
        assertThat(checkReport(reported(releasePleasePr(12, BRANCH, 0), releasePleasePr(30, VDP, 0))))
                .isEqualTo(entries(12, BRANCH, 30, VDP));
        assertThat(checkReport(Map.of("prs_created", "false"))).isEqualTo(JSON.createArrayNode());
        assertThat(checkReport(Map.of())).isEqualTo(JSON.createArrayNode());
        Map<String, Map<String, String>> untrusted = new LinkedHashMap<>();
        untrusted.put("none named", Map.of("prs_created", "true"));
        untrusted.put("no number", changed(pr -> pr.remove("number")));
        untrusted.put("no branch", changed(pr -> pr.remove("headBranchName")));
        untrusted.put("number as a string", changed(pr -> pr.put("number", "30")));
        untrusted.put("number zero", changed(pr -> pr.put("number", 0)));
        untrusted.put("fractional number", changed(pr -> pr.put("number", 30.5)));
        untrusted.put("empty branch", changed(pr -> pr.put("headBranchName", "")));
        untrusted.put("branch not a string", changed(pr -> pr.put("headBranchName", 30)));
        for (Map.Entry<String, Map<String, String>> report : untrusted.entrySet()) {
            assertThat(checkReport(report.getValue())).as(report.getKey()).isNull();
        }
    }

    /**
     * The hold step drafts #12 and #14; release-please pushes #12's branch and
     * then fails before it reports anything, and #12's tip still reads as the
     * regenerate commit.
     */
    @Test
    void aFailedReleasePleaseRun_readiesNothing() throws Exception {
        Repo repo = repo(tempDir.resolve("failed"),
                List.of(regeneratedPr(12, false, BRANCH), regeneratedPr(14, false, AUTHZ)));

        JobRun job = runJob(repo, Map.of(), "failure");

        assertThat(job.readied()).isEmpty();
        assertThat(job.step("drafts")).isNull();
        assertThat(repo.draft(12)).isTrue();
        assertThat(repo.draft(14)).isTrue();

        // The next run: #12's branch reads as release-please's commit, #14's as
        // the regenerate job's, and release-please reports nothing.
        Repo next = repo(tempDir.resolve("next"), List.of(releasePr(12, true, BRANCH), regeneratedPr(14, true, AUTHZ)));
        JobRun nextJob = runJob(next, Map.of("prs_created", "false"));

        assertThat(nextJob.step("drafts").matrix()).isEqualTo(entries(12, BRANCH));
        assertThat(next.draft(12)).isTrue();
        assertThat(next.draft(14)).isFalse();
    }

    /**
     * #30 is reported without its number or with its number as a string, or
     * release-please says it created a PR and names none. Every branch reads
     * as the regenerate commit; #12's and #30's lag release-please's push.
     */
    @Test
    void aReportThatCannotBeTrusted_readiesNothingAndRegeneratesEveryReleasePr() throws Exception {
        ObjectNode nameless = releasePleasePr(30, VDP, 0);
        nameless.remove("number");
        ObjectNode string = releasePleasePr(30, VDP, 0);
        string.put("number", "30");
        Map<String, Map<String, String>> reports = new LinkedHashMap<>();
        reports.put("nameless", reported(releasePleasePr(12, BRANCH, 0), nameless));
        reports.put("string", reported(releasePleasePr(12, BRANCH, 0), string));
        reports.put("none-named", Map.of("prs_created", "true"));
        for (Map.Entry<String, Map<String, String>> report : reports.entrySet()) {
            Repo repo = repo(tempDir.resolve(report.getKey()), List.of(regeneratedPr(12, false, BRANCH),
                    regeneratedPr(14, false, AUTHZ), regeneratedPr(30, false, VDP)));

            JobRun job = runJob(repo, report.getValue());

            assertThat(job.readied()).as(report.getKey()).isEmpty();
            assertThat(job.step("drafts").exitCode()).as(report.getKey()).isNotZero();
            assertThat(job.step("drafts").writtenMatrix()).as(report.getKey())
                    .isEqualTo(entries(12, BRANCH, 14, AUTHZ, 30, VDP));
            assertThat(List.of(repo.draft(12), repo.draft(14), repo.draft(30))).as(report.getKey())
                    .containsOnly(true);
        }
    }

    /**
     * release-please reopened #12 from a snooze, ready, and moved it; the list
     * missed it. Its entry is whole, but the report is not. #14, ready on a
     * regenerated branch, goes back to draft as well.
     */
    @Test
    void anUntrustedReport_stillRegeneratesEachPrItNumbers() throws Exception {
        ObjectNode nameless = releasePleasePr(30, VDP, 0);
        nameless.remove("number");
        List<Pr> openPrs = List.of(regeneratedPr(14, false, AUTHZ));
        Repo repo = new Repo(tempDir, openPrs, List.of(releasePr(12, false, BRANCH)), caughtUp(openPrs), false,
                List.of(), List.of());

        StepRun run = runIn(repo, "drafts", reported(releasePleasePr(12, BRANCH, 0), nameless));

        assertThat(run.exitCode()).as("step output:\n%s", run.log()).isNotZero();
        assertThat(run.writtenMatrix()).isEqualTo(entries(12, BRANCH, 14, AUTHZ));
        assertThat(run.undone()).containsExactlyInAnyOrder(
                "pr ready --undo 12 -R " + REPOSITORY, "pr ready --undo 14 -R " + REPOSITORY);
        assertThat(run.readied()).isEmpty();
    }

    /** release-please's outputs reporting #12 and #30, with #30's entry changed. */
    private static Map<String, String> changed(Consumer<ObjectNode> change) {
        ObjectNode pr = releasePleasePr(30, VDP, 0);
        change.accept(pr);
        return reported(releasePleasePr(12, BRANCH, 0), pr);
    }

    /**
     * {@code REPORTED_PRS} on the env a step gets from release-please's
     * outputs: the reported PRs, or null for a report it does not trust.
     */
    private JsonNode checkReport(Map<String, String> outputs) throws Exception {
        Map<String, String> env = stepEnv("drafts", outputs);
        String program = workflow().at("/jobs/release-please/env/REPORTED_PRS").asText();
        assertThat(program).isNotEmpty();
        Command result = run(tempDir, Map.of(), env.get("REPORTED_NUMBERS") + "\n" + env.get("REPORTED_BRANCHES"),
                "jq", "-cs", "--arg", "created", env.get("PRS_CREATED"), program);
        return result.exitCode() == 0 ? JSON.readTree(result.output()) : null;
    }

    private static Map<String, String> env(JsonNode step) {
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> var : step.path("env").properties()) {
            env.put(var.getKey(), var.getValue().asText());
        }
        return env;
    }

    /**
     * #12's branch has release-please's commit and #14's the regenerate job's,
     * but each PR still records the head before.
     */
    @Test
    void aPrWhoseRecordedHeadLagsItsBranch_isJudgedByTheBranch() throws Exception {
        Repo repo = repo(tempDir, List.of(releasePr(12, false, BRANCH).recording(true),
                regeneratedPr(14, true, AUTHZ).recording(false)));

        JobRun job = runJob(repo, Map.of("prs_created", "false"));

        assertThat(job.readied()).containsExactly("pr ready 14 -R " + REPOSITORY);
        assertThat(job.step("drafts").matrix()).isEqualTo(entries(12, BRANCH));
        assertThat(repo.draft(12)).isTrue();
        assertThat(repo.draft(14)).isFalse();
    }

    @Test
    void aRegeneratedPrIsReadied_andAReleasePleaseHeadedPrNeverIs() throws Exception {
        Repo repo = repo(tempDir, List.of(
                regeneratedPr(12, false, BRANCH),
                releasePr(13, false, VDP),
                // regenerated, but its ready call failed in an earlier run
                regeneratedPr(14, true, AUTHZ),
                releasePr(15, true, BOT)));

        JobRun job = runJob(repo, Map.of("prs_created", "false"));

        assertThat(job.step("drafts").matrix()).isEqualTo(entries(13, VDP, 15, BOT));
        assertThat(List.of(repo.draft(12), repo.draft(13), repo.draft(14), repo.draft(15)))
                .containsExactly(false, true, false, true);
        assertThat(job.readied())
                .noneMatch(call -> call.startsWith("pr ready 13 ") || call.startsWith("pr ready 15 "));
    }

    /**
     * The regenerate job marked #12 ready a moment ago; the search index still
     * shows it a draft. #14 is still a draft; #13 is not a release PR.
     */
    @Test
    void holdStep_holdsAPrMarkedReadyOnlyOnAFreshLookup() throws Exception {
        List<Pr> openPrs = List.of(featurePr(13, false), regeneratedPr(12, false, BRANCH), releasePr(14, true, AUTHZ));
        StepRun run = runIn(new Repo(tempDir, openPrs, List.of(),
                List.of(releasePr(12, true, BRANCH), releasePr(14, true, AUTHZ)), false, List.of(), List.of()),
                "hold", Map.of());

        assertThat(run.exitCode()).as("step output:\n%s", run.log()).isZero();
        assertThat(run.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
        assertThat(run.lookups()).isNotEmpty().allSatisfy(call -> assertThat(call).doesNotContain("--label"));
    }

    @Test
    void holdStep_neverHoldsAForkPr() throws Exception {
        StepRun run = runIn(repo(tempDir, List.of(forkPr(70, false, "someone/a-lake", "main"),
                forkPr(72, false, DELETED_FORK, "main"), regeneratedPr(12, false, BRANCH))), "hold", Map.of());

        assertThat(run.exitCode()).as("step output:\n%s", run.log()).isZero();
        assertThat(run.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
    }

    @Test
    void draftsList_neverRegeneratesAForkPr() throws Exception {
        StepRun run = runIn(repo(tempDir, List.of(forkPr(71, true, "someone/a-lake", "main"),
                        forkPr(73, true, DELETED_FORK, "main"), releasePr(14, true, AUTHZ))),
                "drafts", Map.of("prs_created", "false"));

        assertThat(run.matrix()).isEqualTo(entries(14, AUTHZ));
    }

    /** Newest first: 1,050 newer PRs put the release PRs past any page or limit. */
    @Test
    void bothLookups_seeEveryOpenPr() throws Exception {
        List<Pr> openPrs = new ArrayList<>();
        IntStream.iterate(2100, n -> n > 1050, n -> n - 1).forEach(n -> openPrs.add(featurePr(n, false)));
        openPrs.add(regeneratedPr(12, false, BRANCH));
        openPrs.add(releasePr(14, true, AUTHZ));

        StepRun hold = runIn(repo(tempDir.resolve("hold"), openPrs), "hold", Map.of());
        StepRun drafts = runIn(repo(tempDir.resolve("drafts"), openPrs), "drafts", Map.of("prs_created", "false"));

        assertThat(hold.exitCode()).as("step output:\n%s", hold.log()).isZero();
        assertThat(hold.undone()).containsExactly("pr ready --undo 12 -R " + REPOSITORY);
        assertThat(drafts.matrix()).isEqualTo(entries(14, AUTHZ));
    }

    /** Separate release PRs with bodies near release-please's 65,536-character cap. */
    @Test
    void largePrBodies_neverReachTheEnvironment() throws Exception {
        Map<String, String> outputs = reported(
                releasePleasePr(31, BRANCH + "--components--c31", 65_000),
                releasePleasePr(32, BRANCH + "--components--c32", 65_000),
                releasePleasePr(33, BRANCH + "--components--c33", 65_000));

        assertThat(stepEnv("drafts", outputs)).allSatisfy((name, value) -> {
            assertThat((name + "=" + value).getBytes(StandardCharsets.UTF_8)).hasSizeLessThan(MAX_ENV_ENTRY);
            assertThat(value).doesNotContain("a changelog line");
        });

        StepRun run = runIn(repo(tempDir, List.of(releasePr(31, true, BRANCH + "--components--c31"),
                releasePr(32, true, BRANCH + "--components--c32"), releasePr(33, true, BRANCH + "--components--c33"))),
                "drafts", outputs);
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
            List<Pr> openPrs = List.of(releasePr(12, false, BRANCH));
            StepRun run = runIn(new Repo(tempDir.resolve(stepId), openPrs, List.of(), caughtUp(openPrs), true,
                    List.of(), List.of()), stepId, Map.of("prs_created", "false"));

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

    /**
     * The drafts step fails after handling every PR it could read, and a re-run
     * finds no new release to publish.
     */
    @Test
    void aFailedReleasePleaseJob_stillRegeneratesAndPublishes() throws Exception {
        JsonNode jobs = workflow().path("jobs");

        for (String name : List.of("regenerate-release-branch", "publish")) {
            assertThat(jobs.path(name).path("if").asText().replace(" ", "")).as(name).startsWith("${{!cancelled()&&");
        }
    }

    @Test
    void regenerateCommit_carriesTheTrailerEvenWhenNothingChanged() throws Exception {
        assumeTrue(available("git", "--version"), "git not available on PATH");
        String commitStep = StreamSupport.stream(
                        workflow().at("/jobs/regenerate-release-branch/steps").spliterator(), false)
                .filter(s -> "Commit the regenerated files to the release branch".equals(s.path("name").asText()))
                .findFirst().orElseThrow().get("run").asText();
        Path origin = tempDir.resolve("origin.git");
        Path lake = tempDir.resolve("lake");
        git(tempDir, "init", "-q", "--bare", origin.toString());
        git(tempDir, "init", "-q", "-b", BRANCH, lake.toString());
        for (String path : List.of("a/BUILD.bazel", ".bazelrc.remote-cache", "MODULE.bazel", "package.json",
                "pnpm-lock.yaml", "protolakew", "tools/defs.bzl")) {
            Files.createDirectories(lake.resolve(path).getParent());
            Files.writeString(lake.resolve(path), "generated\n");
        }
        git(lake, "add", "-A");
        git(lake, "-c", "user.name=release-please", "-c", "user.email=rp@example.com",
                "commit", "-q", "-m", "chore(main): release main");
        git(lake, "remote", "add", "origin", origin.toString());
        git(lake, "push", "-q", "origin", "HEAD:" + BRANCH);
        String regenerated = workflow().at("/jobs/release-please/env/REGENERATED_HEAD").asText();

        for (String change : new String[] {null, "a/BUILD.bazel"}) {
            if (change != null) {
                Files.writeString(lake.resolve(change), "regenerated\n");
            }
            Command step = run(lake, Map.of("HOME", tempDir.toString(), "RELEASE_BRANCH", BRANCH),
                    null, "bash", "-e", "-c", commitStep);
            assertThat(step.exitCode()).as("commit step output:\n%s", step.output()).isZero();
            String message = git(origin, "log", "-1", "--format=%B", BRANCH).strip();
            String commit = JSON.writeValueAsString(
                    JSON.createObjectNode().set("commit", JSON.createObjectNode().put("message", message)));
            assertThat(run(tempDir, Map.of(), commit, "jq", "-r", regenerated).output().strip())
                    .as("%s: %s", change, message).isEqualTo("true");
            assertThat(git(origin, "show", BRANCH + ":a/BUILD.bazel"))
                    .isEqualTo(change != null ? "regenerated\n" : "generated\n");
        }
    }

    /**
     * A PR: its number, draft state, head branch, label, head repository and
     * state; whether its branch tip is the regenerate job's commit or
     * release-please's; and the same of the head the PR records, which lags a
     * push.
     */
    private record Pr(int number, boolean draft, String branch, String label, String repo, String state,
            boolean regenerated, boolean recorded) {

        /** The branch tip. */
        String sha() {
            return "%040x".formatted(number);
        }

        String recordedSha() {
            return recorded == regenerated ? sha() : "f" + "%039x".formatted(number);
        }

        /** The same PR, recording a head the branch has moved on from. */
        Pr recording(boolean recordedHead) {
            return new Pr(number, draft, branch, label, repo, state, regenerated, recordedHead);
        }

        /** The PR as {@code gh pr list --json} reports it. */
        ObjectNode listed() {
            ObjectNode pr = JSON.createObjectNode();
            pr.put("number", number);
            pr.put("isDraft", draft);
            pr.put("headRefName", branch);
            pr.put("headRefOid", recordedSha());
            labels(pr);
            return pr;
        }

        /** The PR as the REST pulls API reports it, abridged. */
        ObjectNode pull() {
            ObjectNode pr = JSON.createObjectNode();
            pr.put("number", number);
            pr.put("state", state);
            pr.put("draft", draft);
            pr.put("title", "change " + number);
            pr.put("body", "a change");
            ObjectNode head = pr.putObject("head").put("ref", branch).put("sha", recordedSha())
                    .put("label", "cohub-space:" + branch);
            if (repo.equals(DELETED_FORK)) {
                head.putNull("repo");
            } else {
                head.putObject("repo").put("full_name", repo);
            }
            pr.putObject("base").put("ref", "main");
            labels(pr);
            return pr;
        }

        /** No label at all when {@code label} is empty. */
        private void labels(ObjectNode pr) {
            ArrayNode labels = pr.putArray("labels");
            if (!label.isEmpty()) {
                labels.addObject().put("name", label).put("color", "ededed");
            }
        }

    }

    /** A commit as the REST commits API reports it, abridged: the regenerate job's, or release-please's. */
    private static ObjectNode commit(String sha, boolean regenerated) {
        ObjectNode commit = JSON.createObjectNode().put("sha", sha);
        commit.putObject("commit").put("message", regenerated
                ? "chore: regenerate the lake's generated files for the release versions\n\n" + TRAILER
                : "chore(main): release main");
        return commit;
    }

    private static Pr releasePr(int number, boolean draft, String branch) {
        return new Pr(number, draft, branch, LABEL, REPOSITORY, "open", false, false);
    }

    private static Pr regeneratedPr(int number, boolean draft, String branch) {
        return new Pr(number, draft, branch, LABEL, REPOSITORY, "open", true, true);
    }

    private static Pr closedPr(int number, String branch) {
        return new Pr(number, false, branch, LABEL, REPOSITORY, "closed", false, false);
    }

    private static Pr featurePr(int number, boolean draft) {
        return new Pr(number, draft, "feat/change-" + number, "enhancement", REPOSITORY, "open", false, false);
    }

    /** A fork PR labelled as a release PR. */
    private static Pr forkPr(int number, boolean draft, String repo, String branch) {
        return new Pr(number, draft, branch, LABEL, repo, "open", false, false);
    }

    /** What a {@code --label} list returns from a search index that has caught up. */
    private static List<Pr> caughtUp(List<Pr> openPrs) {
        return openPrs.stream().filter(pr -> LABEL.equals(pr.label())).toList();
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

    private static ArrayNode array(List<Pr> prs, Function<Pr, ObjectNode> shape) {
        ArrayNode array = JSON.createArrayNode();
        prs.forEach(pr -> array.add(shape.apply(pr)));
        return array;
    }

    /** The value GitHub gives an env value of the forms the workflow uses. */
    private static String evaluate(String expression, Map<String, Map<String, String>> steps) throws IOException {
        String text = expression.strip();
        if (!text.contains("${{")) {
            return text;
        }
        if (text.equals("${{ github.token }}")) {
            return "a-token";
        }
        Matcher output = STEP_OUTPUT.matcher(text);
        if (output.matches()) {
            // An output a step did not set reads as an empty string.
            return steps.getOrDefault(output.group(1), Map.of()).getOrDefault(output.group(2), "");
        }
        Matcher field = OUTPUT_FIELD.matcher(text);
        if (field.matches()) {
            // `a || b` is the first truthy operand; `.*.key` skips an item that
            // lacks the key (actions/runner Index.HandleFilteredArray); toJSON
            // pretty-prints.
            String json = steps.getOrDefault("release", Map.of()).getOrDefault(field.group(1), "");
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

    private static Map<String, String> evaluateAll(JsonNode env, Map<String, Map<String, String>> steps)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> var : env.properties()) {
            values.put(var.getKey(), evaluate(var.getValue().asText(), steps));
        }
        return values;
    }

    private Map<String, String> stepEnv(String stepId, Map<String, String> outputs) throws IOException {
        return evaluateAll(jobStep(stepId).path("env"), Map.of("release", outputs));
    }

    /**
     * What the stub gh answers: the open PRs as they are, newest first; PRs only
     * a lookup by number finds (missed, open or closed); the search index's
     * answer to a {@code --label} list; and the lookups that fail. {@code
     * failingCommits} names PRs whose branch tip and recorded head cannot be
     * read.
     */
    private static final class Repo {
        final Path dir;
        final Path bin;
        final Path fixtures;
        final boolean lookupsFail;
        int runs;

        Repo(Path dir, List<Pr> openPrs, List<Pr> missed, List<Pr> labelSearch, boolean lookupsFail,
                List<Integer> failingLookups, List<Integer> failingCommits) throws IOException {
            this.dir = Files.createDirectories(dir);
            this.lookupsFail = lookupsFail;
            bin = Files.createDirectories(dir.resolve("bin"));
            Path gh = bin.resolve("gh");
            Files.writeString(gh, STUB_GH);
            Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwxr-xr-x"));
            // Fixtures go through files: a thousand PRs outgrow an environment entry.
            fixtures = Files.createDirectories(dir.resolve("fixtures"));
            List<Pr> everyone = new ArrayList<>(openPrs);
            everyone.addAll(missed);
            ObjectNode commits = JSON.createObjectNode();
            ObjectNode branches = JSON.createObjectNode();
            ArrayNode failingShas = JSON.createArrayNode();
            ArrayNode failingBranches = JSON.createArrayNode();
            for (Pr pr : everyone) {
                commits.set(pr.sha(), commit(pr.sha(), pr.regenerated()));
                commits.set(pr.recordedSha(), commit(pr.recordedSha(), pr.recorded()));
                if (pr.repo().equals(REPOSITORY)) {
                    branches.set(pr.branch(), commit(pr.sha(), pr.regenerated()));
                }
                if (failingCommits.contains(pr.number())) {
                    failingShas.add(pr.sha()).add(pr.recordedSha());
                    failingBranches.add(pr.branch());
                }
            }
            write("pr-list.json", array(openPrs, Pr::listed));
            write("label-search.json", array(labelSearch, Pr::listed));
            write("pulls.json", array(openPrs, Pr::pull));
            write("lookup.json", array(everyone, Pr::pull));
            write("commits.json", commits);
            write("branches.json", branches);
            write("failing-branches.json", failingBranches);
            write("failing-lookups.json", JSON.valueToTree(failingLookups));
            write("failing-commits.json", failingShas);
        }

        private void write(String name, JsonNode value) throws IOException {
            Files.writeString(fixtures.resolve(name), value.toString());
        }

        /** Only the next {@code scans} pages through the open PRs succeed; the rest fail. */
        void limitScans(int scans) throws IOException {
            Files.writeString(fixtures.resolve("scans-left"), scans + "\n");
        }

        boolean draft(int number) throws IOException {
            for (JsonNode pr : JSON.readTree(fixtures.resolve("lookup.json").toFile())) {
                if (pr.path("number").asInt() == number) {
                    return pr.path("draft").asBoolean();
                }
            }
            throw new AssertionError("no PR #" + number);
        }
    }

    private static Repo repo(Path dir, List<Pr> openPrs) throws IOException {
        return new Repo(dir, openPrs, List.of(), caughtUp(openPrs), false, List.of(), List.of());
    }

    /** Runs one step of the release-please job after a release-please run with the given outputs. */
    private StepRun runIn(Repo repo, String stepId, Map<String, String> outputs) throws Exception {
        return runStep(repo, jobStep(stepId), Map.of("release", outputs));
    }

    /**
     * Runs a step of the release-please job against the repository's stub,
     * given the earlier steps' outputs by step id.
     */
    private StepRun runStep(Repo repo, JsonNode step, Map<String, Map<String, String>> steps) throws Exception {
        JsonNode workflow = workflow();
        JsonNode job = workflow.path("jobs").path("release-please");
        String shell = step.has("shell") ? step.path("shell").asText()
                : job.path("defaults").path("run").path("shell").asText();
        assertThat(SHELLS).as("the shells the test models").containsKey(shell);

        Path runDir = Files.createDirectories(
                repo.dir.resolve("run-" + repo.runs++ + "-" + step.path("id").asText("step")));
        Path script = runDir.resolve("step.sh");
        Files.writeString(script, step.get("run").asText());
        Path output = Files.createFile(runDir.resolve("output"));
        Path calls = Files.createFile(runDir.resolve("gh-calls"));

        Map<String, String> env = new LinkedHashMap<>();
        env.putAll(evaluateAll(workflow.path("env"), steps));
        env.putAll(evaluateAll(job.path("env"), steps));
        env.putAll(evaluateAll(step.path("env"), steps));
        env.put("PATH", repo.bin + ":" + System.getenv("PATH"));
        env.put("GITHUB_REPOSITORY", REPOSITORY);
        env.put("GITHUB_OUTPUT", output.toString());
        env.put("GH_CALLS", calls.toString());
        env.put("GH_FIXTURES", repo.fixtures.toString());
        env.put("GH_FAIL", repo.lookupsFail ? "1" : "");

        List<String> command = new ArrayList<>(SHELLS.get(shell));
        command.add(script.toString());
        Command result = run(runDir, env, null, command.toArray(String[]::new));
        List<Map.Entry<String, String>> written = Files.readAllLines(output).stream()
                .map(line -> line.split("=", 2))
                .map(parts -> Map.entry(parts[0], parts.length > 1 ? parts[1] : ""))
                .toList();
        return new StepRun(result.exitCode(), result.output(), written, Files.readAllLines(calls));
    }

    private JobRun runJob(Repo repo, Map<String, String> outputs) throws Exception {
        return runJob(repo, outputs, "success");
    }

    /**
     * Runs every step of the release-please job in order, each only when its
     * {@code if:} lets GitHub run it. release-please itself is given, by its
     * outcome and outputs.
     */
    private JobRun runJob(Repo repo, Map<String, String> outputs, String release) throws Exception {
        JobRun job = new JobRun(new LinkedHashMap<>());
        Map<String, String> outcomes = new LinkedHashMap<>();
        Map<String, Map<String, String>> stepOutputs = new LinkedHashMap<>();
        for (JsonNode step : workflow().at("/jobs/release-please/steps")) {
            String key = step.has("id") ? step.path("id").asText() : step.path("name").asText();
            if (step.has("uses")) {
                assertThat(step.path("uses").asText()).startsWith("googleapis/release-please-action@");
                outcomes.put(key, release);
                stepOutputs.put(key, outputs);
            } else if (runs(step, outcomes, stepOutputs)) {
                StepRun run = runStep(repo, step, stepOutputs);
                job.steps().put(key, run);
                outcomes.put(key, run.exitCode() == 0 ? "success" : "failure");
                Map<String, String> written = new LinkedHashMap<>();
                run.written().forEach(output -> written.put(output.getKey(), output.getValue()));
                stepOutputs.put(key, written);
            } else {
                outcomes.put(key, "skipped");
                stepOutputs.put(key, Map.of());
            }
        }
        return job;
    }

    /**
     * Whether GitHub runs a step of the release-please job, given each earlier
     * step's outcome and outputs, for the {@code if:} forms the workflow uses:
     * an {@code &&} of {@code always()} and of a step's output or outcome
     * compared with a literal. Without {@code always()}, {@code success()} is
     * implied: no earlier step failed.
     */
    private static boolean runs(JsonNode step, Map<String, String> outcomes,
            Map<String, Map<String, String>> outputs) {
        boolean statusChecked = false;
        for (String term : step.path("if").asText().split("&&")) {
            term = term.strip();
            if (term.isEmpty()) {
                continue;
            }
            if (term.equals("always()")) {
                statusChecked = true;
                continue;
            }
            Matcher condition = CONDITION.matcher(term);
            assertThat(condition.matches()).as("the harness models %s", term).isTrue();
            String value = condition.group(2).equals("outcome") ? outcomes.get(condition.group(1))
                    : outputs.get(condition.group(1)).getOrDefault(condition.group(2).substring(8), "");
            if (value.equals(condition.group(4)) != condition.group(3).equals("==")) {
                return false;
            }
        }
        return statusChecked || outcomes.values().stream().allMatch(o -> o.equals("success") || o.equals("skipped"));
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

    private record Command(int exitCode, String output) {}

    /** Runs a command with only the given environment (plus PATH), and stdin if not null. */
    private static Command run(Path dir, Map<String, String> env, String stdin, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv("PATH"));
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().putAll(env);
        Process process = builder.start();
        if (stdin != null) {
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Command(process.exitValue(), output);
    }

    private static String git(Path dir, String... args) throws Exception {
        String[] command = Stream.concat(Stream.of("git"), Stream.of(args)).toArray(String[]::new);
        Command result = run(dir, Map.of(), null, command);
        assertThat(result.exitCode()).as("git %s:\n%s", String.join(" ", args), result.output()).isZero();
        return result.output();
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

    /** The steps GitHub ran in one run of the release-please job, by id or name. */
    private record JobRun(Map<String, StepRun> steps) {

        /** A step's run, or null if GitHub skipped it. */
        StepRun step(String key) {
            return steps.get(key);
        }

        List<String> readied() {
            return steps.values().stream().flatMap(run -> run.readied().stream()).toList();
        }
    }

    private record StepRun(int exitCode, String log, List<Map.Entry<String, String>> written, List<String> calls) {

        /** The step's single value for an output, once it exited 0. */
        String output(String name) {
            assertThat(exitCode).as("step output:\n%s", log).isZero();
            return written(name);
        }

        String written(String name) {
            List<String> values = written.stream()
                    .filter(line -> line.getKey().equals(name))
                    .map(Map.Entry::getValue)
                    .toList();
            assertThat(values).as("%s outputs; step output:\n%s", name, log).hasSize(1);
            return values.get(0);
        }

        /** The single {@code prs} output the regenerate job's matrix reads. */
        JsonNode matrix() throws IOException {
            return JSON.readTree(output("prs"));
        }

        /** The {@code prs} output a step wrote before it failed. */
        JsonNode writtenMatrix() throws IOException {
            return JSON.readTree(written("prs"));
        }

        List<String> lookups() {
            return calls.stream().filter(call -> !call.startsWith("pr ready")).toList();
        }

        List<String> undone() {
            return calls.stream().filter(call -> call.startsWith("pr ready --undo")).toList();
        }

        List<String> readied() {
            return calls.stream().filter(call -> call.startsWith("pr ready ") && !call.contains("--undo")).toList();
        }

        List<String> annotations(String kind) {
            return log.lines().filter(line -> line.startsWith("::" + kind + "::")).toList();
        }
    }
}
