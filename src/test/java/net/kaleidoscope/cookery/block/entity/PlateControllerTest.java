package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.PlateBehavior;
import net.kaleidoscope.cookery.catalog.PlateServing;
import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.kaleidoscope.cookery.util.BlockStates;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.util.DropUtils;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.PlacementGuard;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlateControllerTest {
    private static final String SAVE = "kaleidoscopecookery:plate";
    private final Item empty = mock(Item.class);
    private final Map<Key, Item> prototypes = new HashMap<>();
    private final Map<Item, String> stamps = new IdentityHashMap<>();
    private final List<Item> dropped = new ArrayList<>();
    private MockedStatic<Item> items;
    private MockedStatic<BlockStates> states;
    private MockedStatic<BlockEntityNbt> nbt;
    private MockedStatic<InventoryUtils> inventory;
    private MockedStatic<DropUtils> drops;
    private MockedStatic<CookeryFoodItems> foods;
    private PlateBehavior behavior;
    private BlockEntity entity;

    @BeforeEach void prepare() throws ReflectiveOperationException {
        when(empty.isEmpty()).thenReturn(true);
        when(empty.copy()).thenReturn(empty);
        items = mockStatic(Item.class);
        items.when(Item::empty).thenReturn(empty);
        states = mockStatic(BlockStates.class);
        states.when(() -> BlockStates.value(any(), any(), anyInt())).thenReturn(4);
        nbt = mockStatic(BlockEntityNbt.class);
        nbt.when(BlockEntityNbt::newData).thenAnswer(call -> {
            CompoundTag tag = new CompoundTag();
            tag.putInt("data_version", 1);
            return tag;
        });
        nbt.when(() -> BlockEntityNbt.dataVersion(any())).thenReturn(1);
        nbt.when(() -> BlockEntityNbt.itemTag(any())).thenAnswer(call -> encode(call.getArgument(0)));
        nbt.when(() -> BlockEntityNbt.putItem(any(), anyString(), any())).thenAnswer(call -> {
            call.<CompoundTag>getArgument(0).put(call.getArgument(1), encode(call.getArgument(2)));
            return null;
        });
        nbt.when(() -> BlockEntityNbt.getItem(any(), anyString(), anyInt())).thenAnswer(call ->
                decode(call.<CompoundTag>getArgument(0).getCompound(call.getArgument(1))));
        inventory = mockStatic(InventoryUtils.class);
        inventory.when(() -> InventoryUtils.createOrEmpty(any(Key.class))).thenAnswer(call ->
                prototypes.getOrDefault(call.getArgument(0), empty));
        drops = mockStatic(DropUtils.class);
        drops.when(() -> DropUtils.dropOnRemove(any(), any())).thenAnswer(call -> {
            dropped.add(call.<Item>getArgument(1).copy());
            return null;
        });
        drops.when(() -> DropUtils.dropAtCenter(any(), any())).thenAnswer(call -> {
            dropped.add(call.<Item>getArgument(1).copy());
            return null;
        });
        foods = mockStatic(CookeryFoodItems.class);
        foods.when(() -> CookeryFoodItems.bakeBite(any(), any())).thenAnswer(call -> call.getArgument(0));
        register("minecraft:apple");
        register("minecraft:bowl");
        register("minecraft:glass_bottle");
        register("minecraft:sweet_berries");
        register("minecraft:glow_berries");
        behavior = mock(PlateBehavior.class);
        when(behavior.maxServings()).thenReturn(4);
        when(behavior.drops()).thenReturn(List.of("minecraft:bowl"));
        when(behavior.servingItems()).thenReturn(List.of(Key.of("minecraft:apple")));
        when(behavior.accepts(any())).thenAnswer(call ->
                behavior.servingItems().size() == 1 && behavior.servingItems().contains(call.getArgument(0)));
        entity = mock(BlockEntity.class);
        var state = BlockEntity.class.getField("blockState");
        state.set(entity, mock(ImmutableBlockState.class));
        var position = BlockEntity.class.getField("pos");
        position.setAccessible(true);
        position.set(entity, new BlockPos(8, 64, 8));
        entity.world = mock(CEWorld.class);
        when(entity.isValid()).thenReturn(true);
    }

    @AfterEach void close() {
        for (MockedStatic<?> handle : new MockedStatic<?>[]{foods, drops, inventory, nbt, states, items}) {
            if (handle != null) handle.close();
        }
    }

    @Test void newPlateStartsWithItsBlockStateServings() {
        assertEquals(4, new PlateController(entity, behavior).servings());
        assertEquals(2, plate(2).servings());
        assertEquals(0, plate(0).servings());
    }

    @Test void oneRefillAddsOneServingAndConsumesOneItem() {
        var plate = plate(0);
        Item held = item("minecraft:apple", 3, "held");
        assertTrue(plate.refill(held));
        assertEquals(1, plate.servings());
        assertEquals(2, held.count());
        assertTrue(plate.takeServing(serving -> {
            assertEquals(1, serving.size());
            assertEquals(1, serving.getFirst().count());
        }));
        assertEquals(0, plate.servings());
        assertTrue(dropped.isEmpty());
    }

    @Test void fullOrIncompatiblePlateRejectsRefillWithoutConsumption() {
        var plate = plate(4);
        Item held = item("minecraft:apple", 3, "held");
        assertFalse(plate.refill(held));
        assertEquals(3, held.count());
        var emptyPlate = plate(0);
        Item berries = item("minecraft:sweet_berries", 2, "held");
        assertFalse(emptyPlate.refill(berries));
        assertFalse(emptyPlate.refill(empty));
        assertFalse(emptyPlate.refill(null));
        assertEquals(2, berries.count());
        assertEquals(0, emptyPlate.servings());
    }

    @Test void berryServingReturnsBothItemsInOrderAndCannotBeRefilled() {
        when(behavior.servingItems()).thenReturn(List.of(Key.of("minecraft:sweet_berries"), Key.of("minecraft:glow_berries")));
        var plate = plate(2);
        List<Item> received = new ArrayList<>();
        assertTrue(plate.takeServing(received::addAll));
        assertEquals(List.of(Key.of("minecraft:sweet_berries"), Key.of("minecraft:glow_berries")),
                received.stream().map(Item::id).toList());
        assertTrue(received.stream().allMatch(item -> item.count() == 1));
        assertEquals(1, plate.servings());
        Item held = item("minecraft:sweet_berries", 2, "held");
        assertFalse(plate.refill(held));
        assertEquals(2, held.count());
    }

    @Test void lastServingKeepsEmptyPlateUntilItsNextInteraction() {
        var plate = plate(1);
        assertTrue(plate.takeServing(serving -> assertEquals(1, serving.size())));
        assertEquals(0, plate.servings());
        assertFalse(plate.takeServing(serving -> fail("An empty plate handed out food")));
        verify(behavior, never()).removeAfterConsumed(any(), any());
        assertTrue(dropped.isEmpty());
        when(behavior.removeAfterConsumed(entity.world, entity.pos)).thenAnswer(call -> {
            plate.onRemove();
            return true;
        });
        assertTrue(plate.removeIfEmpty());
        assertFalse(plate.removeIfEmpty());
        assertEquals(1, countDropped("minecraft:bowl"));
    }

    @Test void outputCopiesDoNotChangeOtherServingsOrBuiltPrototypes() {
        var plate = plate(2);
        List<Item> first = new ArrayList<>();
        assertTrue(plate.takeServing(first::addAll));
        first.getFirst().count(64);
        List<Item> second = new ArrayList<>();
        assertTrue(plate.takeServing(second::addAll));
        assertNotSame(first.getFirst(), second.getFirst());
        assertEquals(1, second.getFirst().count());
        assertEquals(1, prototypes.get(Key.of("minecraft:apple")).count());
    }

    @Test void rejectedEmptyRemovalKeepsThePlateAndContainerForRetry() {
        var plate = plate(0);
        CompoundTag before = saved(plate);
        when(behavior.removeAfterConsumed(entity.world, entity.pos)).thenReturn(false);
        assertFalse(plate.removeIfEmpty());
        assertTrue(dropped.isEmpty());
        assertEquals(before, saved(plate));
        when(behavior.removeAfterConsumed(entity.world, entity.pos)).thenAnswer(call -> {
            plate.onRemove();
            return true;
        });
        assertTrue(plate.removeIfEmpty());
        assertEquals(1, countDropped("minecraft:bowl"));
    }

    @Test void canceledPlacementRemovalCannotProduceFoodOrContainers() {
        var plate = plate(4);
        inventory.clearInvocations();
        try (MockedStatic<PlacementGuard> placement = mockStatic(PlacementGuard.class)) {
            placement.when(() -> PlacementGuard.isPlacing(entity)).thenReturn(true);
            plate.onRemove();
        }
        assertEquals(0, plate.servings());
        assertTrue(dropped.isEmpty());
        plate.onRemove();
        assertTrue(dropped.isEmpty());
        inventory.verifyNoInteractions();
    }

    @Test void callbackSeesCommittedCountAndCannotReenter() {
        var plate = plate(4);
        Item held = item("minecraft:apple", 1, "held");
        assertTrue(plate.takeServing(serving -> {
            assertEquals(3, plate.servings());
            assertFalse(plate.takeServing(nested -> fail("Reentrant serving succeeded")));
            assertFalse(plate.refill(held));
            assertEquals(1, held.count());
            assertFalse(plate.removeIfEmpty());
            verify(entity.world).blockEntityChanged(entity.pos);
        }));
        assertEquals(3, plate.servings());
    }

    @Test void destructionDuringDeliveryDropsOnlyRemainingContentsOnce() {
        var plate = plate(4);
        List<Item> received = new ArrayList<>();
        assertTrue(plate.takeServing(serving -> {
            received.addAll(serving);
            plate.onRemove();
            plate.onRemove();
        }));
        assertEquals(1, received.stream().mapToInt(Item::count).sum());
        assertEquals(3, countDropped("minecraft:apple"));
        assertEquals(1, countDropped("minecraft:bowl"));
        assertEquals(0, plate.servings());
        assertFalse(plate.takeServing(serving -> fail("Removed plate still serves")));
    }

    @Test void receiverFailureDoesNotRestoreDeliveredFoodAndReleasesTheLock() {
        var plate = plate(3);
        assertThrows(IllegalStateException.class, () -> plate.takeServing(serving -> {
            throw new IllegalStateException("Delivery failed after accepting the item");
        }));
        assertEquals(2, plate.servings());
        assertTrue(plate.takeServing(serving -> assertEquals(1, serving.size())));
        assertEquals(1, plate.servings());
    }

    @Test void partialAndEmptyStatesSurviveSaveReload() {
        var plate = plate(4);
        assertTrue(plate.takeServing(serving -> {}));
        var restored = plate(4);
        restored.loadCustomData(saved(plate));
        assertEquals(3, restored.servings());
        for (int index = 0; index < 3; index++) assertTrue(restored.takeServing(serving -> {}));
        var emptyRestored = plate(4);
        emptyRestored.loadCustomData(saved(restored));
        assertEquals(0, emptyRestored.servings());
        assertFalse(emptyRestored.takeServing(serving -> fail("Empty saved plate refilled itself")));
    }

    @Test void untouchedPlateCanSaveAfterCraftEngineUnloadsItemDefinitions() {
        var fullPlate = plate(4);
        var emptyPlate = plate(0);
        prototypes.clear();
        inventory.clearInvocations();
        for (PlateController plate : List.of(fullPlate, emptyPlate)) {
            int expected = plate == fullPlate ? 4 : 0;
            CompoundTag saved = assertDoesNotThrow(() -> saved(plate));
            assertEquals(expected, plate.servings());
            CompoundTag payload = CookeryBlockStateEnvelope.payload(saved, SAVE);
            assertEquals(2, payload.getInt("schema"));
            assertEquals(4, payload.getInt("max_servings"));
            assertEquals(expected, payload.getInt("remaining"));
            assertEquals("minecraft:apple", payload.getList("portion").getCompound(0)
                    .getCompound("item").getString("fixture_id"));
            assertEquals("minecraft:bowl", payload.getList("containers").getCompound(0)
                    .getCompound("item").getString("fixture_id"));
        }
        inventory.verifyNoInteractions();
    }

    @Test void damagedLegacyServingDoesNotEraseExistingContents() {
        var plate = new PlateController(entity, behavior);
        CompoundTag payload = new CompoundTag();
        payload.putInt("schema", 1);
        payload.putString("serving", PlateServing.of("minecraft:apple", 4, QualityProfile.BASE,
                List.of("minecraft:bowl")).takeOne().encode());
        payload.put("source_item", encode(item("minecraft:apple", 1, "legacy")));
        CompoundTag root = new CompoundTag();
        root.put(SAVE, payload);
        plate.loadCustomData(root);
        assertEquals(3, plate.servings());
        CompoundTag broken = new CompoundTag();
        broken.putInt("schema", 1);
        broken.putString("serving", "{broken-json");
        CookeryBlockStateEnvelope.put(root, SAVE, broken);
        assertThrows(IllegalArgumentException.class, () -> plate.loadCustomData(root));
        assertEquals(3, plate.servings());
    }

    @Test void wrongMetadataTypesRejectTheSaveWithoutChangingContents() {
        var plate = plate(3);
        for (String key : List.of("schema", "data_version")) {
            rejects(plate, payload -> payload.putString(key, "bad-type"));
        }
        rejects(plate, payload -> payload.putFloat("schema", 2.5f));
        CompoundTag before = saved(plate);
        CompoundTag broken = new CompoundTag();
        broken.putString(SAVE, "bad-payload");
        assertThrows(IllegalArgumentException.class, () -> plate.loadCustomData(broken));
        assertEquals(before, saved(plate));
    }

    @Test void missingOrInvalidCountersRejectTheWholeSave() {
        var plate = plate(3);
        for (String key : List.of("max_servings", "remaining")) {
            rejects(plate, payload -> payload.remove(key));
            rejects(plate, payload -> payload.putString(key, "bad-type"));
            rejects(plate, payload -> payload.putFloat(key, 1.5f));
        }
        rejects(plate, payload -> payload.putInt("max_servings", 5));
        rejects(plate, payload -> payload.putInt("remaining", -1));
        rejects(plate, payload -> payload.putInt("remaining", 5));
        rejects(plate, payload -> payload.putInt("schema", 99));
    }

    @Test void invalidPortionOrContainerEnvelopeDoesNotPartiallyReplaceContents() {
        var plate = plate(3);
        for (String key : List.of("portion", "containers")) {
            rejects(plate, payload -> payload.remove(key));
            rejects(plate, payload -> payload.putString(key, "bad-list"));
            rejects(plate, payload -> payload.getList(key).getCompound(0).remove("item"));
            rejects(plate, payload -> payload.getList(key).getCompound(0).putString("item", "bad-item"));
            rejects(plate, payload -> {
                ListTag many = new ListTag();
                for (int index = 0; index < 65; index++) many.add(payload.getList(key).getCompound(0).copy());
                payload.put(key, many);
            });
        }
    }

    @Test void decodedEmptyOversizedForeignOrDuplicateFoodDoesNotOverwriteState() {
        var plate = plate(3);
        for (String key : List.of("portion", "containers")) {
            for (int count : new int[]{0, 2}) {
                rejects(plate, payload -> payload.getList(key).getCompound(0).getCompound("item")
                        .putInt("fixture_count", count));
            }
        }
        rejects(plate, payload -> payload.put("portion", new ListTag()));
        rejects(plate, payload -> payload.getList("portion").getCompound(0).getCompound("item")
                .putString("fixture_id", "minecraft:diamond"));
        rejects(plate, payload -> payload.getList("portion").add(payload.getList("portion").getCompound(0).copy()));
    }

    @Test void lateContainerDecodeAndCopyFailuresLeaveTheOriginalSnapshotIntact() {
        var plate = plate(3);
        CompoundTag before = saved(plate);
        CompoundTag candidate = saved(plate);
        CookeryBlockStateEnvelope.payload(candidate, SAVE).putInt("remaining", 1);
        Item brokenContainer = item("minecraft:bowl", 1, "broken");
        when(brokenContainer.copy()).thenThrow(new IllegalStateException("Cannot copy decoded container"));
        nbt.when(() -> BlockEntityNbt.getItem(any(), eq("item"), anyInt())).thenAnswer(call -> {
            Item decoded = decode(call.<CompoundTag>getArgument(0).getCompound("item"));
            return decoded.id().asString().equals("minecraft:bowl") ? brokenContainer : decoded;
        });
        assertThrows(IllegalStateException.class, () -> plate.loadCustomData(candidate));
        assertEquals(before, saved(plate));
    }

    @Test void missingContainerRejectsConstructionBeforeAnyContentsCanTransfer() {
        Item held = item("minecraft:apple", 2, "held");
        prototypes.remove(Key.of("minecraft:bowl"));
        assertThrows(IllegalArgumentException.class, () -> plate(2));
        assertEquals(2, held.count());
        assertTrue(dropped.isEmpty());
        verify(entity.world, never()).blockEntityChanged(any());
        register("minecraft:bowl");
        var plate = plate(2);
        assertTrue(plate.takeServing(serving -> assertEquals(1, serving.size())));
        assertEquals(1, plate.servings());
    }

    @Test void legacyMigrationKeepsRemainingQualityOriginalItemAndSavedDrops() {
        QualityProfile quality = new QualityProfile("FINE", 1.25);
        Item source = item("minecraft:apple", 1, "saved-food-components");
        var plate = plate(4);
        plate.loadCustomData(legacy("minecraft:apple", 4, 3, quality, source, List.of("minecraft:glass_bottle")));
        assertEquals(3, plate.servings());
        foods.verify(() -> CookeryFoodItems.bakeBite(argThat(item -> "saved-food-components".equals(stamps.get(item))), eq(quality)));
        source.count(64);
        CompoundTag migrated = saved(plate);
        assertEquals(2, CookeryBlockStateEnvelope.payload(migrated, SAVE).getInt("schema"));
        var restored = plate(4);
        restored.loadCustomData(migrated);
        assertEquals(3, restored.servings());
        assertTrue(restored.takeServing(serving -> {
            assertEquals(1, serving.getFirst().count());
            assertEquals("saved-food-components", stamps.get(serving.getFirst()));
        }));
        restored.onRemove();
        assertEquals(2, countDropped("minecraft:apple"));
        assertEquals(1, countDropped("minecraft:glass_bottle"));
        assertEquals(0, countDropped("minecraft:bowl"));
    }

    @Test void legacyMissingSourceCanRebuildWithoutChangingItsRemainingCount() {
        var plate = plate(4);
        plate.loadCustomData(legacy("minecraft:apple", 4, 2, QualityProfile.BASE, null, List.of("minecraft:bowl")));
        assertEquals(2, plate.servings());
        assertTrue(plate.takeServing(serving -> assertEquals(Key.of("minecraft:apple"), serving.getFirst().id())));
        assertEquals(1, plate.servings());
    }

    @Test void invalidLegacyIdentityCountOrPayloadDoesNotAlterCurrentState() {
        var plate = plate(3);
        CompoundTag before = saved(plate);
        List<CompoundTag> broken = List.of(
                legacy("minecraft:diamond", 4, 2, QualityProfile.BASE, item("minecraft:diamond", 1, "bad"), List.of()),
                legacy("minecraft:apple", 5, 2, QualityProfile.BASE, item("minecraft:apple", 1, "bad"), List.of()),
                legacy("minecraft:apple", 4, 2, QualityProfile.BASE, item("minecraft:diamond", 1, "bad"), List.of()),
                legacy("minecraft:apple", 4, 2, QualityProfile.BASE, item("minecraft:apple", 2, "bad"), List.of()));
        for (CompoundTag candidate : broken) {
            assertThrows(IllegalArgumentException.class, () -> plate.loadCustomData(candidate));
            assertEquals(before, saved(plate));
        }
    }

    @Test void legacyPartialBerryPlateDoesNotInventItsPreviouslyMissingSecondItem() {
        when(behavior.servingItems()).thenReturn(List.of(Key.of("minecraft:sweet_berries"), Key.of("minecraft:glow_berries")));
        var plate = plate(4);
        plate.loadCustomData(legacy("minecraft:sweet_berries", 4, 2, QualityProfile.BASE,
                item("minecraft:sweet_berries", 1, "legacy"), List.of("minecraft:bowl")));
        var restored = plate(4);
        restored.loadCustomData(saved(plate));
        assertEquals(2, restored.servings());
        assertTrue(restored.takeServing(serving -> {
            assertEquals(1, serving.size());
            assertEquals(Key.of("minecraft:sweet_berries"), serving.getFirst().id());
        }));
        Item refill = item("minecraft:sweet_berries", 1, "refill");
        assertFalse(restored.refill(refill));
        assertEquals(1, refill.count());
    }

    private void rejects(PlateController plate, Consumer<CompoundTag> corrupt) {
        CompoundTag before = saved(plate);
        CompoundTag candidate = saved(plate);
        corrupt.accept(CookeryBlockStateEnvelope.payload(candidate, SAVE));
        assertThrows(IllegalArgumentException.class, () -> plate.loadCustomData(candidate));
        assertEquals(before, saved(plate));
    }

    private CompoundTag legacy(String id, int maximum, int remaining, QualityProfile quality, Item source, List<String> drops) {
        PlateServing serving = PlateServing.of(id, maximum, quality, drops);
        while (serving.bites().remaining() > remaining) serving = serving.takeOne();
        CompoundTag payload = new CompoundTag();
        payload.putInt("schema", 1);
        payload.putString("serving", serving.encode());
        if (source != null) payload.put("source_item", encode(source));
        CompoundTag root = new CompoundTag();
        root.put(SAVE, payload);
        return root;
    }

    private PlateController plate(int servings) {
        states.when(() -> BlockStates.value(any(), any(), anyInt())).thenReturn(servings);
        return new PlateController(entity, behavior);
    }

    private static CompoundTag saved(PlateController plate) {
        CompoundTag tag = new CompoundTag();
        plate.saveCustomData(tag);
        return tag;
    }

    private int countDropped(String id) {
        return dropped.stream().filter(item -> item.id().asString().equals(id)).mapToInt(Item::count).sum();
    }

    private void register(String id) {
        prototypes.put(Key.of(id), item(id, 1, "prototype"));
    }

    private Item item(String id, int count, String stamp) {
        Item item = mock(Item.class);
        AtomicInteger amount = new AtomicInteger(count);
        stamps.put(item, stamp);
        when(item.id()).thenReturn(Key.of(id));
        when(item.count()).thenAnswer(call -> amount.get());
        when(item.count(anyInt())).thenAnswer(call -> {
            amount.set(call.getArgument(0));
            return item;
        });
        when(item.isEmpty()).thenAnswer(call -> amount.get() <= 0);
        when(item.maxStackSize()).thenReturn(64);
        when(item.copy()).thenAnswer(call -> item(id, amount.get(), stamp));
        when(item.copyWithCount(anyInt())).thenAnswer(call -> item(id, call.getArgument(0), stamp));
        doAnswer(call -> { amount.addAndGet(-call.<Integer>getArgument(0)); return null; }).when(item).shrink(anyInt());
        when(item.isSimilar(any())).thenAnswer(call -> {
            Item other = call.getArgument(0);
            return other != null && item.id().equals(other.id()) && stamp.equals(stamps.get(other));
        });
        return item;
    }

    private CompoundTag encode(Item item) {
        CompoundTag tag = new CompoundTag();
        tag.putString("fixture_id", item.id().asString());
        tag.putInt("fixture_count", item.count());
        tag.putString("fixture_stamp", stamps.get(item));
        return tag;
    }

    private Item decode(CompoundTag tag) {
        if (tag == null) return empty;
        return item(tag.getString("fixture_id"), tag.getInt("fixture_count"), tag.getString("fixture_stamp"));
    }
}
