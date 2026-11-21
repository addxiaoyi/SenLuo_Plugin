package net.kaleidoscope.deco;

import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WoodenSeatsTest {
    @Test void eachPinnedWoodMapsToOnlyItsTwoFurnitureFamilies() {
        assertEquals(12, WoodenSeats.WOODS.size());
        for (String wood : WoodenSeats.WOODS) {
            for (String kind : new String[]{"chair", "cook_stool"}) {
                String id = "kaleidoscope_deco:" + kind + "/" + wood;
                var seat = WoodenSeats.parse(id);
                assertEquals(id, seat.id());
                assertEquals(wood, seat.wood());
                assertEquals(kind.equals("chair"), seat.chair());
            }
        }
    }

    @Test void unrelatedAndMalformedFurnitureIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WoodenSeats.parse(null));
        for (String id : new String[]{"minecraft:chair/oak", "kaleidoscope_deco:chair", "kaleidoscope_deco:chair/oak/extra",
                "kaleidoscope_deco:chair/unknown", "kaleidoscope_deco:stool/oak", "kaleidoscope_deco:cook_stool/OAK"})
            assertThrows(IllegalArgumentException.class, () -> WoodenSeats.parse(id), id);
    }

    @Test void sixteenVanillaWoolCarpetsMapToSixteenVariants() {
        assertEquals(16, WoodenSeats.COLORS.size());
        for (String color : WoodenSeats.COLORS) {
            assertEquals(color, WoodenSeats.carpetColor("minecraft:" + color + "_carpet"));
            assertEquals("cushion/" + color, WoodenSeats.variant(color));
        }
        assertEquals("ground", WoodenSeats.variant("empty"));
    }

    @Test void customAndNonWoolCarpetsCannotCreateUnknownCushions() {
        assertNull(WoodenSeats.carpetColor(null));
        for (String id : new String[]{"red_carpet", "other:red_carpet", "minecraft:moss_carpet", "minecraft:red_wool",
                "minecraft:empty_carpet", "minecraft:carpet", "minecraft:red_carpet_extra"})
            assertNull(WoodenSeats.carpetColor(id), id);
        assertThrows(IllegalArgumentException.class, () -> WoodenSeats.variant("moss"));
        assertThrows(IllegalArgumentException.class, () -> WoodenSeats.variant(null));
    }

    @Test void busyOrUnreadableSeatCancelsTheNativeBreak() throws Exception {
        var furniture = mock(BukkitFurniture.class);
        var controller = mock(WoodenSeatController.class);
        var field = Furniture.class.getDeclaredField("controller");
        field.setAccessible(true);
        field.set(furniture, controller);
        when(controller.get(WoodenSeatController.class, 0)).thenReturn(controller);
        when(controller.blocksRemoval()).thenReturn(true);
        var event = mock(FurnitureBreakEvent.class);
        var player = mock(Player.class);
        when(event.furniture()).thenReturn(furniture);
        when(event.player()).thenReturn(player);
        new WoodenSeats().breaking(event);
        verify(event).setCancelled(true);
        verify(player).sendMessage(anyString());
    }

    @Test void readableIdleSeatLeavesNativeBreakAndLootEnabled() throws Exception {
        var furniture = mock(BukkitFurniture.class);
        var controller = mock(WoodenSeatController.class);
        var field = Furniture.class.getDeclaredField("controller");
        field.setAccessible(true);
        field.set(furniture, controller);
        when(controller.get(WoodenSeatController.class, 0)).thenReturn(controller);
        var event = mock(FurnitureBreakEvent.class);
        when(event.furniture()).thenReturn(furniture);
        new WoodenSeats().breaking(event);
        verify(event, never()).setCancelled(anyBoolean());
        verify(event, never()).player();
    }
}
