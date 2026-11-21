package net.kaleidoscope.cookery.nms;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Executes the production dispatch/wear/stack-attribution policy without initializing a server. */
class NativeMiningImplementationTest {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Class<?>[] PARAMETERS = {Stack.class, Level.class, State.class, Position.class, Entity.class};
    public static final class Stack {}
    public static final class Level {}
    public static final class State {}
    public static final class Position {}
    public static final class Entity {}

    public static class StandardItem {
        Runnable duringMining = () -> {};
        public boolean mineBlock(Stack stack, Level level, State state, Position pos, Entity actor) {
            duringMining.run(); return true;
        }
        public boolean mineBlock(int differentSignature) { duringMining.run(); return true; }
    }
    public static class ShearsItem extends StandardItem {
        @Override public boolean mineBlock(Stack stack, Level level, State state, Position pos, Entity actor) {
            duringMining.run(); return true;
        }
    }
    public static final class FishingRodItem extends StandardItem {}
    public static final class InheritedShearsItem extends ShearsItem {}
    public static final class UnknownOverride extends StandardItem {
        @Override public boolean mineBlock(Stack stack, Level level, State state, Position pos, Entity actor) {
            return super.mineBlock(stack, level, state, pos, actor);
        }
    }
    public static final class WrongReturn {
        public int mineBlock(Stack stack, Level level, State state, Position pos, Entity actor) { return 1; }
    }

    private static NativeMiningImplementation inspect(Class<?> type) {
        return NativeMiningImplementation.inspect(type, StandardItem.class, ShearsItem.class, PARAMETERS);
    }
    private static boolean attributed(NativeMiningImplementation implementation) {
        return WALKER.walk(frames -> frames.anyMatch(implementation::matches));
    }

    @ParameterizedTest
    @CsvSource({"false,0,false,0", "false,0.2,false,3", "false,0.2,true,3",
            "true,0,false,3", "true,0.2,false,3", "true,0,true,0", "true,0.2,true,0"})
    void onlyShearsUseTheFireGateInsteadOfTheHardnessGate(boolean shears, float hardness, boolean fire, int expected) {
        var implementation = inspect(shears ? ShearsItem.class : StandardItem.class);
        assertEquals(expected, implementation.baseWear(false, 3, hardness, fire));
        assertEquals(0, implementation.baseWear(true, 3, hardness, fire));
        assertEquals(0, implementation.baseWear(false, 0, hardness, fire));
        assertEquals(0, implementation.baseWear(false, -3, hardness, fire));
    }

    @Test void theRawPositiveToolInputIsRetainedWithoutInventingAnUnbreakingOrEventLimit() {
        assertEquals(Integer.MAX_VALUE, inspect(ShearsItem.class).baseWear(false, Integer.MAX_VALUE, 0, false));
    }

    @Test void inheritedMethodsResolveToTheirRealDeclaringClassAndExactSignature() {
        var ordinary = inspect(FishingRodItem.class); var shears = inspect(InheritedShearsItem.class);
        var item = new FishingRodItem();
        item.duringMining = () -> { assertTrue(attributed(ordinary)); assertFalse(attributed(shears)); };
        item.mineBlock(null, null, null, null, null);
        var cutter = new InheritedShearsItem();
        cutter.duringMining = () -> { assertTrue(attributed(shears)); assertFalse(attributed(ordinary)); };
        cutter.mineBlock(null, null, null, null, null);
        item.duringMining = () -> assertFalse(attributed(ordinary), "A same-name overload is not the captured native method");
        item.mineBlock(0);
        assertFalse(attributed(ordinary)); assertFalse(attributed(shears));
    }

    @Test void unknownOverridesMissingMethodsAndWrongReturnsFailBeforeEnteringNativeMining() {
        assertThrows(IllegalArgumentException.class, () -> inspect(UnknownOverride.class));
        assertThrows(IllegalArgumentException.class, () -> inspect(Object.class));
        assertThrows(IllegalArgumentException.class, () -> inspect(WrongReturn.class));
    }

    @ParameterizedTest
    @CsvSource({"0,normal,1", "0.2,normal,1", "0,unbreaking,0", "0.2,unbreaking,0",
            "0,cancel,0", "0.2,cancel,0", "0,adjusted,7", "0.2,adjusted,7",
            "0,break,1", "0.2,break,1"})
    void realMutableEventsAreAttributedAcrossMultipleShearsMiningCalls(float hardness, String scenario, int expectedWear) {
        var actor = mock(Player.class); var originalMirror = mock(ItemStack.class);
        var implementation = inspect(ShearsItem.class); var shears = new ShearsItem();
        var tracker = new NativeMiningDamageTracker(() -> true);
        var receipt = new AtomicReference<NativeMiningDamageTracker.Decision>();
        for (int plant = 0; plant < 3; plant++) {
            int base = implementation.baseWear(false, 1, hardness, false);
            try (var session = tracker.begin(actor, base,
                    event -> event.getItem() == originalMirror && attributed(implementation),
                    event -> event.getBrokenItem() == originalMirror && attributed(implementation))) {
                assertNotNull(session);
                shears.duringMining = () -> {
                    if (scenario.equals("unbreaking")) return;
                    var event = new PlayerItemDamageEvent(actor, originalMirror, 1, 1);
                    tracker.observe(event);
                    if (scenario.equals("cancel")) { event.setCancelled(true); event.setDamage(-10); }
                    if (scenario.equals("adjusted")) event.setDamage(7);
                    if (scenario.equals("break")) tracker.observe(new PlayerItemBreakEvent(actor, originalMirror));
                };
                shears.mineBlock(null, null, null, null, null);
                receipt.set(session.finish());
            }
            assertTrue(receipt.get().valid(), receipt.get().reason());
            assertEquals(1, receipt.get().baseWear()); assertEquals(expectedWear, receipt.get().resolvedWear());
            assertEquals(scenario.equals("break"), receipt.get().observedBreak());
        }
    }

    @Test void anotherImplementationOrAnotherItemMirrorCannotReuseTheCapturedShearsSession() {
        var actor = mock(Player.class); var mirror = mock(ItemStack.class); var anotherMirror = mock(ItemStack.class);
        var implementation = inspect(ShearsItem.class); var tracker = new NativeMiningDamageTracker(() -> true);
        var ordinary = new StandardItem();
        try (var session = tracker.begin(actor, 1, event -> event.getItem() == mirror && attributed(implementation))) {
            assertNotNull(session);
            ordinary.duringMining = () -> tracker.observe(new PlayerItemDamageEvent(actor, mirror, 1, 1));
            ordinary.mineBlock(null, null, null, null, null);
            assertFalse(session.finish().valid());
        }
        var shears = new ShearsItem();
        try (var session = tracker.begin(actor, 1, event -> event.getItem() == mirror && attributed(implementation))) {
            assertNotNull(session);
            shears.duringMining = () -> tracker.observe(new PlayerItemDamageEvent(actor, anotherMirror, 1, 1));
            shears.mineBlock(null, null, null, null, null);
            assertFalse(session.finish().valid());
        }
    }

    @Test void aRejectedBreakWithNoAttributedToolEventsRemainsUncommittedDespiteExternalListenerChanges() {
        var actor = mock(Player.class); var tracker = new NativeMiningDamageTracker(() -> true);
        try (var session = tracker.begin(actor, 1, event -> false, event -> false)) {
            assertNotNull(session);
            var decision = session.finish(); assertTrue(decision.completeObservation());
            assertEquals(NativeSickleAccess.Status.NOT_COMMITTED,
                    NativeMiningImplementation.rejectedDestruction(decision).status());
        }
        try (var session = tracker.begin(actor, 1, event -> false, event -> false)) {
            assertNotNull(session);
            tracker.observe(new PlayerItemDamageEvent(actor, mock(ItemStack.class), 1, 1));
            var decision = session.finish(); assertFalse(decision.valid()); assertFalse(decision.observedEvent());
            assertTrue(decision.completeObservation());
            assertEquals(NativeSickleAccess.Status.NOT_COMMITTED,
                    NativeMiningImplementation.rejectedDestruction(decision).status(),
                    "Unrelated listener side effects cannot become this operation's native commit");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"unavailable", "damage-attribution", "break-attribution"})
    void aFalseNativeResultCannotHideObserverLossOrFailedToolAttribution(String failure) {
        var actor = mock(Player.class); var mirror = mock(ItemStack.class); var available = new AtomicBoolean(true);
        var tracker = new NativeMiningDamageTracker(available::get);
        try (var session = tracker.begin(actor, 1,
                event -> { throw new IllegalStateException("damage mirror unavailable"); },
                event -> { throw new IllegalStateException("break mirror unavailable"); })) {
            assertNotNull(session);
            if (failure.equals("unavailable")) available.set(false);
            else if (failure.equals("damage-attribution")) tracker.observe(new PlayerItemDamageEvent(actor, mirror, 1, 1));
            else tracker.observe(new PlayerItemBreakEvent(actor, mirror));
            var decision = session.finish();
            assertFalse(decision.valid()); assertFalse(decision.completeObservation());
            assertFalse(decision.observedEvent()); assertFalse(decision.observedBreak());
            assertEquals(NativeSickleAccess.Status.PARTIAL,
                    NativeMiningImplementation.rejectedDestruction(decision).status(),
                    "The false result cannot establish no commit after an observation failure");
        }
    }

    @Test void aFalseNativeResultWithAnAttributedDamageOrOrphanBreakEventStillReportsPartial() {
        var actor = mock(Player.class); var mirror = mock(ItemStack.class);
        var tracker = new NativeMiningDamageTracker(() -> true);
        try (var session = tracker.begin(actor, 1, event -> event.getItem() == mirror, event -> event.getBrokenItem() == mirror)) {
            assertNotNull(session);
            var event = new PlayerItemDamageEvent(actor, mirror, 1, 1); tracker.observe(event);
            event.setCancelled(true);
            assertEquals(NativeSickleAccess.Status.PARTIAL,
                    NativeMiningImplementation.rejectedDestruction(session.finish()).status());
        }
        try (var session = tracker.begin(actor, 1, event -> event.getItem() == mirror, event -> event.getBrokenItem() == mirror)) {
            assertNotNull(session);
            tracker.observe(new PlayerItemBreakEvent(actor, mirror));
            var decision = session.finish(); assertFalse(decision.valid()); assertTrue(decision.observedBreak());
            assertEquals(NativeSickleAccess.Status.PARTIAL,
                    NativeMiningImplementation.rejectedDestruction(decision).status());
        }
    }
}
