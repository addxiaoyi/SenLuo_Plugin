package net.kaleidoscope.cookery.block.entity;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Rotate every display around the source entity's feet without moving its hitboxes. */
public final class ScarecrowWobble {
    public record Transform(Vector3f translation, Quaternionf rotation) {}
    private ScarecrowWobble() {}

    public static Transform transform(Vector3f spawnOffset, Vector3f translation, Quaternionf rotation, float degrees) {
        Quaternionf turn = new Quaternionf().rotateY((float) Math.toRadians(degrees));
        Vector3f moved = turn.transform(new Vector3f(spawnOffset).add(translation)).sub(spawnOffset);
        return new Transform(moved, new Quaternionf(turn).mul(rotation));
    }
}
