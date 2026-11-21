package net.kaleidoscope.tavern;

import net.kaleidoscope.series.InventoryTrade;
import net.kaleidoscope.series.Items;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BottleTransactionTest {
    @Test void canceledTradeKeepsTheFilledBottle() {
        var fixture = new Fixture();
        try (var ids = mockStatic(Items.class); var trades = mockStatic(InventoryTrade.class)) {
            ids.when(() -> Items.id(fixture.held)).thenReturn(Fixture.CARRIER);
            fixture.controller.interact(fixture.player);
            assertTrue(fixture.controller.hasContents());
            verify(fixture.furniture, never()).setVariant(anyString());
        }
    }

    @Test void furnitureRemovedByTheTradeEventCannotPayOut() {
        var fixture = new Fixture();
        try (var ids = mockStatic(Items.class); var trades = mockStatic(InventoryTrade.class)) {
            ids.when(() -> Items.id(fixture.held)).thenReturn(Fixture.CARRIER);
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(1), anyList(), any()))
                    .thenAnswer(call -> {
                        when(fixture.furniture.isValid()).thenReturn(false);
                        assertFalse(call.getArgument(4, BooleanSupplier.class).getAsBoolean());
                        return false;
                    });
            fixture.controller.interact(fixture.player);
            assertTrue(fixture.controller.hasContents());
            verify(fixture.furniture, never()).setVariant(anyString());
        }
    }

    @Test void theBottleIsClearedBeforeTheInventoryReceivesTheDrink() {
        var fixture = new Fixture();
        try (var ids = mockStatic(Items.class); var trades = mockStatic(InventoryTrade.class)) {
            ids.when(() -> Items.id(fixture.held)).thenReturn(Fixture.CARRIER);
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(1), anyList(), any()))
                    .thenAnswer(call -> {
                        assertTrue(fixture.controller.hasContents());
                        assertTrue(call.getArgument(4, BooleanSupplier.class).getAsBoolean());
                        assertFalse(fixture.controller.hasContents());
                        return true;
                    });
            fixture.controller.interact(fixture.player);
            assertFalse(fixture.controller.hasContents());
            verify(fixture.furniture).setUnsaved();
            verify(fixture.furniture).setVariant("ground");
        }
    }

    private static final class Fixture {
        static final String CARRIER = "kaleidoscope_tavern:empty_bottle";
        final Furniture furniture = mock(Furniture.class);
        final TavernPlugin plugin = mock(TavernPlugin.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final ItemStack held = mock(ItemStack.class);
        final BottleController controller;

        Fixture() {
            when(furniture.id()).thenReturn(Key.of(CARRIER));
            when(furniture.isValid()).thenReturn(true);
            when(furniture.setVariant(anyString())).thenReturn(true);
            when(plugin.isEnabled()).thenReturn(true);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getItemInMainHand()).thenReturn(held);
            ItemStack drink = mock(ItemStack.class);
            when(drink.getAmount()).thenReturn(1);
            when(drink.clone()).thenReturn(drink);
            controller = new BottleController(furniture, plugin);
            controller.fill(drink);
            clearInvocations(furniture);
        }
    }
}
