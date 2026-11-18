package net.kaleidoscope.cookery.block.entity;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrashCanTransformsTest {
    @Test void lidRotatesAroundSourcePivotAndSourceZRotationChangesSign() {
        var pose=new TrashCanAnimation.Pose(0,25,10,5,0,0,0);
        for(float yaw:new float[]{0,(float)Math.PI/2,(float)Math.PI}) {
            var transform=TrashCanTransforms.part(1,pose,yaw);
            Vector3f pivot=new Vector3f(0,.421875f,0);
            Vector3f result=transform.rotation().transform(pivot).add(transform.translation());
            assertEquals(0,result.x,.00001);assertEquals(.421875+5.0/16,result.y,.00001);assertEquals(0,result.z,.00001);
        }
        var roll=TrashCanTransforms.part(1,new TrashCanAnimation.Pose(0,0,90,0,0,0,0),0);
        Vector3f direction=roll.rotation().transform(new Vector3f(1,0,0));
        assertEquals(-1,direction.y,.00001);
    }
    @Test void pupilInheritsEyeRaiseWhileParentDoesNotBlinkOrSway() {
        var pose=new TrashCanAnimation.Pose(0,0,0,0,4,-1,-2);
        var eye=TrashCanTransforms.part(2,pose,0);
        var pupil=TrashCanTransforms.part(3,pose,0);
        assertEquals(new Vector3f(0,.25f,0),eye.translation());
        assertEquals(new Vector3f(-.0625f,.125f,0),pupil.translation());
        var rotated=TrashCanTransforms.part(3,pose,(float)Math.PI/2);
        assertEquals(0,rotated.translation().x,.00001);assertEquals(.0625f,rotated.translation().z,.00001);
    }
}
