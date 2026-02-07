package com.nuti.traffic.sim;

import java.util.Arrays;

public final class VehicleState {

    private final int vehicleCount;
    private final int[] cellIdx;
    private final int[] dirIdx;

    public VehicleState(int vehicleCount) {
        if (vehicleCount < 0) {
            throw new IllegalArgumentException("vehicleCount must be >= 0");
        }
        this.vehicleCount = vehicleCount;
        this.cellIdx = new int[vehicleCount];
        this.dirIdx = new int[vehicleCount];
        Arrays.fill(this.cellIdx, -1);
        Arrays.fill(this.dirIdx, -1);
    }

    public int vehicleCount() {
        return vehicleCount;
    }

    public int cellIdx(int vehicleId) {
        return cellIdx[vehicleId];
    }

    public int dirIdx(int vehicleId) {
        return dirIdx[vehicleId];
    }

    public void set(int vehicleId, int cellIdx, int dirIdx) {
        this.cellIdx[vehicleId] = cellIdx;
        this.dirIdx[vehicleId] = dirIdx;
    }

    public int[] cellIdxArray() {
        return cellIdx;
    }

    public int[] dirIdxArray() {
        return dirIdx;
    }
}
