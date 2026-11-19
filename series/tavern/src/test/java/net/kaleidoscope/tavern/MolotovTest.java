package net.kaleidoscope.tavern;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kaleidoscope.series.Items;
import net.momirealms.antigrieflib.Flag;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Fire;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MolotovTest {
    @BeforeAll static void initialize() { MolotovTestItems.initialize(); }

    @Test void airUseKeepsTheCanonicalSpearComponentWithoutEditingTheBottle() {
        try (var f = new Fixture()) {
            ItemStack before = f.hand();
            f.begin();
            assertEquals(2, f.hand().getAmount()); assertNotNull(f.hand().getData(DataComponentTypes.CONSUMABLE));
            assertSame(before, f.hand()); verify(f.player).startUsingItem(EquipmentSlot.HAND);
            verify(f.inventory, never()).setItem(anyInt(), nullable(ItemStack.class));
            assertTrue(f.balls.isEmpty());
        }
    }
    @Test void legacyItemsWithoutTheCanonicalUseComponentAreRetainedAndRefused() {
        try (var f = new Fixture()) {
            ItemStack legacy = MolotovTestItems.bottle(2); f.slots[0] = legacy; f.begin();
            assertSame(legacy, f.hand()); verify(f.player, never()).startUsingItem(any()); assertTrue(f.balls.isEmpty());
        }
    }
    @Test void naturalUseCompletionNeverConsumesTheBottleOrReturnsGlassware() {
        try (var f = new Fixture()) {
            var event = mock(org.bukkit.event.player.PlayerItemConsumeEvent.class); when(event.getItem()).thenReturn(f.original);
            f.molotov.consume(event); verify(event).setCancelled(true); assertEquals(f.original, f.hand());
            verify(f.inventory, never()).setItem(anyInt(), nullable(ItemStack.class)); assertTrue(f.balls.isEmpty());
        }
    }
    @Test void aNineTickReleasePreservesAllOriginalComponentsAndDoesNotLaunch() {
        try (var f = new Fixture()) { f.begin(); f.release(9); assertEquals(f.original, f.hand()); assertTrue(f.balls.isEmpty()); }
    }
    @Test void tenTicksLaunchExactlyOneNativeThrowableAndConsumeOnlyTheSelectedStack() {
        try (var f = new Fixture()) {
            f.slots[1] = f.original.asQuantity(5); f.begin(); f.release(10);
            assertEquals(1, f.balls.size()); assertEquals(f.original.asQuantity(1), f.hand());
            assertEquals(5, f.slots[1].getAmount());
            Ball ball = f.balls.getFirst(); verify(ball.entity).setPersistent(false); verify(ball.entity).setGravity(true);
            verify(ball.entity).setShooter(f.player); verify(ball.entity).setItem(f.original.asQuantity(1));
            verify(f.world).playSound(eq(f.at), eq(Sound.ENTITY_SNOWBALL_THROW), eq(SoundCategory.PLAYERS), eq(.5f), anyFloat());
        }
    }
    @Test void creativeThrowDoesNotConsumeAHandItem() {
        try (var f = new Fixture()) { when(f.player.getGameMode()).thenReturn(GameMode.CREATIVE); f.begin(); f.release(20); assertEquals(f.original, f.hand()); assertEquals(1, f.balls.size()); }
    }
    @Test void offhandUseAndReleasePreserveTheMainHandAndDebitOnlyTheOffhand() {
        try (var f = new Fixture()) {
            f.slots[40] = f.original.clone(); when(f.event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
            f.begin(); f.release(10);
            assertEquals(f.original, f.slots[0]); assertEquals(1, f.slots[40].getAmount()); assertEquals(1, f.balls.size());
            verify(f.player).startUsingItem(EquipmentSlot.OFF_HAND);
        }
    }
    @Test void blockUseRetainsThePlacedBlockItemPath() {
        try (var f = new Fixture()) { when(f.event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK); f.begin(); assertEquals(f.original, f.hand()); verify(f.player, never()).startUsingItem(any()); }
    }
    @Test void deniedUseOrProtectionDoesNotCreateAnActiveSession() {
        try (var f = new Fixture()) {
            when(f.event.useItemInHand()).thenReturn(Event.Result.DENY); f.begin();
            when(f.event.useItemInHand()).thenReturn(Event.Result.DEFAULT); when(f.plugin.mayUse(eq(f.player), any())).thenReturn(false); f.begin();
            assertEquals(f.original, f.hand()); verify(f.player, never()).startUsingItem(any());
        }
    }
    @Test void staleStopEventAndFullHandDriftCannotSpendAnotherItem() {
        try (var f = new Fixture()) {
            f.begin(); ItemStack other = new MolotovTestItems.Stack(MolotovPhysics.ITEM, 2, "changed-components"); f.slots[0] = other;
            f.release(20); assertSame(other, f.hand()); assertTrue(f.balls.isEmpty());
        }
    }
    @Test void quantityDriftRetainsTheCanonicalComponentAndTheListenersWholeChangedStack() {
        try (var f = new Fixture()) {
            f.begin(); ItemStack changed = f.hand().asQuantity(7); f.slots[0] = changed;
            f.release(20); assertSame(changed, f.hand()); assertNotNull(f.hand().getData(DataComponentTypes.CONSUMABLE));
            assertEquals(7, f.hand().getAmount()); assertTrue(f.balls.isEmpty());
        }
    }
    @Test void selectedSlotDriftCannotConsumeTheOldUnselectedBottle() {
        try (var f = new Fixture()) {
            f.begin(); f.selected = 1; f.release(20);
            assertEquals(f.original, f.slots[0]); assertTrue(f.balls.isEmpty());
        }
    }
    @Test void cancelledLaunchPreservesTheOriginalBottleAndDiscardsTheUncommittedEntity() {
        try (var f = new Fixture()) {
            f.onEvent = event -> { if (event instanceof ProjectileLaunchEvent launch) launch.setCancelled(true); };
            f.begin(); f.release(20); assertEquals(f.original, f.hand()); assertFalse(f.balls.getFirst().valid.get());
        }
    }
    @Test void disablingDuringLaunchDoesNotConsumeTheBottle() {
        try (var f = new Fixture()) {
            f.onEvent = event -> { if (event instanceof ProjectileLaunchEvent) when(f.plugin.isEnabled()).thenReturn(false); };
            f.begin(); f.release(20); assertEquals(f.original, f.hand()); assertFalse(f.balls.getFirst().valid.get());
        }
    }
    @Test void launchCallbackInventoryDriftKeepsTheCallbackItemAndRemovesTheUnpaidFlight() {
        try (var f = new Fixture()) {
            ItemStack replacement = new MolotovTestItems.Stack("minecraft:diamond", 3, "replacement");
            f.onEvent = event -> { if (event instanceof ProjectileLaunchEvent) f.slots[0] = replacement; };
            f.begin(); f.release(20); assertSame(replacement, f.hand()); assertFalse(f.balls.getFirst().valid.get());
        }
    }
    @Test void synchronousReleaseReentryCannotLaunchTwice() {
        try (var f = new Fixture()) {
            f.onEvent = event -> { if (event instanceof ProjectileLaunchEvent) { f.begin(); f.release(20); } };
            f.begin(); f.release(20); assertEquals(1, f.balls.size()); assertEquals(1, f.hand().getAmount());
        }
    }
    @Test void unavailableEntitySchedulerRejectsAnUncommittedLaunch() {
        try (var f = new Fixture()) {
            f.scheduleFlights = false; f.begin(); f.release(20);
            assertEquals(f.original, f.hand()); assertFalse(f.balls.getFirst().valid.get());
        }
    }
    @Test void aCancelledImpactDoesNotDetonateOrRemoveTheFlight() {
        try (var f = new Fixture()) {
            f.begin(); f.release(10); Ball ball = f.balls.getFirst();
            ProjectileHitEvent event = new ProjectileHitEvent(ball.entity); event.setCancelled(true);
            // The annotation is part of the cancellation boundary; Bukkit does not invoke this handler for this event.
            assertTrue(Molotov.class.getMethod("hit", ProjectileHitEvent.class).getAnnotation(org.bukkit.event.EventHandler.class).ignoreCancelled());
            assertTrue(ball.valid.get()); assertTrue(f.ignitions.isEmpty());
        } catch (ReflectiveOperationException failure) { fail(failure); }
    }
    @Test void impactFiresTheSourceLayersAndEffectsExactlyOnceWithoutAnExplosion() {
        try (var f = new Fixture()) {
            f.begin(); f.release(10); Ball ball = f.balls.getFirst();
            f.molotov.hit(new ProjectileHitEvent(ball.entity)); f.molotov.hit(new ProjectileHitEvent(ball.entity));
            assertTrue(f.ignitions.size() >= 29 && f.ignitions.size() <= 49);
            assertEquals(f.ignitions.size(), f.burning.size());
            assertTrue(f.burning.stream().allMatch(key -> key.split(",")[1].equals("64")));
            assertFalse(ball.valid.get());
            verify(f.world, times(1)).playSound(any(Location.class), eq(Sound.ITEM_FIRECHARGE_USE), eq(SoundCategory.BLOCKS), eq(2f), eq(1f));
            verify(f.world, times(1)).playSound(any(Location.class), eq(Sound.BLOCK_GLASS_BREAK), eq(SoundCategory.BLOCKS), eq(2f), eq(1f));
            verify(f.world, times(1)).spawnParticle(eq(Particle.FLAME), any(Location.class), eq(30), eq(3d), eq(1d), eq(3d), eq(.1d));
            verify(f.world, times(1)).spawnParticle(eq(Particle.SMOKE), any(Location.class), eq(20), eq(3d), eq(1d), eq(3d), eq(.1d));
        }
    }
    @Test void cancelledIgnitionsKeepTheWholeColumnUnchanged() {
        try (var f = new Fixture()) {
            f.onEvent = event -> { if (event instanceof BlockIgniteEvent ignite) ignite.setCancelled(true); };
            f.begin(); f.release(10); f.molotov.hit(new ProjectileHitEvent(f.balls.getFirst().entity));
            assertTrue(f.burning.isEmpty()); assertTrue(f.ignitions.size() >= 29);
            assertTrue(f.ignitions.stream().allMatch(event -> event.getBlock().getY() == 64));
        }
    }
    @Test void protectionDenialSuppressesFireButStillConsumesTheAlreadyThrownBottle() {
        try (var f = new Fixture()) {
            f.begin(); f.release(10); when(f.plugin.allows(eq(f.player), eq(Flag.PLACE), any(Location.class))).thenReturn(false);
            f.molotov.hit(new ProjectileHitEvent(f.balls.getFirst().entity));
            assertTrue(f.burning.isEmpty()); assertTrue(f.ignitions.isEmpty()); assertEquals(1, f.hand().getAmount());
        }
    }
    @Test void foreignOrUnloadedFireNeighborsAreNeverReadAsBlockData() {
        try (var f = new Fixture()) {
            f.begin(); f.release(10);
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenAnswer(call -> call.getArgument(0, Location.class).getBlockX() <= 0);
            f.molotov.hit(new ProjectileHitEvent(f.balls.getFirst().entity));
            assertTrue(f.blocks.keySet().stream().allMatch(key -> Integer.parseInt(key.split(",")[0]) < 0));
        }
    }
    @Test void postIgnitionOwnershipDriftCannotPlaceFireUsingAStaleNeighborhoodCheck() {
        try (var f = new Fixture()) {
            f.onEvent = event -> { if (event instanceof BlockIgniteEvent) f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false); };
            f.begin(); f.release(10); f.molotov.hit(new ProjectileHitEvent(f.balls.getFirst().entity)); assertTrue(f.burning.isEmpty());
        }
    }
    @Test void selfDisableOrPlayerLogoutDuringIgnitionCannotWriteFireAfterTheCallback() {
        try (var f = new Fixture()) {
            f.onEvent = event -> { if (event instanceof BlockIgniteEvent) when(f.plugin.isEnabled()).thenReturn(false); };
            f.begin(); f.release(10); f.molotov.hit(new ProjectileHitEvent(f.balls.getFirst().entity)); assertTrue(f.burning.isEmpty());
        }
        try (var f = new Fixture()) {
            f.onEvent = event -> { if (event instanceof BlockIgniteEvent) when(f.player.isOnline()).thenReturn(false); };
            f.begin(); f.release(10); f.molotov.hit(new ProjectileHitEvent(f.balls.getFirst().entity)); assertTrue(f.burning.isEmpty());
        }
    }
    @Test void nativeSnowballDamageIsCancelledIncludingTheBlazeSpecialCase() {
        try (var f = new Fixture()) {
            f.begin(); f.release(10); EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
            when(event.getDamager()).thenReturn(f.balls.getFirst().entity); f.molotov.nativeDamage(event); verify(event).setCancelled(true);
        }
    }
    @Test void nativeProjectileSegmentsHitTheClientOnlyBottleOutlineBeforeTheWallBehindIt() {
        try (var f = new Fixture()) {
            MolotovController target = f.bottleAt(1);
            f.begin(); f.release(10); Ball ball = f.balls.getFirst(); ball.position.add(0, 0, 2);
            f.molotov.hit(new ProjectileHitEvent(ball.entity, mock(Block.class)));
            verify(target).struck(ball.entity); assertFalse(ball.valid.get());
        }
    }
    @Test void aCancelledSyntheticBottleCollisionCannotBreakTheProtectedFurniture() {
        try (var f = new Fixture()) {
            MolotovController target = f.bottleAt(1); Entity base = target.bukkitEntity();
            f.onEvent = event -> { if (event instanceof ProjectileHitEvent hit && hit.getHitEntity() == base) hit.setCancelled(true); };
            f.begin(); f.release(10); Ball ball = f.balls.getFirst(); ball.position.add(0, 0, 2);
            f.molotov.hit(new ProjectileHitEvent(ball.entity, mock(Block.class)));
            verify(target, never()).struck(any());
        }
    }
    @Test void ordinaryProjectilesCanTriggerPlacedBottlesWithoutBecomingMolotovsThemselves() {
        try (var f = new Fixture()) {
            MolotovController target = f.bottleAt(1); Ball arrow = new Ball(f.at, true);
            f.molotov.launched(new ProjectileLaunchEvent(arrow.entity)); arrow.position.add(0, 0, 2);
            f.molotov.hit(new ProjectileHitEvent(arrow.entity, mock(Block.class)));
            verify(target).struck(arrow.entity); assertTrue(arrow.valid.get()); assertTrue(f.ignitions.isEmpty());
        }
    }
    @Test void startupRecoversAlreadyLoadedFurnitureBeforeTracingExistingProjectilesAndDoesNotReloadItTwice() throws Exception {
        try (var f = new Fixture(); var api = mockStatic(CraftEngineFurniture.class)) {
            MolotovController target = f.unindexedBottleAt(1);
            BukkitFurniture furniture = (BukkitFurniture) target.furniture();
            setController(furniture, target);
            Entity base = target.bukkitEntity();
            api.when(() -> CraftEngineFurniture.getLoadedFurnitureByMetaEntity(base)).thenReturn(furniture);
            Ball arrow = new Ball(f.at, true);
            f.startupEntities(target.bukkitEntity(), arrow.entity);

            f.molotov.start(); f.molotov.start();
            arrow.position.add(0, 0, 2);
            f.molotov.hit(new ProjectileHitEvent(arrow.entity, mock(Block.class)));

            verify(target, times(1)).onLoad(); verify(target).struck(arrow.entity);
            assertTrue(arrow.valid.get()); assertTrue(f.ignitions.isEmpty());
        }
    }
    @Test void chunkLoadRecoveryFindsTheMolotovControllerInAnAbsoluteCompositeBehaviorSlot() throws Exception {
        try (var f = new Fixture(); var api = mockStatic(CraftEngineFurniture.class)) {
            MolotovController target = f.unindexedBottleAt(1);
            BukkitFurniture furniture = (BukkitFurniture) target.furniture();
            FurnitureController composite = mock(FurnitureController.class);
            FurnitureDefinition definition = mock(FurnitureDefinition.class);
            when(furniture.config()).thenReturn(definition);
            when(definition.behaviors()).thenReturn(List.of(mock(net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate.class),
                    mock(net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate.class)));
            when(composite.get(MolotovController.class, 1)).thenReturn(target);
            setController(furniture, composite);
            Entity base = target.bukkitEntity();
            api.when(() -> CraftEngineFurniture.getLoadedFurnitureByMetaEntity(base)).thenReturn(furniture);
            var event = new EntitiesLoadEvent(mock(Chunk.class), List.of(target.bukkitEntity()));

            f.molotov.loadedEntities(event); f.molotov.loadedEntities(event);

            verify(target, times(1)).onLoad(); verify(composite, times(2)).get(MolotovController.class, 1);
        }
    }
    @Test void startupRecoverySkipsInvalidAndForeignRegionFurniture() throws Exception {
        try (var f = new Fixture(); var api = mockStatic(CraftEngineFurniture.class)) {
            MolotovController target = f.unindexedBottleAt(1);
            BukkitFurniture furniture = (BukkitFurniture) target.furniture();
            setController(furniture, target);
            Entity base = target.bukkitEntity();
            api.when(() -> CraftEngineFurniture.getLoadedFurnitureByMetaEntity(base)).thenReturn(furniture);
            var event = new EntitiesLoadEvent(mock(Chunk.class), List.of(target.bukkitEntity()));
            when(furniture.isValid()).thenReturn(false); f.molotov.loadedEntities(event);
            when(furniture.isValid()).thenReturn(true);
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
            f.molotov.loadedEntities(event);

            verify(target, never()).onLoad();
        }
    }
    @Test void rackFlightsKeepTheirProtectionActorEvenThoughTheSourceHasNoNativeShooter() {
        try (var f = new Fixture()) {
            assertTrue(f.molotov.launch(f.player, false, f.at, new Vector(), f.original.asQuantity(1), () -> true));
            Ball ball = f.balls.getFirst(); verify(ball.entity).setShooter(null);
            assertSame(f.player, f.molotov.responsibleFor(ball.entity));
        }
    }
    @Test void quitKeepsPermanentComponentsAndPreventsALateStopEvent() {
        try (var f = new Fixture()) { f.begin(); f.molotov.quit(new PlayerQuitEvent(f.player, net.kyori.adventure.text.Component.empty(), org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED)); f.release(20); assertEquals(f.original, f.hand()); assertTrue(f.balls.isEmpty()); }
    }
    @Test void closeKeepsActiveHandsAndRemovesPaidTransientFlightsWithoutRefunding() {
        try (var f = new Fixture()) { f.begin(); f.molotov.close(); assertEquals(f.original, f.hand()); }
        try (var f = new Fixture()) { f.begin(); f.release(10); f.molotov.close(); assertFalse(f.balls.getFirst().valid.get()); assertEquals(1, f.hand().getAmount()); }
    }

    private static void setController(Furniture furniture, FurnitureController controller) throws ReflectiveOperationException {
        var field = Furniture.class.getField("controller"); field.setAccessible(true); field.set(furniture, controller);
    }

    private static final class Ball {
        final Snowball entity = mock(Snowball.class);
        final AtomicBoolean valid = new AtomicBoolean(true);
        final PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        final Map<NamespacedKey, Object> data = new HashMap<>();
        final Location position;
        Ball(Location at, boolean scheduling) {
            position = at.clone();
            when(entity.getUniqueId()).thenReturn(UUID.randomUUID()); when(entity.isValid()).thenAnswer(call -> valid.get());
            when(entity.getLocation()).thenAnswer(call -> position.clone()); when(entity.getWorld()).thenReturn(at.getWorld());
            when(entity.getPersistentDataContainer()).thenReturn(pdc);
            doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(2)); return null; }).when(pdc).set(any(), any(), any());
            when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenAnswer(call -> data.containsKey(call.getArgument(0)));
            doAnswer(call -> { valid.set(false); return null; }).when(entity).remove();
            EntityScheduler scheduler = mock(EntityScheduler.class); when(entity.getScheduler()).thenReturn(scheduler);
            when(scheduler.runAtFixedRate(any(), any(), any(), eq(1L), eq(1L))).thenReturn(scheduling ? mock(ScheduledTask.class) : null);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TavernPlugin plugin = mock(TavernPlugin.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final World world = mock(World.class);
        final PluginManager manager = mock(PluginManager.class);
        final Location at = new Location(world, .5, 64.5, .5);
        final ItemStack original = MolotovTestItems.bottle(2);
        final ItemStack[] slots = new ItemStack[41];
        final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        final Consumable consumable = mock(Consumable.class);
        final MockedStatic<Items> items = mockStatic(Items.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final List<Ball> balls = new ArrayList<>();
        final List<BlockIgniteEvent> ignitions = new ArrayList<>();
        final Set<String> burning = new HashSet<>();
        final Map<String, Block> blocks = new HashMap<>();
        final Molotov molotov;
        Consumer<Event> onEvent = ignored -> {};
        boolean scheduleFlights = true, active;
        int selected;
        Fixture() {
            when(consumable.consumeSeconds()).thenReturn(3600f); when(consumable.animation()).thenReturn(ItemUseAnimation.SPEAR);
            original.setData(DataComponentTypes.CONSUMABLE, consumable);
            slots[0] = original.clone();
            when(plugin.getName()).thenReturn("KaleidoscopeTavernPlugin"); when(plugin.namespace()).thenReturn("kaleidoscopetavernplugin");
            when(plugin.isEnabled()).thenReturn(true); when(plugin.mayUse(eq(player), any())).thenReturn(true);
            when(plugin.allows(eq(player), any(), any())).thenReturn(true);
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; }).when(plugin).onPlayer(eq(player), any());
            when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
            when(player.getInventory()).thenReturn(inventory); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getLocation()).thenAnswer(call -> at.clone()); when(player.getEyeLocation()).thenAnswer(call -> at.clone().add(0, .1, 0));
            when(player.getVelocity()).thenReturn(new Vector()); when(player.getWorld()).thenReturn(world);
            when(inventory.getHeldItemSlot()).thenAnswer(call -> selected);
            when(inventory.getItem(anyInt())).thenAnswer(call -> slots[(int) call.getArgument(0)]);
            when(inventory.getItem(any(EquipmentSlot.class))).thenAnswer(call -> slots[call.getArgument(0) == EquipmentSlot.OFF_HAND ? 40 : selected]);
            when(inventory.getItemInMainHand()).thenAnswer(call -> slots[selected]);
            doAnswer(call -> { slots[(int) call.getArgument(0)] = call.getArgument(1); return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
            when(event.getPlayer()).thenReturn(player); when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND); when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
            when(event.getItem()).thenAnswer(call -> slots[event.getHand() == EquipmentSlot.OFF_HAND ? 40 : selected]);
            when(player.hasActiveItem()).thenAnswer(call -> active);
            when(player.getActiveItem()).thenAnswer(call -> hand());
            doAnswer(call -> { active = true; return null; }).when(player).startUsingItem(any());
            doAnswer(call -> { active = false; return null; }).when(player).clearActiveItem();
            EntityScheduler scheduler = mock(EntityScheduler.class); when(player.getScheduler()).thenReturn(scheduler);
            when(scheduler.runAtFixedRate(any(), any(), any(), eq(1L), eq(1L))).thenReturn(mock(ScheduledTask.class));
            when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getBlockAt(any(Location.class))).thenAnswer(call -> block(call.getArgument(0)));
            when(world.spawn(any(Location.class), eq(Snowball.class), org.mockito.ArgumentMatchers.<Consumer<Snowball>>any())).thenAnswer(call -> {
                Ball ball = new Ball(call.getArgument(0), scheduleFlights); balls.add(ball);
                call.<Consumer<Snowball>>getArgument(2).accept(ball.entity);
                ProjectileLaunchEvent launch = new ProjectileLaunchEvent(ball.entity); manager.callEvent(launch);
                if (launch.isCancelled()) ball.valid.set(false);
                return ball.entity;
            });
            Fire fire = mock(Fire.class); when(fire.getAllowedFaces()).thenReturn(Set.of());
            bukkit.when(() -> Bukkit.createBlockData(Material.FIRE)).thenReturn(fire);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            doAnswer(call -> { Event e = call.getArgument(0); if (e instanceof BlockIgniteEvent ignite) ignitions.add(ignite); onEvent.accept(e); return null; }).when(manager).callEvent(any(Event.class));
            items.when(() -> Items.id(any())).thenAnswer(call -> call.getArgument(0) instanceof MolotovTestItems.Stack s ? s.id : "minecraft:air");
            molotov = new Molotov(plugin);
        }
        Block block(Location at) {
            String key = at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
            return blocks.computeIfAbsent(key, ignored -> {
                Block block = mock(Block.class), below = mock(Block.class);
                when(block.getType()).thenReturn(Material.AIR); when(block.getY()).thenReturn(at.getBlockY());
                when(block.getLocation()).thenReturn(at.clone()); when(block.getRelative(BlockFace.DOWN)).thenReturn(below);
                when(below.getType()).thenReturn(Material.STONE); when(block.canPlace(any(BlockData.class))).thenReturn(at.getBlockY() == 64);
                doAnswer(call -> { burning.add(key); return null; }).when(block).setBlockData(any(), eq(true));
                return block;
            });
        }
        ItemStack hand() { return slots[event.getHand() == EquipmentSlot.OFF_HAND ? 40 : selected]; }
        MolotovController bottleAt(int z) {
            MolotovController target = unindexedBottleAt(z); molotov.loaded(target); return target;
        }
        MolotovController unindexedBottleAt(int z) {
            MolotovController target = mock(MolotovController.class);
            var furniture = mock(BukkitFurniture.class);
            when(furniture.uuid()).thenReturn(UUID.randomUUID()); when(target.furniture()).thenReturn(furniture);
            when(furniture.isValid()).thenReturn(true);
            when(target.ground()).thenReturn(true); when(target.usable()).thenReturn(true);
            when(target.location()).thenReturn(new Location(world, 0, 64, z)); when(target.bukkitEntity()).thenReturn(mock(Entity.class));
            when(target.struck(any())).thenReturn(true);
            doAnswer(call -> { molotov.loaded(target); return null; }).when(target).onLoad();
            return target;
        }
        void startupEntities(Entity... entities) {
            Chunk chunk = mock(Chunk.class); when(chunk.isLoaded()).thenReturn(true); when(chunk.getEntities()).thenReturn(entities);
            when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk}); bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            RegionScheduler scheduler = mock(RegionScheduler.class); bukkit.when(Bukkit::getRegionScheduler).thenReturn(scheduler);
            doAnswer(call -> { call.getArgument(4, Runnable.class).run(); return null; }).when(scheduler)
                    .execute(eq(plugin), eq(world), anyInt(), anyInt(), any(Runnable.class));
        }
        void begin() { molotov.use(event); }
        void release(int ticks) { ItemStack item = hand(); active = false; molotov.release(new PlayerStopUsingItemEvent(player, item, ticks)); }
        @Override public void close() { molotov.close(); bukkit.close(); items.close(); }
    }
}
