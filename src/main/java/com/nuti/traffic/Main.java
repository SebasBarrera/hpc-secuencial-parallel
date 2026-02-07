package com.nuti.traffic;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.nuti.traffic.bench.BenchmarkRunner;
import com.nuti.traffic.bench.SweepRunner;
import com.nuti.traffic.sim.ParallelEngine;
import com.nuti.traffic.sim.RunMode;
import com.nuti.traffic.sim.SequentialEngine;
import com.nuti.traffic.sim.SimulationConfig;

import java.nio.file.Path;
import java.util.Arrays;

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

    @Option(names = "--benchmark", defaultValue = "false", description = "Ejecuta el BenchmarkRunner (warmup + repeticiones) y genera summary CSV")
    private boolean benchmark;

    @Option(names = "--sweep", defaultValue = "false", description = "Ejecuta un barrido (varios N/ticks/threads) y genera un CSV unico")
    private boolean sweep;

    @Option(names = "--reps", defaultValue = "3", description = "Repeticiones por configuracion en benchmark")
    private int repetitions;

    @Option(names = "--mode", defaultValue = "seq", description = "Modo de ejecucion: seq|par")
    private String mode;

    @Option(names = "--threads", defaultValue = "1", description = "Numero de hilos (mode=par) o lista separada por comas (benchmark)")
    private String threads;

    @Option(names = "--nList", defaultValue = "", description = "Lista separada por comas de valores de N (solo sweep). Si vacio usa --vehicles")
    private String nList;

    @Option(names = "--ticksList", defaultValue = "", description = "Lista separada por comas de valores de ticks (solo sweep). Si vacio usa --ticks")
    private String ticksList;

    @Option(names = "--out", description = "Ruta de salida: ticks CSV (runs) o summary CSV (benchmark)")
    private Path out;

    @Override
    public void run() {
        validateArgs();

        if (benchmark) {
            int[] threadList = parseThreadsList(threads);
            Path outSummary = (out != null) ? out : Path.of("data", "summary.csv");
            new BenchmarkRunner().runBenchmark(grid, vehicles, ticks, seed, turnProb, lightPeriod, repetitions, threadList, outSummary);
            return;
        }

        if (sweep) {
            int[] threadList = parseThreadsList(threads);
            int[] nVals = (nList != null && !nList.isBlank()) ? parseIntList("--nList", nList) : new int[] { vehicles };
            int[] tickVals = (ticksList != null && !ticksList.isBlank()) ? parseIntList("--ticksList", ticksList) : new int[] { ticks };
            Path outSweep = (out != null) ? out : Path.of("data", "sweep.csv");
            new SweepRunner().runSweep(grid, nVals, tickVals, seed, turnProb, lightPeriod, repetitions, threadList, outSweep);
            return;
        }

        RunMode runMode = parseMode(mode);
        int threadsInt = parseThreadsInt(threads);
        SimulationConfig config = new SimulationConfig(
                grid,
                vehicles,
                ticks,
                seed,
                turnProb,
                lightPeriod,
                runMode,
                threadsInt,
                out
        );

        if (runMode == RunMode.SEQUENTIAL) {
            new SequentialEngine().run(config);
            return;
        }

        new ParallelEngine().run(config);
    }

    private void validateArgs() {
        if (benchmark && sweep) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--benchmark and --sweep cannot be used together");
        }
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
        if (repetitions <= 0) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--reps must be > 0");
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

    private static int parseThreadsInt(String threads) {
        try {
            int p = Integer.parseInt(threads.trim());
            if (p <= 0) {
                throw new IllegalArgumentException("--threads must be > 0");
            }
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid --threads for run mode (expected integer): " + threads);
        }
    }

    private static int[] parseThreadsList(String threads) {
        if (threads == null || threads.isBlank()) {
            return new int[] { 1 };
        }
        String[] parts = threads.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid --threads list: " + threads);
            }
            if (out[i] <= 0) {
                throw new IllegalArgumentException("Invalid thread count in --threads list: " + out[i]);
            }
        }
        Arrays.sort(out);
        return out;
    }

    private static int[] parseIntList(String optName, String s) {
        if (s == null || s.isBlank()) {
            return new int[0];
        }
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid " + optName + " list: " + s);
            }
            if (out[i] <= 0) {
                throw new IllegalArgumentException("Invalid value in " + optName + " list: " + out[i]);
            }
        }
        Arrays.sort(out);
        return out;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
