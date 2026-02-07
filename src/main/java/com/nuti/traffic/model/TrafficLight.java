package com.nuti.traffic.model;

import java.util.Objects;

public final class TrafficLight {

    private final int period;
    private TrafficLightState state;

    public TrafficLight(int period, TrafficLightState initialState) {
        if (period <= 0) {
            throw new IllegalArgumentException("period must be > 0");
        }
        this.period = period;
        this.state = Objects.requireNonNull(initialState, "initialState");
    }

    public int period() {
        return period;
    }

    public TrafficLightState state() {
        return state;
    }

    public void update(int tick) {
        if (tick <= 0) {
            return;
        }
        if (tick % period == 0) {
            state = (state == TrafficLightState.H_GREEN) ? TrafficLightState.V_GREEN : TrafficLightState.H_GREEN;
        }
    }

    public boolean allows(Direction dir) {
        if (dir.isHorizontal()) {
            return state == TrafficLightState.H_GREEN;
        }
        return state == TrafficLightState.V_GREEN;
    }
}
