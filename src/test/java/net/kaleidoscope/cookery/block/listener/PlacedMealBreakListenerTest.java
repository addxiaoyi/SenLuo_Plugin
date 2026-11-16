package net.kaleidoscope.cookery.block.listener;

import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kaleidoscope.cookery.block.entity.PlacedMealController;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import net.kaleidoscope.cookery.catalog.PlacedMealState;
import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlacedMealBreakListenerTest {
    private static final BlockPos ANCHOR = new BlockPos(15, 70, 8);
    private static final BlockPos CHILD = new BlockPos(16, 70, 8);
    private final World world = mock(World.class);
    private final BukkitWorld adaptedWorld = mock(BukkitWorld.class);
    private final CEWorld storage = mock(CEWorld.class);
    private final Player player = mock(Player.class);
    private final BukkitServerPlayer adaptedPlayer = mock(BukkitServerPlayer.class);
    private final PluginManager events = mock(PluginManager.class);
    private final RegionScheduler scheduler = mock(RegionScheduler.class);
    private final Map<BlockPos, Block> blocks = new HashMap<>();
    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    private final Map<BlockPos, PlacedMealController> meals = new HashMap<>();
    private final List<Event> fired = new ArrayList<>();
    private final List<Consumer<ScheduledTask>> cleanup = new ArrayList<>();
    private final UUID group = UUID.randomUUID();
    private final PlacedMealBreakListener listener = new PlacedMealBreakListener();
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<BukkitAdaptor> adaptor;
    private MockedStatic<InteractGuard> protection;
    private Consumer<Event> callback = ignored -> {};
    private PlacedMealController root;

    @BeforeEach void prepare() throws ReflectiveOperationException {
        when(adaptedPlayer.platformPlayer()).thenReturn(player);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(adaptedWorld.storageWorld()).thenReturn(storage);
        when(adaptedWorld.platformWorld()).thenReturn(world);
        when(storage.world()).thenReturn(adaptedWorld);
        when(storage.getBlockEntityAtIfLoaded(any())).thenAnswer(call -> entities.get(call.getArgument(0)));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                blocks.get(new BlockPos(call.getArgument(0), call.getArgument(1), call.getArgument(2))));
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        bukkit.when(Bukkit::getRegionScheduler).thenReturn(scheduler);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(eq(world), anyInt(), anyInt())).thenReturn(true);
        adaptor = mockStatic(BukkitAdaptor.class);
        adaptor.when(() -> BukkitAdaptor.adapt(world)).thenReturn(adaptedWorld);
        adaptor.when(() -> BukkitAdaptor.adapt(player)).thenReturn(adaptedPlayer);
        protection = mockStatic(InteractGuard.class);
        protection.when(() -> InteractGuard.canBreak(eq(adaptedPlayer), eq(adaptedWorld), anyInt(), anyInt(), anyInt()))
                .thenReturn(true);
        doAnswer(call -> {
            Event event = call.getArgument(0);
            fired.add(event);
            callback.accept(event);
            return null;
        }).when(events).callEvent(any());
        when(scheduler.runDelayed(nullable(Plugin.class), any(Location.class), any(), eq(1L))).thenAnswer(call -> {
            cleanup.add(call.getArgument(2));
            return null;
        });
        root = cell(ANCHOR);
        cell(CHILD);
    }

    @AfterEach void close() {
        // Exercise the actual one-tick map cleanup so tests do not retain static BREAKING entries.
        cleanup.forEach(task -> task.accept(mock(ScheduledTask.class)));
        if (protection != null) protection.close();
        if (adaptor != null) adaptor.close();
        if (bukkit != null) bukkit.close();
    }

    @Test void wholeFootprintIsProtectedAndOnlyOtherCellsReceiveSyntheticBukkitEvents() {
        var event = custom(CHILD);
        listener.breaking(event);
        assertFalse(event.isCancelled());
        assertTrue(event.dropItems());
        protection.verify(() -> InteractGuard.canBreak(adaptedPlayer, adaptedWorld, 15, 70, 8));
        protection.verify(() -> InteractGuard.canBreak(adaptedPlayer, adaptedWorld, 16, 70, 8));
        assertEquals(1, fired.size());
        BlockBreakEvent check = assertInstanceOf(BlockBreakEvent.class, fired.getFirst());
        assertSame(blocks.get(ANCHOR), check.getBlock());
        assertSame(player, check.getPlayer());
        assertTrue(fired.stream().noneMatch(CustomBlockBreakEvent.class::isInstance));
    }

    @Test void protectedAnchorCancelsAnOtherwiseAllowedChildBreak() {
        protection.when(() -> InteractGuard.canBreak(adaptedPlayer, adaptedWorld, 15, 70, 8)).thenReturn(false);
        var event = custom(CHILD);
        listener.breaking(event);
        assertTrue(event.isCancelled());
        assertTrue(fired.isEmpty());
        verify(root, never()).dropDecision(any());
    }

    @Test void foreignRegionCellIsRejectedBeforeItsBlockIsRead() {
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 1, 0)).thenReturn(false);
        var event = custom(ANCHOR);
        listener.breaking(event);
        assertTrue(event.isCancelled());
        verify(world, never()).getBlockAt(16, 70, 8);
        assertTrue(fired.isEmpty());
    }

    @Test void missingOrReplacementGroupMemberPreventsWholeDishRemoval() {
        when(meals.get(CHILD).group()).thenReturn(UUID.randomUUID());
        var replaced = custom(ANCHOR);
        listener.breaking(replaced);
        assertTrue(replaced.isCancelled());
        assertTrue(fired.isEmpty());
        entities.remove(CHILD);
        var missing = custom(ANCHOR);
        listener.breaking(missing);
        assertTrue(missing.isCancelled());
        assertTrue(fired.isEmpty());
    }

    @Test void canceledSyntheticBukkitBreakCancelsOuterCustomBreak() {
        callback = event -> ((BlockBreakEvent) event).setCancelled(true);
        var event = custom(ANCHOR);
        listener.breaking(event);
        assertTrue(event.isCancelled());
        assertEquals(1, fired.size());
        assertSame(blocks.get(CHILD), ((BlockBreakEvent) fired.getFirst()).getBlock());
    }

    @Test void syntheticDropSuppressionCarriesIntoTheAcceptedDecision() {
        callback = event -> ((BlockBreakEvent) event).setDropItems(false);
        var event = custom(ANCHOR);
        listener.breaking(event);
        assertFalse(event.isCancelled());
        assertFalse(event.dropItems());
        listener.accepted(event);
        assertFalse(decision(root).getAsBoolean());
    }

    @Test void ceConversionDuringSyntheticBreakDoesNotReenterOrEmitASecondCustomEvent() {
        callback = event -> {
            BlockBreakEvent synthetic = assertInstanceOf(BlockBreakEvent.class, event);
            var converted = custom(synthetic.getBlock() == blocks.get(CHILD) ? CHILD : ANCHOR);
            listener.remember(synthetic);
            listener.breaking(converted);
            listener.accepted(converted);
            assertFalse(PlacedMealBreakListener.removeEmpty(player, root));
        };
        var event = custom(ANCHOR);
        listener.breaking(event);
        assertFalse(event.isCancelled());
        assertEquals(1, fired.size());
        verify(root, never()).dropDecision(any());
        verify(meals.get(CHILD), never()).dropDecision(any());
        verify(root, never()).removeMeal();
    }

    @Test void acceptedDecisionObservesLateBukkitCancellationAndDropFlagChanges() {
        BlockBreakEvent original = new BlockBreakEvent(blocks.get(CHILD), player);
        listener.remember(original);
        var event = custom(CHILD);
        listener.breaking(event);
        listener.accepted(event);
        BooleanSupplier decision = decision(meals.get(CHILD));
        assertTrue(decision.getAsBoolean());
        original.setDropItems(false);
        assertFalse(decision.getAsBoolean());
        original.setCancelled(true);
        // Cancellation prevents this removal; it must not poison a later natural removal in this tick.
        assertTrue(decision.getAsBoolean());
        original.setCancelled(false);
        original.setDropItems(true);
        event.setDropItems(false);
        assertFalse(decision.getAsBoolean());
        event.setCancelled(true);
        assertTrue(decision.getAsBoolean());
    }

    @Test void creativeBreakDoesNotDropUnlessItsPendingRemovalWasCancelled() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        var event = custom(ANCHOR);
        listener.accepted(event);
        BooleanSupplier decision = decision(root);
        assertFalse(decision.getAsBoolean());
        event.setCancelled(true);
        assertTrue(decision.getAsBoolean());
    }

    @Test void emptyRemovalUsesPlayerPermissionAndAnEventForEveryCell() {
        protection.when(() -> InteractGuard.canBreak(adaptedPlayer, adaptedWorld, 15, 70, 8)).thenReturn(false);
        assertFalse(PlacedMealBreakListener.removeEmpty(player, root));
        verify(root, never()).removeMeal();
        protection.when(() -> InteractGuard.canBreak(adaptedPlayer, adaptedWorld, 15, 70, 8)).thenReturn(true);
        when(root.removeMeal()).thenReturn(true);
        assertTrue(PlacedMealBreakListener.removeEmpty(player, root));
        assertEquals(List.of(blocks.get(ANCHOR), blocks.get(CHILD)), fired.stream()
                .map(event -> assertInstanceOf(BlockBreakEvent.class, event).getBlock()).toList());
        assertTrue(decision(root).getAsBoolean());
        verify(root).removeMeal();
    }

    @Test void emptyRemovalCancellationLeavesControllerAndDropDecisionUntouched() {
        callback = event -> ((BlockBreakEvent) event).setCancelled(true);
        assertFalse(PlacedMealBreakListener.removeEmpty(player, root));
        verify(root, never()).removeMeal();
        verify(root, never()).dropDecision(any());
    }

    private PlacedMealController cell(BlockPos pos) throws ReflectiveOperationException {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(pos.x());
        when(block.getY()).thenReturn(pos.y());
        when(block.getZ()).thenReturn(pos.z());
        when(block.getLocation()).thenReturn(new Location(world, pos.x(), pos.y(), pos.z()));
        blocks.put(pos, block);
        PlacedMealController meal = mock(PlacedMealController.class);
        when(meal.group()).thenReturn(group);
        when(meal.complete()).thenReturn(true);
        when(meal.footprint()).thenReturn(List.of(ANCHOR, CHILD));
        when(meal.state()).thenReturn(new PlacedMealState(1, CookeryCatalog.SOURCE_COMMIT,
                "kaleidoscopecookery:braised_pork_ribs", 4, 4, QualityProfile.BASE));
        when(meal.get(PlacedMealController.class, 0)).thenCallRealMethod();
        BlockEntity entity = mock(BlockEntity.class);
        entity.world = storage;
        field(entity, "pos", pos);
        field(entity, "controller", meal);
        when(meal.blockEntity()).thenReturn(entity);
        entities.put(pos, entity);
        meals.put(pos, meal);
        return meal;
    }

    private CustomBlockBreakEvent custom(BlockPos pos) {
        ImmutableBlockState state = mock(ImmutableBlockState.class, RETURNS_DEEP_STUBS);
        return new CustomBlockBreakEvent(adaptedPlayer, blocks.get(pos).getLocation(), blocks.get(pos), state, true);
    }

    private static BooleanSupplier decision(PlacedMealController meal) {
        ArgumentCaptor<BooleanSupplier> captured = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(meal).dropDecision(captured.capture());
        return captured.getValue();
    }

    private static void field(BlockEntity entity, String name, Object value) throws ReflectiveOperationException {
        Field field = BlockEntity.class.getField(name);
        field.setAccessible(true);
        field.set(entity, value);
    }
}
