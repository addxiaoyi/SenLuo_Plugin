package net.kaleidoscope.cookery.catalog;

import java.util.List;
import java.util.Objects;

/** Block positions from the source layouts; NinePart coordinates are absolute X/Z offsets. */
public enum PlacedMealLayout {
    SINGLE("1x1", List.of(new Offset("single", 0, 0))),
    DOUBLE("1x2", List.of(new Offset("right", 0, 0), new Offset("left", 1, 0))),
    NINE("3x3", List.of(
            new Offset("left_up", -1, -1), new Offset("up", 0, -1), new Offset("right_up", 1, -1),
            new Offset("left_center", -1, 0), new Offset("center", 0, 0), new Offset("right_center", 1, 0),
            new Offset("left_down", -1, 1), new Offset("down", 0, 1), new Offset("right_down", 1, 1)));

    private final String id;
    private final List<Offset> offsets;

    PlacedMealLayout(String id, List<Offset> offsets) {
        this.id = id;
        this.offsets = List.copyOf(offsets);
    }

    public String id() { return id; }

    public String anchorPart() {
        return switch (this) { case SINGLE -> "single"; case DOUBLE -> "right"; case NINE -> "center"; };
    }

    public static PlacedMealLayout of(String id) {
        for (PlacedMealLayout layout : values()) if (layout.id.equals(id)) return layout;
        throw new IllegalArgumentException("Unknown placed meal layout: " + id);
    }

    public List<Cell> cells(Pos anchor, Facing facing) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(facing, "facing");
        return offsets.stream().map(offset -> {
            Offset placed = orient(offset, facing);
            return new Cell(offset.part(), anchor.offset(placed.x(), placed.z()));
        }).toList();
    }

    public Pos anchor(Pos clicked, Facing facing, String part) {
        Objects.requireNonNull(clicked, "clicked");
        Objects.requireNonNull(facing, "facing");
        Offset offset = offsets.stream().filter(candidate -> candidate.part().equals(part)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid " + id + " meal part: " + part));
        Offset placed = orient(offset, facing);
        return clicked.offset(-placed.x(), -placed.z());
    }

    /** The world layer must additionally compare item ID, facing and saved anchor ownership. */
    public boolean owns(Pos anchor, Facing facing, Cell cell) {
        return cell != null && cells(anchor, facing).contains(cell);
    }

    private Offset orient(Offset offset, Facing facing) {
        if (this != DOUBLE || offset.part().equals("right")) return offset;
        Facing left = facing.clockwise();
        return new Offset(offset.part(), left.x, left.z);
    }

    private record Offset(String part, int x, int z) {}

    public record Cell(String part, Pos pos) {
        public Cell {
            if (part == null || part.isBlank()) throw new IllegalArgumentException("Meal cell part is missing");
            Objects.requireNonNull(pos, "pos");
        }
    }

    public record Pos(int x, int y, int z) {
        public Pos offset(int dx, int dz) {
            return new Pos(Math.addExact(x, dx), y, Math.addExact(z, dz));
        }
    }

    public enum Facing {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        private final int x;
        private final int z;

        Facing(int x, int z) { this.x = x; this.z = z; }

        public Facing clockwise() { return values()[(ordinal() + 1) % values().length]; }

        public static Facing of(String name) {
            if (name == null) throw new IllegalArgumentException("Placed meal facing is missing");
            return switch (name) {
                case "north" -> NORTH;
                case "east" -> EAST;
                case "south" -> SOUTH;
                case "west" -> WEST;
                default -> throw new IllegalArgumentException("Invalid placed meal facing: " + name);
            };
        }
    }
}
