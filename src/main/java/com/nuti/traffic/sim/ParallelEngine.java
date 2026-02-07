package com.nuti.traffic.sim;

import com.nuti.traffic.grid.GridLoader;
import com.nuti.traffic.io.CsvTicksWriter;
import com.nuti.traffic.model.Direction;
import com.nuti.traffic.model.Grid;
import com.nuti.traffic.model.TrafficLight;
import com.nuti.traffic.model.TrafficLightState;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ParallelEngine implements SimulationEngine {

    private final GridLoader gridLoader = new GridLoader();
    private final VehicleInitializer initializer = new VehicleInitializer();
    private final CsvTicksWriter csvTicksWriter = new CsvTicksWriter();

    @Override
    public SimulationResult run(SimulationConfig config) {
        int threads = config.threads();
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be > 0");
        }

        Grid grid = gridLoader.load(config.gridPath());

        int n = config.vehicles();
        int ticks = config.ticks();
        VehicleState vehicles = new VehicleState(n);

        Occupancy occA = new Occupancy(grid.cellCount());
        Occupancy occB = new Occupancy(grid.cellCount());
        int[] occ = occA.array();
        int[] occNext = occB.array();

        TrafficLight[] lights = buildLights(grid, config.lightPeriod());
        initializer.initialize(grid, config.seed(), n, vehicles, occ);

        int[] propTargetCell = new int[n];
        int[] propTargetDir = new int[n];
        boolean[] propCanMove = new boolean[n];

        int[] winners = new int[grid.cellCount() * 4];
        int[] axisMin = new int[grid.cellCount() * 2];
        int[] axisWinner = new int[grid.cellCount()];

        MetricsCollector metrics = new MetricsCollector(ticks);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Instant start = Instant.now();
            System.out.println("[" + start + "] START PARALLEL run grid=" + config.gridPath() + " N=" + n + " ticks=" + ticks + " threads=" + config.threads() + " seed=" + config.seed());
            long startNs = System.nanoTime();

            int workerCount = Math.min(threads, Math.max(1, n));
            int[] movedCounts = new int[workerCount];
            int[] stoppedCounts = new int[workerCount];

            final class OccBuffers {
                private volatile int[] occ;
                private volatile int[] occNext;

                private OccBuffers(int[] occ, int[] occNext) {
                    this.occ = occ;
                    this.occNext = occNext;
                }

                private void swap() {
                    int[] a = occ;
                    occ = occNext;
                    occNext = a;
                }
            }

            OccBuffers buffers = new OccBuffers(occ, occNext);
            Phaser phaser = new Phaser(workerCount + 1);

            AtomicReference<Throwable> workerError = new AtomicReference<>();

            int winnersLen = winners.length;
            int axisMinLen = axisMin.length;
            int axisWinnerLen = axisWinner.length;

            int chunk = (n + workerCount - 1) / workerCount;
            for (int t = 0; t < workerCount; t++) {
                int threadId = t;
                int startIdx = t * chunk;
                int endIdx = Math.min(n, startIdx + chunk);

                int wStart = threadId * winnersLen / workerCount;
                int wEnd = (threadId + 1) * winnersLen / workerCount;
                int amStart = threadId * axisMinLen / workerCount;
                int amEnd = (threadId + 1) * axisMinLen / workerCount;
                int awStart = threadId * axisWinnerLen / workerCount;
                int awEnd = (threadId + 1) * axisWinnerLen / workerCount;

                pool.execute(() -> {
                    try {
                        int[] cellArr = vehicles.cellIdxArray();
                        int[] dirArr = vehicles.dirIdxArray();

                        for (int tick = 0; tick < ticks; tick++) {
                            int phase = phaser.arriveAndAwaitAdvance();
                            if (phase < 0) {
                                return;
                            }

                            for (int k = wStart; k < wEnd; k++) {
                                winners[k] = -1;
                            }
                            for (int k = amStart; k < amEnd; k++) {
                                axisMin[k] = Integer.MAX_VALUE;
                            }
                            for (int k = awStart; k < awEnd; k++) {
                                axisWinner[k] = -1;
                            }

                            int[] occLocal = buffers.occ;
                            for (int i = startIdx; i < endIdx; i++) {
                                MoveRules.computeProposalForVehicle(
                                        grid,
                                        lights,
                                        vehicles,
                                        occLocal,
                                        config,
                                        tick,
                                        i,
                                        propTargetCell,
                                        propTargetDir,
                                        propCanMove
                                );
                            }

                            phase = phaser.arriveAndAwaitAdvance();
                            if (phase < 0) {
                                return;
                            }

                            int[] occNextLocal = buffers.occNext;
                            int occLen = occNextLocal.length;
                            int cStart = threadId * occLen / workerCount;
                            int cEnd = (threadId + 1) * occLen / workerCount;
                            for (int k = cStart; k < cEnd; k++) {
                                occNextLocal[k] = -1;
                            }

                            phase = phaser.arriveAndAwaitAdvance();
                            if (phase < 0) {
                                return;
                            }

                            int moved = 0;
                            int stopped = 0;

                            for (int i = startIdx; i < endIdx; i++) {
                                int cell = cellArr[i];
                                int dirIdx = dirArr[i];

                                int nextCell = cell;
                                int nextDirIdx = dirIdx;

                                if (propCanMove[i]) {
                                    int key = propTargetCell[i] * 4 + propTargetDir[i];
                                    if (winners[key] == i) {
                                        nextCell = propTargetCell[i];
                                        nextDirIdx = propTargetDir[i];
                                    }
                                }

                                if (nextCell != cell) {
                                    moved++;
                                } else {
                                    stopped++;
                                }

                                cellArr[i] = nextCell;
                                dirArr[i] = nextDirIdx;

                                int nextKey = nextCell * 4 + nextDirIdx;
                                if (occNextLocal[nextKey] != -1) {
                                    throw new IllegalStateException("Double-occupancy at tick=" + tick + " cellIdx=" + nextCell + " dirIdx=" + nextDirIdx);
                                }
                                occNextLocal[nextKey] = i;
                            }

                            movedCounts[threadId] = moved;
                            stoppedCounts[threadId] = stopped;

                            phase = phaser.arriveAndAwaitAdvance();
                            if (phase < 0) {
                                return;
                            }
                        }
                    } catch (Throwable t2) {
                        workerError.compareAndSet(null, t2);
                        phaser.forceTermination();
                    }
                });
            }

            for (int tick = 0; tick < ticks; tick++) {
                updateLights(lights, tick);

                int phase = phaser.arriveAndAwaitAdvance();
                if (phase < 0) {
                    Throwable t = workerError.get();
                    if (t != null) {
                        throw new RuntimeException(t);
                    }
                    throw new IllegalStateException("Worker phaser terminated unexpectedly");
                }

                phase = phaser.arriveAndAwaitAdvance();
                if (phase < 0) {
                    Throwable t = workerError.get();
                    if (t != null) {
                        throw new RuntimeException(t);
                    }
                    throw new IllegalStateException("Worker phaser terminated unexpectedly");
                }

                Throwable t = workerError.get();
                if (t != null) {
                    throw new RuntimeException(t);
                }

                resolveWinnersWithAxisExclusion(n, propTargetCell, propTargetDir, propCanMove, winners, axisMin, axisWinner);

                phase = phaser.arriveAndAwaitAdvance();
                if (phase < 0) {
                    t = workerError.get();
                    if (t != null) {
                        throw new RuntimeException(t);
                    }
                    throw new IllegalStateException("Worker phaser terminated unexpectedly");
                }

                phase = phaser.arriveAndAwaitAdvance();
                if (phase < 0) {
                    t = workerError.get();
                    if (t != null) {
                        throw new RuntimeException(t);
                    }
                    throw new IllegalStateException("Worker phaser terminated unexpectedly");
                }

                int moved = 0;
                int stopped = 0;
                for (int t = 0; t < workerCount; t++) {
                    moved += movedCounts[t];
                    stopped += stoppedCounts[t];
                }
                metrics.record(tick, moved, stopped);

                buffers.swap();
            }

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            Instant end = Instant.now();
            System.out.println("[" + end + "] END PARALLEL run elapsed=" + elapsedMs + " ms");

            double avgFlow = metrics.avgFlow(ticks);
            double avgStopped = metrics.avgStopped(ticks);

            System.out.println("MODE: PARALLEL");
            System.out.println("N=" + n + " ticks=" + ticks + " threads=" + config.threads() + " moved_avg=" + avgFlow + " stopped_avg=" + avgStopped + " time_ms=" + elapsedMs);

            if (config.writeTicksCsv()) {
                Path outTicks = (config.outTicksCsv() != null)
                        ? config.outTicksCsv()
                        : defaultTicksPath(config.mode(), n, ticks, config.threads());
                csvTicksWriter.write(outTicks, metrics.movedPerTick(), metrics.stoppedPerTick());
            }

            return new SimulationResult(RunMode.PARALLEL, n, ticks, config.threads(), elapsedMs, avgFlow, avgStopped);
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Path defaultTicksPath(RunMode mode, int n, int ticks, int threads) {
        String m = (mode == RunMode.PARALLEL) ? "par" : "seq";
        String p = (mode == RunMode.PARALLEL) ? ("_P" + threads) : "";
        return Path.of("data", "ticks_" + m + "_N" + n + "_T" + ticks + p + ".csv");
    }

    private static TrafficLight[] buildLights(Grid grid, int period) {
        TrafficLight[] lights = new TrafficLight[grid.intersectionCount()];
        for (int i = 0; i < lights.length; i++) {
            lights[i] = new TrafficLight(period, TrafficLightState.H_GREEN);
        }
        return lights;
    }

    private static void updateLights(TrafficLight[] lights, int tick) {
        for (TrafficLight l : lights) {
            l.update(tick);
        }
    }

    private static void resolveWinnersWithAxisExclusion(
            int n,
            int[] propTargetCell,
            int[] propTargetDir,
            boolean[] propCanMove,
            int[] winners,
            int[] axisMin,
            int[] axisWinner
    ) {
        for (int i = 0; i < n; i++) {
            if (!propCanMove[i]) {
                continue;
            }
            int cell = propTargetCell[i];
            int dirIdx = propTargetDir[i];
            int axis = Direction.fromIndex(dirIdx).isHorizontal() ? 0 : 1;
            int k = cell * 2 + axis;
            if (i < axisMin[k]) {
                axisMin[k] = i;
            }
        }

        for (int cell = 0; cell < axisWinner.length; cell++) {
            int hMin = axisMin[cell * 2];
            int vMin = axisMin[cell * 2 + 1];
            if (hMin == Integer.MAX_VALUE && vMin == Integer.MAX_VALUE) {
                continue;
            }
            if (vMin == Integer.MAX_VALUE || hMin < vMin) {
                axisWinner[cell] = 0;
            } else {
                axisWinner[cell] = 1;
            }
        }

        for (int i = 0; i < n; i++) {
            if (!propCanMove[i]) {
                continue;
            }
            int cell = propTargetCell[i];
            int dirIdx = propTargetDir[i];
            int axis = Direction.fromIndex(dirIdx).isHorizontal() ? 0 : 1;
            if (axisWinner[cell] != axis) {
                continue;
            }

            int key = cell * 4 + dirIdx;
            int w = winners[key];
            if (w == -1 || i < w) {
                winners[key] = i;
            }
        }
    }
}
