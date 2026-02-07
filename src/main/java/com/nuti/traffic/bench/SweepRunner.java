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

public final class SweepRunner {

    private final SequentialEngine sequential = new SequentialEngine();
    private final ParallelEngine parallel = new ParallelEngine();

    public void runSweep(
            Path grid,
            int[] nList,
            int[] ticksList,
            long seed,
            double turnProb,
            int lightPeriod,
            int repetitions,
            int[] threadList,
            Path outCsv
    ) {
        if (nList.length == 0) {
            throw new IllegalArgumentException("nList must be non-empty");
        }
        if (ticksList.length == 0) {
            throw new IllegalArgumentException("ticksList must be non-empty");
        }
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions must be >= 1");
        }
        if (threadList.length == 0) {
            throw new IllegalArgumentException("threadList must be non-empty");
        }

        List<Row> rows = new ArrayList<>();

        for (int ticks : ticksList) {
            for (int n : nList) {
                Stats seq = measure(grid, n, ticks, seed, turnProb, lightPeriod, repetitions, RunMode.SEQUENTIAL, 1);
                rows.add(new Row(grid.toString(), "SEQUENTIAL", n, ticks, 1, repetitions, seq.meanTimeMs, seq.stdTimeMs, seq.meanFlow, seq.meanStopped, 1.0, 1.0));

                for (int p : threadList) {
                    Stats par = measure(grid, n, ticks, seed, turnProb, lightPeriod, repetitions, RunMode.PARALLEL, p);
                    double speedup = seq.meanTimeMs / par.meanTimeMs;
                    double efficiency = speedup / p;
                    rows.add(new Row(grid.toString(), "PARALLEL", n, ticks, p, repetitions, par.meanTimeMs, par.stdTimeMs, par.meanFlow, par.meanStopped, speedup, efficiency));
                }
            }
        }

        writeCsv(outCsv, rows);
    }

    private Stats measure(
            Path grid,
            int vehicles,
            int ticks,
            long seed,
            double turnProb,
            int lightPeriod,
            int repetitions,
            RunMode mode,
            int threads
    ) {
        long[] times = new long[repetitions];
        double[] flows = new double[repetitions];
        double[] stoppeds = new double[repetitions];

        for (int r = 0; r < repetitions; r++) {
            SimulationConfig cfg = new SimulationConfig(grid, vehicles, ticks, seed, turnProb, lightPeriod, mode, threads, null, false);
            SimulationResult res = (mode == RunMode.SEQUENTIAL) ? sequential.run(cfg) : parallel.run(cfg);
            times[r] = res.timeMs();
            flows[r] = res.avgFlow();
            stoppeds[r] = res.avgStopped();
        }

        return Stats.from(times, flows, stoppeds);
    }

    private void writeCsv(Path out, List<Row> rows) {
        try {
            Path parent = out.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter w = Files.newBufferedWriter(out)) {
                w.write("grid,mode,N,ticks,threads,reps,time_ms,std_time_ms,avg_flow,avg_stopped,speedup,efficiency");
                w.newLine();
                for (Row row : rows) {
                    w.write(row.grid);
                    w.write(',');
                    w.write(row.mode);
                    w.write(',');
                    w.write(Integer.toString(row.n));
                    w.write(',');
                    w.write(Integer.toString(row.ticks));
                    w.write(',');
                    w.write(Integer.toString(row.threads));
                    w.write(',');
                    w.write(Integer.toString(row.reps));
                    w.write(',');
                    w.write(Double.toString(row.timeMs));
                    w.write(',');
                    w.write(Double.toString(row.stdTimeMs));
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
            throw new RuntimeException("Failed to write sweep CSV: " + out, e);
        }
    }

    private record Row(
            String grid,
            String mode,
            int n,
            int ticks,
            int threads,
            int reps,
            double timeMs,
            double stdTimeMs,
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
