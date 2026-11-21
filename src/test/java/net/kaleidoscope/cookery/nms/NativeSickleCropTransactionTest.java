package net.kaleidoscope.cookery.nms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeSickleCropTransactionTest {
    private static final class Fixture implements NativeSickleCropTransaction.Operations<byte[]> {
        final List<String> order = new ArrayList<>();
        final List<byte[]> published = new ArrayList<>();
        final List<byte[]> computed = new ArrayList<>(List.of(
                "wheat|count=1|custom_data={kept:1b}|components=opaque".getBytes(StandardCharsets.UTF_8),
                "seeds|count=3|custom_data={kept:2b}|components=opaque".getBytes(StandardCharsets.UTF_8)));
        boolean original = true, source = true, resetSucceeds = true;
        String world = "mature";
        String failAt;
        Runnable afterStatistics = () -> {}, afterLoot = () -> {}, afterPublish = () -> {};
        final IllegalArgumentException failure = new IllegalArgumentException("native callback failure");
        void stage(String stage) { order.add(stage); if (stage.equals(failAt)) throw failure; }
        @Override public boolean originalBlockPresent() { return original; }
        @Override public void statisticsAndExhaustion() {
            stage("statistics/exhaustion"); assertEquals("mature", world); afterStatistics.run();
        }
        @Override public List<byte[]> calculateLootInOriginalWorld() {
            stage("loot"); assertEquals("mature", world, "Loot must not observe the reset world");
            assertEquals("statistics/exhaustion", order.getFirst()); afterLoot.run(); return computed;
        }
        @Override public boolean commitReset() {
            stage("reset"); if (resetSucceeds) { world = "reset"; original = false; } return resetSucceeds;
        }
        @Override public void publish(byte[] drop) {
            stage("publish"); assertEquals("reset", world); published.add(drop); afterPublish.run();
        }
        @Override public void afterBreak() { stage("after-break"); }
        NativeSickleAccess.Outcome run() { return NativeSickleCropTransaction.commit(this, () -> source); }
    }

    @Test void nativeStatisticsPrecedeLootWhileLootStillSeesTheOriginalWorldAndAllOutputSurvives() {
        var f = new Fixture();
        byte[] first = f.computed.get(0).clone(), second = f.computed.get(1).clone();
        var outcome = f.run();
        assertEquals(NativeSickleAccess.Status.COMMITTED, outcome.status());
        assertNull(outcome.failure());
        assertEquals(List.of("statistics/exhaustion", "loot", "reset", "publish", "publish", "after-break"), f.order);
        assertEquals(2, f.published.size());
        assertArrayEquals(first, f.published.get(0)); assertArrayEquals(second, f.published.get(1));
    }

    @Test void precommitBlockOrSourceDriftHasNoIrreversibleEffects() {
        for (boolean block : List.of(true, false)) {
            var f = new Fixture(); f.original = block; f.source = !block;
            var outcome = f.run();
            assertEquals(NativeSickleAccess.Status.NOT_COMMITTED, outcome.status());
            assertFalse(outcome.committed()); assertTrue(f.order.isEmpty()); assertTrue(f.published.isEmpty());
        }
    }

    @Test void invalidPrecommitArgumentsAreNotSwallowedIntoASuccessOrPartialCommit() {
        var f = new Fixture();
        var failure = new IllegalArgumentException("invalid source");
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> NativeSickleCropTransaction.commit(f, () -> { throw failure; }));
        assertSame(failure, thrown); assertTrue(f.order.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"block", "source"})
    void statisticsCallbackDriftIsTruthfullyPartialAndNeverResetsOrPublishesStaleLoot(String field) {
        var f = new Fixture();
        f.afterStatistics = () -> { if (field.equals("block")) f.original = false; else f.source = false; };
        var outcome = f.run();
        assertEquals(NativeSickleAccess.Status.PARTIAL, outcome.status()); assertTrue(outcome.committed());
        assertEquals(List.of("statistics/exhaustion"), f.order); assertTrue(f.published.isEmpty());
        assertTrue(outcome.detail().contains(field.equals("block") ? "block changed" : "source changed"));
    }

    @ParameterizedTest @ValueSource(strings = {"block", "source"})
    void lootCallbackDriftCannotOverwriteAReplacementOrPublishTheOldSource(String field) {
        var f = new Fixture();
        f.afterLoot = () -> { if (field.equals("block")) f.original = false; else f.source = false; };
        var outcome = f.run();
        assertEquals(NativeSickleAccess.Status.PARTIAL, outcome.status());
        assertEquals(List.of("statistics/exhaustion", "loot"), f.order); assertTrue(f.published.isEmpty());
    }

    @Test void invalidatedResetDoesNotPublishOldMatureOutputButDoesNotPretendStatisticsDidNotHappen() {
        var f = new Fixture(); f.resetSucceeds = false;
        var outcome = f.run();
        assertEquals(NativeSickleAccess.Status.PARTIAL, outcome.status()); assertTrue(outcome.committed());
        assertEquals(List.of("statistics/exhaustion", "loot", "reset"), f.order); assertTrue(f.published.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"statistics/exhaustion", "loot", "reset", "publish", "after-break"})
    void nativeRuntimeFailuresCarryTheirOriginalCauseAndNeverBecomeACompleteResult(String stage) {
        var f = new Fixture(); f.failAt = stage;
        var outcome = f.run();
        assertEquals(NativeSickleAccess.Status.PARTIAL, outcome.status()); assertTrue(outcome.committed());
        assertSame(f.failure, outcome.failure()); assertNotEquals(NativeSickleAccess.Status.COMMITTED, outcome.status());
    }

    @Test void postcommitItemSpawnSourceChangesDoNotDropTheRemainingFrozenOutput() {
        var f = new Fixture();
        f.afterPublish = () -> { f.source = false; f.computed.clear(); };
        var outcome = f.run();
        assertEquals(NativeSickleAccess.Status.COMMITTED, outcome.status());
        assertEquals(2, f.published.size(), "Publication must use its complete frozen list");
        assertEquals("after-break", f.order.getLast());
    }

    @Test void aNullNativeDropIsAnExplicitPartialFailureRatherThanSilentTruncation() {
        var f = new Fixture(); f.computed.add(null);
        var outcome = f.run();
        assertEquals(NativeSickleAccess.Status.PARTIAL, outcome.status()); assertInstanceOf(NullPointerException.class, outcome.failure());
        assertEquals(List.of("statistics/exhaustion", "loot"), f.order); assertTrue(f.published.isEmpty());
    }

    @Test void aFailureCannotBeAttachedToANonpartialOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new NativeSickleAccess.Outcome(
                NativeSickleAccess.Status.COMMITTED, "invalid", new IllegalArgumentException()));
    }
}
