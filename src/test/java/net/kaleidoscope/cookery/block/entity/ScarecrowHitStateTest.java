package net.kaleidoscope.cookery.block.entity;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScarecrowHitStateTest {
    @Test void fiveTickWindowIsInclusiveAndExpiredHitStartsAnotherWobble() {
        var state=new ScarecrowHitState();
        assertEquals(ScarecrowHitState.Result.WOBBLE,state.strike(100,false));
        assertEquals(ScarecrowHitState.Result.DESTROY,state.strike(105,false));
        assertEquals(ScarecrowHitState.Result.WOBBLE,state.strike(106,false));
        assertEquals(ScarecrowHitState.Result.DESTROY,state.strike(106,false));
        assertEquals(ScarecrowHitState.Result.WOBBLE,state.strike(112,false));
    }
    @Test void creativeImmediatelyDestroysAndResetUsesSourceInitialZeroTime() {
        var state=new ScarecrowHitState();assertEquals(ScarecrowHitState.Result.DESTROY,state.strike(100,true));
        assertEquals(ScarecrowHitState.Result.WOBBLE,state.strike(100,false));state.reset();
        assertEquals(0,state.angle(100));assertEquals(ScarecrowHitState.Result.DESTROY,state.strike(5,false));
        assertEquals(ScarecrowHitState.Result.WOBBLE,state.strike(6,false));
    }
    @Test void sourceWobbleStopsAtFiveTicksAndRotatesEveryPartAroundFeet() {
        var state=new ScarecrowHitState();state.strike(100,false);
        assertEquals(0,state.angle(100));assertEquals(2.598076f,state.angle(101),.00001);
        assertEquals(-2.598076f,state.angle(102),.00001);assertEquals(0,state.angle(105));
        Vector3f offset=new Vector3f(.75f,1.6f,0),shift=new Vector3f(.2f,.1f,.3f);
        Quaternionf rotation=new Quaternionf().rotateZ(.2182f);
        var pose=ScarecrowWobble.transform(offset,shift,rotation,90);
        Vector3f finalCenter=new Vector3f(offset).add(pose.translation());
        assertEquals(.3f,finalCenter.x,.00001);assertEquals(1.7f,finalCenter.y,.00001);assertEquals(-.95f,finalCenter.z,.00001);
        assertEquals(new Vector3f(.75f,1.6f,0),offset);assertEquals(new Vector3f(.2f,.1f,.3f),shift);
        var rest=ScarecrowWobble.transform(offset,shift,rotation,0);
        assertEquals(0,rest.translation().distance(shift),.000001);assertEquals(rotation,rest.rotation());
    }
}
