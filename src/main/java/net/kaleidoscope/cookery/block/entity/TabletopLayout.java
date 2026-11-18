package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.TableBehavior;

/** Positions from the pinned TableBlockEntityRender, relative to the block center. */
public final class TabletopLayout {
    public static final float SCALE = .65f;
    private static final float[][][] OFFSETS = {
            {{0, 0, 0}},
            {{-.25f, 0, .1f}, {.25f, .01f, -.1f}},
            {{.25f, 0, -.2f}, {-.25f, .01f, 0}, {.24f, .02f, .2f}},
            {{.25f, 0, -.3f}, {-.24f, .01f, -.1f}, {.24f, .02f, .1f}, {-.25f, .03f, .3f}}
    };
    private TabletopLayout() {}
    public record Pose(float x, float y, float z, float yaw) {}
    public static Pose pose(int count, int index, int line, boolean blockItem) {
        if (count < 1 || count > 4 || index < 0 || index >= count)
            throw new IllegalArgumentException("Invalid tabletop slot");
        float[] offset = OFFSETS[count - 1][index];
        boolean axisX = line == TableBehavior.LINE_X;
        float x = (axisX ? -offset[0] : offset[2]) * SCALE;
        float z = (axisX ? -offset[2] : -offset[0]) * SCALE;
        return new Pose(x, .8125f + (offset[1] - (blockItem ? .25f : 0)) * SCALE, z,
                (float) Math.toRadians(axisX ? 180 : 90));
    }
}
