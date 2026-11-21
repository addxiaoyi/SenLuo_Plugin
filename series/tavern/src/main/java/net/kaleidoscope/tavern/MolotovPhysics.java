package net.kaleidoscope.tavern;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Numeric rules from Tavern c4ec188; the source loops a 7x7 square, not an 11x11 circle. */
final class MolotovPhysics {
    static final String ITEM = "kaleidoscope_tavern:molotov";
    static final int MIN_CHARGE = 10;
    static final int USE_TICKS = 72000;
    private MolotovPhysics() {}

    static boolean charged(int ticks) { return ticks >= MIN_CHARGE; }

    static Vector thrown(float pitch, float yaw, Vector movement, boolean onGround, RandomGenerator random) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        Vector direction = new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)).normalize();
        // Projectile.shoot uses triangle(0, 0.0172275 * inaccuracy), then scales without re-normalizing.
        direction.add(new Vector(triangle(random), triangle(random), triangle(random))).multiply(0.8);
        return direction.add(new Vector(movement.getX(), onGround ? 0 : movement.getY(), movement.getZ()));
    }

    private static double triangle(RandomGenerator random) { return 0.0172275 * (random.nextDouble() - random.nextDouble()); }
    static float throwPitch(float random) { return 0.4f / (random * 0.4f + 0.8f); }

    static List<Column> fireColumns(RandomGenerator random) {
        List<Column> result = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            int squared = dx * dx + dz * dz;
            if (squared > 9) {
                double overshoot = Math.sqrt(squared) - 3;
                if (overshoot > 2 || random.nextFloat() >= (1.0f - (float) overshoot / 2.0f) * 0.6f) continue;
            }
            result.add(new Column(dx, dz));
        }
        return List.copyOf(result);
    }

    static BlockFace facing(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90), 4)) {
            case 1 -> BlockFace.EAST;
            case 2 -> BlockFace.SOUTH;
            case 3 -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    enum Storage {
        GROUND(0), HOLDER(1), TILTED(3), CIRCULAR(6), CELLAR(9);
        final int slots;
        Storage(int slots) { this.slots = slots; }
        static Storage of(String id) {
            return switch (id) {
                case ITEM -> GROUND;
                case "kaleidoscope_tavern:holder" -> HOLDER;
                case "kaleidoscope_tavern:tilted_rack" -> TILTED;
                case "kaleidoscope_tavern:circular_rack" -> CIRCULAR;
                case "kaleidoscope_tavern:cellar_cabinet" -> CELLAR;
                default -> throw new IllegalArgumentException("Not a Molotov storage: " + id);
            };
        }
    }

    static int clickedSlot(Storage storage, BlockFace facing, BlockFace face, double x, double y, double z) {
        double localX = switch (facing) {
            case NORTH -> 1 - x;
            case EAST -> 1 - z;
            case WEST -> z;
            default -> x;
        };
        double localZ = switch (facing) {
            case NORTH -> z;
            case EAST -> 1 - x;
            case WEST -> x;
            default -> 1 - z;
        };
        return switch (storage) {
            case GROUND, HOLDER -> 0;
            case TILTED -> localX < 1.0 / 3 ? 0 : localX < 2.0 / 3 ? 1 : 2;
            case CIRCULAR -> {
                double angle = (Math.toDegrees(Math.atan2(localZ - 0.5, localX - 0.5)) + 360) % 360;
                yield angle > 300 ? 5 : angle > 240 ? 0 : angle > 180 ? 1 : angle > 120 ? 2 : angle > 60 ? 3 : 4;
            }
            case CELLAR -> face != facing ? -1 : ((int) (localX * 3) % 3) + (2 - (int) (y * 3) % 3) * 3;
        };
    }

    static Location shootPosition(Storage storage, Location block, BlockFace facing) {
        if (storage == Storage.CIRCULAR || storage == Storage.GROUND) return block.clone().add(0.5, 0.5, 0.5);
        BlockFace direction = storage == Storage.TILTED ? facing.getOppositeFace() : facing;
        return block.clone().add(0.5 + direction.getModX() * 0.5, storage == Storage.CELLAR ? 0.5 : 0.875,
                0.5 + direction.getModZ() * 0.5);
    }

    static Vector rackVelocity(Storage storage, BlockFace facing, double random) {
        if (storage == Storage.GROUND) return new Vector();
        if (storage == Storage.CIRCULAR) return new Vector(0, random * 2 + 0.5, 0);
        BlockFace direction = storage == Storage.TILTED ? facing.getOppositeFace() : facing;
        double vertical = storage == Storage.TILTED ? 0.75 : storage == Storage.CELLAR ? 0.1 : 0.375;
        double factor = storage == Storage.CELLAR ? random * 2 + 0.5 : random + 0.5;
        return new Vector(direction.getModX(), vertical, direction.getModZ()).multiply(factor);
    }

    static BoundingBox bottleBox(Location block) {
        return new BoundingBox(block.getBlockX() + 5.0 / 16, block.getBlockY(), block.getBlockZ() + 5.0 / 16,
                block.getBlockX() + 11.0 / 16, block.getBlockY() + 14.0 / 16, block.getBlockZ() + 11.0 / 16);
    }

    record Column(int x, int z) {}
}
