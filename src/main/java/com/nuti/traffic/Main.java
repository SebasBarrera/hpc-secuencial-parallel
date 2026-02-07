package com.nuti.traffic;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "traffic-abm",
        mixinStandardHelpOptions = true,
        description = "Simulacion ABM de trafico urbano (secuencial y paralela)."
)
public class Main implements Runnable {

    @Override
    public void run() {
        throw new CommandLine.ParameterException(new CommandLine(this), "Not implemented yet");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
