package net.kaleidoscope.cookery.block.entity;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Convert the source renderer's (x,-y,-z) basis and ModelPart pivots to item displays. */
final class TrashCanTransforms {
    record Transform(Vector3f translation, Quaternionf rotation) {}
    private TrashCanTransforms() {}
    static Transform part(int part,TrashCanAnimation.Pose pose,float yaw) {
        Quaternionf local=new Quaternionf(); Vector3f offset=new Vector3f();
        float pivotY=0;
        switch(part) {
            case 0 -> {local.rotateY(radians(-pose.bodyY()));pivotY=-.125f;}
            case 1 -> {
                local.rotateZ(radians(-pose.lidZ())).rotateX(radians(pose.lidX()));
                pivotY=.421875f; offset.y=pose.lidY()/16;
            }
            case 2 -> offset.y=pose.eyeY()/16;
            case 3 -> offset.set(pose.pupilX()/16,(pose.eyeY()+pose.pupilY())/16,0);
            default -> throw new IllegalArgumentException("Unknown trash can part");
        }
        Vector3f pivot=new Vector3f(0,pivotY,0);
        Vector3f translation=new Vector3f(pivot).sub(local.transform(new Vector3f(pivot))).add(offset).rotateY(yaw);
        return new Transform(translation,new Quaternionf().rotateY(yaw).mul(local));
    }
    private static float radians(float degrees){return (float)Math.toRadians(degrees);}
}
