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

public final class SequentialEngine {

    private final GridLoader gridLoader = new GridLoader();
    private final VehicleInitializer initializer = new VehicleInitializer();
    private final CsvTicksWriter csvTicksWriter = new CsvTicksWriter();

    public SimulationResult run(SimulationConfig config) {
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

        Instant start = Instant.now();
        long startNs = System.nanoTime();
        System.out.println("[" + start + "] START SEQUENTIAL run grid=" + config.gridPath() + " N=" + n + " ticks=" + ticks + " seed=" + config.seed());

        for (int tick = 0; tick < ticks; tick++) {
            updateLights(lights, tick);

            computeProposals(grid, lights, vehicles, occ, config, tick, propTargetCell, propTargetDir, propCanMove);
            resolveWinnersWithAxisExclusion(n, propTargetCell, propTargetDir, propCanMove, winners, axisMin, axisWinner);
            int[] swapped = applyMoves(grid, vehicles, occ, occNext, n, propTargetCell, propTargetDir, propCanMove, winners, tick, metrics);
            occ = swapped;
            occNext = (occ == occA.array()) ? occB.array() : occA.array();
        }

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        Instant end = Instant.now();
        System.out.println("[" + end + "] END SEQUENTIAL run elapsed=" + elapsedMs + " ms");

        double avgFlow = metrics.avgFlow(ticks);
        double avgStopped = metrics.avgStopped(ticks);

        System.out.println("MODE: SEQUENTIAL");
        System.out.println("N=" + n + " ticks=" + ticks + " moved_avg=" + avgFlow + " stopped_avg=" + avgStopped + " time_ms=" + elapsedMs);

        Path outTicks = (config.outTicksCsv() != null)
                ? config.outTicksCsv()
                : defaultTicksPath(config.mode(), n, ticks, config.threads());
        csvTicksWriter.write(outTicks, metrics.movedPerTick(), metrics.stoppedPerTick());

        return new SimulationResult(RunMode.SEQUENTIAL, n, ticks, 1, elapsedMs, avgFlow, avgStopped);
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

    private static void computeProposals(
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
        int n = vehicles.vehicleCount();
        Arrays.fill(propCanMove, false);

        for (int i = 0; i < n; i++) {
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
                continue;
            }

            if (grid.cellTypeAt(cell) != CellType.INTERSECTION && grid.cellTypeAt(target) == CellType.INTERSECTION) {
                int intersectionIndex = grid.intersectionIndexAtCell(target);
                if (intersectionIndex < 0) {
                    continue;
                }
                if (!lights[intersectionIndex].allows(attemptDir)) {
                    continue;
                }
            }

            if (!Occupancy.canOccupy(occ, target, attemptDirIdx)) {
                continue;
            }

            propTargetCell[i] = target;
            propTargetDir[i] = attemptDirIdx;
            propCanMove[i] = true;
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
        Arrays.fill(winners, -1);
        Arrays.fill(axisWinner, -1);
        Arrays.fill(axisMin, Integer.MAX_VALUE);

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

    private static int[] applyMoves(
            Grid grid,
            VehicleState vehicles,
            int[] occ,
            int[] occNext,
            int n,
            int[] propTargetCell,
            int[] propTargetDir,
            boolean[] propCanMove,
            int[] winners,
            int tick,
            MetricsCollector metrics
    ) {
        Occupancy.clearAll(occNext);

        int moved = 0;
        int stopped = 0;

        int[] cellArr = vehicles.cellIdxArray();
        int[] dirArr = vehicles.dirIdxArray();

        for (int i = 0; i < n; i++) {
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
            if (occNext[nextKey] != -1) {
                throw new IllegalStateException("Double-occupancy at tick=" + tick + " cellIdx=" + nextCell + " dirIdx=" + nextDirIdx);
            }
            occNext[nextKey] = i;
        }

        metrics.record(tick, moved, stopped);
        return occNext;
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
