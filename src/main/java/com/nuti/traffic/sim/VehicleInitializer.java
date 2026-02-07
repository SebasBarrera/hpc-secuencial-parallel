package com.nuti.traffic.sim;

import com.nuti.traffic.model.CellType;
import com.nuti.traffic.model.Direction;
import com.nuti.traffic.model.Grid;

import java.util.SplittableRandom;

public final class VehicleInitializer {

    public void initialize(Grid grid, long seed, int vehicleCount, VehicleState vehicles, int[] occ) {
        int transitable = 0;
        for (int cellIdx = 0; cellIdx < grid.cellCount(); cellIdx++) {
            if (grid.isTransitable(cellIdx)) {
                transitable++;
            }
        }
        long capacity = 2L * transitable;
        if (vehicleCount > capacity) {
            throw new IllegalArgumentException("Cannot place N=" + vehicleCount + " vehicles: capacity=" + capacity);
        }

        int[] candidateKeys = buildCandidateKeys(grid);
        shuffle(candidateKeys, new SplittableRandom(seed));

        int placed = 0;
        for (int k = 0; k < candidateKeys.length && placed < vehicleCount; k++) {
            int key = candidateKeys[k];
            int cellIdx = key / 4;
            int dirIdx = key % 4;

            if (!Occupancy.canOccupy(occ, cellIdx, dirIdx)) {
                continue;
            }

            Occupancy.set(occ, cellIdx, dirIdx, placed);
            vehicles.set(placed, cellIdx, dirIdx);
            placed++;
        }

        if (placed != vehicleCount) {
            throw new IllegalArgumentException("Could not place all vehicles: requested=" + vehicleCount + " placed=" + placed);
        }
    }

    private static int[] buildCandidateKeys(Grid grid) {
        int max = grid.cellCount() * 4;
        int[] tmp = new int[max];
        int n = 0;

        for (int cellIdx = 0; cellIdx < grid.cellCount(); cellIdx++) {
            if (!grid.isTransitable(cellIdx)) {
                continue;
            }

            CellType type = grid.cellTypeAt(cellIdx);
            if (type == CellType.INTERSECTION) {
                for (Direction d : Direction.values()) {
                    tmp[n++] = Occupancy.key(cellIdx, d.index());
                }
                continue;
            }

            if (isRoadHorizontal(grid, cellIdx)) {
                tmp[n++] = Occupancy.key(cellIdx, Direction.EAST.index());
                tmp[n++] = Occupancy.key(cellIdx, Direction.WEST.index());
            } else {
                tmp[n++] = Occupancy.key(cellIdx, Direction.NORTH.index());
                tmp[n++] = Occupancy.key(cellIdx, Direction.SOUTH.index());
            }
        }

        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static boolean isRoadHorizontal(Grid grid, int cellIdx) {
        int x = grid.x(cellIdx);
        int y = grid.y(cellIdx);
        boolean left = grid.inBounds(x - 1, y) && grid.isTransitable(grid.idx(x - 1, y));
        boolean right = grid.inBounds(x + 1, y) && grid.isTransitable(grid.idx(x + 1, y));
        return left || right;
    }

    private static void shuffle(int[] a, SplittableRandom rnd) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }
}
