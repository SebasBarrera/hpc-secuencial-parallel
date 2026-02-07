package com.nuti.traffic.sim;

import com.nuti.traffic.grid.GridLoader;
import com.nuti.traffic.io.CsvTicksWriter;
import com.nuti.traffic.model.Direction;
import com.nuti.traffic.model.Grid;
import com.nuti.traffic.model.TrafficLight;
import com.nuti.traffic.model.TrafficLightState;

import java.nio.file.Path;
import java.time.Instant;
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
        int[] winnersStamp = new int[winners.length];
        int[] axisMin = new int[grid.cellCount() * 2];
        int[] axisMinStamp = new int[axisMin.length];

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

            int chunk = (n + workerCount - 1) / workerCount;
            for (int t = 0; t < workerCount; t++) {
                int threadId = t;
                int startIdx = t * chunk;
                int endIdx = Math.min(n, startIdx + chunk);

                pool.execute(() -> {
                    try {
                        int[] cellArr = vehicles.cellIdxArray();
                        int[] dirArr = vehicles.dirIdxArray();

                        for (int tick = 0; tick < ticks; tick++) {
                            int phase = phaser.arriveAndAwaitAdvance();
                            if (phase < 0) {
                                return;
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

                            phase = phaser.arriveAndAwaitAdvance();
                            if (phase < 0) {
                                return;
                            }

                            int moved = 0;
                            int stopped = 0;

                            int[] occLocal2 = buffers.occ;
                            int[] occNextLocal = buffers.occNext;
                            int stamp = tick + 1;

                            for (int i = startIdx; i < endIdx; i++) {
                                int cell = cellArr[i];
                                int dirIdx = dirArr[i];

                                int oldKey = cell * 4 + dirIdx;

                                int nextCell = cell;
                                int nextDirIdx = dirIdx;

                                if (propCanMove[i]) {
                                    int key = propTargetCell[i] * 4 + propTargetDir[i];
                                    if (winnersStamp[key] == stamp && winners[key] == i) {
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

                                occLocal2[oldKey] = -1;

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

                int stamp = tick + 1;
                resolveWinnersWithAxisExclusionStamped(
                        n,
                        propTargetCell,
                        propTargetDir,
                        propCanMove,
                        winners,
                        winnersStamp,
                        axisMin,
                        axisMinStamp,
                        stamp
                );

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
                for (int wi = 0; wi < workerCount; wi++) {
                    moved += movedCounts[wi];
                    stopped += stoppedCounts[wi];
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

    private static void resolveWinnersWithAxisExclusionStamped(
            int n,
            int[] propTargetCell,
            int[] propTargetDir,
            boolean[] propCanMove,
            int[] winners,
            int[] winnersStamp,
            int[] axisMin,
            int[] axisMinStamp,
            int stamp
    ) {
        for (int i = 0; i < n; i++) {
            if (!propCanMove[i]) {
                continue;
            }
            int cell = propTargetCell[i];
            int dirIdx = propTargetDir[i];
            int axis = Direction.fromIndex(dirIdx).isHorizontal() ? 0 : 1;
            int k = cell * 2 + axis;

            if (axisMinStamp[k] != stamp) {
                axisMinStamp[k] = stamp;
                axisMin[k] = i;
                continue;
            }
            if (i < axisMin[k]) {
                axisMin[k] = i;
            }
        }

        for (int i = 0; i < n; i++) {
            if (!propCanMove[i]) {
                continue;
            }
            int cell = propTargetCell[i];
            int dirIdx = propTargetDir[i];
            int axis = Direction.fromIndex(dirIdx).isHorizontal() ? 0 : 1;

            int hKey = cell * 2;
            int vKey = cell * 2 + 1;
            int hMin = (axisMinStamp[hKey] == stamp) ? axisMin[hKey] : Integer.MAX_VALUE;
            int vMin = (axisMinStamp[vKey] == stamp) ? axisMin[vKey] : Integer.MAX_VALUE;

            if (hMin == Integer.MAX_VALUE && vMin == Integer.MAX_VALUE) {
                continue;
            }
            int axisWinner = (vMin == Integer.MAX_VALUE || hMin < vMin) ? 0 : 1;
            if (axisWinner != axis) {
                continue;
            }

            int key = cell * 4 + dirIdx;
            if (winnersStamp[key] != stamp) {
                winnersStamp[key] = stamp;
                winners[key] = i;
                continue;
            }

            int w = winners[key];
            if (i < w) {
                winners[key] = i;
            }
        }
    }
}
