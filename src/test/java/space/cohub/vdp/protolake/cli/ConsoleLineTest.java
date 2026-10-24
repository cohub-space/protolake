package space.cohub.vdp.protolake.cli;

import org.junit.jupiter.api.Test;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each line of CLI output reaches stdout or stderr in one write, so the other stream cannot
 * split it when docker merges the two (ConsoleLine).
 */
class ConsoleLineTest {

    private static final Pattern PRINTF = Pattern.compile("System\\.(out|err)\\.(printf|format)\\(");

    private static final String MESSAGE = "Bundle api_demo imports cohub/api/v1/exposure.proto and builds Java"
            + " packages, whose generated code would load cohub's annotation classes from companion packages"
            + " that are not published yet.";

    @Test
    void print_reachesTheStreamInOneWrite() {
        List<String> writes = new ArrayList<>();
        PrintStream stream = consoleStream(writes);

        ConsoleLine.print(stream, "  %s:%d:%d: %s", "api_demo/v1/things.proto", 5, 1, MESSAGE);

        assertThat(writes).containsExactly(
                "  api_demo/v1/things.proto:5:1: " + MESSAGE + System.lineSeparator());
    }

    @Test
    void printf_reachesTheStreamInPieces() {
        // The premise: on a stream built like System.err, printf writes each piece of its
        // format separately, which is what lets the other stream split the line.
        List<String> writes = new ArrayList<>();
        PrintStream stream = consoleStream(writes);

        stream.printf("  %s:%d:%d: %s%n", "api_demo/v1/things.proto", 5, 1, MESSAGE);

        assertThat(writes).hasSizeGreaterThan(1).contains("api_demo/v1/things.proto", "5", "1");
    }

    @Test
    void cliSources_printNoLineWithPrintf() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.list(Path.of("src/main/java/space/cohub/vdp/protolake/cli"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (PRINTF.matcher(lines.get(i)).find()) {
                        offenders.add(source.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertThat(offenders).as("printf calls in the CLI; use ConsoleLine.print").isEmpty();
    }

    /** A stream built the way the JDK builds System.err, recording each write that leaves it. */
    private static PrintStream consoleStream(List<String> writes) {
        OutputStream descriptor = new OutputStream() {
            @Override
            public void write(int b) {
                writes.add(String.valueOf((char) b));
            }

            @Override
            public void write(byte[] b, int off, int len) {
                writes.add(new String(b, off, len, StandardCharsets.UTF_8));
            }
        };
        return new PrintStream(new BufferedOutputStream(descriptor, 128), true, StandardCharsets.UTF_8);
    }
}
