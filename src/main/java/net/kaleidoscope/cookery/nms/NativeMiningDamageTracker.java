package net.kaleidoscope.cookery.nms;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Keeps real native damage-event references until the complete synchronous mining call returns. */
public final class NativeMiningDamageTracker {
    public record Decision(boolean valid, int baseWear, int originalEventWear, int finalEventWear,
                           int resolvedWear, boolean observedEvent, boolean observedBreak, String reason) {}
    private final BooleanSupplier available;
    private final ThreadLocal<Session> current = new ThreadLocal<>();

    public NativeMiningDamageTracker(BooleanSupplier available) { this.available = available; }

    public Session begin(Player actor, int baseWear, Predicate<PlayerItemDamageEvent> belongsToNativeMining) {
        return begin(actor, baseWear, belongsToNativeMining, ignored -> false);
    }

    public Session begin(Player actor, int baseWear, Predicate<PlayerItemDamageEvent> belongsToNativeMining,
                         Predicate<PlayerItemBreakEvent> belongsToNativeBreak) {
        if (!available.getAsBoolean() || current.get() != null) return null;
        if (baseWear < 0) throw new IllegalArgumentException("Negative native tool wear");
        Session session = new Session(actor, baseWear, belongsToNativeMining, belongsToNativeBreak);
        current.set(session); return session;
    }

    public void observe(PlayerItemDamageEvent event) {
        Session session = current.get();
        if (session == null || event.getPlayer() != session.actor) return;
        try {
            if (!session.belongs.test(event)) { session.foreignEvent = true; return; }
            session.count = Math.min(2, session.count + 1);
            if (session.event == null) session.event = event;
        } catch (RuntimeException failure) { session.observationFailure = failure; }
    }

    public void observe(PlayerItemBreakEvent event) {
        Session session = current.get();
        if (session == null || event.getPlayer() != session.actor) return;
        try {
            if (!session.belongsBreak.test(event)) { session.foreignEvent = true; return; }
            session.breakCount = Math.min(2, session.breakCount + 1);
            if (session.breakEvent == null) session.breakEvent = event;
        } catch (RuntimeException failure) { session.observationFailure = failure; }
    }

    public final class Session implements AutoCloseable {
        private final Player actor;
        private final int baseWear;
        private final Predicate<PlayerItemDamageEvent> belongs;
        private final Predicate<PlayerItemBreakEvent> belongsBreak;
        private PlayerItemDamageEvent event;
        private int count;
        private PlayerItemBreakEvent breakEvent;
        private int breakCount;
        private RuntimeException observationFailure;
        private boolean foreignEvent;
        private boolean closed;
        private Session(Player actor, int baseWear, Predicate<PlayerItemDamageEvent> belongs,
                        Predicate<PlayerItemBreakEvent> belongsBreak) {
            this.actor = actor; this.baseWear = baseWear; this.belongs = belongs;
            this.belongsBreak = belongsBreak;
        }
        public Decision finish() {
            if (closed || current.get() != this) throw new IllegalStateException("Native damage session is not current on this thread");
            if (!available.getAsBoolean()) return invalid("native damage observer became unavailable");
            if (observationFailure != null) return invalid("native damage event attribution failed: " + observationFailure);
            if (foreignEvent) return invalid("an unrelated damage event occurred during the native mining transaction");
            if (count > 1 || breakCount > 1)
                return invalid("multiple/reentrant native damage events cannot certify one mining transition");
            if (event == null) {
                if (breakEvent != null) return invalid("a break event occurred without its native player damage event");
                return new Decision(true, baseWear, 0, 0, 0, false, false, "native durability processing produced no damage event");
            }
            if (event.getOriginalDamage() != baseWear)
                return invalid("native damage event base differs from the captured TOOL component");
            int finalDamage = event.isCancelled() ? 0 : event.getDamage();
            if (breakEvent != null && finalDamage <= 0) return invalid("a break event occurred without positive resolved damage");
            // Native applyDamage also accepts a negative event adjustment, clamped by setDamageValue.
            return new Decision(true, baseWear, event.getOriginalDamage(), finalDamage, finalDamage, true, breakEvent != null,
                    event.isCancelled() ? "native damage event canceled" : "final PlayerItemDamageEvent adjustment");
        }
        private Decision invalid(String reason) { return new Decision(false, baseWear, 0, 0, 0, event != null, breakEvent != null, reason); }
        @Override public void close() {
            if (closed) return;
            if (current.get() != this) throw new IllegalStateException("Native damage session closed on another thread or out of order");
            closed = true; current.remove();
        }
    }
}
