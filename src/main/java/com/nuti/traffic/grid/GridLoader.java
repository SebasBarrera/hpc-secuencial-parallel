package com.nuti.traffic.grid;

import com.nuti.traffic.model.CellType;
import com.nuti.traffic.model.Grid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GridLoader {

    public Grid load(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new GridValidationException("Failed to read grid file: " + path, e);
        }

        if (lines.isEmpty()) {
            throw new GridValidationException("Grid file is empty: " + path);
        }

        int height = lines.size();
        int width = lines.get(0).length();
        if (width == 0) {
            throw new GridValidationException("Grid has empty first line: " + path);
        }

        for (int y = 0; y < height; y++) {
            String line = lines.get(y);
            if (line.length() != width) {
                throw new GridValidationException("Non-rectangular grid at line " + (y + 1) + ": expected width=" + width + " got=" + line.length());
            }
        }

        CellType[] cells = new CellType[width * height];
        int[] intersectionIndexByCell = new int[cells.length];
        for (int i = 0; i < intersectionIndexByCell.length; i++) {
            intersectionIndexByCell[i] = -1;
        }
        List<Integer> intersections = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            String line = lines.get(y);
            for (int x = 0; x < width; x++) {
                char c = line.charAt(x);
                int idx = y * width + x;
                CellType t = switch (c) {
                    case '.' -> CellType.ROAD;
                    case '+' -> CellType.INTERSECTION;
                    case '#' -> CellType.BLOCK;
                    default -> throw new GridValidationException("Invalid character '" + c + "' at (x=" + x + ", y=" + y + ")");
                };
                cells[idx] = t;
                if (t == CellType.INTERSECTION) {
                    intersectionIndexByCell[idx] = intersections.size();
                    intersections.add(idx);
                }
            }
        }

        validateIntersectionsStrict4Way(width, height, cells);
        validateNoCrossroadsAsRoad(width, height, cells);

        int[] intersectionCellIdx = new int[intersections.size()];
        for (int i = 0; i < intersections.size(); i++) {
            intersectionCellIdx[i] = intersections.get(i);
        }

        return new Grid(width, height, cells, intersectionIndexByCell, intersectionCellIdx);
    }

    private static void validateIntersectionsStrict4Way(int width, int height, CellType[] cells) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (cells[idx] != CellType.INTERSECTION) {
                    continue;
                }

                if (y - 1 < 0 || y + 1 >= height || x - 1 < 0 || x + 1 >= width) {
                    throw new GridValidationException("Intersection '+' cannot be on boundary at (x=" + x + ", y=" + y + ")");
                }

                if (!isTransitable(cells[(y - 1) * width + x])
                        || !isTransitable(cells[(y + 1) * width + x])
                        || !isTransitable(cells[y * width + (x - 1)])
                        || !isTransitable(cells[y * width + (x + 1)])) {
                    throw new GridValidationException("Invalid intersection '+' (requires 4-way transitable neighbors) at (x=" + x + ", y=" + y + ")");
                }
            }
        }
    }

    private static void validateNoCrossroadsAsRoad(int width, int height, CellType[] cells) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (cells[idx] != CellType.ROAD) {
                    continue;
                }

                boolean hasH = (x - 1 >= 0 && isTransitable(cells[y * width + (x - 1)]))
                        || (x + 1 < width && isTransitable(cells[y * width + (x + 1)]));
                boolean hasV = (y - 1 >= 0 && isTransitable(cells[(y - 1) * width + x]))
                        || (y + 1 < height && isTransitable(cells[(y + 1) * width + x]));

                if (hasH && hasV) {
                    throw new GridValidationException("Invalid road '.' representing a crossroads; use '+' at (x=" + x + ", y=" + y + ")");
                }
            }
        }
    }

    private static boolean isTransitable(CellType t) {
        return t == CellType.ROAD || t == CellType.INTERSECTION;
    }
}
