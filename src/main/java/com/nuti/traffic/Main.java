package com.nuti.traffic;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.nuti.traffic.sim.RunMode;
import com.nuti.traffic.sim.SequentialEngine;
import com.nuti.traffic.sim.SimulationConfig;

import java.nio.file.Path;

@Command(
        name = "traffic-abm",
        mixinStandardHelpOptions = true,
        description = "Simulacion ABM de trafico urbano (secuencial y paralela)."
)
public class Main implements Runnable {

    @Option(names = "--grid", required = true, description = "Ruta del archivo de rejilla (.txt)")
    private Path grid;

    @Option(names = "--vehicles", required = true, description = "Numero de vehiculos (N)")
    private int vehicles;

    @Option(names = "--ticks", required = true, description = "Numero de ticks (T)")
    private int ticks;

    @Option(names = "--seed", required = true, description = "Semilla determinista")
    private long seed;

    @Option(names = "--turnProb", defaultValue = "0.2", description = "Probabilidad de giro en interseccion [0..1]")
    private double turnProb;

    @Option(names = "--period", defaultValue = "10", description = "Periodo del semaforo (ticks) para modo periodico")
    private int lightPeriod;

    @Option(names = "--mode", defaultValue = "seq", description = "Modo de ejecucion: seq|par")
    private String mode;

    @Option(names = "--threads", defaultValue = "1", description = "Numero de hilos (solo para mode=par)")
    private int threads;

    @Option(names = "--out", description = "Ruta de salida ticks CSV (opcional)")
    private Path outTicks;

    @Override
    public void run() {
        validateArgs();

        RunMode runMode = parseMode(mode);
        SimulationConfig config = new SimulationConfig(
                grid,
                vehicles,
                ticks,
                seed,
                turnProb,
                lightPeriod,
                runMode,
                threads,
                outTicks
        );

        if (runMode == RunMode.SEQUENTIAL) {
            new SequentialEngine().run(config);
            return;
        }

        throw new CommandLine.ParameterException(new CommandLine(this), "Parallel mode not implemented yet");
    }

    private void validateArgs() {
        if (vehicles < 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--vehicles must be >= 0");
        }
        if (ticks <= 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--ticks must be > 0");
        }
        if (turnProb < 0.0 || turnProb > 1.0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--turnProb must be in [0,1]");
        }
        if (lightPeriod <= 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--period must be > 0");
        }
        if (threads <= 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--threads must be > 0");
        }
    }

    private static RunMode parseMode(String mode) {
        if (mode == null) {
            return RunMode.SEQUENTIAL;
        }
        return switch (mode.toLowerCase()) {
            case "seq" -> RunMode.SEQUENTIAL;
            case "par" -> RunMode.PARALLEL;
            default -> throw new IllegalArgumentException("Invalid --mode: " + mode + " (expected seq|par)");
        };
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
