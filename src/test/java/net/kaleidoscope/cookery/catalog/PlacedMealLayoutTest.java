package net.kaleidoscope.cookery.catalog;

import net.kaleidoscope.cookery.catalog.PlacedMealLayout.Cell;
import net.kaleidoscope.cookery.catalog.PlacedMealLayout.Facing;
import net.kaleidoscope.cookery.catalog.PlacedMealLayout.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlacedMealLayoutTest {
    private static final Pos ANCHOR = new Pos(14, 75, -16);

    @Test void singleCellIsItsOwnAnchorInEveryDirection() {
        var layout = PlacedMealLayout.of("1x1");
        assertEquals("single", layout.anchorPart());
        for (Facing facing : Facing.values()) {
            assertEquals(List.of(new Cell("single", ANCHOR)), layout.cells(ANCHOR, facing));
            assertEquals(ANCHOR, layout.anchor(ANCHOR, facing, "single"));
        }
    }

    @Test void doubleMealPlacesLeftClockwiseFromTheClickedRightCell() {
        var layout = PlacedMealLayout.DOUBLE;
        Map<Facing, Pos> left = Map.of(Facing.NORTH, new Pos(15, 75, -16),
                Facing.EAST, new Pos(14, 75, -15), Facing.SOUTH, new Pos(13, 75, -16),
                Facing.WEST, new Pos(14, 75, -17));
        assertEquals("right", layout.anchorPart());
        for (Facing facing : Facing.values()) {
            assertEquals(List.of(new Cell("right", ANCHOR), new Cell("left", left.get(facing))),
                    layout.cells(ANCHOR, facing));
            assertEquals(ANCHOR, layout.anchor(left.get(facing), facing, "left"));
            assertEquals(ANCHOR, layout.anchor(ANCHOR, facing, "right"));
        }
    }

    @Test void ninePartsUseAbsoluteOffsetsWithoutRotatingWithFacing() {
        var layout = PlacedMealLayout.NINE;
        List<Cell> expected = List.of(
                new Cell("left_up", new Pos(13, 75, -17)), new Cell("up", new Pos(14, 75, -17)),
                new Cell("right_up", new Pos(15, 75, -17)), new Cell("left_center", new Pos(13, 75, -16)),
                new Cell("center", ANCHOR), new Cell("right_center", new Pos(15, 75, -16)),
                new Cell("left_down", new Pos(13, 75, -15)), new Cell("down", new Pos(14, 75, -15)),
                new Cell("right_down", new Pos(15, 75, -15)));
        assertEquals("center", layout.anchorPart());
        for (Facing facing : Facing.values()) {
            assertEquals(expected, layout.cells(ANCHOR, facing));
            for (Cell cell : expected) assertEquals(ANCHOR, layout.anchor(cell.pos(), facing, cell.part()));
        }
    }

    @Test void ownershipRequiresTheCorrectCellPositionPartAndHeight() {
        for (PlacedMealLayout layout : PlacedMealLayout.values()) {
            for (Facing facing : Facing.values()) {
                List<Cell> cells = layout.cells(ANCHOR, facing);
                assertEquals(cells.size(), cells.stream().map(Cell::pos).distinct().count());
                for (Cell cell : cells) {
                    assertTrue(layout.owns(ANCHOR, facing, cell));
                    assertFalse(layout.owns(ANCHOR, facing, new Cell("foreign", cell.pos())));
                    assertFalse(layout.owns(ANCHOR, facing,
                            new Cell(cell.part(), new Pos(cell.pos().x(), 76, cell.pos().z()))));
                }
                assertFalse(layout.owns(ANCHOR, facing, null));
                assertThrows(UnsupportedOperationException.class, () -> cells.add(new Cell("extra", ANCHOR)));
            }
        }
    }

    @Test void invalidPartsDirectionsAndCoordinateOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlacedMealLayout.of("2x2"));
        assertThrows(IllegalArgumentException.class, () -> PlacedMealLayout.of(null));
        assertThrows(IllegalArgumentException.class, () -> Facing.of("up"));
        assertThrows(IllegalArgumentException.class, () -> Facing.of(null));
        assertThrows(IllegalArgumentException.class, () -> PlacedMealLayout.DOUBLE.anchor(ANCHOR, Facing.NORTH, "center"));
        assertThrows(IllegalArgumentException.class, () -> PlacedMealLayout.NINE.anchor(ANCHOR, Facing.NORTH, "left"));
        assertThrows(ArithmeticException.class, () -> PlacedMealLayout.DOUBLE.cells(new Pos(Integer.MAX_VALUE, 0, 0), Facing.NORTH));
        assertThrows(ArithmeticException.class, () -> PlacedMealLayout.NINE.cells(new Pos(Integer.MIN_VALUE, 0, 0), Facing.SOUTH));
        assertEquals(Facing.EAST, Facing.of("north").clockwise());
        assertEquals(Facing.NORTH, Facing.WEST.clockwise());
    }
}
