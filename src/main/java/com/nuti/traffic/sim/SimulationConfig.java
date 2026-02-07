package com.nuti.traffic.sim;

import java.nio.file.Path;

public record SimulationConfig(
        Path gridPath,
        int vehicles,
        int ticks,
        long seed,
        double turnProb,
        int lightPeriod,
        RunMode mode,
        int threads,
        Path outTicksCsv
) {
}
