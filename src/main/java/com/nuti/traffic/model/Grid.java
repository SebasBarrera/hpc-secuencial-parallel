package com.nuti.traffic.model;

import java.util.Arrays;

public final class Grid {

    private final int width;
    private final int height;
    private final CellType[] cells;
    private final int[] intersectionIndexByCell;
    private final int[] intersectionCellIdx;

    public Grid(int width, int height, CellType[] cells, int[] intersectionIndexByCell, int[] intersectionCellIdx) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid grid dimensions");
        }
        if (cells.length != width * height) {
            throw new IllegalArgumentException("cells length mismatch");
        }
        if (intersectionIndexByCell.length != cells.length) {
            throw new IllegalArgumentException("intersectionIndexByCell length mismatch");
        }
        this.width = width;
        this.height = height;
        this.cells = Arrays.copyOf(cells, cells.length);
        this.intersectionIndexByCell = Arrays.copyOf(intersectionIndexByCell, intersectionIndexByCell.length);
        this.intersectionCellIdx = Arrays.copyOf(intersectionCellIdx, intersectionCellIdx.length);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int cellCount() {
        return cells.length;
    }

    public int x(int idx) {
        return idx % width;
    }

    public int y(int idx) {
        return idx / width;
    }

    public int idx(int x, int y) {
        return y * width + x;
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    public CellType cellTypeAt(int idx) {
        return cells[idx];
    }

    public CellType cellTypeAt(int x, int y) {
        return cells[idx(x, y)];
    }

    public boolean isTransitable(int idx) {
        CellType t = cells[idx];
        return t == CellType.ROAD || t == CellType.INTERSECTION;
    }

    public int intersectionCount() {
        return intersectionCellIdx.length;
    }

    public int intersectionIndexAtCell(int cellIdx) {
        return intersectionIndexByCell[cellIdx];
    }

    public int intersectionCellIdx(int intersectionIndex) {
        return intersectionCellIdx[intersectionIndex];
    }
}
