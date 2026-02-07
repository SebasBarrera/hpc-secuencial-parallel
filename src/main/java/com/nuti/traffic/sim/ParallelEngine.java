package com.nuti.traffic.sim;

import com.nuti.traffic.grid.GridLoader;
import com.nuti.traffic.io.CsvTicksWriter;
import com.nuti.traffic.model.CellType;
import com.nuti.traffic.model.Direction;
import com.nuti.traffic.model.Grid;
import com.nuti.traffic.model.TrafficLight;
import com.nuti.traffic.model.TrafficLightState;
import com.nuti.traffic.util.DeterministicRng;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

public final class ParallelEngine implements SimulationEngine {

    private final GridLoader gridLoader = new GridLoader();
    private final VehicleInitializer initializer = new VehicleInitializer();
    private final CsvTicksWriter csvTicksWriter = new CsvTicksWriter();

    @Override
    public SimulationResult run(SimulationConfig config) {
        if (config.threads() <= 0) {
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

        AtomicIntegerArray axisMin = new AtomicIntegerArray(grid.cellCount() * 2);
        int[] axisWinner = new int[grid.cellCount()];

        AtomicIntegerArray winners = new AtomicIntegerArray(grid.cellCount() * 4);

        MetricsCollector metrics = new MetricsCollector(ticks);

        ExecutorService pool = Executors.newFixedThreadPool(config.threads());
        try {
            Instant start = Instant.now();
            long startNs = System.nanoTime();
            System.out.println("[" + start + "] START PARALLEL run grid=" + config.gridPath() + " N=" + n + " ticks=" + ticks + " threads=" + config.threads() + " seed=" + config.seed());

            for (int tick = 0; tick < ticks; tick++) {
                updateLights(lights, tick);

                parallelComputeProposals(pool, config.threads(), grid, lights, vehicles, occ, config, tick, propTargetCell, propTargetDir, propCanMove);
                resolveAxisWinnersParallel(pool, config.threads(), n, propTargetCell, propTargetDir, propCanMove, axisMin, axisWinner);
                resolveWinnersParallel(pool, config.threads(), n, propTargetCell, propTargetDir, propCanMove, axisWinner, winners);

                int[] swapped = applyMovesParallel(pool, config.threads(), vehicles, occNext, n, propTargetCell, propTargetDir, propCanMove, winners, tick, metrics);
                occ = swapped;
                occNext = (occ == occA.array()) ? occB.array() : occA.array();
            }

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            Instant end = Instant.now();
            System.out.println("[" + end + "] END PARALLEL run elapsed=" + elapsedMs + " ms");

            double avgFlow = metrics.avgFlow(ticks);
            double avgStopped = metrics.avgStopped(ticks);

            System.out.println("MODE: PARALLEL");
            System.out.println("N=" + n + " ticks=" + ticks + " threads=" + config.threads() + " moved_avg=" + avgFlow + " stopped_avg=" + avgStopped + " time_ms=" + elapsedMs);

            Path outTicks = (config.outTicksCsv() != null)
                    ? config.outTicksCsv()
                    : defaultTicksPath(config.mode(), n, ticks, config.threads());
            csvTicksWriter.write(outTicks, metrics.movedPerTick(), metrics.stoppedPerTick());

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

    private static void parallelComputeProposals(
            ExecutorService pool,
            int threads,
            Grid grid,
            TrafficLight[] lights,
            VehicleState vehicles,
            int[] occ,
            SimulationConfig config,
            int tick,
            int[] propTargetCell,
            int[] propTargetDir,
            boolean[] propCanMove
    ) {
        CountDownLatch latch = new CountDownLatch(threads);
        int n = vehicles.vehicleCount();
        int chunk = (n + threads - 1) / threads;

        for (int t = 0; t < threads; t++) {
            int start = t * chunk;
            int end = Math.min(n, start + chunk);
            pool.execute(() -> {
                try {
                    for (int i = start; i < end; i++) {
                        computeProposalForVehicle(grid, lights, vehicles, occ, config, tick, i, propTargetCell, propTargetDir, propCanMove);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        await(latch);
    }

    private static void computeProposalForVehicle(
            Grid grid,
            TrafficLight[] lights,
            VehicleState vehicles,
            int[] occ,
            SimulationConfig config,
            int tick,
            int i,
            int[] propTargetCell,
            int[] propTargetDir,
            boolean[] propCanMove
    ) {
        int cell = vehicles.cellIdx(i);
        int dirIdx = vehicles.dirIdx(i);
        Direction dir = Direction.fromIndex(dirIdx);

        int attemptDirIdx = dirIdx;
        Direction attemptDir = dir;

        if (grid.cellTypeAt(cell) == CellType.INTERSECTION) {
            double r = DeterministicRng.unitDouble(config.seed(), i, tick, 1L);
            if (r < config.turnProb()) {
                double r2 = DeterministicRng.unitDouble(config.seed(), i, tick, 2L);
                attemptDir = (r2 < 0.5) ? leftTurn(dir) : rightTurn(dir);
                attemptDirIdx = attemptDir.index();
            }
        }

        int target = nextCell(grid, cell, attemptDir);
        if (target < 0 || !grid.isTransitable(target)) {
            propCanMove[i] = false;
            return;
        }

        if (grid.cellTypeAt(cell) != CellType.INTERSECTION && grid.cellTypeAt(target) == CellType.INTERSECTION) {
            int intersectionIndex = grid.intersectionIndexAtCell(target);
            if (intersectionIndex < 0) {
                propCanMove[i] = false;
                return;
            }
            if (!lights[intersectionIndex].allows(attemptDir)) {
                propCanMove[i] = false;
                return;
            }
        }

        if (!Occupancy.canOccupy(occ, target, attemptDirIdx)) {
            propCanMove[i] = false;
            return;
        }

        propTargetCell[i] = target;
        propTargetDir[i] = attemptDirIdx;
        propCanMove[i] = true;
    }

    private static void resolveAxisWinnersParallel(
            ExecutorService pool,
            int threads,
            int n,
            int[] propTargetCell,
            int[] propTargetDir,
            boolean[] propCanMove,
            AtomicIntegerArray axisMin,
            int[] axisWinner
    ) {
        for (int i = 0; i < axisMin.length(); i++) {
            axisMin.set(i, Integer.MAX_VALUE);
        }
        Arrays.fill(axisWinner, -1);

        CountDownLatch latch = new CountDownLatch(threads);
        int chunk = (n + threads - 1) / threads;

        for (int t = 0; t < threads; t++) {
            int start = t * chunk;
            int end = Math.min(n, start + chunk);
            pool.execute(() -> {
                try {
                    for (int i = start; i < end; i++) {
                        if (!propCanMove[i]) {
                            continue;
                        }
                        int cell = propTargetCell[i];
                        int dirIdx = propTargetDir[i];
                        int axis = Direction.fromIndex(dirIdx).isHorizontal() ? 0 : 1;
                        casMin(axisMin, cell * 2 + axis, i);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        await(latch);

        for (int cell = 0; cell < axisWinner.length; cell++) {
            int hMin = axisMin.get(cell * 2);
            int vMin = axisMin.get(cell * 2 + 1);
            if (hMin == Integer.MAX_VALUE && vMin == Integer.MAX_VALUE) {
                continue;
            }
            if (vMin == Integer.MAX_VALUE || hMin < vMin) {
                axisWinner[cell] = 0;
            } else {
                axisWinner[cell] = 1;
            }
        }
    }

    private static void resolveWinnersParallel(
            ExecutorService pool,
            int threads,
            int n,
            int[] propTargetCell,
            int[] propTargetDir,
            boolean[] propCanMove,
            int[] axisWinner,
            AtomicIntegerArray winners
    ) {
        for (int i = 0; i < winners.length(); i++) {
            winners.set(i, Integer.MAX_VALUE);
        }

        CountDownLatch latch = new CountDownLatch(threads);
        int chunk = (n + threads - 1) / threads;

        for (int t = 0; t < threads; t++) {
            int start = t * chunk;
            int end = Math.min(n, start + chunk);
            pool.execute(() -> {
                try {
                    for (int i = start; i < end; i++) {
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
                        casMin(winners, key, i);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        await(latch);
    }

    private static int[] applyMovesParallel(
            ExecutorService pool,
            int threads,
            VehicleState vehicles,
            int[] occNext,
            int n,
            int[] propTargetCell,
            int[] propTargetDir,
            boolean[] propCanMove,
            AtomicIntegerArray winners,
            int tick,
            MetricsCollector metrics
    ) {
        Occupancy.clearAll(occNext);

        int[] movedCounts = new int[threads];
        int[] stoppedCounts = new int[threads];

        int[] cellArr = vehicles.cellIdxArray();
        int[] dirArr = vehicles.dirIdxArray();

        CountDownLatch latch = new CountDownLatch(threads);
        int chunk = (n + threads - 1) / threads;

        for (int t = 0; t < threads; t++) {
            int threadId = t;
            int start = t * chunk;
            int end = Math.min(n, start + chunk);
            pool.execute(() -> {
                int moved = 0;
                int stopped = 0;
                try {
                    for (int i = start; i < end; i++) {
                        int cell = cellArr[i];
                        int dirIdx = dirArr[i];

                        int nextCell = cell;
                        int nextDirIdx = dirIdx;

                        if (propCanMove[i]) {
                            int key = propTargetCell[i] * 4 + propTargetDir[i];
                            if (winners.get(key) == i) {
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
                        if (occNext[nextKey] != -1) {
                            throw new IllegalStateException("Double-occupancy at tick=" + tick + " cellIdx=" + nextCell + " dirIdx=" + nextDirIdx);
                        }
                        occNext[nextKey] = i;
                    }

                    movedCounts[threadId] = moved;
                    stoppedCounts[threadId] = stopped;
                } finally {
                    latch.countDown();
                }
            });
        }

        await(latch);

        int moved = 0;
        int stopped = 0;
        for (int t = 0; t < threads; t++) {
            moved += movedCounts[t];
            stopped += stoppedCounts[t];
        }
        metrics.record(tick, moved, stopped);

        return occNext;
    }

    private static void casMin(AtomicIntegerArray arr, int index, int value) {
        while (true) {
            int cur = arr.get(index);
            if (value >= cur) {
                return;
            }
            if (arr.compareAndSet(index, cur, value)) {
                return;
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static int nextCell(Grid grid, int cellIdx, Direction dir) {
        int x = grid.x(cellIdx) + dir.dx();
        int y = grid.y(cellIdx) + dir.dy();
        if (!grid.inBounds(x, y)) {
            return -1;
        }
        return grid.idx(x, y);
    }

    private static Direction leftTurn(Direction dir) {
        return switch (dir) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
        };
    }

    private static Direction rightTurn(Direction dir) {
        return switch (dir) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case EAST -> Direction.SOUTH;
            case WEST -> Direction.NORTH;
        };
    }
}
