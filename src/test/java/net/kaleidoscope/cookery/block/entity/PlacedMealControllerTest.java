package net.kaleidoscope.cookery.block.entity;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import net.kaleidoscope.cookery.block.behavior.PlacedMealBehavior;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.catalog.PlacedMealDefinition;
import net.kaleidoscope.cookery.catalog.PlacedMealLayout;
import net.kaleidoscope.cookery.catalog.PlacedMealState;
import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.kaleidoscope.cookery.item.behavior.PlacedMealItemBehavior;
import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.kaleidoscope.cookery.util.BlockStates;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.util.DropUtils;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.PlacementGuard;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlacedMealControllerTest {
    private static final String SAVE = "kaleidoscopecookery:placed_meal";
    private static final BlockPos ORIGIN = new BlockPos(8, 70, 8);
    private final CookeryCatalog.Snapshot catalog = CookeryCatalog.bundled().snapshot();
    private final Item empty = mock(Item.class);
    private final World world = mock(World.class);
    private final CEWorld storage = mock(CEWorld.class, RETURNS_DEEP_STUBS);
    private final RegionScheduler scheduler = mock(RegionScheduler.class);
    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    private final Map<ImmutableBlockState, CellState> blockStates = new IdentityHashMap<>();
    private final Map<Block, BlockPos> blocks = new IdentityHashMap<>();
    private final Map<Key, Item> prototypes = new HashMap<>();
    private final Map<Item, String> stamps = new IdentityHashMap<>();
    private final List<Item> dropped = new ArrayList<>();
    private final List<BlockPos> removed = new ArrayList<>();
    private final List<Scheduled> queued = new ArrayList<>();
    private final List<MockedStatic<?>> handles = new ArrayList<>();
    private MockedStatic<InventoryUtils> inventory;
    private MockedStatic<BlockEntityNbt> nbt;
    private MockedStatic<PlacementGuard> placement;
    private MockedStatic<PlacedMealItemBehavior> itemPlacement;
    private Integer ownedChunkX;

    @BeforeEach void prepare() {
        when(empty.isEmpty()).thenReturn(true);
        when(empty.copy()).thenReturn(empty);
        staticMock(Item.class).when(Item::empty).thenReturn(empty);
        when(storage.world().platformWorld()).thenReturn(world);
        when(storage.getBlockEntityAtIfLoaded(any())).thenAnswer(call -> entities.get(call.getArgument(0)));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Block block = mock(Block.class);
            blocks.put(block, new BlockPos(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            return block;
        });
        var bukkit = staticMock(Bukkit.class);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(eq(world), anyInt(), anyInt()))
                .thenAnswer(call -> ownedChunkX == null || ownedChunkX == call.<Integer>getArgument(1));
        bukkit.when(Bukkit::getRegionScheduler).thenReturn(scheduler);
        doAnswer(call -> { queued.add(new Scheduled(call.getArgument(2), call.getArgument(4))); return null; })
                .when(scheduler).execute(nullable(Plugin.class), eq(world), anyInt(), anyInt(), any(Runnable.class));
        var states = staticMock(BlockStates.class);
        states.when(() -> BlockStates.sync(any(), any())).thenAnswer(call -> {
            call.<BlockEntity>getArgument(0).setBlockState(call.getArgument(1));
            field(call.getArgument(0), "blockState", call.getArgument(1));
            return null;
        });
        inventory = staticMock(InventoryUtils.class);
        inventory.when(() -> InventoryUtils.createOrEmpty(any(Key.class)))
                .thenAnswer(call -> prototypes.getOrDefault(call.getArgument(0), empty));
        nbt = staticMock(BlockEntityNbt.class);
        nbt.when(BlockEntityNbt::newData).thenAnswer(call -> {
            CompoundTag tag = new CompoundTag(); tag.putInt("data_version", 1); return tag;
        });
        nbt.when(() -> BlockEntityNbt.dataVersion(any())).thenReturn(1);
        nbt.when(() -> BlockEntityNbt.itemTag(any())).thenAnswer(call -> encode(call.getArgument(0)));
        nbt.when(() -> BlockEntityNbt.getItem(any(), anyString(), anyInt()))
                .thenAnswer(call -> decode(call.<CompoundTag>getArgument(0).getCompound(call.getArgument(1))));
        staticMock(DropUtils.class).when(() -> DropUtils.dropOnRemove(any(), any())).thenAnswer(call -> {
            dropped.add(call.<Item>getArgument(1).copy()); return null;
        });
        staticMock(CraftEngineBlocks.class).when(() -> CraftEngineBlocks.remove(any(Block.class), isNull(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(call -> {
                    BlockPos pos = blocks.get(call.getArgument(0));
                    removed.add(pos);
                    BlockEntity entity = entities.remove(pos);
                    if (entity != null) entity.controller.onRemove();
                    return true;
                });
        placement = staticMock(PlacementGuard.class);
        itemPlacement = staticMock(PlacedMealItemBehavior.class);
    }

    @AfterEach void close() {
        for (int index = handles.size() - 1; index >= 0; index--) handles.get(index).close();
    }

    @Test void dropDecisionReadsFinalCancellationAndExpiresAfterItsTick() {
        var behavior = behavior("braised_pork_ribs");
        var root = cell(behavior, ORIGIN, "right", 0);
        cell(behavior, ORIGIN.offset(1, 0, 0), "left", 0);
        assertTrue(root.assemble());
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(false);
        root.dropDecision(allowed::get);
        allowed.set(true);
        root.onRemove();
        assertEquals(1, dropped.size());

        dropped.clear();
        var fresh = cell(behavior, ORIGIN, "right", 0);
        cell(behavior, ORIGIN.offset(1, 0, 0), "left", 0);
        assertTrue(fresh.assemble());
        fresh.dropDecision(() -> false);
        when(world.getGameTime()).thenReturn(1L);
        fresh.onRemove();
        assertEquals(1, dropped.size(), "A cancelled break must not suppress a later unrelated removal");
    }

    @Test void assemblyRequiresEveryCellAndSharesOneGroupIdentity() {
        var behavior = behavior("braised_pork_ribs");
        var root = cell(behavior, ORIGIN, "right", 0);
        assertFalse(root.complete());
        assertFalse(root.assemble());
        var left = cell(behavior, new BlockPos(9, 70, 8), "left", 0);
        assertFalse(left.assemble());
        assertTrue(root.assemble());
        assertTrue(root.complete());
        assertEquals(root.group(), left.group());
        assertEquals(root.state(), left.state());
    }

    @Test void foreignReplacementPartCannotCompleteOrConsumeThePreviousGroup() {
        var meal = meal("braised_pork_ribs", ORIGIN);
        var root = meal.getFirst();
        var behavior = behavior("braised_pork_ribs");
        var replacement = cell(behavior, new BlockPos(9, 70, 8), "left", 0);
        assertNotEquals(root.group(), replacement.group());
        assertFalse(root.complete());
        assertFalse(root.consume((food, commit) -> fail("Incomplete meal reached consumer")));
        assertFalse(root.removeMeal());
    }

    @Test void consumptionCommitsExactlyOnceAndRefusesSynchronousReentry() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        assertTrue(root.consume((food, commit) -> {
            assertFalse(root.consume((nested, again) -> fail("Reentrant consumption")));
            assertFalse(root.removeMeal());
            assertTrue(commit.getAsBoolean());
            assertEquals(1, root.state().consumed());
            assertFalse(commit.getAsBoolean());
            return true;
        }));
        assertEquals(1, root.state().consumed());
        assertTrue(dropped.isEmpty());
    }

    @Test void canceledCallbackCannotUseItsCommitAfterReturning() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        AtomicReference<BooleanSupplier> deferred = new AtomicReference<>();
        assertFalse(root.consume((food, commit) -> { deferred.set(commit); return false; }));
        assertFalse(deferred.get().getAsBoolean());
        assertEquals(0, root.state().consumed());
    }

    @Test void oldCanceledCommitCannotRunInsideTheNextConsumption() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        AtomicReference<BooleanSupplier> deferred = new AtomicReference<>();
        assertFalse(root.consume((food, commit) -> { deferred.set(commit); return false; }));
        assertTrue(root.consume((food, commit) -> {
            assertFalse(deferred.get().getAsBoolean());
            assertEquals(0, root.state().consumed());
            return commit.getAsBoolean();
        }));
        assertEquals(1, root.state().consumed());
    }

    @Test void throwingCallbackInvalidatesCommitAndReleasesTheController() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        AtomicReference<BooleanSupplier> deferred = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> root.consume((food, commit) -> {
            deferred.set(commit); throw new IllegalStateException("Consume listener failed");
        }));
        assertFalse(deferred.get().getAsBoolean());
        assertTrue(root.consume((food, commit) -> commit.getAsBoolean()));
        assertEquals(1, root.state().consumed());
    }

    @Test void removalBeforeCommitKeepsTheWholeMealAndCancelsTheBite() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        assertFalse(root.consume((food, commit) -> { root.onRemove(); return commit.getAsBoolean(); }));
        assertEquals(List.of("kaleidoscopecookery:blaze_lamb_chop"), dropIds());
        assertEquals(0, root.state().consumed());
        assertFalse(root.complete());
    }

    @Test void removalAfterCommitDropsOnlyTheSavedContainerAndExtras() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        assertTrue(root.consume((food, commit) -> {
            assertTrue(commit.getAsBoolean()); root.onRemove(); root.onRemove(); return true;
        }));
        assertEquals(List.of("minecraft:bowl", "minecraft:blaze_rod"), dropIds());
        assertEquals(1, root.state().consumed());
    }

    @Test void everyPartRemovesItsWholeGroupWithOneDrop() {
        for (String id : List.of("braised_pork_ribs", "cold_cut_ham_slices")) {
            int cells = id.equals("braised_pork_ribs") ? 2 : 9;
            for (int broken = 0; broken < cells; broken++) {
                entities.clear(); dropped.clear(); removed.clear();
                var meal = meal(id, ORIGIN);
                meal.get(broken).onRemove();
                meal.forEach(PlacedMealController::onRemove);
                assertEquals(List.of("kaleidoscopecookery:" + id), dropIds(), id + "/" + broken);
                assertEquals(cells, removed.stream().distinct().count());
                assertTrue(entities.isEmpty());
            }
        }
    }

    @Test void multiCellBitesSynchronizeAndOnlyConsumedMealExtrasDrop() {
        var meal = meal("braised_pork_ribs", ORIGIN);
        var root = meal.getFirst();
        assertTrue(root.consume((food, commit) -> commit.getAsBoolean()));
        assertTrue(meal.stream().allMatch(member -> member.state().consumed() == 1));
        meal.getLast().onRemove();
        assertEquals(List.of("minecraft:bowl", "minecraft:bone"), dropIds());
    }

    @Test void finalBiteKeepsCompleteEmptyMealUntilExplicitRemoval() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        for (int bite = 0; bite < 3; bite++) assertTrue(root.consume((food, commit) -> commit.getAsBoolean()));
        assertTrue(root.state().empty());
        assertTrue(root.complete());
        assertTrue(dropped.isEmpty());
        assertTrue(removed.isEmpty());
        assertFalse(root.consume((food, commit) -> fail("Empty meal consumed again")));
        assertTrue(root.removeMeal());
        assertEquals(List.of("minecraft:bowl", "minecraft:blaze_rod"), dropIds());
        assertFalse(root.removeMeal());
    }

    @Test void loadedMembersCannotBeAdoptedIntoANewPlacementGroup() {
        var members = meal("braised_pork_ribs", ORIGIN);
        var root = members.getFirst();
        for (var member : members) member.loadCustomData(saved(member));
        assertTrue(root.complete());
        assertFalse(root.assemble());
        UUID original = root.group();
        var fresh = cell(behavior("braised_pork_ribs"), ORIGIN, "right", 0);
        assertNotEquals(original, fresh.group());
        assertFalse(fresh.assemble());
        assertFalse(fresh.complete());
    }

    @Test void pendingPlacementCannotBeConsumedOrRemovedBeforeItsEventFinishes() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        itemPlacement.when(() -> PlacedMealItemBehavior.placing(root.blockEntity())).thenReturn(true);
        assertFalse(root.consume((food, commit) -> fail("Pending placement consumed")));
        assertFalse(root.removeMeal());
        root.onRemove();
        assertTrue(dropped.isEmpty());
        assertTrue(removed.isEmpty());
    }

    @Test void deferredRegionCleanupCannotRemoveANewGroupInTheSameCell() {
        BlockPos anchor = new BlockPos(15, 70, 8);
        var meal = meal("braised_pork_ribs", anchor);
        ownedChunkX = 0;
        meal.getFirst().onRemove();
        assertEquals(1, queued.size());
        assertFalse(removed.contains(new BlockPos(16, 70, 8)));
        assertEquals(1, dropped.size());
        ownedChunkX = 1;
        var replacement = cell(behavior("braised_pork_ribs"), new BlockPos(16, 70, 8), "left", 0);
        queued.getFirst().action().run();
        assertSame(replacement, entities.get(new BlockPos(16, 70, 8)).controller);
        assertFalse(removed.contains(new BlockPos(16, 70, 8)));
    }

    @Test void deferredChildRemovalCannotDestroyAReplacementAnchor() {
        BlockPos anchor = new BlockPos(15, 70, 8);
        var meal = meal("braised_pork_ribs", anchor);
        ownedChunkX = 1;
        meal.getLast().onRemove();
        assertEquals(1, queued.size());
        assertTrue(dropped.isEmpty());
        ownedChunkX = 0;
        var replacement = cell(behavior("braised_pork_ribs"), anchor, "right", 0);
        queued.getFirst().action().run();
        assertSame(replacement, entities.get(anchor).controller);
        assertTrue(dropped.isEmpty());
    }

    @Test void rollbackRemovalNeverDropsOrTouchesOtherCells() {
        var meal = meal("braised_pork_ribs", ORIGIN);
        placement.when(() -> PlacementGuard.isPlacing(any())).thenReturn(true);
        meal.forEach(PlacedMealController::onRemove);
        assertTrue(dropped.isEmpty());
        assertTrue(removed.isEmpty());
        assertTrue(queued.isEmpty());
    }

    @Test void untouchedAndConsumedSnapshotsSaveAfterRegistriesAreUnloaded() {
        var untouched = meal("blaze_lamb_chop", ORIGIN).getFirst();
        var consumed = meal("cold_style_sashimi", new BlockPos(3, 70, 3)).getFirst();
        assertTrue(consumed.consume((food, commit) -> commit.getAsBoolean()));
        CompoundTag wholeBefore = saved(untouched);
        CompoundTag consumedBefore = saved(consumed);
        prototypes.clear(); inventory.clearInvocations();
        assertEquals(wholeBefore, saved(untouched));
        assertEquals(consumedBefore, saved(consumed));
        inventory.verifyNoInteractions();
        consumed.onRemove();
        assertEquals(List.of("minecraft:bowl", "minecraft:snowball", "minecraft:snowball"), dropIds());
    }

    @Test void currentSnapshotRetainsGroupComponentsAndQualityAcrossReload() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        CompoundTag saved = saved(root);
        CompoundTag payload = CookeryBlockStateEnvelope.payload(saved, SAVE);
        var state = PlacedMealState.fresh(behavior("blaze_lamb_chop").definition(), new QualityProfile("POOR", .3));
        payload.putString("state", state.encode());
        payload.getCompound("whole").putString("stamp", "saved-custom-components");
        root.loadCustomData(saved);
        assertEquals(new QualityProfile("POOR", .3), root.state().quality());
        assertEquals(payload.getString("group"), root.group().toString());
        assertEquals(saved, saved(root));
        root.onRemove();
        assertEquals("saved-custom-components", stamps.get(dropped.getFirst()));
    }

    @Test void malformedMetadataAndItemPayloadsNeverReplaceLiveState() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        for (String key : List.of("schema", "data_version", "state", "group", "extras")) {
            rejects(root, payload -> payload.remove(key));
            if (!key.equals("state") && !key.equals("group")) rejects(root, payload -> payload.putString(key, "wrong"));
        }
        rejects(root, payload -> payload.putString("state", "{broken"));
        rejects(root, payload -> payload.putString("state", root.state().consumeOne().encode()));
        rejects(root, payload -> payload.putInt("schema", 2));
        rejects(root, payload -> payload.putFloat("schema", 1.5f));
        rejects(root, payload -> payload.putString("group", "invalid-uuid"));
        rejects(root, payload -> payload.getCompound("whole").putString("id", "minecraft:diamond"));
        rejects(root, payload -> payload.getCompound("whole").putInt("count", 2));
        rejects(root, payload -> payload.remove("whole"));
        rejects(root, payload -> payload.getList("extras").getCompound(0).remove("item"));
        rejects(root, payload -> payload.getList("extras").getCompound(0).getCompound("item").putInt("count", 0));
        rejects(root, payload -> {
            ListTag extra = new ListTag();
            for (int index = 0; index < 65; index++) extra.add(payload.getList("extras").getCompound(0).copy());
            payload.put("extras", extra);
        });
        CompoundTag before = saved(root);
        CompoundTag broken = new CompoundTag();
        broken.putString(SAVE, "wrong-payload-type");
        assertThrows(IllegalArgumentException.class, () -> root.loadCustomData(broken));
        assertEquals(before, saved(root));
    }

    @Test void lateItemCopyFailureCannotPartiallyReplaceAGroupOrFoodSnapshot() {
        var root = meal("blaze_lamb_chop", ORIGIN).getFirst();
        CompoundTag before = saved(root);
        CompoundTag candidate = saved(root);
        CookeryBlockStateEnvelope.payload(candidate, SAVE).putString("group", UUID.randomUUID().toString());
        Item broken = item("minecraft:blaze_rod", 1, "broken");
        when(broken.copy()).thenThrow(new IllegalStateException("Cannot clone saved component"));
        nbt.when(() -> BlockEntityNbt.getItem(any(), anyString(), anyInt())).thenAnswer(call -> {
            Item restored = decode(call.<CompoundTag>getArgument(0).getCompound(call.getArgument(1)));
            return restored.id().asString().equals("minecraft:blaze_rod") ? broken : restored;
        });
        assertThrows(IllegalStateException.class, () -> root.loadCustomData(candidate));
        assertEquals(before, saved(root));
    }

    private void rejects(PlacedMealController controller, Consumer<CompoundTag> corrupt) {
        CompoundTag before = saved(controller);
        CompoundTag candidate = saved(controller);
        corrupt.accept(CookeryBlockStateEnvelope.payload(candidate, SAVE));
        assertThrows(IllegalArgumentException.class, () -> controller.loadCustomData(candidate));
        assertEquals(before, saved(controller));
    }

    private List<PlacedMealController> meal(String name, BlockPos anchor) {
        PlacedMealBehavior behavior = behavior(name);
        List<PlacedMealController> members = new ArrayList<>();
        for (var cell : behavior.definition().layout().cells(new PlacedMealLayout.Pos(anchor.x(), anchor.y(), anchor.z()), PlacedMealLayout.Facing.NORTH)) {
            members.add(cell(behavior, new BlockPos(cell.pos().x(), cell.pos().y(), cell.pos().z()), cell.part(), 0));
        }
        PlacedMealController root = members.stream().filter(member -> member.blockEntity().pos.equals(anchor)).findFirst().orElseThrow();
        assertTrue(root.assemble());
        members.remove(root); members.addFirst(root);
        return List.copyOf(members);
    }

    private PlacedMealBehavior behavior(String name) {
        var definition = PlacedMealDefinition.from(catalog, "kaleidoscopecookery:" + name);
        prototypes.computeIfAbsent(Key.of(definition.itemId()), key -> item(key.asString(), 1, "built"));
        definition.drops().forEach(id -> prototypes.computeIfAbsent(Key.of(id), key -> item(id, 1, "built")));
        PlacedMealBehavior behavior = mock(PlacedMealBehavior.class);
        when(behavior.id()).thenReturn(Key.of(definition.itemId()));
        when(behavior.definition()).thenReturn(definition);
        when(behavior.bites(any())).thenAnswer(call -> blockStates.get(call.getArgument(0)).bites());
        when(behavior.part(any())).thenAnswer(call -> blockStates.get(call.getArgument(0)).part());
        when(behavior.facing(any())).thenReturn(Direction.NORTH);
        when(behavior.anchor(any(), any())).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            var anchor = definition.layout().anchor(new PlacedMealLayout.Pos(pos.x(), pos.y(), pos.z()),
                    PlacedMealLayout.Facing.NORTH, blockStates.get(call.getArgument(1)).part());
            return new BlockPos(anchor.x(), anchor.y(), anchor.z());
        });
        when(behavior.cells(any(), any())).thenAnswer(call -> {
            BlockPos anchor = call.getArgument(0);
            return definition.layout().cells(new PlacedMealLayout.Pos(anchor.x(), anchor.y(), anchor.z()), PlacedMealLayout.Facing.NORTH);
        });
        when(behavior.state(any(), anyString(), anyInt())).thenAnswer(call -> blockState(call.getArgument(1), call.getArgument(2)));
        return behavior;
    }

    private PlacedMealController cell(PlacedMealBehavior behavior, BlockPos pos, String part, int bites) {
        BlockEntity entity = mock(BlockEntity.class);
        field(entity, "pos", pos); field(entity, "blockState", blockState(part, bites)); entity.world = storage;
        PlacedMealController controller = new PlacedMealController(entity, behavior);
        field(entity, "controller", controller); entities.put(pos, entity);
        return controller;
    }

    private ImmutableBlockState blockState(String part, int bites) {
        ImmutableBlockState state = mock(ImmutableBlockState.class);
        blockStates.put(state, new CellState(part, bites));
        return state;
    }

    private static void field(BlockEntity entity, String name, Object value) {
        try { Field field = BlockEntity.class.getField(name); field.setAccessible(true); field.set(entity, value); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static CompoundTag saved(PlacedMealController controller) {
        CompoundTag saved = new CompoundTag(); controller.saveCustomData(saved); return saved;
    }

    private List<String> dropIds() { return dropped.stream().map(item -> item.id().asString()).toList(); }

    private Item item(String id, int count, String stamp) {
        Item item = mock(Item.class);
        AtomicInteger amount = new AtomicInteger(count);
        stamps.put(item, stamp);
        when(item.id()).thenReturn(Key.of(id));
        when(item.count()).thenAnswer(call -> amount.get());
        when(item.count(anyInt())).thenAnswer(call -> { amount.set(call.getArgument(0)); return item; });
        when(item.isEmpty()).thenAnswer(call -> amount.get() <= 0);
        when(item.copy()).thenAnswer(call -> item(id, amount.get(), stamp));
        when(item.copyWithCount(anyInt())).thenAnswer(call -> item(id, call.getArgument(0), stamp));
        return item;
    }

    private CompoundTag encode(Item item) {
        CompoundTag tag = new CompoundTag(); tag.putString("id", item.id().asString());
        tag.putInt("count", item.count()); tag.putString("stamp", stamps.get(item)); return tag;
    }

    private Item decode(CompoundTag tag) {
        return tag == null ? empty : item(tag.getString("id"), tag.getInt("count"), tag.getString("stamp"));
    }

    private <T> MockedStatic<T> staticMock(Class<T> type) {
        MockedStatic<T> handle = mockStatic(type); handles.add(handle); return handle;
    }

    private record CellState(String part, int bites) {}
    private record Scheduled(int chunkX, Runnable action) {}
}
