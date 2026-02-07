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

            int chunk = (n + workerCount - 1) / workerCount;
            for (int t = 0; t < workerCount; t++) {
                int threadId = t;
                int startIdx = t * chunk;
                int endIdx = Math.min(n, startIdx + chunk);
                pool.execute(() -> {
                    for (int tick = 0; tick < ticks; tick++) {
                        phaser.arriveAndAwaitAdvance();

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

                        phaser.arriveAndAwaitAdvance();
                        phaser.arriveAndAwaitAdvance();

                        int[] occNextLocal = buffers.occNext;
                        int moved = 0;
                        int stopped = 0;

                        int[] cellArr = vehicles.cellIdxArray();
                        int[] dirArr = vehicles.dirIdxArray();

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

                        phaser.arriveAndAwaitAdvance();
                    }
                });
            }

            for (int tick = 0; tick < ticks; tick++) {
                updateLights(lights, tick);

                Arrays.fill(winners, -1);
                Arrays.fill(axisWinner, -1);
                Arrays.fill(axisMin, Integer.MAX_VALUE);

                phaser.arriveAndAwaitAdvance();
                phaser.arriveAndAwaitAdvance();

                resolveWinnersWithAxisExclusion(n, propTargetCell, propTargetDir, propCanMove, winners, axisMin, axisWinner);

                Occupancy.clearAll(buffers.occNext);

                phaser.arriveAndAwaitAdvance();
                phaser.arriveAndAwaitAdvance();

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
