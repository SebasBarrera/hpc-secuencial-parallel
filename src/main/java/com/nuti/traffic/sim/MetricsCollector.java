package com.nuti.traffic.sim;

public final class MetricsCollector {

    private final int[] moved;
    private final int[] stopped;
    private long movedSum;
    private long stoppedSum;

    public MetricsCollector(int ticks) {
        this.moved = new int[ticks];
        this.stopped = new int[ticks];
    }

    public void record(int tick, int movedCount, int stoppedCount) {
        moved[tick] = movedCount;
        stopped[tick] = stoppedCount;
        movedSum += movedCount;
        stoppedSum += stoppedCount;
    }

    public int[] movedPerTick() {
        return moved;
    }

    public int[] stoppedPerTick() {
        return stopped;
    }

    public double avgFlow(int ticks) {
        if (ticks <= 0) {
            return 0.0;
        }
        return movedSum / (double) ticks;
    }

    public double avgStopped(int ticks) {
        if (ticks <= 0) {
            return 0.0;
        }
        return stoppedSum / (double) ticks;
    }
}
