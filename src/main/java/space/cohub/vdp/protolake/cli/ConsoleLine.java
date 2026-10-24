package space.cohub.vdp.protolake.cli;

import java.io.PrintStream;

/**
 * Prints one line of CLI output in a single write.
 *
 * <p>System.out and System.err flush after every write, and {@code printf} writes each piece
 * of its format separately, so one {@code printf} line reaches the descriptor in several
 * writes. protolakew runs the CLI in a container whose stdout and stderr docker copies
 * separately, so a line from the other stream could land between those writes, splitting
 * an error's {@code file:line:column:} or a phase's status. {@code println} of one string
 * is one write.
 */
final class ConsoleLine {

    private ConsoleLine() {}

    /** Prints {@code format} filled with {@code args}, as {@code printf} would, then a line separator. */
    static void print(PrintStream stream, String format, Object... args) {
        stream.println(String.format(format, args));
    }
}
