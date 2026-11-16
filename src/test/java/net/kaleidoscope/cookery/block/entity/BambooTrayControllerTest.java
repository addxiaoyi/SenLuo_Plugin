package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BambooTrayControllerTest {
    private static final String SAVE = "kaleidoscopecookery:bamboo_tray";
    private final BukkitItem empty = mock(BukkitItem.class);
    private final BukkitItem stored = mock(BukkitItem.class);
    private final BukkitItem decoded = mock(BukkitItem.class);
    private final Item[] slots = new Item[4];
    private final BambooTrayProgress[] progress = new BambooTrayProgress[4];
    private MockedStatic<Item> items;
    private MockedStatic<BlockEntityNbt> nbt;
    private BambooTrayController tray;
    private BambooTrayContainer container;

    @BeforeEach void prepare() throws ReflectiveOperationException {
        when(empty.isEmpty()).thenReturn(true);
        when(empty.copy()).thenReturn(empty);
        when(stored.copy()).thenReturn(stored);
        when(decoded.copy()).thenReturn(decoded);
        when(decoded.count()).thenReturn(8);
        items = mockStatic(Item.class);
        items.when(Item::empty).thenReturn(empty);
        nbt = mockStatic(BlockEntityNbt.class);
        nbt.when(() -> BlockEntityNbt.getItem(any(), eq("item"), anyInt())).thenReturn(decoded);
        Arrays.fill(slots, empty);
        slots[0] = stored;
        Arrays.setAll(progress, ignored -> new BambooTrayProgress());
        progress[0].restore(1800, (byte) 1);
        container = mock(BambooTrayContainer.class);
        when(container.getItem(anyInt())).thenAnswer(call -> slots[call.getArgument(0)]);
        when(container.getMaxStackSize(any())).thenReturn(64);
        doAnswer(call -> { slots[call.getArgument(0)] = call.getArgument(1); return null; })
                .when(container).setItem(anyInt(), any());
        // Inventory construction needs a live NMS registry; exercise the real load method on a seeded fixture.
        tray = mock(BambooTrayController.class, CALLS_REAL_METHODS);
        field(BlockEntityController.class, "blockEntity", mock(BlockEntity.class));
        field(BambooTrayController.class, "progress", progress);
        field(BambooTrayController.class, "processingItems", slots.clone());
        field(BambooTrayController.class, "container", container);
        field(BambooTrayController.class, "element", mock(BambooTrayElement.class));
    }

    @AfterEach void close() {
        for (MockedStatic<?> handle : new MockedStatic<?>[]{nbt, items}) {
            if (handle != null) handle.close();
        }
    }

    @Test void wrongInventoryTypeDoesNotEraseExistingItems() {
        rejects(payload -> payload.putString("items", "damaged-list"));
    }

    @Test void presentWrongMetadataTypesDoNotBecomeDefaults() {
        for (String key : new String[]{"schema", "data_version", "progress", "completion"}) {
            rejects(payload -> payload.putString(key, "wrong-type"));
        }
        rejects(payload -> payload.putFloat("schema", 1.5f));
    }

    @Test void overlongAndInvalidProgressArraysAreRejected() {
        rejects(payload -> payload.putIntArray("progress", new int[5]));
        rejects(payload -> payload.putByteArray("completion", new byte[5]));
        rejects(payload -> payload.putIntArray("progress", new int[]{-1}));
        rejects(payload -> payload.putByteArray("completion", new byte[]{3}));
        rejects(payload -> payload.putByteArray("completion", new byte[]{-1}));
    }

    @Test void invalidSlotAndDuplicateSlotRejectTheWholeInventory() {
        rejects(payload -> payload.getList("items").getCompound(0).putInt("slot", 4));
        rejects(payload -> payload.getList("items").getCompound(0).putFloat("slot", 0.5f));
        rejects(payload -> payload.getList("items").add(entry(0)));
    }

    @Test void encodedEmptyItemCannotSilentlyEraseAStoredStack() {
        nbt.when(() -> BlockEntityNbt.getItem(any(), eq("item"), anyInt())).thenReturn(empty);
        rejects(payload -> {});
    }

    @Test void oversizedDecodedStackIsRejectedBeforeContainerClamping() {
        when(decoded.count()).thenReturn(100);
        rejects(payload -> {});
    }

    @Test void invalidItemAtLaterSlotDoesNotPartiallyLoadEarlierSlots() {
        rejects(payload -> {
            CompoundTag broken = new CompoundTag();
            broken.putInt("slot", 1);
            payload.getList("items").add(broken);
        });
    }

    @Test void legacyMissingFieldsAndShortArraysRemainCompatible() {
        CompoundTag payload = validPayload();
        payload.remove("schema");
        payload.remove("data_version");
        payload.putIntArray("progress", new int[]{19});
        payload.remove("completion");
        tray.loadCustomData(root(payload, false));
        assertSame(decoded, tray.item(0));
        assertEquals(19, progress[0].ticks());
        assertEquals(0, progress[0].completionCode());
        for (int slot = 1; slot < 4; slot++) assertTrue(tray.item(slot).isEmpty());
    }

    @Test void validEnvelopeRestoresProgressAndCompletedDirection() {
        tray.loadCustomData(root(validPayload(), true));
        assertSame(decoded, tray.item(0));
        assertEquals(900, progress[0].ticks());
        assertEquals(2, progress[0].completionCode());
    }

    @Test void wrongLegacyRootTypeIsRejectedWithoutTouchingCurrentState() {
        CompoundTag root = new CompoundTag();
        root.putString(SAVE, "damaged-payload");
        assertThrows(IllegalArgumentException.class, () -> tray.loadCustomData(root));
        unchanged();
    }

    private void rejects(Consumer<CompoundTag> corrupt) {
        for (boolean envelope : new boolean[]{false, true}) {
            CompoundTag payload = validPayload();
            corrupt.accept(payload);
            assertThrows(IllegalArgumentException.class, () -> tray.loadCustomData(root(payload, envelope)));
            unchanged();
        }
    }

    private void unchanged() {
        assertSame(stored, tray.item(0));
        assertEquals(1800, progress[0].ticks());
        assertEquals(1, progress[0].completionCode());
        for (int slot = 1; slot < 4; slot++) {
            assertSame(empty, tray.item(slot));
            assertEquals(0, progress[slot].ticks());
            assertEquals(0, progress[slot].completionCode());
        }
        verify(container, never()).setItem(anyInt(), any());
    }

    private static CompoundTag validPayload() {
        CompoundTag payload = new CompoundTag();
        payload.putInt("schema", 1);
        payload.putInt("data_version", 4671);
        ListTag inventory = new ListTag();
        inventory.add(entry(0));
        payload.put("items", inventory);
        payload.putIntArray("progress", new int[]{900, 0, 0, 0});
        payload.putByteArray("completion", new byte[]{2, 0, 0, 0});
        return payload;
    }

    private static CompoundTag entry(int slot) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("slot", slot);
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:kelp");
        item.putInt("count", 8);
        entry.put("item", item);
        return entry;
    }

    private static CompoundTag root(CompoundTag payload, boolean envelope) {
        CompoundTag root = new CompoundTag();
        if (envelope) CookeryBlockStateEnvelope.put(root, SAVE, payload);
        else root.put(SAVE, payload);
        return root;
    }

    private void field(Class<?> owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tray, value);
    }
}
