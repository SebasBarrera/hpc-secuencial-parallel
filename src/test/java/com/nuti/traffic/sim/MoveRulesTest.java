package com.nuti.traffic.sim;

import com.nuti.traffic.model.CellType;
import com.nuti.traffic.model.Direction;
import com.nuti.traffic.model.Grid;
import com.nuti.traffic.model.TrafficLight;
import com.nuti.traffic.model.TrafficLightState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MoveRulesTest {

    @Test
    void proposal_blockedByRedLight_whenEnteringIntersection() {
        Grid grid = simple3x3Intersection();

        // Make the center intersection vertical green (horizontal red)
        TrafficLight[] lights = new TrafficLight[]{new TrafficLight(10, TrafficLightState.V_GREEN)};

        VehicleState vs = new VehicleState(1);
        // vehicle at (0,1) heading EAST -> target is (1,1) which is '+'
        int start = grid.idx(0, 1);
        vs.set(0, start, Direction.EAST.index());

        int[] occ = new Occupancy(grid.cellCount()).array();
        Occupancy.set(occ, start, Direction.EAST.index(), 0);

        SimulationConfig cfg = new SimulationConfig(
                null,
                1,
                1,
                42L,
                0.0,
                10,
                RunMode.SEQUENTIAL,
                1,
                null
        );

        int[] propCell = new int[1];
        int[] propDir = new int[1];
        boolean[] canMove = new boolean[1];

        MoveRules.computeProposalForVehicle(grid, lights, vs, occ, cfg, 1, 0, propCell, propDir, canMove);

        assertFalse(canMove[0]);
    }

    @Test
    void proposal_allowsEnteringIntersection_whenGreenForAxis() {
        Grid grid = simple3x3Intersection();
        TrafficLight[] lights = new TrafficLight[]{new TrafficLight(10, TrafficLightState.H_GREEN)};

        VehicleState vs = new VehicleState(1);
        int start = grid.idx(0, 1);
        vs.set(0, start, Direction.EAST.index());

        int[] occ = new Occupancy(grid.cellCount()).array();
        Occupancy.set(occ, start, Direction.EAST.index(), 0);

        SimulationConfig cfg = new SimulationConfig(
                null,
                1,
                1,
                42L,
                0.0,
                10,
                RunMode.SEQUENTIAL,
                1,
                null
        );

        int[] propCell = new int[1];
        int[] propDir = new int[1];
        boolean[] canMove = new boolean[1];

        MoveRules.computeProposalForVehicle(grid, lights, vs, occ, cfg, 1, 0, propCell, propDir, canMove);

        assertTrue(canMove[0]);
        assertEquals(grid.idx(1, 1), propCell[0]);
        assertEquals(Direction.EAST.index(), propDir[0]);
    }

    @Test
    void proposal_blockedByOccupancy_inTargetSlot() {
        Grid grid = simple3x3Intersection();
        TrafficLight[] lights = new TrafficLight[]{new TrafficLight(10, TrafficLightState.H_GREEN)};

        VehicleState vs = new VehicleState(2);
        int start0 = grid.idx(0, 1);
        int start1 = grid.idx(2, 1);

        // vehicle 0 wants to go EAST into center
        vs.set(0, start0, Direction.EAST.index());
        // vehicle 1 already occupies center with EAST direction
        vs.set(1, grid.idx(1, 1), Direction.EAST.index());

        int[] occ = new Occupancy(grid.cellCount()).array();
        Occupancy.set(occ, start0, Direction.EAST.index(), 0);
        Occupancy.set(occ, start1, Direction.WEST.index(), 1); // irrelevant placement
        Occupancy.set(occ, grid.idx(1, 1), Direction.EAST.index(), 1);

        SimulationConfig cfg = new SimulationConfig(
                null,
                2,
                1,
                42L,
                0.0,
                10,
                RunMode.SEQUENTIAL,
                1,
                null
        );

        int[] propCell = new int[2];
        int[] propDir = new int[2];
        boolean[] canMove = new boolean[2];
        Arrays.fill(canMove, true);

        MoveRules.computeProposalForVehicle(grid, lights, vs, occ, cfg, 1, 0, propCell, propDir, canMove);

        assertFalse(canMove[0]);
    }

    @Test
    void proposal_blockedByAxisMix_inTargetCell() {
        Grid grid = simple3x3Intersection();
        TrafficLight[] lights = new TrafficLight[]{new TrafficLight(10, TrafficLightState.H_GREEN)};

        VehicleState vs = new VehicleState(2);
        int start0 = grid.idx(0, 1);

        // vehicle 0 wants to go EAST into center (horizontal)
        vs.set(0, start0, Direction.EAST.index());
        // vehicle 1 occupies center with NORTH direction (vertical)
        vs.set(1, grid.idx(1, 1), Direction.NORTH.index());

        int[] occ = new Occupancy(grid.cellCount()).array();
        Occupancy.set(occ, start0, Direction.EAST.index(), 0);
        Occupancy.set(occ, grid.idx(1, 1), Direction.NORTH.index(), 1);

        SimulationConfig cfg = new SimulationConfig(
                null,
                2,
                1,
                42L,
                0.0,
                10,
                RunMode.SEQUENTIAL,
                1,
                null
        );

        int[] propCell = new int[2];
        int[] propDir = new int[2];
        boolean[] canMove = new boolean[2];

        MoveRules.computeProposalForVehicle(grid, lights, vs, occ, cfg, 1, 0, propCell, propDir, canMove);

        assertFalse(canMove[0]);
    }

    private static Grid simple3x3Intersection() {
        int w = 3;
        int h = 3;
        //
        // #.#
        // .+.
        // #.#
        //
        // border must be '#', so we can only use a 5x5 in loader; for rules tests we can build Grid directly.
        CellType[] cells = new CellType[w * h];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = CellType.BLOCK;
        }

        cells[0 * w + 1] = CellType.ROAD;
        cells[1 * w + 0] = CellType.ROAD;
        cells[1 * w + 1] = CellType.INTERSECTION;
        cells[1 * w + 2] = CellType.ROAD;
        cells[2 * w + 1] = CellType.ROAD;

        int[] intersectionIndexByCell = new int[w * h];
        Arrays.fill(intersectionIndexByCell, -1);
        intersectionIndexByCell[1 * w + 1] = 0;

        int[] intersectionCellIdx = new int[]{1 * w + 1};

        return new Grid(w, h, cells, intersectionIndexByCell, intersectionCellIdx);
    }
}
