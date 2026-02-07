package com.nuti.traffic.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TrafficLightTest {

    @Test
    void update_tick0_doesNotChangeState() {
        TrafficLight l = new TrafficLight(3, TrafficLightState.H_GREEN);
        l.update(0);
        assertEquals(TrafficLightState.H_GREEN, l.state());
    }

    @Test
    void update_beforePeriod_doesNotChangeState() {
        TrafficLight l = new TrafficLight(3, TrafficLightState.H_GREEN);
        l.update(1);
        l.update(2);
        assertEquals(TrafficLightState.H_GREEN, l.state());
    }

    @Test
    void update_atPeriod_toggles() {
        TrafficLight l = new TrafficLight(3, TrafficLightState.H_GREEN);
        l.update(3);
        assertEquals(TrafficLightState.V_GREEN, l.state());
        l.update(6);
        assertEquals(TrafficLightState.H_GREEN, l.state());
    }

    @Test
    void allows_matchesAxis() {
        TrafficLight l = new TrafficLight(10, TrafficLightState.H_GREEN);
        assertTrue(l.allows(Direction.EAST));
        assertTrue(l.allows(Direction.WEST));
        assertFalse(l.allows(Direction.NORTH));
        assertFalse(l.allows(Direction.SOUTH));

        l.update(10);
        assertFalse(l.allows(Direction.EAST));
        assertFalse(l.allows(Direction.WEST));
        assertTrue(l.allows(Direction.NORTH));
        assertTrue(l.allows(Direction.SOUTH));
    }
}
