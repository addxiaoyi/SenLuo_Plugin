package net.kaleidoscope.deco;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishRackContentsTest {
    private ItemStack fish(Material type, int count, String metadata) {
        ItemStack item = mock(ItemStack.class, metadata);
        when(item.getType()).thenReturn(type);
        when(item.getAmount()).thenReturn(count);
        when(item.isEmpty()).thenReturn(count == 0);
        when(item.clone()).thenAnswer(invocation -> fish(type, count, metadata));
        when(item.asQuantity(anyInt())).thenAnswer(invocation -> fish(type, invocation.getArgument(0), metadata));
        return item;
    }

    @Test void acceptsExactlyPinnedFishTagMaterials() {
        for (Material type : List.of(Material.COD, Material.COOKED_COD, Material.SALMON, Material.COOKED_SALMON, Material.TROPICAL_FISH))
            assertTrue(FishRackContents.accepts(fish(type, 64, "named")));
        assertFalse(FishRackContents.accepts(fish(Material.PUFFERFISH, 1, "wrong fish")));
        assertFalse(FishRackContents.accepts(fish(Material.COD, 0, "empty")));
        assertFalse(FishRackContents.accepts(null));
    }

    @Test void insertionPlansDoNotMutateUntilCommittedAndHaveThreeSingleSlots() {
        FishRackContents rack = new FishRackContents();
        var source = fish(Material.COD, 64, "named cod");
        var next = rack.adding(source);
        assertEquals(0, rack.size());
        assertEquals(1, next.getFirst().getAmount());
        rack.replace(next);
        rack.replace(rack.adding(fish(Material.COOKED_SALMON, 4, "enchanted salmon")));
        rack.replace(rack.adding(fish(Material.TROPICAL_FISH, 8, "component fish")));
        assertEquals("fishrack/cod/salmon/tropical", FishRackContents.variant(rack.snapshot()));
        assertNull(rack.adding(source));
        assertEquals(64, source.getAmount());
    }

    @Test void takeIsLifoAndSnapshotsDoNotExposeStoredStacks() {
        FishRackContents rack = new FishRackContents();
        rack.replace(List.of(fish(Material.COD, 1, "first"), fish(Material.COOKED_SALMON, 1, "last")));
        assertEquals(Material.COOKED_SALMON, rack.last().getType());
        var next = rack.removing();
        assertEquals(2, rack.size());
        rack.replace(next);
        assertEquals(Material.COD, rack.last().getType());
        assertNotSame(rack.snapshot().getFirst(), rack.snapshot().getFirst());
        rack.replace(rack.removing());
        assertNull(rack.last());
        assertNull(rack.removing());
        assertEquals("fishrack/empty", FishRackContents.variant(rack.snapshot()));
    }

    @Test void malformedSavesCannotReplaceExistingContents() {
        FishRackContents rack = new FishRackContents();
        var cod = fish(Material.COD, 1, "kept");
        rack.replace(List.of(cod));
        for (var bad : List.of(List.of(fish(Material.COD, 2, "too many")), List.of(fish(Material.STONE, 1, "invalid")),
                List.of(cod, cod, cod, cod))) {
            assertThrows(IllegalArgumentException.class, () -> rack.replace(bad));
            assertEquals(1, rack.size());
        }
    }
}
