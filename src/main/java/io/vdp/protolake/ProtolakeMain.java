package io.vdp.protolake;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vdp.protolake.cli.CliCommandRunner;
import jakarta.inject.Inject;

@QuarkusMain
public class ProtolakeMain implements QuarkusApplication {

    @Inject
    CliCommandRunner cliRunner;

    @Override
    public int run(String... args) throws Exception {
        if (args.length == 0 || "serve".equals(args[0])) {
            Quarkus.waitForExit();
            return 0;
        }
        int exitCode = cliRunner.execute(args);
        Quarkus.asyncExit(exitCode);
        return exitCode;
    }
}
