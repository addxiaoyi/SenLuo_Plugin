package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.plugin.user.BukkitServerPlayer;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StrawLandingTest {
    private final Block block = mock(Block.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final BukkitWorld ceWorld = mock(BukkitWorld.class);
    private final BukkitServerPlayer cePlayer = mock(BukkitServerPlayer.class);
    private final ImmutableBlockState original = mock(ImmutableBlockState.class, RETURNS_DEEP_STUBS);
    private final AtomicReference<ImmutableBlockState> current = new AtomicReference<>();
    private final AtomicReference<Material> material = new AtomicReference<>(Material.NOTE_BLOCK);
    private final PluginManager events = mock(PluginManager.class);
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<BukkitAdaptor> adapt;
    private MockedStatic<CraftEngineBlocks> blocks;
    private MockedStatic<InventoryUtils> inventory;
    private MockedStatic<InteractGuard> protection;
    private MockedStatic<Particles> particles;
    private Consumer<EntityChangeBlockEvent> callback = event -> {};
    private final List<EntityChangeBlockEvent> fired = new ArrayList<>();

    private Item item(Key id, int amount) {
        Item item = mock(Item.class); when(item.id()).thenReturn(id); when(item.count()).thenReturn(amount);
        when(item.isEmpty()).thenReturn(amount==0);
        when(item.copyWithCount(anyInt())).thenAnswer(call -> item(id,call.getArgument(0))); return item;
    }
    @BeforeEach void setup() {
        bukkit=mockStatic(Bukkit.class);adapt=mockStatic(BukkitAdaptor.class);blocks=mockStatic(CraftEngineBlocks.class);
        inventory=mockStatic(InventoryUtils.class);protection=mockStatic(InteractGuard.class);particles=mockStatic(Particles.class);
        when(block.getWorld()).thenReturn(world);when(block.getX()).thenReturn(8);when(block.getY()).thenReturn(64);when(block.getZ()).thenReturn(8);
        when(block.getLocation()).thenReturn(new Location(world,8,64,8));when(block.getType()).thenAnswer(call -> material.get());
        when(block.isEmpty()).thenAnswer(call -> material.get()==Material.AIR);
        when(player.getWorld()).thenReturn(world);when(player.isValid()).thenReturn(true);when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(world.isChunkLoaded(0,0)).thenReturn(true);when(original.owner().value().id()).thenReturn(StrawLanding.STRAW);current.set(original);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(true);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        BlockData air=mock(BlockData.class);bukkit.when(() -> Bukkit.createBlockData(Material.AIR)).thenReturn(air);
        adapt.when(() -> BukkitAdaptor.adapt(world)).thenReturn(ceWorld);adapt.when(() -> BukkitAdaptor.adapt(player)).thenReturn(cePlayer);
        blocks.when(() -> CraftEngineBlocks.getCustomBlockState(block)).thenAnswer(call -> current.get());
        blocks.when(() -> CraftEngineBlocks.remove(block,false)).thenAnswer(call -> {current.set(null);material.set(Material.AIR);return true;});
        inventory.when(() -> InventoryUtils.createOrEmpty(any(Key.class))).thenAnswer(call -> item(call.getArgument(0),1));
        protection.when(() -> InteractGuard.canBreak(cePlayer,ceWorld,8,64,8)).thenReturn(true);
        doAnswer(call -> {var event=(EntityChangeBlockEvent)call.getArgument(0);fired.add(event);callback.accept(event);return null;}).when(events).callEvent(any());
    }
    @AfterEach void close() {particles.close();protection.close();inventory.close();blocks.close();adapt.close();bukkit.close();}
    private void land(double height) {StrawLanding.land(block,player,height,()->0);}

    @Test void probabilityUsesSourceThresholdAndClamp() {
        assertEquals(0,StrawLanding.breakChance(2.999));assertEquals(.1f,StrawLanding.breakChance(3));
        assertEquals(.5f,StrawLanding.breakChance(15));assertEquals(1,StrawLanding.breakChance(30));
        assertEquals(1,StrawLanding.breakChance(Double.POSITIVE_INFINITY));assertEquals(0,StrawLanding.breakChance(Double.NaN));
        assertEquals(0,StrawLanding.breakChance(-9));
    }
    @Test void shortFallPlaysSoundWithoutDrawingOrDestroying() {
        DoubleSupplier random=mock(DoubleSupplier.class);StrawLanding.land(block,player,2.9,random);
        verifyNoInteractions(random);blocks.verify(() -> CraftEngineBlocks.remove(any(),anyBoolean()),never());
        verify(world).playSound(any(Location.class),eq("minecraft:block.grass.fall"),eq(1f),eq(1f));
        inventory.verifyNoInteractions();verifyNoInteractions(ceWorld);
    }
    @Test void equalityAtProbabilityThresholdDoesNotBreak() {
        StrawLanding.land(block,player,15,()->.5);assertSame(original,current.get());assertTrue(fired.isEmpty());
    }
    @Test void successfulFallDropsFivePaniclesAndFourSeedsOnlyOnce() {
        land(30);land(30);assertNull(current.get());assertEquals(1,fired.size());
        ArgumentCaptor<Item> outputs=ArgumentCaptor.forClass(Item.class);
        verify(ceWorld,times(2)).dropItemNaturally(any(Vec3d.class),outputs.capture());
        assertEquals(List.of(5,4),outputs.getAllValues().stream().map(Item::count).toList());
        assertEquals(List.of(Key.of("kaleidoscopecookery:rice_panicle"),Key.of("kaleidoscopecookery:rice_seed")),outputs.getAllValues().stream().map(Item::id).toList());
        particles.verify(() -> Particles.emit(eq(world),eq(Particle.CAMPFIRE_COSY_SMOKE),eq(8.5),eq(64.5),eq(8.5),eq(10),eq(.1),eq(.1),eq(.1),eq(.05),isNull()));
    }
    @Test void eventCancellationKeepsBlockAndProducesNothing() {
        callback=event -> event.setCancelled(true);land(30);assertSame(original,current.get());verifyNoInteractions(ceWorld);
    }
    @Test void eventReplacementDoesNotRemoveTheReplacementOrProduceDrops() {
        callback=event -> current.set(mock(ImmutableBlockState.class));land(30);
        blocks.verify(() -> CraftEngineBlocks.remove(any(),anyBoolean()),never());verifyNoInteractions(ceWorld);
    }
    @Test void callbackReentryCannotDuplicateDestruction() {
        callback=event -> land(30);land(30);assertEquals(1,fired.size());
        blocks.verify(() -> CraftEngineBlocks.remove(block,false),times(1));verify(ceWorld,times(2)).dropItemNaturally(any(Vec3d.class),any(Item.class));
    }
    @Test void deniedProtectionAndSpectatorCannotDestroy() {
        protection.when(() -> InteractGuard.canBreak(cePlayer,ceWorld,8,64,8)).thenReturn(false);land(30);
        protection.when(() -> InteractGuard.canBreak(cePlayer,ceWorld,8,64,8)).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);land(30);
        assertTrue(fired.isEmpty());assertSame(original,current.get());verifyNoInteractions(ceWorld);
    }
    @Test void missingDropPreservesBaleBeforeAnyEvent() {
        Item missing=item(Key.of("kaleidoscopecookery:rice_seed"),0);
        inventory.when(() -> InventoryUtils.createOrEmpty(Key.of("kaleidoscopecookery:rice_seed"))).thenReturn(missing);
        land(30);assertTrue(fired.isEmpty());assertSame(original,current.get());verifyNoInteractions(ceWorld);
    }
    @Test void failedRemovalIncludingCeFalsePositiveCannotCreateDrops() {
        blocks.when(() -> CraftEngineBlocks.remove(block,false)).thenReturn(false);land(30);verifyNoInteractions(ceWorld);
        blocks.when(() -> CraftEngineBlocks.remove(block,false)).thenReturn(true);land(30);verifyNoInteractions(ceWorld);
    }
    @Test void unloadedInvalidOrForeignRegionNeverReadsOrChangesBlock() {
        when(world.isChunkLoaded(0,0)).thenReturn(false);land(30);
        when(world.isChunkLoaded(0,0)).thenReturn(true);when(player.isValid()).thenReturn(false);land(30);
        when(player.isValid()).thenReturn(true);bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);land(30);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(false);land(30);
        blocks.verifyNoInteractions();inventory.verifyNoInteractions();
    }
    @Test void thrownCallbackReleasesTheGuardForNextLanding() {
        callback=event -> {throw new IllegalStateException("listener failure");};assertThrows(IllegalStateException.class,()->land(30));
        callback=event -> {};land(30);assertNull(current.get());verify(ceWorld,times(2)).dropItemNaturally(any(Vec3d.class),any(Item.class));
    }
    @Test void adventureLandingStillFollowsEntityRulesWhenProtectionAllowsIt() {
        when(player.getGameMode()).thenReturn(GameMode.ADVENTURE);land(30);
        assertNull(current.get());verify(ceWorld,times(2)).dropItemNaturally(any(Vec3d.class),any(Item.class));
    }
    @Test void eventChangingProtectionOrRegionCannotContinueDestruction() {
        callback=event -> protection.when(() -> InteractGuard.canBreak(cePlayer,ceWorld,8,64,8)).thenReturn(false);
        land(30);assertSame(original,current.get());verifyNoInteractions(ceWorld);
        protection.when(() -> InteractGuard.canBreak(cePlayer,ceWorld,8,64,8)).thenReturn(true);
        callback=event -> bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);
        land(30);assertSame(original,current.get());verifyNoInteractions(ceWorld);
    }
}
