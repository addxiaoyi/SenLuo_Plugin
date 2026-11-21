package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.block.behavior.EightImmortalsTableBehavior;
import net.kaleidoscope.cookery.block.entity.EightImmortalsTableController;
import net.kaleidoscope.cookery.block.listener.EightImmortalsTableBreakListener;
import net.kaleidoscope.cookery.nms.*;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.bukkit.api.*;
import net.momirealms.craftengine.bukkit.api.event.*;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.context.EventTrigger;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.*;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EightImmortalsTableItemBehaviorTest {
    private static final BlockPos ANCHOR = new BlockPos(8, 70, 8);
    private static final Key ID = Key.of("kaleidoscopecookery:eight_immortals_table");
    private final World world = mock(World.class);
    private final BukkitWorld level = mock(BukkitWorld.class);
    private final CEWorld storage = mock(CEWorld.class);
    private final EightImmortalsTableBehavior table = mock(EightImmortalsTableBehavior.class);
    private final BlockDefinition definition = mock(BlockDefinition.class);
    private final Map<String, ImmutableBlockState> states = new LinkedHashMap<>();
    private final Map<BlockPos, BlockEntity> entities = new LinkedHashMap<>();
    private final Map<BlockPos, Block> blocks = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> restored = new LinkedHashMap<>();
    private final List<MockedStatic<?>> handles = new ArrayList<>();
    private final PluginManager events = mock(PluginManager.class);
    private final List<Event> fired = new ArrayList<>();
    private final NmsBridge bridge = mock(NmsBridge.class);
    private final Player player = mock(Player.class);
    private final org.bukkit.entity.Player actor = mock(org.bukkit.entity.Player.class);
    private final BlockPlaceContext context = mock(BlockPlaceContext.class);
    private final Item held = mock(Item.class), copy = mock(Item.class);
    private final AtomicInteger count = new AtomicInteger(2);
    private EightImmortalsTableItemBehavior item;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<CraftEngineBlocks> ce;
    private MockedStatic<InteractGuard> guard;
    private MockedStatic<BukkitAdaptor> adapters;
    private Consumer<Event> callback = e -> {};
    private BiFunction<BlockPos, ImmutableBlockState, Boolean> writer;
    private int writes;
    private ImmutableBlockState state() { return states.get("right_bottom"); }
    private List<BlockPos> footprint() { return EightImmortalsTableBehavior.PARTS.stream().map(p -> EightImmortalsTableBehavior.cell(ANCHOR, Direction.NORTH, p)).toList(); }

    @BeforeEach void setup() {
        when(definition.id()).thenReturn(ID); when(table.block()).thenReturn(definition);
        when(table.getFirst(EightImmortalsTableBehavior.class)).thenReturn(table);
        when(table.facing(any())).thenReturn(Direction.NORTH);
        when(table.createBlockEntityController(any())).thenAnswer(c -> new EightImmortalsTableController(c.getArgument(0), table));
        for (String part : EightImmortalsTableBehavior.PARTS) {
            var state = mock(ImmutableBlockState.class, RETURNS_DEEP_STUBS);
            when(state.behavior()).thenReturn(table); when(table.part(state)).thenReturn(part); states.put(part, state);
        }
        when(table.partState(any(), anyString())).thenAnswer(c -> states.get(c.getArgument(1)));
        when(table.anchor(any(), any())).thenReturn(ANCHOR);
        when(definition.getStateForPlacement(context)).thenReturn(state());
        when(definition.eventFunctions(EventTrigger.PLACE)).thenReturn(List.of());
        when(level.platformWorld()).thenReturn(world); when(level.storageWorld()).thenReturn(storage); when(storage.world()).thenReturn(level);
        when(storage.getBlockEntityAtIfLoaded(any())).thenAnswer(c -> entities.get(c.getArgument(0)));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true); when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(c -> block(new BlockPos(c.getArgument(0), c.getArgument(1), c.getArgument(2))));
        when(level.getBlock(any(BlockPos.class))).thenAnswer(c -> {
            BlockPos pos = c.getArgument(0); var existing = mock(ExistingBlock.class);
            when(existing.canBeReplaced(context)).thenAnswer(ignore -> !entities.containsKey(pos)); return existing;
        });
        bukkit = staticMock(Bukkit.class);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(eq(world), anyInt(), anyInt())).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(actor)).thenReturn(true); bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        doAnswer(c -> { Event event = c.getArgument(0); fired.add(event); callback.accept(event); return null; }).when(events).callEvent(any());
        adapters = staticMock(BukkitAdaptor.class); adapters.when(() -> BukkitAdaptor.adapt(world)).thenReturn(level);
        ce = staticMock(CraftEngineBlocks.class); ce.when(() -> CraftEngineBlocks.byId(ID)).thenReturn(definition);
        ce.when(() -> CraftEngineBlocks.place(any(Location.class), any(ImmutableBlockState.class), eq(UpdateFlags.UPDATE_ALL), eq(false)))
                .thenAnswer(c -> { Location loc = c.getArgument(0); writes++; return writer.apply(new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), c.getArgument(1)); });
        writer = (pos, state) -> { add(pos, state); return true; };
        guard = staticMock(InteractGuard.class); guard.when(() -> InteractGuard.canPlace(eq(player), eq(level), any())).thenReturn(true);
        staticMock(NmsBridgeProvider.class).when(NmsBridgeProvider::bridge).thenReturn(bridge);
        when(bridge.canPlaceKitchenPart(any(), anyInt(), anyInt(), anyInt(), any(), any())).thenReturn(true);
        var stack = mock(org.bukkit.inventory.ItemStack.class); when(stack.clone()).thenReturn(stack);
        staticMock(InventoryUtils.class).when(() -> InventoryUtils.bukkitCopy(any())).thenReturn(stack);
        staticMock(FoliaUtil.class);
        when(player.platformPlayer()).thenReturn(actor); when(actor.getWorld()).thenReturn(world);
        when(actor.isOnline()).thenReturn(true); when(actor.isValid()).thenReturn(true); when(actor.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(context.getPlayer()).thenReturn(player); when(context.getLevel()).thenReturn(level); when(context.getClickedPos()).thenReturn(ANCHOR);
        when(context.getAgainstPos()).thenReturn(new BlockPos(8, 69, 8)); when(context.getClickedFace()).thenReturn(Direction.UP);
        when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND); when(context.canPlace()).thenReturn(true);
        when(held.id()).thenReturn(ID); when(copy.id()).thenReturn(ID); when(held.count()).thenAnswer(c -> count.get());
        when(held.count(anyInt())).thenAnswer(c -> { count.set(c.getArgument(0)); return held; }); when(held.isEmpty()).thenAnswer(c -> count.get() <= 0);
        when(held.isSimilar(copy)).thenReturn(true); when(held.copyWithCount(1)).thenReturn(copy); when(copy.copy()).thenReturn(copy);
        when(context.getItem()).thenReturn(held); when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(held);
        item = spy(new EightImmortalsTableItemBehavior(ID)); doReturn(true).when(item).canPlace(context, state());
        doNothing().when(item).sendPlaceGameEvent(context, ANCHOR);
    }
    @AfterEach void close() { for (int i = handles.size()-1; i >= 0; i--) handles.get(i).close(); }
    private <T> MockedStatic<T> staticMock(Class<T> type) { var h = mockStatic(type); handles.add(h); return h; }
    private EightImmortalsTableController add(BlockPos pos, ImmutableBlockState state) {
        var entity = new BlockEntity(pos, state); entity.world = storage; entity.setValid(true); entities.put(pos, entity);
        return entity.controller.get(EightImmortalsTableController.class, 0);
    }
    private Block block(BlockPos pos) {
        return blocks.computeIfAbsent(pos, p -> {
            var block = mock(Block.class); var data = mock(BlockData.class);
            when(block.getWorld()).thenReturn(world); when(block.getX()).thenReturn(p.x()); when(block.getY()).thenReturn(p.y()); when(block.getZ()).thenReturn(p.z());
            when(block.getBlockData()).thenReturn(data); when(block.getLocation()).thenReturn(new Location(world, p.x(), p.y(), p.z()));
            when(block.getState()).thenAnswer(c -> {
                var previous = mock(BlockState.class); when(previous.getBlockData()).thenReturn(data); when(previous.getBlock()).thenReturn(block);
                when(previous.getWorld()).thenReturn(world); when(previous.getLocation()).thenReturn(new Location(world, p.x(), p.y(), p.z()));
                when(previous.getX()).thenReturn(p.x()); when(previous.getY()).thenReturn(p.y()); when(previous.getZ()).thenReturn(p.z());
                when(previous.update(true, false)).thenAnswer(call -> {
                    var removed = entities.remove(pos); if (removed != null) removed.controller.onRemove();
                    restored.merge(pos, 1, Integer::sum); return true;
                }); return previous;
            }); return block;
        });
    }
    @Test void fourCellsOneMultiPlaceAndDebitOnlyAfterAllCallbacks() {
        callback = event -> {
            assertEquals(2, count.get()); assertTrue(EightImmortalsTableItemBehavior.placing());
            if (event instanceof BlockMultiPlaceEvent multi) assertEquals(4, multi.getReplacedBlockStates().size());
        };
        assertEquals(InteractionResult.SUCCESS, item.place(context)); assertEquals(1, count.get()); assertEquals(4, entities.size());
        assertEquals(4, fired.stream().filter(CustomBlockAttemptPlaceEvent.class::isInstance).count());
        assertEquals(1, fired.stream().filter(BlockMultiPlaceEvent.class::isInstance).count());
        assertEquals(1, fired.stream().filter(CustomBlockPlaceEvent.class::isInstance).count());
        assertEquals(1, entities.values().stream().map(e -> e.controller.get(EightImmortalsTableController.class, 0).group()).distinct().count());
        assertFalse(EightImmortalsTableItemBehavior.placing());
    }
    @Test void cancelledMultiPlaceRestoresAllFourWithoutDebiting() {
        callback = e -> { if (e instanceof BlockMultiPlaceEvent multi) multi.setCancelled(true); };
        assertEquals(InteractionResult.FAIL, item.place(context)); assertTrue(entities.isEmpty()); assertEquals(2, count.get()); assertEquals(4, restored.size());
    }
    @Test void deniedAttemptWritesNothing() {
        callback = e -> { if (e instanceof CustomBlockAttemptPlaceEvent attempt) attempt.setCancelled(true); };
        assertEquals(InteractionResult.FAIL, item.place(context)); assertEquals(0, writes); assertEquals(2, count.get());
    }
    @Test void cancellationAfterSideReplacementPreservesTheNewBlock() {
        BlockPos side = footprint().get(2);
        callback = e -> { if (e instanceof CustomBlockPlaceEvent placed) { add(side, states.get("right_top")); placed.setCancelled(true); } };
        assertEquals(InteractionResult.FAIL, item.place(context)); assertEquals(Set.of(side), entities.keySet());
        assertFalse(restored.containsKey(side)); assertEquals(2, count.get());
    }
    @Test void failedThirdWriteRollsBackTheFirstTwo() {
        writer = (pos, state) -> { if (writes == 3) return false; add(pos, state); return true; };
        assertEquals(InteractionResult.FAIL, item.place(context)); assertTrue(entities.isEmpty()); assertEquals(2, restored.size()); assertEquals(2, count.get());
    }
    @Test void writeThatMutatesThenThrowsIsAlsoRolledBack() {
        writer = (pos, state) -> { add(pos, state); if (writes == 3) throw new IllegalStateException("failure"); return true; };
        assertThrows(IllegalStateException.class, () -> item.place(context)); assertTrue(entities.isEmpty()); assertEquals(3, restored.size());
        assertFalse(EightImmortalsTableItemBehavior.placing());
    }
    @Test void callbackCannotFundPlacementWithChangedCount() {
        callback = e -> { if (e instanceof CustomBlockPlaceEvent) count.set(1); };
        assertEquals(InteractionResult.FAIL, item.place(context)); assertTrue(entities.isEmpty()); assertEquals(1, count.get());
    }
    @Test void callbackCannotReenterPlacement() {
        callback = e -> assertEquals(InteractionResult.FAIL, item.place(context));
        assertEquals(InteractionResult.SUCCESS, item.place(context)); assertEquals(4, writes);
    }
    @Test void canBuildCallbackCannotReenterBeforeSnapshotsExist() {
        doAnswer(c -> { assertEquals(InteractionResult.FAIL, item.place(context)); return true; }).when(item).canPlace(context, state());
        assertEquals(InteractionResult.SUCCESS, item.place(context)); assertEquals(4, writes);
    }
    @Test void protectionDenialAtAnySidePreventsWrites() {
        BlockPos side = footprint().get(3); guard.when(() -> InteractGuard.canPlace(player, level, side)).thenReturn(false);
        assertEquals(InteractionResult.FAIL, item.place(context)); assertEquals(0, writes); assertEquals(2, count.get());
    }
    @Test void changedPermissionAfterMultiPlaceCausesRollback() {
        callback = e -> { if (e instanceof BlockMultiPlaceEvent) guard.when(() -> InteractGuard.canPlace(eq(player), eq(level), any())).thenReturn(false); };
        assertEquals(InteractionResult.FAIL, item.place(context)); assertTrue(entities.isEmpty()); assertEquals(2, count.get());
    }
    @Test void nativeBorderCollisionOrUsePermissionFailureAtAnySidePreventsWrites() {
        BlockPos side = footprint().get(3);
        when(bridge.canPlaceKitchenPart(any(), eq(side.x()), eq(side.y()), eq(side.z()), any(), any())).thenReturn(false);
        assertEquals(InteractionResult.FAIL, item.place(context)); assertEquals(0, writes);
    }
    @Test void contextualNonreplaceableSideRejectsPlacement() {
        var solid = mock(ExistingBlock.class); when(level.getBlock(footprint().get(1))).thenReturn(solid);
        assertEquals(InteractionResult.FAIL, item.place(context)); assertEquals(0, writes);
    }
    @Test void changedReplaceableSnapshotIsNotOverwritten() {
        callback = e -> { if (e instanceof CustomBlockAttemptPlaceEvent) doReturn(mock(BlockData.class)).when(block(footprint().get(1))).getBlockData(); };
        assertEquals(InteractionResult.FAIL, item.place(context)); assertEquals(0, writes);
    }
    @Test void worldRegionHeightAndInvalidPlayersRejectBeforeWrites() {
        when(world.getMaxHeight()).thenReturn(70); assertEquals(InteractionResult.FAIL, item.place(context)); when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); assertEquals(InteractionResult.FAIL, item.place(context)); when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(eq(world), anyInt(), anyInt())).thenReturn(false);
        assertEquals(InteractionResult.FAIL, item.place(context)); bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(eq(world), anyInt(), anyInt())).thenReturn(true);
        when(actor.getGameMode()).thenReturn(GameMode.SPECTATOR); assertEquals(InteractionResult.FAIL, item.place(context)); when(actor.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(actor.isOnline()).thenReturn(false); assertEquals(InteractionResult.FAIL, item.place(context)); assertEquals(0, writes);
    }
    @Test void creativePlacementDoesNotConsumeAndFunctionsMayCancel() {
        when(player.isCreativeMode()).thenReturn(true);
        assertEquals(InteractionResult.SUCCESS, item.place(context)); assertEquals(2, count.get());
        entities.clear();
        when(definition.eventFunctions(EventTrigger.PLACE)).thenReturn(List.of(c -> c.getParameterOrThrow(DirectContextParameters.EVENT).setCancelled(true)));
        assertEquals(InteractionResult.FAIL, item.place(context)); assertTrue(entities.isEmpty()); assertEquals(2, count.get());
    }
    private CustomBlockBreakEvent breaking() {
        var event = mock(CustomBlockBreakEvent.class);
        when(event.bukkitBlock()).thenReturn(block(ANCHOR)); when(event.getPlayer()).thenReturn(actor); when(event.dropItems()).thenReturn(true);
        guard.when(() -> InteractGuard.canBreak(any(), eq(level), anyInt(), anyInt(), anyInt())).thenReturn(true);
        return event;
    }
    @Test void breakingChecksThreeLinkedCellsAndPropagatesDropSuppression() {
        assertEquals(InteractionResult.SUCCESS, item.place(context)); fired.clear();
        var event = breaking();
        callback = e -> { if (e instanceof BlockBreakEvent linked) linked.setDropItems(false); };
        new EightImmortalsTableBreakListener().breaking(event);
        verify(event, never()).setCancelled(true); verify(event).setDropItems(false);
        assertEquals(3, fired.stream().filter(BlockBreakEvent.class::isInstance).count());
    }
    @Test void breakingCancelledLinkedCellPreservesAllParts() {
        assertEquals(InteractionResult.SUCCESS, item.place(context));
        var event = breaking(); callback = e -> { if (e instanceof BlockBreakEvent linked) linked.setCancelled(true); };
        new EightImmortalsTableBreakListener().breaking(event);
        verify(event).setCancelled(true); assertEquals(4, entities.size());
    }
    @Test void breakingCallbackReplacementCancelsOriginalBreak() {
        assertEquals(InteractionResult.SUCCESS, item.place(context));
        var event = breaking(); callback = e -> { if (e instanceof BlockBreakEvent) add(ANCHOR,state()); };
        new EightImmortalsTableBreakListener().breaking(event); verify(event).setCancelled(true);
    }
    @Test void breakingDuringPlacementCannotDropUnpaidTable() {
        callback = e -> {
            if (e instanceof BlockMultiPlaceEvent) {
                var event = breaking(); new EightImmortalsTableBreakListener().breaking(event); verify(event).setCancelled(true);
            }
        };
        assertEquals(InteractionResult.SUCCESS, item.place(context)); assertEquals(1,count.get());
    }
    @Test void lastBreakPermissionCallbackCannotReplaceAnEarlierMember() {
        assertEquals(InteractionResult.SUCCESS, item.place(context));
        var event = breaking(); var calls = new AtomicInteger();
        guard.when(() -> InteractGuard.canBreak(any(), eq(level), anyInt(), anyInt(), anyInt())).thenAnswer(c -> {
            if (calls.incrementAndGet() == 8) add(footprint().get(1), states.get("left_bottom"));
            return true;
        });
        new EightImmortalsTableBreakListener().breaking(event); verify(event).setCancelled(true);
    }
    @Test void breakingCallbackMayDetachSourceAndMustCancelWithoutAccessingIt() {
        assertEquals(InteractionResult.SUCCESS, item.place(context));
        var event = breaking();
        callback = e -> { if (e instanceof BlockBreakEvent) { var old = entities.remove(ANCHOR); if (old != null) old.world = null; } };
        assertDoesNotThrow(() -> new EightImmortalsTableBreakListener().breaking(event)); verify(event).setCancelled(true);
    }
    @Test void breakingCallbackCannotContinueAfterPlayerDisconnects() {
        assertEquals(InteractionResult.SUCCESS, item.place(context));
        var event = breaking(); callback = e -> { if (e instanceof BlockBreakEvent) when(actor.isOnline()).thenReturn(false); };
        new EightImmortalsTableBreakListener().breaking(event); verify(event).setCancelled(true);
    }
    @Test void linkedEventOnlyBypassesRecursionForItsOwnBlock() {
        assertEquals(InteractionResult.SUCCESS, item.place(context));
        var event = breaking(); var listener = new EightImmortalsTableBreakListener();
        callback = e -> {
            if (e instanceof BlockBreakEvent linked) {
                var nested = breaking(); listener.breaking(nested); verify(nested).setCancelled(true);
                var bridged = breaking(); when(bridged.bukkitBlock()).thenReturn(linked.getBlock());
                listener.breaking(bridged); verify(bridged, never()).setCancelled(true);
            }
        };
        listener.breaking(event); verify(event, never()).setCancelled(true);
    }
}
