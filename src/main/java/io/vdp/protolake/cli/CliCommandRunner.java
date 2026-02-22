package io.vdp.protolake.cli;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import picocli.CommandLine;

/**
 * Dispatches CLI commands using Picocli.
 * Registered as a CDI bean so commands can inject other CDI beans.
 */
@ApplicationScoped
public class CliCommandRunner {

    @Inject
    InitCommand initCommand;

    @Inject
    CreateBundleCommand createBundleCommand;

    @Inject
    BuildCommand buildCommand;

    @Inject
    ValidateCommand validateCommand;

    public int execute(String[] args) {
        CommandLine cli = new CommandLine(new ProtolakeCli())
                .addSubcommand("init", initCommand)
                .addSubcommand("create-bundle", createBundleCommand)
                .addSubcommand("build", buildCommand)
                .addSubcommand("validate", validateCommand);

        return cli.execute(args);
    }

    /**
     * Top-level Picocli command (never executed directly, only subcommands).
     */
    @CommandLine.Command(name = "protolake", mixinStandardHelpOptions = true,
            description = "Proto Lake CLI - manage proto lakes and bundles",
            subcommands = CommandLine.HelpCommand.class)
    static class ProtolakeCli implements Runnable {
        @Override
        public void run() {
            new CommandLine(this).usage(System.out);
        }
    }
}
