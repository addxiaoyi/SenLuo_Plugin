package net.kaleidoscope.doll;

import net.kaleidoscope.series.ItemState;
import net.kaleidoscope.series.SavedItems;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DollItemRestoreTest {
    private static final String SAVE_KEY = "kaleidoscope_doll:pose_v1";

    @Test void itemPoseLoadedAfterPlacementRefreshesBothDisplayAndFutureDrop() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller.onLoad(); // CraftEngine initializes the default display before it loads item data.
            clearInvocations(fixture.display, fixture.furniture);
            var expected = DollPose.origin().adjust(0, 1);
            fixture.saved.putString("pose", expected.encode());
            doAnswer(call -> {
                assertEquals(expected.encode(), fixture.controller.pose().encode());
                return null;
            }).when(fixture.display).rebuild();

            fixture.controller.loadCustomDataFromItem(fixture.held);

            assertFalse(fixture.controller.blocked());
            assertEquals(expected.encode(), fixture.controller.pose().encode());
            assertEquals(expected.encode(), fixture.encoded.get().getString("pose"));
            verify(fixture.furniture).setSourceItem(fixture.preparedSource);
            verify(fixture.display).rebuild();
        }
    }

    @Test void corruptItemPosePreservesTheOriginalSnapshotWithoutRefreshingOrRewriting() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.saved.putString("pose", "NaN,1,1,0,0,0,0,0,0");

            fixture.controller.loadCustomDataFromItem(fixture.held);

            assertTrue(fixture.controller.blocked());
            assertTrue(fixture.controller.pose().isOrigin());
            verify(fixture.furniture, never()).setSourceItem(any());
            verify(fixture.display, never()).rebuild();
            assertNull(fixture.encoded.get());
            var recovery = new CompoundTag();
            fixture.controller.saveCustomData(recovery);
            assertEquals("original item bytes", recovery.getCompound(SAVE_KEY).getString("source_item"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Furniture furniture = mock(Furniture.class);
        final DollPlugin plugin = mock(DollPlugin.class);
        final Item held = mock(Item.class), source = mock(Item.class);
        final Item preparedSource = mock(Item.class);
        final ItemStack carried = mock(ItemStack.class), original = mock(ItemStack.class), packed = mock(ItemStack.class);
        final DollDisplay display = mock(DollDisplay.class);
        final CompoundTag saved = new CompoundTag();
        final AtomicReference<CompoundTag> encoded = new AtomicReference<>();
        final MockedStatic<ItemState> states = mockStatic(ItemState.class);
        final MockedStatic<SavedItems> snapshots = mockStatic(SavedItems.class);
        final DollController controller;

        Fixture() throws Exception {
            when(plugin.namespace()).thenReturn("fixture");
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            when(furniture.id()).thenReturn(Key.of("kaleidoscope_doll:doll_0"));
            var ceWorld = mock(net.momirealms.craftengine.core.world.World.class);
            when(ceWorld.platformWorld()).thenReturn(mock(World.class));
            when(furniture.position()).thenReturn(new WorldPosition(ceWorld, 0, 64, 0));
            when(furniture.sourceItem()).thenReturn(source);
            when(original.asQuantity(1)).thenReturn(original);
            var meta = mock(ItemMeta.class);
            when(original.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            saved.putInt("version", 1);
            saved.putString("pose", DollPose.origin().encode());
            states.when(() -> ItemState.read(eq(carried), any(NamespacedKey.class))).thenReturn(saved);
            states.when(() -> ItemState.write(eq(original), any(NamespacedKey.class), any(CompoundTag.class)))
                    .thenAnswer(call -> { encoded.set(call.getArgument(2)); return packed; });
            snapshots.when(() -> SavedItems.put(any(CompoundTag.class), eq("source_item"), eq(carried)))
                    .thenAnswer(call -> {
                        call.getArgument(0, CompoundTag.class).putString("source_item", "original item bytes");
                        return null;
                    });
            controller = spy(new DollController(furniture, plugin));
            doReturn(carried).when(controller).bukkitStack(held);
            doReturn(original).when(controller).bukkitStack(source);
            doReturn(preparedSource).when(controller).ceItem(packed);
            Field field = DollController.class.getDeclaredField("display");
            field.setAccessible(true);
            field.set(controller, display);
        }

        @Override public void close() {
            snapshots.close();
            states.close();
        }
    }
}
