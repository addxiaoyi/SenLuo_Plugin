package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceCropHarvestSourceTest {
    private final KaleidoscopeCookeryPlugin plugin = mock(KaleidoscopeCookeryPlugin.class);
    private final Player player = mock(Player.class);
    private final org.bukkit.entity.Player actor = mock(org.bukkit.entity.Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Item held = mock(Item.class), snapshot = mock(Item.class);

    private void source() {
        when(plugin.isEnabled()).thenReturn(true);
        when(player.platformPlayer()).thenReturn(actor);
        when(actor.getInventory()).thenReturn(inventory);
        when(inventory.getHeldItemSlot()).thenReturn(2);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(held);
        when(held.count()).thenReturn(3);
        when(held.copyWithCount(1)).thenReturn(snapshot);
        when(held.isSimilar(snapshot)).thenReturn(true);
    }

    @Test void pluginDisableOrReplacementInvalidatesBeforeCommit() {
        source();
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            var source = SourceCropHarvestSource.capture(player);
            assertNotNull(source);
            assertTrue(source.matches(player));
            when(plugin.isEnabled()).thenReturn(false);
            assertFalse(source.matches(player));
            assertNull(SourceCropHarvestSource.capture(player));
            when(plugin.isEnabled()).thenReturn(true);
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(mock(KaleidoscopeCookeryPlugin.class));
            assertFalse(source.matches(player));
        }
    }

    @Test void identicalItemInAnotherHotbarSlotStillInvalidatesTheOriginalInteraction() {
        source();
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            var source = SourceCropHarvestSource.capture(player);
            when(inventory.getHeldItemSlot()).thenReturn(3);
            assertFalse(source.matches(player));
        }
    }

    @Test void componentAndCountChangesInvalidateTheCopiedSource() {
        source();
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            var source = SourceCropHarvestSource.capture(player);
            assertSame(snapshot, source.held());
            when(held.isSimilar(snapshot)).thenReturn(false);
            assertFalse(source.matches(player));
            when(held.isSimilar(snapshot)).thenReturn(true);
            when(held.count()).thenReturn(2);
            assertFalse(source.matches(player));
        }
    }

    @Test void emptyHandMustStayEmptyInTheSameSlot() {
        source();
        when(held.isEmpty()).thenReturn(true);
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            var source = SourceCropHarvestSource.capture(player);
            assertNull(source.held());
            assertTrue(source.matches(player));
            when(held.isEmpty()).thenReturn(false);
            assertFalse(source.matches(player));
        }
    }

    @Test void offhandSnapshotPreservesTheFullStackWithoutDependingOnMainHandOrSelectedSlot() {
        source();
        var offhand = mock(Item.class);
        var offhandSnapshot = mock(Item.class);
        when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(offhand);
        when(offhand.count()).thenReturn(19);
        when(offhand.copyWithCount(1)).thenReturn(offhandSnapshot);
        when(offhand.isSimilar(offhandSnapshot)).thenReturn(true);
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            var source = SourceCropHarvestSource.capture(player, InteractionHand.OFF_HAND);
            assertNotNull(source);
            assertEquals(InteractionHand.OFF_HAND, source.hand());
            assertEquals(19, source.count());
            assertSame(offhandSnapshot, source.held());
            assertTrue(source.matches(player));

            when(inventory.getHeldItemSlot()).thenReturn(7);
            when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(mock(Item.class));
            when(held.count()).thenReturn(1);
            when(held.isSimilar(snapshot)).thenReturn(false);
            assertTrue(source.matches(player));
            verify(offhand).copyWithCount(1);
            verify(offhand, atLeastOnce()).isSimilar(offhandSnapshot);
            verify(player, never()).getItemInHand(InteractionHand.MAIN_HAND);
        }
    }

    @Test void offhandCountComponentsAndReplacementMustAllMatchTheCapturedStack() {
        source();
        when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(held);
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            var source = SourceCropHarvestSource.capture(player, InteractionHand.OFF_HAND);
            assertNotNull(source);
            assertTrue(source.matches(player));

            when(held.count()).thenReturn(2);
            assertFalse(source.matches(player));
            when(held.count()).thenReturn(3);
            assertTrue(source.matches(player));
            when(held.isSimilar(snapshot)).thenReturn(false);
            assertFalse(source.matches(player));

            var replacement = mock(Item.class);
            when(replacement.count()).thenReturn(3);
            when(replacement.isSimilar(snapshot)).thenReturn(true);
            when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(replacement);
            assertTrue(source.matches(player));
            when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(null);
            assertFalse(source.matches(player));
        }
    }

    @Test void emptyOffhandCanIgnoreMainHandChangesButCannotBecomeNonempty() {
        source();
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            var source = SourceCropHarvestSource.capture(player, InteractionHand.OFF_HAND);
            assertNotNull(source);
            assertNull(source.held());
            assertEquals(0, source.count());
            when(inventory.getHeldItemSlot()).thenReturn(8);
            when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(null);
            assertTrue(source.matches(player));
            when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(held);
            assertFalse(source.matches(player));
        }
    }

    @Test void mainHandCaptureStillLocksTheSelectedSlotAndIgnoresOffhandChanges() {
        source();
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            var source = SourceCropHarvestSource.capture(player);
            assertNotNull(source);
            assertEquals(InteractionHand.MAIN_HAND, source.hand());
            assertEquals(2, source.slot());
            when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(mock(Item.class));
            assertTrue(source.matches(player));
            when(inventory.getHeldItemSlot()).thenReturn(3);
            assertFalse(source.matches(player));
            when(inventory.getHeldItemSlot()).thenReturn(2);
            assertTrue(source.matches(player));
            verify(player, never()).getItemInHand(InteractionHand.OFF_HAND);
        }
    }

    @Test void nullHandCannotCreateASnapshotOrReadTheActor() {
        try (var plugins = mockStatic(KaleidoscopeCookeryPlugin.class)) {
            plugins.when(KaleidoscopeCookeryPlugin::instance).thenReturn(plugin);
            assertNull(SourceCropHarvestSource.capture(player, null));
            verifyNoInteractions(player);
        }
    }
}
