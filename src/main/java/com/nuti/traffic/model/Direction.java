package com.nuti.traffic.model;

public enum Direction {
    NORTH(0, -1, 0),
    SOUTH(0, 1, 1),
    EAST(1, 0, 2),
    WEST(-1, 0, 3);

    private final int dx;
    private final int dy;
    private final int index;

    Direction(int dx, int dy, int index) {
        this.dx = dx;
        this.dy = dy;
        this.index = index;
    }

    public int dx() {
        return dx;
    }

    public int dy() {
        return dy;
    }

    public int index() {
        return index;
    }

    public boolean isHorizontal() {
        return this == EAST || this == WEST;
    }

    public boolean isVertical() {
        return this == NORTH || this == SOUTH;
    }

    public Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }

    public static Direction fromIndex(int index) {
        return switch (index) {
            case 0 -> NORTH;
            case 1 -> SOUTH;
            case 2 -> EAST;
            case 3 -> WEST;
            default -> throw new IllegalArgumentException("Invalid direction index: " + index);
        };
    }
}
