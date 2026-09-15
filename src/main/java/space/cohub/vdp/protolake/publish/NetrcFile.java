package space.cohub.vdp.protolake.publish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The one {@code ~/.netrc} entry a publish needs. rules_jvm_external's
 * publisher uploads with {@code MAVEN_USER}/{@code MAVEN_PASSWORD} but reads
 * — the {@code maven-metadata.xml} it merges the new version into — through a
 * downloader that authenticates from {@code ~/.netrc} only, and treats a 401
 * or 403 as "no such file": on a private registry the read comes back empty,
 * the publisher bootstraps a one-version metadata and its authenticated
 * upload replaces the registry's version list. Writing the registry host's
 * entry from the same token before the publish runs makes the read
 * authenticated too.
 *
 * <p>The file is rewritten line by line so the upstream parser still reads
 * it: its {@code NetrcParser} stops at a {@code default} entry (the host entry
 * goes before it), treats {@code #} to end of line as a comment (comment
 * lines keep their own line) and reads a {@code macdef} block up to a blank
 * line (kept verbatim). The host's own entry — single- or multi-line, or
 * packed on a line with other entries — is removed and re-added on one line.
 */
public final class NetrcFile {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private NetrcFile() {
    }

    /**
     * Ensures {@code home/.netrc} carries {@code machine host login user password secret}
     * ahead of any {@code default} entry, keeping every other line. The file
     * is owner-only whether or not its content changed, and is never on disk
     * with the secret and wider permissions. Returns the file.
     */
    public static Path ensureMachineEntry(Path home, String host, String login, String password)
            throws IOException {
        if (host == null || host.isEmpty() || login == null || login.isEmpty()
                || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("host, login and password are all required");
        }
        Path link = home.resolve(".netrc");
        // A dotfiles-managed ~/.netrc is a symlink: write its target, never
        // replace the link with a copy that later drifts from it.
        Path netrc = Files.isSymbolicLink(link) ? link.toRealPath() : link;
        String existing = Files.exists(netrc) ? Files.readString(netrc, StandardCharsets.UTF_8) : null;
        String rendered = render(existing == null ? "" : existing, host, login, password);
        rejectUnparsable(rendered);               // upstream swallows a parse error into "no credentials"
        if (existing != null && rendered.equals(existing)) {
            restrict(netrc);                      // content current; permissions may not be
            return netrc;
        }
        Files.createDirectories(netrc.getParent());
        Path temp = Files.createTempFile(netrc.getParent(), ".netrc.", ".tmp");
        try {
            restrict(temp);                       // owner-only before the secret lands
            Files.writeString(temp, rendered, StandardCharsets.UTF_8);
            Files.move(temp, netrc, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        restrict(netrc);
        return netrc;
    }

    /**
     * {@code existing} with {@code host}'s entry removed and the wanted entry
     * on its own line ahead of the first {@code default} entry (or appended),
     * read and written with the grammar of rules_jvm_external's
     * {@code NetrcParser}: a line is a comment only when it starts with
     * {@code #}; every other line splits on whitespace into items; a
     * {@code machine} or {@code default} item opens a credential whose
     * {@code login}/{@code password}/{@code account} values are the NEXT item,
     * opaque (a password may be {@code default} or contain {@code #}), and
     * ends at the next {@code machine}/{@code macdef}/{@code default} item;
     * a {@code macdef} item skips to the next blank line; {@code default}
     * ends the read, so whatever follows it is preserved verbatim. Entries are
     * written back one per line; unknown items are kept where they were.
     */
    static String render(String existing, String host, String login, String password) {
        String wanted = "machine " + host + " login " + login + " password " + password;
        List<Item> items = parse(existing);
        List<String> out = new ArrayList<>();
        boolean inserted = false;
        for (Item item : items) {
            switch (item.kind) {
                case COMMENT, OPAQUE -> out.add(item.text);
                case MACHINE -> {
                    if (item.tokens.size() >= 2 && item.tokens.get(1).equals(host)) {
                        out.addAll(item.trailing);            // comment lines seen inside it stay
                        continue;
                    }
                    out.add(String.join(" ", item.tokens));
                    out.addAll(item.trailing);
                }
                case DEFAULT -> {
                    if (!inserted) {
                        out.add(wanted);                       // the parser stops at default
                        inserted = true;
                    }
                    out.add(String.join(" ", item.tokens));
                    out.addAll(item.trailing);
                }
                case MACDEF -> {
                    out.add(String.join(" ", item.tokens));
                    out.addAll(item.body);
                    if (item.body.isEmpty() || !item.body.get(item.body.size() - 1).isBlank()) {
                        out.add("");                            // a body ends at a blank line
                    }
                }
            }
        }
        if (!inserted) {
            out.add(wanted);
        }
        return String.join("\n", out) + "\n";
    }

    private enum Kind { COMMENT, OPAQUE, MACHINE, DEFAULT, MACDEF }

    /**
     * The registry host whose credential the publish needs in ~/.netrc, or
     * empty when the publish is not against an HTTP registry with a token
     * (a file repository, a local install).
     */
    public static java.util.Optional<String> registryHost(String mavenRepo, String registryToken) {
        if (mavenRepo == null || !mavenRepo.startsWith("http") || registryToken == null || registryToken.isEmpty()) {
            return java.util.Optional.empty();
        }
        String host = java.net.URI.create(mavenRepo).getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("MAVEN_REPO has no host: " + mavenRepo);
        }
        return java.util.Optional.of(host);
    }

    /**
     * Fails closed on a rendered file the publisher's parser would reject:
     * {@code Netrc.fromUserHome()} turns that IOException into an empty
     * credential map, so the metadata read would be anonymous again while the
     * log claims otherwise. Rejected: an item outside the grammar at top level
     * or inside a credential (a trailing {@code # comment} on an entry line is
     * one — the parser comments whole lines only), a {@code machine} without a
     * name, a key without its value. Text after {@code default} is unread by
     * the parser and passes.
     */
    static void rejectUnparsable(String rendered) throws IOException {
        for (Item item : parse(rendered)) {
            switch (item.kind) {
                // Diagnostics name a line and a rule, never the text: a malformed
                // entry can carry a password in any position, and the message
                // lands in operation metadata and CI logs.
                case OPAQUE -> {
                    if (!item.afterDefault) {
                        throw new IOException("~/.netrc line " + item.line + ": text outside the netrc grammar "
                                + "(an entry starts with machine, default or macdef; the parser comments whole "
                                + "lines only) — fix or move the file aside");
                    }
                }
                case MACHINE, DEFAULT -> {
                    int head = item.kind == Kind.MACHINE ? 2 : 1;
                    if (item.tokens.size() < head) {
                        throw new IOException("~/.netrc line " + item.line + ": 'machine' without a name");
                    }
                    for (int i = head; i < item.tokens.size(); i += 2) {
                        String key = item.tokens.get(i);
                        if (!CREDENTIAL_KEYS.contains(key) || i + 1 >= item.tokens.size()) {
                            throw new IOException("~/.netrc line " + item.line + ": an entry carries an item "
                                    + "outside login/password/account, or a key without its value (the parser "
                                    + "comments whole lines only) — fix or move the file aside");
                        }
                    }
                }
                default -> { }
            }
        }
    }

    private static final class Item {
        final Kind kind;
        final String text;                       // COMMENT / OPAQUE: the line verbatim
        final List<String> tokens = new ArrayList<>();
        final List<String> trailing = new ArrayList<>();   // comment lines inside a credential
        final List<String> body = new ArrayList<>();       // macdef: lines to the blank terminator
        boolean afterDefault;                              // OPAQUE: unread by the parser
        int line;                                          // 1-based, for diagnostics only

        Item(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        Item at(int line) {
            this.line = line;
            return this;
        }
    }

    /** The parser's token stream: an item, a newline, or a comment line. */
    private record Tok(String item, int line, boolean newline, String commentLine) {
        static Tok item(String s, int line) { return new Tok(s, line, false, null); }
        static Tok nl(int line) { return new Tok(null, line, true, null); }
        static Tok comment(String s, int line) { return new Tok(null, line, false, s); }
    }

    private static final Set<String> CREDENTIAL_KEYS = Set.of("login", "password", "account");
    private static final Set<String> ENTRY_KEYS = Set.of("machine", "macdef", "default");

    static List<Item> parse(String contents) {
        String[] lines = contents.split("\n", -1);
        int last = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        List<Tok> toks = new ArrayList<>();
        for (int i = 0; i < last; i++) {
            String line = lines[i];
            if (line.startsWith("#")) {
                toks.add(Tok.comment(line, i));
            } else {
                for (String s : line.split("\\s+")) {
                    if (!s.isEmpty()) {
                        toks.add(Tok.item(s, i));
                    }
                }
            }
            toks.add(Tok.nl(i));
        }
        List<Item> items = new ArrayList<>();
        int[] cursor = {0};
        while (cursor[0] < toks.size()) {
            Tok t = toks.get(cursor[0]);
            if (t.newline()) {
                cursor[0]++;
            } else if (t.commentLine() != null) {
                items.add(new Item(Kind.COMMENT, t.commentLine()).at(t.line() + 1));
                cursor[0]++;
            } else if (t.item().equals("machine") || t.item().equals("default")) {
                Item entry = new Item(t.item().equals("machine") ? Kind.MACHINE : Kind.DEFAULT, null).at(t.line() + 1);
                entry.tokens.add(t.item());
                cursor[0]++;
                if (entry.kind == Kind.MACHINE) {
                    String name = nextItem(toks, cursor, entry);
                    if (name != null) {
                        entry.tokens.add(name);
                    }
                }
                credential(toks, cursor, entry);
                items.add(entry);
                if (entry.kind == Kind.DEFAULT) {
                    // The parser is done: the rest of the file is invisible to it.
                    int from = cursor[0] < toks.size() ? toks.get(cursor[0]).line() : last;
                    boolean midLine = cursor[0] < toks.size() && !toks.get(cursor[0]).newline()
                            && toks.get(cursor[0]).commentLine() == null;
                    if (midLine) {
                        StringBuilder rest = new StringBuilder();
                        while (cursor[0] < toks.size() && !toks.get(cursor[0]).newline()) {
                            rest.append(rest.length() == 0 ? "" : " ").append(toks.get(cursor[0]).item());
                            cursor[0]++;
                        }
                        Item tail = new Item(Kind.OPAQUE, rest.toString()).at(toks.get(cursor[0] - 1).line() + 1);
                        tail.afterDefault = true;
                        items.add(tail);
                        from++;
                    }
                    for (int i = from; i < last; i++) {
                        Item tail = new Item(Kind.OPAQUE, lines[i]).at(i + 1);
                        tail.afterDefault = true;
                        items.add(tail);
                    }
                    break;
                }
            } else if (t.item().equals("macdef")) {
                Item macro = new Item(Kind.MACDEF, null).at(t.line() + 1);
                int line = t.line();
                while (cursor[0] < toks.size() && !toks.get(cursor[0]).newline()) {
                    macro.tokens.add(toks.get(cursor[0]).item());
                    cursor[0]++;
                }
                // body: raw lines up to and including the first blank line
                int i = line + 1;
                for (; i < last; i++) {
                    macro.body.add(lines[i]);
                    if (lines[i].isBlank()) {
                        i++;
                        break;
                    }
                }
                while (cursor[0] < toks.size() && toks.get(cursor[0]).line() < i) {
                    cursor[0]++;
                }
                items.add(macro);
            } else {
                // not valid at top level for the parser; keep the line's remaining items verbatim
                int atLine = t.line() + 1;
                StringBuilder rest = new StringBuilder();
                while (cursor[0] < toks.size() && !toks.get(cursor[0]).newline()) {
                    rest.append(rest.length() == 0 ? "" : " ").append(toks.get(cursor[0]).item());
                    cursor[0]++;
                }
                items.add(new Item(Kind.OPAQUE, rest.toString()).at(atLine));
            }
        }
        return items;
    }

    /** Reads a credential's key/value items; ends at the next entry key. */
    private static void credential(List<Tok> toks, int[] cursor, Item entry) {
        while (cursor[0] < toks.size()) {
            Tok t = toks.get(cursor[0]);
            if (t.newline()) {
                cursor[0]++;
                continue;
            }
            if (t.commentLine() != null) {
                entry.trailing.add(t.commentLine());
                cursor[0]++;
                continue;
            }
            if (ENTRY_KEYS.contains(t.item())) {
                return;
            }
            entry.tokens.add(t.item());
            cursor[0]++;
            if (CREDENTIAL_KEYS.contains(t.item())) {
                String value = nextItem(toks, cursor, entry);   // opaque, even "default" or "a#b"
                if (value != null) {
                    entry.tokens.add(value);
                }
            }
        }
    }

    /** The next item token, skipping newlines and keeping comment lines on the entry. */
    private static String nextItem(List<Tok> toks, int[] cursor, Item entry) {
        while (cursor[0] < toks.size()) {
            Tok t = toks.get(cursor[0]);
            cursor[0]++;
            if (t.newline()) {
                continue;
            }
            if (t.commentLine() != null) {
                entry.trailing.add(t.commentLine());
                continue;
            }
            return t.item();
        }
        return null;
    }

    private static void restrict(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // a non-POSIX filesystem has no mode bits to set
        }
    }
}
