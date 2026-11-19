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
}
