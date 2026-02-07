package com.nuti.traffic.sim;

public record SimulationResult(
        RunMode mode,
        int vehicles,
        int ticks,
        int threads,
        long timeMs,
        double avgFlow,
        double avgStopped
) {
}
