package net.kaleidoscope.doll;

import net.kaleidoscope.series.InventoryTrade;
import net.kaleidoscope.series.Items;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MovingDollRecoveryTest {
    @Test void protectionCallbacksCannotReenterTheSameRecovery() {
        var fixture = new Fixture();
        try (var bukkit = mockStatic(Bukkit.class); var ids = mockStatic(Items.class);
             var trades = mockStatic(InventoryTrade.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
            ids.when(() -> Items.id(fixture.item)).thenReturn("doll:one");
            when(fixture.plugin.mayUse(eq(fixture.player), any())).thenAnswer(call -> {
                fixture.moving.damage(fixture.attack(fixture.other));
                return true;
            });
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(0), anyList(), any()))
                    .thenAnswer(call -> call.getArgument(4, BooleanSupplier.class).getAsBoolean());
            fixture.moving.damage(fixture.attack(fixture.player));
            trades.verify(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(0), anyList(), any()), times(1));
            verify(fixture.plugin, never()).mayUse(eq(fixture.other), any());
            verify(fixture.stand, times(1)).remove();
        }
    }

    @Test void anotherPlayerCannotReclaimTheSameDollFromInsideTheTradeEvent() {
        var fixture = new Fixture();
        try (var bukkit = mockStatic(Bukkit.class); var ids = mockStatic(Items.class);
             var trades = mockStatic(InventoryTrade.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
            ids.when(() -> Items.id(fixture.item)).thenReturn("doll:one");
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(0), anyList(), any()))
                    .thenAnswer(call -> {
                        fixture.moving.damage(fixture.attack(fixture.other));
                        assertTrue(call.getArgument(4, BooleanSupplier.class).getAsBoolean());
                        assertTrue(fixture.valid.get(), "Entity removal callbacks must run after the inventory commit");
                        return true;
                    });
            fixture.moving.damage(fixture.attack(fixture.player));
            trades.verify(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(0), anyList(), any()), times(1));
            verify(fixture.stand, times(1)).remove();
            verify(fixture.plugin).collected(fixture.player, "doll:one");
            verify(fixture.plugin, never()).collected(eq(fixture.other), anyString());
        }
    }

    @Test void removalCallbacksCannotHaveTheirInventoryChangesOverwrittenByTheTrade() {
        var fixture = new Fixture();
        var bonus = mock(ItemStack.class);
        var live = new AtomicReference<ItemStack[]>();
        doAnswer(call -> { live.set(call.getArgument(0)); return null; })
                .when(fixture.inventory).setStorageContents(any());
        doAnswer(call -> {
            fixture.valid.set(false);
            fixture.inventory.setStorageContents(new ItemStack[]{bonus});
            return null;
        }).when(fixture.stand).remove();
        try (var bukkit = mockStatic(Bukkit.class); var ids = mockStatic(Items.class);
             var trades = mockStatic(InventoryTrade.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
            ids.when(() -> Items.id(fixture.item)).thenReturn("doll:one");
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(0), anyList(), any()))
                    .thenAnswer(call -> {
                        if (!call.getArgument(4, BooleanSupplier.class).getAsBoolean()) return false;
                        fixture.inventory.setStorageContents(new ItemStack[]{fixture.item});
                        return true;
                    });
            fixture.moving.damage(fixture.attack(fixture.player));
            assertSame(bonus, live.get()[0], "The removal listener's later inventory update must survive");
            verify(fixture.inventory, times(2)).setStorageContents(any());
            verify(fixture.stand, times(1)).remove();
        }
    }

    @Test void disappearingDuringTheTradeEventCannotCreateAReward() {
        var fixture = new Fixture();
        try (var bukkit = mockStatic(Bukkit.class); var ids = mockStatic(Items.class);
             var trades = mockStatic(InventoryTrade.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
            ids.when(() -> Items.id(fixture.item)).thenReturn("doll:one");
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(0), anyList(), any()))
                    .thenAnswer(call -> {
                        fixture.valid.set(false);
                        assertFalse(call.getArgument(4, BooleanSupplier.class).getAsBoolean());
                        return false;
                    });
            fixture.moving.damage(fixture.attack(fixture.player));
            verify(fixture.plugin, never()).collected(any(), anyString());
            verify(fixture.stand, never()).remove();
        }
    }

    private static final class Fixture {
        final DollPlugin plugin = mock(DollPlugin.class);
        final Player player = mock(Player.class), other = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final ArmorStand stand = mock(ArmorStand.class);
        final ItemStack item = mock(ItemStack.class);
        final AtomicBoolean valid = new AtomicBoolean(true);
        final MovingDolls moving;

        Fixture() {
            FileConfiguration config = mock(FileConfiguration.class);
            DollCatalog catalog = mock(DollCatalog.class);
            PersistentDataContainer pdc = mock(PersistentDataContainer.class);
            EntityEquipment equipment = mock(EntityEquipment.class);
            when(plugin.getName()).thenReturn("Fixture");
            when(plugin.namespace()).thenReturn("fixture");
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.catalog()).thenReturn(catalog);
            when(catalog.dolls()).thenReturn(List.of("doll:one"));
            when(config.getInt("phantom.lifetime-ticks", 3600)).thenReturn(3600);
            when(config.getDouble("phantom.chance", .03)).thenReturn(.03);
            when(stand.getPersistentDataContainer()).thenReturn(pdc);
            when(pdc.has(any(), eq(PersistentDataType.BYTE))).thenReturn(true);
            when(stand.getEquipment()).thenReturn(equipment);
            when(equipment.getHelmet()).thenReturn(item);
            when(stand.getLocation()).thenReturn(new Location(null, 0, 64, 0));
            when(stand.getUniqueId()).thenReturn(UUID.randomUUID());
            when(stand.isValid()).thenAnswer(call -> valid.get());
            doAnswer(call -> { valid.set(false); return null; }).when(stand).remove();
            when(player.getInventory()).thenReturn(inventory);
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(plugin.mayUse(any(), any())).thenReturn(true);
            when(plugin.allows(any(), any(), any())).thenReturn(true);
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                    .when(plugin).attempt(any(), any());
            moving = new MovingDolls(plugin);
        }

        EntityDamageByEntityEvent attack(Player actor) {
            EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
            when(event.getEntity()).thenReturn(stand);
            when(event.getDamager()).thenReturn(actor);
            return event;
        }
    }
}
