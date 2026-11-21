package net.kaleidoscope.doll;

import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LotteryRestoreTest {
    private static final String SAVE_KEY = "kaleidoscope_doll:lottery_v1";

    @Test void newMachineAcceptsAbsentLotteryDataEvenWhenOtherControllersHaveData() {
        CompoundTag other = new CompoundTag();
        other.putString("other:controller", "unrelated state");
        for (CompoundTag root : List.of(new CompoundTag(), other)) {
            var controller = controller();

            controller.loadCustomData(root);

            assertFalse(controller.blocked());
            assertFalse(controller.hasContents());
            CompoundTag saved = new CompoundTag();
            controller.saveCustomData(saved);
            CompoundTag state = SavedState.payload(saved.getCompound(SAVE_KEY), SavedState.namespace(SAVE_KEY),
                    SavedState.SERIES_SOURCE, 1, null);
            assertEquals(1, state.getInt("version"));
            for (int i = 0; i < 3; i++) assertEquals("", state.getString("tier_" + i));
            assertFalse(state.containsKey("pending"));
        }
    }

    @Test void wrongTypeAtKnownKeyBlocksTheMachineAndPreservesTheOriginalTag() {
        var controller = controller();
        CompoundTag root = new CompoundTag();
        root.putString(SAVE_KEY, "opaque damaged lottery data");

        controller.loadCustomData(root);

        assertTrue(controller.blocked());
        CompoundTag saved = new CompoundTag();
        controller.saveCustomData(saved);
        assertEquals(root.get(SAVE_KEY), saved.get(SAVE_KEY));
    }

    @Test void malformedKnownCompoundBlocksTheMachineAndPreservesEveryField() {
        var controller = controller();
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("version", 1);
        malformed.putString("tier_0", "doll:not_in_the_pool");
        malformed.putString("pending", "doll:one");
        malformed.putInt("tier", 0);
        malformed.putInt("ticks", 17);
        CompoundTag root = new CompoundTag();
        root.put(SAVE_KEY, malformed);

        controller.loadCustomData(root);

        assertTrue(controller.blocked());
        CompoundTag saved = new CompoundTag();
        controller.saveCustomData(saved);
        assertEquals(malformed, saved.getCompound(SAVE_KEY));
    }

    private static LotteryController controller() {
        Furniture furniture = mock(Furniture.class);
        DollPlugin plugin = mock(DollPlugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        var world = mock(net.momirealms.craftengine.core.world.World.class);
        when(world.platformWorld()).thenReturn(mock(World.class));
        when(furniture.position()).thenReturn(new WorldPosition(world, 0, 64, 0));
        when(plugin.catalog()).thenReturn(new DollCatalog(List.of("doll:one"), List.of(),
                List.of(List.of("doll:one"), List.of("doll:one"), List.of("doll:one")),
                List.of("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond"), Map.of()));
        return new LotteryController(furniture, plugin);
    }
}
