package net.kaleidoscope.cookery.nms;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real mutable Bukkit events prove that the tracker retains a reference, not an early damage value. */
class NativeMiningDamageTrackerTest {
    private final Player actor = mock(Player.class), otherActor = mock(Player.class);
    private final ItemStack originalMirror = mock(ItemStack.class), anotherMirror = mock(ItemStack.class);
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final NativeMiningDamageTracker tracker = new NativeMiningDamageTracker(available::get);

    private NativeMiningDamageTracker.Session begin(int baseWear) {
        var session = tracker.begin(actor, baseWear, event -> event.getItem() == originalMirror);
        assertNotNull(session); return session;
    }

    @Test void laterPriorityDamageAdjustmentIsReadAfterNativeCallRatherThanClampedToTheToolBase() {
        try (var session = begin(3)) {
            // The first damage is already reduced by native Unbreaking; originalDamage is the TOOL input.
            var event = new PlayerItemDamageEvent(actor, originalMirror, 1, 3);
            assertSame(originalMirror, event.getItem());
            tracker.observe(event);
            event.setDamage(11);
            var receipt = session.finish();
            assertTrue(receipt.valid()); assertTrue(receipt.observedEvent());
            assertEquals(3, receipt.baseWear()); assertEquals(11, receipt.resolvedWear());
        }
    }

    @Test void lateCancellationProducesZeroResolvedWearWithoutErasingTheNativeBase() {
        try (var session = begin(4)) {
            var event = new PlayerItemDamageEvent(actor, originalMirror, 2, 4);
            tracker.observe(event); event.setDamage(9); event.setCancelled(true);
            var receipt = session.finish();
            assertTrue(receipt.valid()); assertTrue(receipt.observedEvent());
            assertEquals(4, receipt.baseWear()); assertEquals(0, receipt.resolvedWear());
            assertTrue(receipt.reason().contains("canceled"));
        }
    }

    @Test void anInitiallyCanceledEventCanStillBeUncanceledByALaterPriority() {
        try (var session = begin(3)) {
            var event = new PlayerItemDamageEvent(actor, originalMirror, 1, 3);
            event.setCancelled(true); tracker.observe(event);
            event.setCancelled(false); event.setDamage(7);
            var receipt = session.finish();
            assertTrue(receipt.valid()); assertTrue(receipt.observedEvent()); assertEquals(7, receipt.resolvedWear());
        }
    }

    @Test void nativeUnbreakingZeroMayProduceNoEventAtAll() {
        try (var session = begin(5)) {
            var receipt = session.finish();
            assertTrue(receipt.valid()); assertFalse(receipt.observedEvent());
            assertTrue(receipt.completeObservation());
            assertEquals(5, receipt.baseWear()); assertEquals(0, receipt.resolvedWear());
        }
    }

    @Test void attributionRejectsUnrelatedSameActorDamageRatherThanCertifyingItAsNativeWear() {
        var nativeMiningFrame = new AtomicBoolean(false);
        try (var session = tracker.begin(actor, 4,
                event -> nativeMiningFrame.get() && event.getItem() == originalMirror)) {
            assertNotNull(session);
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 4, 4));
            nativeMiningFrame.set(true);
            tracker.observe(new PlayerItemDamageEvent(otherActor, originalMirror, 4, 4));
            tracker.observe(new PlayerItemDamageEvent(actor, anotherMirror, 4, 4));
            var expected = new PlayerItemDamageEvent(actor, originalMirror, 2, 4);
            tracker.observe(expected);
            var receipt = session.finish();
            assertFalse(receipt.valid()); assertTrue(receipt.reason().contains("unrelated"));
        }
    }

    @Test void multipleOrReentrantDamageEventsCannotBeRebasedAsOneNativeMiningTransition() {
        try (var session = begin(2)) {
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 2, 2));
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 1, 2));
            var receipt = session.finish();
            assertFalse(receipt.valid()); assertTrue(receipt.observedEvent());
            assertTrue(receipt.reason().contains("multiple/reentrant"));
        }
    }

    @Test void aNestedSessionIsRejectedWithoutReplacingTheOuterSession() {
        try (var outer = begin(2)) {
            assertNull(tracker.begin(actor, 7, event -> true));
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 1, 2));
            var receipt = outer.finish();
            assertTrue(receipt.valid()); assertEquals(2, receipt.baseWear()); assertEquals(1, receipt.resolvedWear());
        }
    }

    @Test void observerAbsenceRejectsBeforeCreatingAThreadLocalSession() {
        available.set(false);
        assertNull(tracker.begin(actor, 3, event -> { fail("Unavailable observer must not attribute events"); return true; }));
        available.set(true);
        try (var session = begin(3)) { assertTrue(session.finish().valid()); }
    }

    @Test void observerLossDuringTheNativeCallMakesEvenARecordedEventUncertifiable() {
        try (var session = begin(3)) {
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 3, 3));
            available.set(false);
            var receipt = session.finish();
            assertFalse(receipt.valid()); assertTrue(receipt.reason().contains("unavailable"));
            assertFalse(receipt.completeObservation());
        }
        available.set(true);
        try (var session = begin(3)) { assertTrue(session.finish().valid()); }
    }

    @Test void mismatchedOriginalDamageDoesNotBecomeAValidReceiptForTheCapturedTool() {
        try (var session = begin(3)) {
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 3, 8));
            var receipt = session.finish();
            assertFalse(receipt.valid()); assertTrue(receipt.reason().contains("base differs"));
        }
    }

    @Test void negativeEventAdjustedDamageIsRetainedForTheNativeClampedRepairTransition() {
        try (var session = begin(3)) {
            var event = new PlayerItemDamageEvent(actor, originalMirror, 3, 3);
            tracker.observe(event); event.setDamage(-1);
            var receipt = session.finish();
            assertTrue(receipt.valid()); assertEquals(-1, receipt.resolvedWear());
        }
    }

    @Test void attributionFailureIsReportedAndTheRealEventDispatchIsNotChangedByThrowingFromTheObserver() {
        try (var session = tracker.begin(actor, 3, event -> { throw new IllegalStateException("raw mirror unavailable"); })) {
            assertNotNull(session);
            assertDoesNotThrow(() -> tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 3, 3)));
            var receipt = session.finish();
            assertFalse(receipt.valid()); assertTrue(receipt.reason().contains("attribution failed"));
            assertFalse(receipt.completeObservation());
            assertTrue(receipt.reason().contains("raw mirror unavailable"));
        }
    }

    @Test void nativeExceptionsUnwindTheThreadLocalAndDoNotLeakOldEventsIntoTheNextPlant() {
        var failure = new IllegalArgumentException("native mining failed");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> {
            try (var session = begin(3)) {
                tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 2, 3));
                throw failure;
            }
        }));
        tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 9, 3));
        try (var next = begin(3)) {
            var receipt = next.finish();
            assertTrue(receipt.valid()); assertFalse(receipt.observedEvent()); assertEquals(0, receipt.resolvedWear());
        }
    }

    @Test void duplicateCloseCannotClearANewerSessionAndClosedFinishFails() {
        var first = begin(3); first.close(); first.close();
        assertThrows(IllegalStateException.class, first::finish);
        try (var next = begin(4)) {
            first.close();
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 2, 4));
            assertEquals(2, next.finish().resolvedWear());
        }
    }

    @Test void closeAndFinishOnAnotherThreadCannotStealTheOwnersSession() throws InterruptedException {
        try (var session = begin(3)) {
            var closeFailure = new AtomicReference<Throwable>();
            var finishFailure = new AtomicReference<Throwable>();
            Thread other = new Thread(() -> {
                try { session.close(); } catch (Throwable failure) { closeFailure.set(failure); }
                try { session.finish(); } catch (Throwable failure) { finishFailure.set(failure); }
            });
            other.start(); other.join(5000);
            assertFalse(other.isAlive(), "Wrong-thread access should reject immediately");
            assertInstanceOf(IllegalStateException.class, closeFailure.get());
            assertInstanceOf(IllegalStateException.class, finishFailure.get());
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 1, 3));
            assertEquals(1, session.finish().resolvedWear());
        }
    }

    @Test void independentRegionThreadsKeepTheirOwnActorsAndEventReferences() throws InterruptedException {
        var entered = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var otherReceipt = new AtomicReference<NativeMiningDamageTracker.Decision>();
        var otherFailure = new AtomicReference<Throwable>();
        try (var ownerSession = begin(3)) {
            Thread other = new Thread(() -> {
                try (var session = tracker.begin(otherActor, 8, event -> event.getItem() == anotherMirror)) {
                    entered.countDown(); assertNotNull(session);
                    assertTrue(proceed.await(5, TimeUnit.SECONDS));
                    tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 3, 3));
                    tracker.observe(new PlayerItemDamageEvent(otherActor, anotherMirror, 5, 8));
                    otherReceipt.set(session.finish());
                } catch (Throwable failure) { otherFailure.set(failure); entered.countDown(); }
            });
            other.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                tracker.observe(new PlayerItemDamageEvent(otherActor, anotherMirror, 8, 8));
                tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 2, 3));
                var own = ownerSession.finish();
                assertTrue(own.valid()); assertEquals(3, own.baseWear()); assertEquals(2, own.resolvedWear());
            } finally { proceed.countDown(); other.join(5000); }
            assertFalse(other.isAlive()); assertNull(otherFailure.get());
            assertNotNull(otherReceipt.get()); assertTrue(otherReceipt.get().valid());
            assertEquals(8, otherReceipt.get().baseWear()); assertEquals(5, otherReceipt.get().resolvedWear());
        }
    }

    @Test void completedDecisionContainsNumbersRatherThanAnEscapingMutableEventReference() {
        var event = new PlayerItemDamageEvent(actor, originalMirror, 2, 3);
        NativeMiningDamageTracker.Decision receipt;
        try (var session = begin(3)) { tracker.observe(event); receipt = session.finish(); }
        event.setDamage(99); event.setCancelled(true);
        assertTrue(receipt.valid()); assertEquals(2, receipt.resolvedWear());
    }

    @Test void invalidInitialBaseCannotLeaveAStuckSession() {
        assertThrows(IllegalArgumentException.class, () -> tracker.begin(actor, -1, event -> true));
        try (var session = begin(1)) { assertTrue(session.finish().valid()); }
    }

    @Test void anAttributedRealBreakEventIsRetainedForSingleToolExhaustion() {
        try (var session = tracker.begin(actor, 2, event -> event.getItem() == originalMirror,
                event -> event.getBrokenItem() == originalMirror)) {
            assertNotNull(session);
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 1, 2));
            tracker.observe(new PlayerItemBreakEvent(actor, originalMirror));
            assertTrue(session.finish().valid()); assertTrue(session.finish().observedBreak());
        }
    }

    @Test void aCanceledDamageEventIgnoresEvenALateNegativeAdjustment() {
        try (var session = begin(2)) {
            var event = new PlayerItemDamageEvent(actor, originalMirror, 1, 2);
            tracker.observe(event); event.setDamage(-100); event.setCancelled(true);
            assertEquals(0, session.finish().resolvedWear()); assertTrue(session.finish().valid());
        }
    }

    @Test void anUnrelatedBreakEventCannotCertifyAnArbitraryEmptyHandAsNativeExhaustion() {
        try (var session = begin(2)) {
            tracker.observe(new PlayerItemDamageEvent(actor, originalMirror, 2, 2));
            tracker.observe(new PlayerItemBreakEvent(actor, anotherMirror));
            assertFalse(session.finish().valid());
        }
    }
}
