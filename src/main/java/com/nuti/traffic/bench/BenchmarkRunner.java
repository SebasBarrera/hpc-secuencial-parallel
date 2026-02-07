package com.nuti.traffic.bench;

import com.nuti.traffic.sim.ParallelEngine;
import com.nuti.traffic.sim.RunMode;
import com.nuti.traffic.sim.SequentialEngine;
import com.nuti.traffic.sim.SimulationConfig;
import com.nuti.traffic.sim.SimulationResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BenchmarkRunner {

    private final SequentialEngine sequential = new SequentialEngine();
    private final ParallelEngine parallel = new ParallelEngine();

    public void runBenchmark(
            Path grid,
            int vehicles,
            int ticks,
            long seed,
            double turnProb,
            int lightPeriod,
            int repetitions,
            int[] threadList,
            Path outSummaryCsv
    ) {
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions must be >= 1");
        }
        if (threadList.length == 0) {
            throw new IllegalArgumentException("threadList must be non-empty");
        }

        SimulationConfig warmupSeq = new SimulationConfig(grid, vehicles, ticks, seed, turnProb, lightPeriod, RunMode.SEQUENTIAL, 1, null);
        sequential.run(warmupSeq);
        for (int p : threadList) {
            SimulationConfig warmupPar = new SimulationConfig(grid, vehicles, ticks, seed, turnProb, lightPeriod, RunMode.PARALLEL, p, null);
            parallel.run(warmupPar);
        }

        Stats seqStats = measureSequential(grid, vehicles, ticks, seed, turnProb, lightPeriod, repetitions);
        List<Row> rows = new ArrayList<>();

        rows.add(new Row("SEQUENTIAL", vehicles, ticks, 1, seqStats.meanTimeMs, seqStats.meanFlow, seqStats.meanStopped, 1.0, 1.0));

        System.out.println("BENCHMARK SEQUENTIAL reps=" + repetitions + " mean_time_ms=" + seqStats.meanTimeMs + " std_time_ms=" + seqStats.stdTimeMs);

        for (int p : threadList) {
            Stats parStats = measureParallel(grid, vehicles, ticks, seed, turnProb, lightPeriod, repetitions, p);
            double speedup = seqStats.meanTimeMs / parStats.meanTimeMs;
            double efficiency = speedup / p;
            rows.add(new Row("PARALLEL", vehicles, ticks, p, parStats.meanTimeMs, parStats.meanFlow, parStats.meanStopped, speedup, efficiency));

            System.out.println("BENCHMARK PARALLEL P=" + p + " reps=" + repetitions + " mean_time_ms=" + parStats.meanTimeMs + " std_time_ms=" + parStats.stdTimeMs + " speedup=" + speedup + " efficiency=" + efficiency);
        }

        writeSummary(outSummaryCsv, rows);
    }

    private Stats measureSequential(Path grid, int vehicles, int ticks, long seed, double turnProb, int lightPeriod, int repetitions) {
        long[] times = new long[repetitions];
        double[] flows = new double[repetitions];
        double[] stoppeds = new double[repetitions];

        for (int r = 0; r < repetitions; r++) {
            SimulationConfig cfg = new SimulationConfig(grid, vehicles, ticks, seed, turnProb, lightPeriod, RunMode.SEQUENTIAL, 1, null);
            SimulationResult res = sequential.run(cfg);
            times[r] = res.timeMs();
            flows[r] = res.avgFlow();
            stoppeds[r] = res.avgStopped();
        }

        return Stats.from(times, flows, stoppeds);
    }

    private Stats measureParallel(Path grid, int vehicles, int ticks, long seed, double turnProb, int lightPeriod, int repetitions, int threads) {
        long[] times = new long[repetitions];
        double[] flows = new double[repetitions];
        double[] stoppeds = new double[repetitions];

        for (int r = 0; r < repetitions; r++) {
            SimulationConfig cfg = new SimulationConfig(grid, vehicles, ticks, seed, turnProb, lightPeriod, RunMode.PARALLEL, threads, null);
            SimulationResult res = parallel.run(cfg);
            times[r] = res.timeMs();
            flows[r] = res.avgFlow();
            stoppeds[r] = res.avgStopped();
        }

        return Stats.from(times, flows, stoppeds);
    }

    private void writeSummary(Path out, List<Row> rows) {
        try {
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(out)) {
                w.write("mode,N,ticks,threads,time_ms,avg_flow,avg_stopped,speedup,efficiency");
                w.newLine();
                for (Row row : rows) {
                    w.write(row.mode);
                    w.write(',');
                    w.write(Integer.toString(row.n));
                    w.write(',');
                    w.write(Integer.toString(row.ticks));
                    w.write(',');
                    w.write(Integer.toString(row.threads));
                    w.write(',');
                    w.write(Double.toString(row.timeMs));
                    w.write(',');
                    w.write(Double.toString(row.avgFlow));
                    w.write(',');
                    w.write(Double.toString(row.avgStopped));
                    w.write(',');
                    w.write(Double.toString(row.speedup));
                    w.write(',');
                    w.write(Double.toString(row.efficiency));
                    w.newLine();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write summary CSV: " + out, e);
        }
    }

    private record Row(
            String mode,
            int n,
            int ticks,
            int threads,
            double timeMs,
            double avgFlow,
            double avgStopped,
            double speedup,
            double efficiency
    ) {
    }

    private static final class Stats {
        private final double meanTimeMs;
        private final double stdTimeMs;
        private final double meanFlow;
        private final double meanStopped;

        private Stats(double meanTimeMs, double stdTimeMs, double meanFlow, double meanStopped) {
            this.meanTimeMs = meanTimeMs;
            this.stdTimeMs = stdTimeMs;
            this.meanFlow = meanFlow;
            this.meanStopped = meanStopped;
        }

        private static Stats from(long[] times, double[] flows, double[] stoppeds) {
            return new Stats(mean(times), stddev(times), mean(flows), mean(stoppeds));
        }

        private static double mean(long[] a) {
            double s = 0.0;
            for (long v : a) {
                s += v;
            }
            return s / a.length;
        }

        private static double stddev(long[] a) {
            if (a.length <= 1) {
                return 0.0;
            }
            double m = mean(a);
            double s2 = 0.0;
            for (long v : a) {
                double d = v - m;
                s2 += d * d;
            }
            return Math.sqrt(s2 / (a.length - 1));
        }

        private static double mean(double[] a) {
            double s = 0.0;
            for (double v : a) {
                s += v;
            }
            return s / a.length;
        }
    }
}
