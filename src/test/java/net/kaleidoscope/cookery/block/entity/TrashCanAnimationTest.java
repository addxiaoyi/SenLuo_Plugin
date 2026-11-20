package net.kaleidoscope.cookery.block.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static net.kaleidoscope.cookery.block.entity.TrashCanAnimation.Clip.*;

class TrashCanAnimationTest {
    @Test void keepsFractionalSourceKeyframeTimesAndCubicOvershoot() {
        var put=TrashCanAnimation.sample(PUT,.125f);
        assertEquals(-12.5f,put.lidX(),.0001);assertEquals(2,put.lidY(),.0001);
        assertEquals(-12.5f,TrashCanAnimation.sample(PUT,.2083f).lidX(),.0001);
        // Equal adjacent values still overshoot under the source Catmull-Rom tangents.
        assertEquals(-14.53125f,TrashCanAnimation.sample(PUT,(.125f+.2083f)/2).lidX(),.0001);
        assertEquals(TrashCanAnimation.Pose.REST,TrashCanAnimation.sample(PUT,5));
        assertEquals(.5833f,TrashCanAnimation.length(PUT),.00001);
    }
    @Test void blinkMovesOnlyChildPupilAndSwayKeepsParentEyeRaised() {
        var blink=TrashCanAnimation.sample(PLAYER1,.5f);
        assertEquals(4,blink.eyeY());assertEquals(-2,blink.pupilY());assertEquals(0,blink.pupilX());
        assertEquals(0,TrashCanAnimation.sample(PLAYER1,.5833f).pupilY(),.0001);
        assertEquals(-1,TrashCanAnimation.sample(PLAYER2,1).pupilX(),.0001);
        assertEquals(1,TrashCanAnimation.sample(PLAYER2,2).pupilX(),.0001);
        assertEquals(4,TrashCanAnimation.sample(PLAYER2,10).eyeY());
    }
    @Test void independentTracksAddAndRestartWhileIdleAlternativesReplace() {
        var state=new TrashCanAnimation();state.start(PUT,0);state.start(ENTER,0);state.start(PLAYER1,0);
        var at=state.pose(2);var put=TrashCanAnimation.sample(PUT,.1f);var enter=TrashCanAnimation.sample(ENTER,.1f);
        assertEquals(put.lidX()-10,at.lidX(),.0001);assertEquals(enter.lidZ(),at.lidZ(),.0001);
        assertEquals(put.lidY()+enter.lidY()+.5f,at.lidY(),.0001);assertTrue(state.moving(2));
        state.start(PLAYER2,4);assertEquals(4,state.pose(4).eyeY());
        state.stopIdle();assertEquals(0,state.pose(4).eyeY());
        assertFalse(state.moving(20));state.clear();assertEquals(TrashCanAnimation.Pose.REST,state.pose(20));
    }
}
