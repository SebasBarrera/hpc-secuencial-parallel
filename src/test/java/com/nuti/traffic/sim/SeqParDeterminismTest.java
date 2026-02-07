package com.nuti.traffic.sim;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SeqParDeterminismTest {

    @Test
    void seqAndPar_sameSeed_sameMetricsAndTimeIndependentFields() {
        SimulationConfig base = new SimulationConfig(
                Path.of("grids", "ejemplo1.txt"),
                200,
                200,
                42L,
                0.2,
                10,
                RunMode.SEQUENTIAL,
                1,
                null
        );

        SimulationResult seq = new SequentialEngine().run(base);

        SimulationConfig parCfg = new SimulationConfig(
                base.gridPath(),
                base.vehicles(),
                base.ticks(),
                base.seed(),
                base.turnProb(),
                base.lightPeriod(),
                RunMode.PARALLEL,
                4,
                null
        );

        SimulationResult par = new ParallelEngine().run(parCfg);

        assertEquals(seq.vehicles(), par.vehicles());
        assertEquals(seq.ticks(), par.ticks());
        assertEquals(seq.avgFlow(), par.avgFlow(), 1e-9);
        assertEquals(seq.avgStopped(), par.avgStopped(), 1e-9);
    }
}
