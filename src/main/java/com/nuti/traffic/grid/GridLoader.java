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

        validateBordersAreBlocks(width, height, cells);
        validateIntersectionsStrict4Way(width, height, cells);
        validateRoadSegmentsAreStraight(width, height, cells);

        int[] intersectionCellIdx = new int[intersections.size()];
        for (int i = 0; i < intersections.size(); i++) {
            intersectionCellIdx[i] = intersections.get(i);
        }

        return new Grid(width, height, cells, intersectionIndexByCell, intersectionCellIdx);
    }

    private static void validateBordersAreBlocks(int width, int height, CellType[] cells) {
        for (int x = 0; x < width; x++) {
            if (cells[x] != CellType.BLOCK) {
                throw new GridValidationException("Border cell must be '#' at (x=" + x + ", y=0)");
            }
            if (cells[(height - 1) * width + x] != CellType.BLOCK) {
                throw new GridValidationException("Border cell must be '#' at (x=" + x + ", y=" + (height - 1) + ")");
            }
        }
        for (int y = 0; y < height; y++) {
            if (cells[y * width] != CellType.BLOCK) {
                throw new GridValidationException("Border cell must be '#' at (x=0, y=" + y + ")");
            }
            if (cells[y * width + (width - 1)] != CellType.BLOCK) {
                throw new GridValidationException("Border cell must be '#' at (x=" + (width - 1) + ", y=" + y + ")");
            }
        }
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

                boolean left = isTransitable(cells[y * width + (x - 1)]);
                boolean right = isTransitable(cells[y * width + (x + 1)]);
                boolean up = isTransitable(cells[(y - 1) * width + x]);
                boolean down = isTransitable(cells[(y + 1) * width + x]);

                boolean hasH = left || right;
                boolean hasV = up || down;
                if (!hasH || !hasV) {
                    throw new GridValidationException("Invalid intersection '+' (requires perpendicular street connectivity) at (x=" + x + ", y=" + y + ")");
                }
            }
        }
    }

    private static void validateRoadSegmentsAreStraight(int width, int height, CellType[] cells) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (cells[idx] != CellType.ROAD) {
                    continue;
                }

                boolean left = x - 1 >= 0 && isTransitable(cells[y * width + (x - 1)]);
                boolean right = x + 1 < width && isTransitable(cells[y * width + (x + 1)]);
                boolean up = y - 1 >= 0 && isTransitable(cells[(y - 1) * width + x]);
                boolean down = y + 1 < height && isTransitable(cells[(y + 1) * width + x]);

                int degree = (left ? 1 : 0) + (right ? 1 : 0) + (up ? 1 : 0) + (down ? 1 : 0);
                if (degree != 2) {
                    throw new GridValidationException("Invalid road '.' connectivity (expected degree=2) at (x=" + x + ", y=" + y + ")");
                }

                boolean hasH = left || right;
                boolean hasV = up || down;
                if (hasH && hasV) {
                    throw new GridValidationException("Invalid road '.' representing a turn/crossroads; use '+' at (x=" + x + ", y=" + y + ")");
                }
            }
        }
    }

    private static boolean isTransitable(CellType t) {
        return t == CellType.ROAD || t == CellType.INTERSECTION;
    }
}
