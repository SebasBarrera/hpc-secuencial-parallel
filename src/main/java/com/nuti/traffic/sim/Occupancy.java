package com.nuti.traffic.sim;

import com.nuti.traffic.model.Direction;

import java.util.Arrays;

public final class Occupancy {

    private final int[] occ;

    public Occupancy(int cellCount) {
        this.occ = new int[cellCount * 4];
        Arrays.fill(this.occ, -1);
    }

    public int[] array() {
        return occ;
    }

    public static int key(int cellIdx, int dirIdx) {
        return cellIdx * 4 + dirIdx;
    }

    public static boolean canOccupy(int[] occ, int cellIdx, int dirIdx) {
        int base = cellIdx * 4;
        if (occ[base + dirIdx] != -1) {
            return false;
        }

        Direction d = Direction.fromIndex(dirIdx);
        if (d.isHorizontal()) {
            return occ[base + Direction.NORTH.index()] == -1 && occ[base + Direction.SOUTH.index()] == -1;
        }
        return occ[base + Direction.EAST.index()] == -1 && occ[base + Direction.WEST.index()] == -1;
    }

    public static void set(int[] occ, int cellIdx, int dirIdx, int vehicleId) {
        occ[key(cellIdx, dirIdx)] = vehicleId;
    }

    public static void clearAll(int[] occ) {
        Arrays.fill(occ, -1);
    }
}
