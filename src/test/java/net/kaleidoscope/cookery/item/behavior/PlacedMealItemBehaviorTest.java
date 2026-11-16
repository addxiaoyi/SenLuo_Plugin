package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.block.behavior.PlacedMealBehavior;
import net.kaleidoscope.cookery.block.entity.PlacedMealController;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.catalog.PlacedMealDefinition;
import net.kaleidoscope.cookery.catalog.PlacedMealLayout;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.PlacementGuard;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.bukkit.util.LevelUtils;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.context.EventTrigger;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Direction;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlacedMealItemBehaviorTest {
    private static final BlockPos ANCHOR = new BlockPos(8, 70, 8);
    private static final BlockPos LEFT = new BlockPos(9, 70, 8);
    private final World world = mock(World.class);
    private final BukkitWorld level = mock(BukkitWorld.class);
    private final CEWorld storage = mock(CEWorld.class);
    private final PlacedMealBehavior meal = mock(PlacedMealBehavior.class);
    private final ImmutableBlockState state = mock(ImmutableBlockState.class, RETURNS_DEEP_STUBS);
    private final BlockDefinition definition = mock(BlockDefinition.class);
    private final PluginManager events = mock(PluginManager.class);
    private final List<Event> fired = new ArrayList<>();
    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    private final Map<BlockPos, Block> blocks = new HashMap<>();
    private final Map<BlockPos, Integer> restored = new HashMap<>();
    private final List<MockedStatic<?>> handles = new ArrayList<>();
    private BiFunction<BlockPos, Integer, Boolean> placement;
    private int writes;
    private Consumer<Event> callback = event -> {};
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<CraftEngineBlocks> ceBlocks;
    private MockedStatic<InventoryUtils> inventory;
    private MockedStatic<CookeryFoodItems> foods;
    private MockedStatic<InteractGuard> guard;

    @BeforeEach void prepare() {
        var dish = PlacedMealDefinition.from(CookeryCatalog.bundled().snapshot(), "kaleidoscopecookery:braised_pork_ribs");
        when(meal.definition()).thenReturn(dish);
        when(meal.id()).thenReturn(net.momirealms.craftengine.core.util.Key.of(dish.itemId()));
        when(meal.block()).thenReturn(definition);
        when(definition.getStateForPlacement(any())).thenReturn(state);
        when(definition.eventFunctions(EventTrigger.PLACE)).thenReturn(List.of());
        when(state.behavior()).thenReturn(meal);
        when(meal.getFirst(PlacedMealBehavior.class)).thenReturn(meal);
        when(meal.state(any(), anyString(), anyInt())).thenReturn(state);
        when(meal.cells(any(), any())).thenAnswer(call -> {
            BlockPos anchor = call.getArgument(0);
            return dish.layout().cells(new PlacedMealLayout.Pos(anchor.x(), anchor.y(), anchor.z()), PlacedMealLayout.Facing.NORTH);
        });
        when(level.platformWorld()).thenReturn(world);
        when(level.storageWorld()).thenReturn(storage);
        when(storage.getBlockEntityAtIfLoaded(any())).thenAnswer(call -> entities.get(call.getArgument(0)));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> block(new BlockPos(call.getArgument(0), call.getArgument(1), call.getArgument(2))));
        when(world.getBlockAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return block(new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        });
        bukkit = staticMock(Bukkit.class);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(eq(world), anyInt(), anyInt())).thenReturn(true);
        bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        doAnswer(call -> { Event event = call.getArgument(0); fired.add(event); callback.accept(event); return null; })
                .when(events).callEvent(any());
        staticMock(BukkitAdaptor.class).when(() -> BukkitAdaptor.adapt(world)).thenReturn(level);
        staticMock(PlacementGuard.class);
        ceBlocks = staticMock(CraftEngineBlocks.class);
        ceBlocks.when(() -> CraftEngineBlocks.byId(any())).thenReturn(definition);
        ceBlocks.when(() -> CraftEngineBlocks.place(any(Location.class), any(), eq(UpdateFlags.UPDATE_ALL), eq(false)))
                .thenAnswer(call -> {
                    Location location = call.getArgument(0);
                    BlockPos pos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
                    writes++;
                    return placement.apply(pos, writes);
                });
        placement = (pos, count) -> { add(pos, UUID.randomUUID()); return true; };
        guard = staticMock(InteractGuard.class);
        guard.when(() -> InteractGuard.canPlace(any(Player.class), eq(level), any())).thenReturn(true);
        inventory = staticMock(InventoryUtils.class);
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        inventory.when(() -> InventoryUtils.bukkitCopy(any())).thenReturn(item);
        foods = staticMock(CookeryFoodItems.class);
        staticMock(LevelUtils.class);
    }

    @AfterEach void close() {
        for (int index = handles.size() - 1; index >= 0; index--) handles.get(index).close();
    }

    @Test void partialPlacementFailureCannotOverwriteAReplacementInAnEarlierCell() {
        UUID replacement = UUID.randomUUID();
        placement = (pos, count) -> {
            if (count == 1) { add(pos, UUID.randomUUID()); return true; }
            add(ANCHOR, replacement);
            return false;
        };
        assertFalse(PlacedMealItemBehavior.placeAt(location(), state));
        assertNotNull(entities.get(ANCHOR), "Rollback erased the callback's replacement block");
        assertEquals(replacement, entities.get(ANCHOR).controller.get(PlacedMealController.class, 0).group());
        assertEquals(0, restored.getOrDefault(ANCHOR, 0));
        assertEquals(0, restored.getOrDefault(LEFT, 0));
    }

    @Test void thrownLaterPlacementPreservesReplacementAndRollsBackOnlyItsOwnCells() {
        UUID replacement = UUID.randomUUID();
        placement = (pos, count) -> {
            if (count == 1) { add(pos, UUID.randomUUID()); return true; }
            add(ANCHOR, replacement);
            throw new IllegalStateException("Second cell placement failed");
        };
        assertThrows(IllegalStateException.class, () -> PlacedMealItemBehavior.placeAt(location(), state));
        assertNotNull(entities.get(ANCHOR), "Rollback erased the callback's replacement block");
        assertEquals(replacement, entities.get(ANCHOR).controller.get(PlacedMealController.class, 0).group());
        assertEquals(0, restored.getOrDefault(ANCHOR, 0));
    }

    @Test void successfulPlacementHasOneMultiPlaceEventAndChargesAfterEveryCallback() {
        PlayerFixture player = player();
        callback = event -> {
            assertEquals(2, player.held().count());
            assertTrue(PlacedMealItemBehavior.placing());
        };
        assertEquals(InteractionResult.SUCCESS, player.behavior().place(player.context()));
        assertEquals(1, player.held().count());
        assertEquals(2, entities.size());
        assertEquals(2, fired.stream().filter(CustomBlockAttemptPlaceEvent.class::isInstance).count());
        assertEquals(1, fired.stream().filter(BlockPlaceEvent.class::isInstance).count());
        assertEquals(1, fired.stream().filter(BlockMultiPlaceEvent.class::isInstance).count());
        assertEquals(1, fired.stream().filter(CustomBlockPlaceEvent.class::isInstance).count());
        assertFalse(PlacedMealItemBehavior.placing());
        verify(entities.get(ANCHOR).controller).loadCustomDataFromItem(any());
    }

    @Test void cancelledMultiPlaceRestoresOnlyItsOwnCellsWithoutCharging() {
        PlayerFixture player = player();
        callback = event -> { if (event instanceof BlockMultiPlaceEvent placed) placed.setCancelled(true); };
        assertEquals(InteractionResult.FAIL, player.behavior().place(player.context()));
        assertTrue(entities.isEmpty());
        assertEquals(2, player.held().count());
        assertEquals(Map.of(ANCHOR, 1, LEFT, 1), restored);
    }

    @Test void cancellationAfterReplacingASideCellDoesNotOverwriteTheNewGroup() {
        PlayerFixture player = player();
        UUID foreign = UUID.randomUUID();
        callback = event -> {
            if (event instanceof CustomBlockPlaceEvent placed) { add(LEFT, foreign); placed.setCancelled(true); }
        };
        assertEquals(InteractionResult.FAIL, player.behavior().place(player.context()));
        assertNull(entities.get(ANCHOR));
        assertEquals(foreign, entities.get(LEFT).controller.get(PlacedMealController.class, 0).group());
        assertEquals(0, restored.getOrDefault(LEFT, 0));
        assertEquals(2, player.held().count());
    }

    @Test void callbackRemovingAnchorCannotLeaveASideCellOrChargeFood() {
        PlayerFixture player = player();
        callback = event -> { if (event instanceof BlockPlaceEvent) entities.remove(ANCHOR); };
        assertEquals(InteractionResult.FAIL, player.behavior().place(player.context()));
        assertTrue(entities.isEmpty());
        assertEquals(2, player.held().count());
        assertEquals(1, restored.get(LEFT));
    }

    @Test void changedHandOrCountDuringCallbackCannotFundPlacement() {
        PlayerFixture player = player();
        callback = event -> { if (event instanceof CustomBlockPlaceEvent) player.held().count(1); };
        assertEquals(InteractionResult.FAIL, player.behavior().place(player.context()));
        assertTrue(entities.isEmpty());
        assertEquals(1, player.held().count());
    }

    @Test void deniedAttemptAndBadFoodDataNeverWriteTheWorld() {
        PlayerFixture player = player();
        callback = event -> { if (event instanceof CustomBlockAttemptPlaceEvent attempt) attempt.setCancelled(true); };
        assertEquals(InteractionResult.FAIL, player.behavior().place(player.context()));
        assertEquals(0, writes);
        assertEquals(2, player.held().count());
        foods.when(() -> CookeryFoodItems.read(any())).thenThrow(new IllegalArgumentException("Bad food PDC"));
        assertThrows(IllegalArgumentException.class, () -> player.behavior().place(player.context()));
        assertEquals(0, writes);
        assertFalse(PlacedMealItemBehavior.placing());
    }

    @Test void laterProtectionDenialAndForeignRegionDoNotReadOrPlaceTheirBlocks() {
        PlayerFixture player = player();
        guard.when(() -> InteractGuard.canPlace(player.player(), level, LEFT)).thenReturn(false);
        assertEquals(InteractionResult.FAIL, player.behavior().place(player.context()));
        assertEquals(0, writes);
        assertEquals(2, player.held().count());
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, 0)).thenReturn(false);
        clearInvocations(world);
        assertFalse(PlacedMealItemBehavior.placeAt(new Location(world, 15, 70, 8), state));
        verify(world, never()).getBlockAt(16, 70, 8);
    }

    @Test void feedbackFailureAfterDebitPreservesCommittedCellsAndOneCharge() {
        PlayerFixture player = player();
        doThrow(new IllegalStateException("Sound system failed")).when(level).playBlockSound(any(), any(net.momirealms.craftengine.core.sound.SoundData.class));
        assertThrows(IllegalStateException.class, () -> player.behavior().place(player.context()));
        assertEquals(1, player.held().count());
        assertEquals(2, entities.size());
        assertTrue(restored.isEmpty());
        assertFalse(PlacedMealItemBehavior.placing());
    }

    @Test void nestedPlacementCannotConsumeOrCreateAdditionalCells() {
        PlayerFixture player = player();
        callback = event -> {
            assertEquals(InteractionResult.FAIL, player.behavior().place(player.context()));
            assertFalse(PlacedMealItemBehavior.placeAt(location(), state));
        };
        assertEquals(InteractionResult.SUCCESS, player.behavior().place(player.context()));
        assertEquals(2, writes);
        assertEquals(1, player.held().count());
    }

    @Test void placeFunctionCancellationRollsBackBeforeDebit() {
        PlayerFixture player = player();
        when(definition.eventFunctions(EventTrigger.PLACE)).thenReturn(List.of(context ->
                context.getParameterOrThrow(DirectContextParameters.EVENT).setCancelled(true)));
        assertEquals(InteractionResult.FAIL, player.behavior().place(player.context()));
        assertTrue(entities.isEmpty());
        assertEquals(2, player.held().count());
    }

    private PlayerFixture player() {
        Player player = mock(Player.class);
        org.bukkit.entity.Player actor = mock(org.bukkit.entity.Player.class);
        when(player.platformPlayer()).thenReturn(actor);
        BlockPlaceContext context = mock(BlockPlaceContext.class);
        when(context.getPlayer()).thenReturn(player);
        when(context.getLevel()).thenReturn(level);
        when(context.getClickedPos()).thenReturn(ANCHOR);
        when(context.getAgainstPos()).thenReturn(new BlockPos(8, 69, 8));
        when(context.getClickedFace()).thenReturn(Direction.UP);
        when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(context.canPlace()).thenReturn(true);
        AtomicInteger count = new AtomicInteger(2);
        Item held = mock(Item.class);
        Item copy = mock(Item.class);
        var id = meal.id();
        when(held.id()).thenReturn(id);
        when(copy.id()).thenReturn(id);
        when(held.count()).thenAnswer(call -> count.get());
        when(held.count(anyInt())).thenAnswer(call -> { count.set(call.getArgument(0)); return held; });
        when(held.isEmpty()).thenAnswer(call -> count.get() <= 0);
        when(held.isSimilar(copy)).thenReturn(true);
        when(held.copyWithCount(1)).thenReturn(copy);
        when(copy.copy()).thenReturn(copy);
        when(context.getItem()).thenReturn(held);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(held);
        var behavior = spy(new PlacedMealItemBehavior(meal.id()));
        doReturn(true).when(behavior).canPlace(context, state);
        // Paper resolves GameEvent constants through its live registry; transaction tests stop at that boundary.
        doNothing().when(behavior).sendPlaceGameEvent(context, ANCHOR);
        return new PlayerFixture(player, held, context, behavior);
    }

    private record PlayerFixture(Player player, Item held, BlockPlaceContext context, PlacedMealItemBehavior behavior) {}

    private Location location() { return new Location(world, ANCHOR.x(), ANCHOR.y(), ANCHOR.z()); }

    private Block block(BlockPos pos) {
        return blocks.computeIfAbsent(pos, key -> {
            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(pos.x()); when(block.getY()).thenReturn(pos.y()); when(block.getZ()).thenReturn(pos.z());
            when(block.isReplaceable()).thenAnswer(call -> !entities.containsKey(pos));
            when(block.getLocation()).thenReturn(new Location(world, pos.x(), pos.y(), pos.z()));
            when(block.getState()).thenAnswer(call -> {
                BlockState prior = mock(BlockState.class);
                when(prior.getWorld()).thenReturn(world);
                when(prior.getX()).thenReturn(pos.x()); when(prior.getY()).thenReturn(pos.y()); when(prior.getZ()).thenReturn(pos.z());
                when(prior.getLocation()).thenReturn(new Location(world, pos.x(), pos.y(), pos.z()));
                when(prior.getBlock()).thenReturn(block);
                when(prior.update(true, false)).thenAnswer(update -> {
                    restored.merge(pos, 1, Integer::sum); entities.remove(pos); return true;
                });
                return prior;
            });
            return block;
        });
    }

    private PlacedMealController add(BlockPos pos, UUID group) {
        BlockEntity entity = mock(BlockEntity.class);
        PlacedMealController controller = mock(PlacedMealController.class);
        when(controller.behavior()).thenReturn(meal);
        when(controller.blockEntity()).thenReturn(entity);
        when(controller.group()).thenReturn(group);
        when(controller.get(any(), eq(0))).thenAnswer(call -> controller);
        when(controller.assemble()).thenAnswer(call -> {
            for (BlockEntity member : entities.values()) {
                var other = member.controller.get(PlacedMealController.class, 0);
                when(other.group()).thenReturn(group);
            }
            return true;
        });
        when(controller.complete()).thenAnswer(call -> entities.size() == 2 && entities.containsKey(ANCHOR) && entities.containsKey(LEFT));
        field(entity, "controller", controller);
        entity.world = storage;
        field(entity, "pos", pos);
        entities.put(pos, entity);
        PlacedMealItemBehavior.created(controller);
        return controller;
    }

    private static void field(BlockEntity entity, String name, Object value) {
        try { Field field = BlockEntity.class.getField(name); field.setAccessible(true); field.set(entity, value); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private <T> MockedStatic<T> staticMock(Class<T> type) {
        var handle = mockStatic(type); handles.add(handle); return handle;
    }
}
