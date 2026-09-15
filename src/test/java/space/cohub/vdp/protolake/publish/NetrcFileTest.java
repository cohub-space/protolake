package space.cohub.vdp.protolake.publish;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rendered file must read correctly under rules_jvm_external's
 * NetrcParser: it stops at {@code default}, treats {@code #} to end of line
 * as a comment, and reads a {@code macdef} body up to a blank line.
 */
class NetrcFileTest {

    private static final String HOST = "us-central1-maven.pkg.dev";
    private static final String ENTRY = "machine " + HOST + " login oauth2accesstoken password tok";

    @TempDir
    Path home;

    private String perms(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    @Test
    void createsTheFileOwnerOnly_withTheEntry() throws Exception {
        Path netrc = NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        assertThat(Files.readString(netrc)).isEqualTo(ENTRY + "\n");
        assertThat(perms(netrc)).isEqualTo("rw-------");
        assertThat(Files.list(home).count()).isEqualTo(1);       // no temp file left behind
    }

    @Test
    void hostEntryGoesBeforeDefault_whichTheParserStopsAt() throws Exception {
        Files.writeString(home.resolve(".netrc"),
                "machine github.com login me password gh-secret\n"
                        + "default login anon password none\n");

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        assertThat(Files.readString(home.resolve(".netrc"))).isEqualTo(
                "machine github.com login me password gh-secret\n"
                        + ENTRY + "\n"
                        + "default login anon password none\n");
    }

    @Test
    void replacesTheHostsMultiLineEntry_andKeepsCommentLinesOnTheirOwnLine() throws Exception {
        Files.writeString(home.resolve(".netrc"),
                "# personal entries\n"
                        + "machine github.com login me password gh-secret\n"
                        + "# registry credential\n"
                        + "machine " + HOST + "\n"
                        + "  login oauth2accesstoken\n"
                        + "  password stale\n"
                        + "machine example.org login x password y\n");

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        assertThat(Files.readString(home.resolve(".netrc"))).isEqualTo(
                "# personal entries\n"
                        + "machine github.com login me password gh-secret\n"
                        + "# registry credential\n"
                        + "machine example.org login x password y\n"
                        + ENTRY + "\n");
    }

    @Test
    void removesTheHostFromAPackedLine_andKeepsTheOthers() throws Exception {
        Files.writeString(home.resolve(".netrc"),
                "machine a login x password y machine " + HOST + " login u password p machine b login q password r\n");

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        // packed entries are written back one per line (the parser reads tokens)
        assertThat(Files.readString(home.resolve(".netrc"))).isEqualTo(
                "machine a login x password y\nmachine b login q password r\n" + ENTRY + "\n");
    }

    @Test
    void recognisesDefaultAndMacdefMidLine_theParserDoes() throws Exception {
        // `default` packed after another entry still ends the parser's search:
        // the host goes before it. A packed `macdef` still owns the next lines.
        Files.writeString(home.resolve(".netrc"),
                "machine other login u password p default login anon password none\n"
                        + "machine x login a password b macdef init\ncd /tmp\n\n");

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        // everything after default is invisible to the parser: preserved verbatim
        assertThat(Files.readString(home.resolve(".netrc"))).isEqualTo(
                "machine other login u password p\n"
                        + ENTRY + "\n"
                        + "default login anon password none\n"
                        + "machine x login a password b macdef init\ncd /tmp\n\n");
    }

    @Test
    void aFileTheParserWouldRejectFailsClosed_insteadOfALogLineOverAnAnonymousRead() throws Exception {
        // "#" mid-line is an item to the parser, not a comment: upstream would
        // throw, then swallow it into "no credentials" — refuse to pretend.
        Files.writeString(home.resolve(".netrc"), "machine a login x password y # personal\n");

        assertThatThrownBy(() -> NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("line 1");
        assertThat(Files.readString(home.resolve(".netrc")))
                .isEqualTo("machine a login x password y # personal\n");   // untouched
        assertThatThrownBy(() -> NetrcFile.rejectUnparsable("machine a login x password y\nmachine\n"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("line 2").hasMessageContaining("without a name");
        // a stray item after an entry belongs to that entry's credential (the
        // parser reads on until the next entry keyword): the entry's line is named
        assertThatThrownBy(() -> NetrcFile.rejectUnparsable("machine a login x password y\njunk token\n"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("line 1").hasMessageContaining("outside login/password/account");
        assertThatThrownBy(() -> NetrcFile.rejectUnparsable("junk token\nmachine a login x password y\n"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("line 1").hasMessageContaining("outside the netrc grammar");
        // after default's credential the parser stops: a further entry is unread
        NetrcFile.rejectUnparsable("default login a password b\nmachine unread login y password z\n");
    }

    @Test
    void aRejectionNeverQuotesTheFile_aPasswordMaySitAnywhereInAMalformedEntry() throws Exception {
        String secret = "SYNTHETIC_PRIVATE_PASSWORD";
        for (String contents : new String[] {
                "machine other login user password " + secret + " # note\n",
                "machine other login user password " + secret + " typo\n",
                "machine other login user password " + secret + "\nmachine\n",
                "machine other login user password " + secret + "\n" + secret + " stray\n"}) {
            Files.writeString(home.resolve(".netrc"), contents);
            assertThatThrownBy(() -> NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok-secret"))
                    .isInstanceOf(java.io.IOException.class)
                    .satisfies(e -> {
                        for (Throwable c = e; c != null; c = c.getCause()) {
                            assertThat(String.valueOf(c.getMessage())).doesNotContain(secret).doesNotContain("tok-secret");
                        }
                    });
            assertThat(Files.readString(home.resolve(".netrc"))).isEqualTo(contents);
        }
    }

    @Test
    void aSymlinkedNetrcIsWrittenThroughToItsTarget() throws Exception {
        Path dotfiles = home.resolve("dotfiles");
        Files.createDirectories(dotfiles);
        Path target = dotfiles.resolve("netrc");
        Files.writeString(target, "machine a login x password y\n");
        Files.createSymbolicLink(home.resolve(".netrc"), target);

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        assertThat(Files.isSymbolicLink(home.resolve(".netrc"))).isTrue();
        assertThat(Files.readString(target)).isEqualTo("machine a login x password y\n" + ENTRY + "\n");
        assertThat(perms(target)).isEqualTo("rw-------");
    }

    @Test
    void registryHost_onlyForAnHttpRegistryWithAToken() {
        assertThat(NetrcFile.registryHost("https://us-central1-maven.pkg.dev/cohub-487604/maven-internal", "tok"))
                .contains("us-central1-maven.pkg.dev");
        assertThat(NetrcFile.registryHost("http://localhost:8081/repo", "tok")).contains("localhost");
        assertThat(NetrcFile.registryHost("file:///home/protolake/.m2/repository", "tok")).isEmpty();
        assertThat(NetrcFile.registryHost("https://x.example/repo", "")).isEmpty();
        assertThat(NetrcFile.registryHost(null, "tok")).isEmpty();
    }

    @Test
    void credentialValuesAreOpaque_evenKeywordsAndHashes() throws Exception {
        Files.writeString(home.resolve(".netrc"),
                "machine a login user password abc#123\n"
                        + "machine b login user password default\n"
                        + "machine c\nlogin\nuser\npassword\nsplit\n");

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        assertThat(Files.readString(home.resolve(".netrc"))).isEqualTo(
                "machine a login user password abc#123\n"
                        + "machine b login user password default\n"
                        + "machine c login user password split\n"
                        + ENTRY + "\n");
    }

    @Test
    void aContinuationLineThatStartsAnotherEntryKeepsThatEntry() throws Exception {
        // The host's entry continues on line 2, where another host's entry
        // also begins: only the host's tokens go, the other credential stays.
        Files.writeString(home.resolve(".netrc"),
                "machine " + HOST + "\nlogin oauth2accesstoken password old machine other.example login user password other\n");

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        assertThat(Files.readString(home.resolve(".netrc")))
                .isEqualTo("machine other.example login user password other\n" + ENTRY + "\n");
    }

    @Test
    void keepsAMacdefBodyVerbatim() throws Exception {
        String macro = "macdef init\n  cd /tmp\n  get file\n\n";
        Files.writeString(home.resolve(".netrc"), "machine a login x password y\n" + macro);

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        assertThat(Files.readString(home.resolve(".netrc")))
                .isEqualTo("machine a login x password y\n" + macro + ENTRY + "\n");
    }

    @Test
    void closesAnUnterminatedMacdefBeforeAppending() throws Exception {
        Files.writeString(home.resolve(".netrc"), "macdef init\n  cd /tmp\n");

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        assertThat(Files.readString(home.resolve(".netrc")))
                .isEqualTo("macdef init\n  cd /tmp\n\n" + ENTRY + "\n");
    }

    @Test
    void isIdempotent_andRestoresOwnerOnlyPermissionsEvenWhenUnchanged() throws Exception {
        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");
        Path netrc = home.resolve(".netrc");
        String contents = Files.readString(netrc);
        Files.setPosixFilePermissions(netrc, PosixFilePermissions.fromString("rw-r--r--"));

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "tok");

        assertThat(Files.readString(netrc)).isEqualTo(contents);
        assertThat(perms(netrc)).isEqualTo("rw-------");
    }

    @Test
    void refreshesTheHostsCredential() throws Exception {
        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "old");

        NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", "new");

        assertThat(Files.readString(home.resolve(".netrc")))
                .isEqualTo("machine " + HOST + " login oauth2accesstoken password new\n");
    }

    @Test
    void refusesAnEmptyCredential() {
        assertThatThrownBy(() -> NetrcFile.ensureMachineEntry(home, HOST, "oauth2accesstoken", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(home.resolve(".netrc")).doesNotExist();
    }
}
