package net.kaleidoscope.cookery.block.entity;

/** The upstream entity owns one last-hit time shared by every attacker. It is not saved. */
public final class ScarecrowHitState {
    public enum Result { WOBBLE, DESTROY }
    private long lastHit;
    private boolean wobbling;

    public Result strike(long gameTime, boolean creative) {
        if (creative) return Result.DESTROY;
        if (gameTime - lastHit > 5) {
            lastHit = gameTime;
            wobbling = true;
            return Result.WOBBLE;
        }
        return Result.DESTROY;
    }

    public float angle(long gameTime) {
        long elapsed = gameTime - lastHit;
        return !wobbling || elapsed < 0 || elapsed >= 5 ? 0 :
                (float) Math.sin(elapsed / 1.5f * (float) Math.PI) * 3;
    }

    public void reset() { lastHit = 0; wobbling = false; }
}
