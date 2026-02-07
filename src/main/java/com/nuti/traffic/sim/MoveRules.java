package com.nuti.traffic.sim;

import com.nuti.traffic.model.CellType;
import com.nuti.traffic.model.Direction;
import com.nuti.traffic.model.Grid;
import com.nuti.traffic.model.TrafficLight;
import com.nuti.traffic.util.DeterministicRng;

final class MoveRules {

    private MoveRules() {
    }

    static void computeProposalForVehicle(
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
