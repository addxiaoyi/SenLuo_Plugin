package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.kaleidoscope.cookery.util.*;
import net.kaleidoscope.compat.Rules;
import net.momirealms.craftengine.bukkit.api.*;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HangingStringHarvestTest {
    @BeforeAll static void initializeGameRules() {
        try(var access=mockStatic(io.papermc.paper.registry.RegistryAccess.class);
            var bridge=mockStatic(io.papermc.paper.InternalAPIBridge.class)) {
            var internal=mock(io.papermc.paper.InternalAPIBridge.class);
            bridge.when(io.papermc.paper.InternalAPIBridge::get).thenReturn(internal);
            var provider=mock(io.papermc.paper.registry.RegistryAccess.class,c->mock(org.bukkit.Registry.class));
            access.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(provider);
            // Other no-server tests may already have initialized Registry with empty mocks.
            var registry = Registry.GAME_RULE;
            doAnswer(c->mock(GameRule.class)).when(registry).getOrThrow(any(net.kyori.adventure.key.Key.class));
            assertNotNull(Rules.DO_TILE_DROPS);
        }
    }
    private static final Key CHILI=Key.of("kaleidoscopecookery:red_chili"), SOUND=Key.of("minecraft:block.sweet_berry_bush.pick_berries");
    private final Block block=mock(Block.class);
    private final World world=mock(World.class);
    private final org.bukkit.entity.Player player=mock(org.bukkit.entity.Player.class);
    private final Player cePlayer=mock(Player.class);
    private final BukkitWorld ceWorld=mock(BukkitWorld.class);
    private final Property<Boolean> sheared=mock(Property.class);
    private final ImmutableBlockState full=mock(ImmutableBlockState.class), half=mock(ImmutableBlockState.class);
    private final BlockData data=mock(BlockData.class);
    private final AtomicReference<ImmutableBlockState> current=new AtomicReference<>();
    private final AtomicReference<Item> hand=new AtomicReference<>();
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<BukkitAdaptor> adapt;
    private MockedStatic<CraftEngineBlocks> blocks;
    private MockedStatic<InventoryUtils> inventory;
    private MockedStatic<InteractGuard> protection;
    private MockedStatic<Particles> particles;
    private Item item(Key id,int count) {
        Item result=mock(Item.class);when(result.id()).thenReturn(id);when(result.count()).thenReturn(count);
        when(result.isEmpty()).thenReturn(count==0);when(result.copyWithCount(anyInt())).thenAnswer(c->item(id,c.getArgument(0)));return result;
    }
    @BeforeEach void setup() {
        bukkit=mockStatic(Bukkit.class);adapt=mockStatic(BukkitAdaptor.class);blocks=mockStatic(CraftEngineBlocks.class);
        inventory=mockStatic(InventoryUtils.class);protection=mockStatic(InteractGuard.class);particles=mockStatic(Particles.class);
        current.set(full);hand.set(item(CHILI,0));
        when(block.getWorld()).thenReturn(world);when(block.getX()).thenReturn(8);when(block.getY()).thenReturn(64);when(block.getZ()).thenReturn(8);
        when(block.getLocation()).thenReturn(new Location(world,8,64,8));when(block.isEmpty()).thenAnswer(c->current.get()==null);
        when(world.isChunkLoaded(0,0)).thenReturn(true);when(player.getWorld()).thenReturn(world);when(player.isValid()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);when(cePlayer.platformPlayer()).thenReturn(player);
        when(cePlayer.getItemInHand(InteractionHand.MAIN_HAND)).thenAnswer(c->hand.get());
        doAnswer(c->{hand.set(c.getArgument(1));return null;}).when(cePlayer).setItemInHand(eq(InteractionHand.MAIN_HAND),any());
        when(full.get(sheared)).thenReturn(false);when(full.with(sheared,true)).thenReturn(half);when(half.get(sheared)).thenReturn(true);
        bukkit.when(()->Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(true);bukkit.when(()->Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
        adapt.when(()->BukkitAdaptor.adapt(world)).thenReturn(ceWorld);
        protection.when(()->InteractGuard.canBreak(cePlayer,ceWorld,8,64,8)).thenReturn(true);
        blocks.when(()->CraftEngineBlocks.getCustomBlockState(block)).thenAnswer(c->current.get());
        blocks.when(()->CraftEngineBlocks.getBukkitBlockData(any(ImmutableBlockState.class))).thenReturn(data);
        blocks.when(()->CraftEngineBlocks.place(block.getLocation(),half,false,false)).thenAnswer(c->{current.set(half);return true;});
        blocks.when(()->CraftEngineBlocks.remove(block,false)).thenAnswer(c->{current.set(null);return true;});
        inventory.when(()->InventoryUtils.createOrEmpty(CHILI)).thenAnswer(c->item(CHILI,1));
    }
    @AfterEach void close(){particles.close();protection.close();inventory.close();blocks.close();adapt.close();bukkit.close();}
    private boolean pick(ImmutableBlockState state){return HangingStringHarvest.pick(block,cePlayer,state,sheared,CHILI,3,SOUND);}
    private void collapse(){HangingStringHarvest.collapse(block,current.get(),sheared,CHILI,3,()->false);}

    @Test void twoPicksCommitBeforeDeliveryAndGiveExactlySix() {
        assertTrue(pick(full));assertSame(half,current.get());assertEquals(3,hand.get().count());
        inventory.when(()->InventoryUtils.give(eq(cePlayer),any(Item.class))).thenAnswer(c->{assertNull(current.get());assertEquals(3,((Item)c.getArgument(1)).count());return null;});
        assertTrue(pick(half));assertNull(current.get());
        inventory.verify(()->InventoryUtils.give(eq(cePlayer),any(Item.class)),times(1));
        verify(cePlayer,times(1)).setItemInHand(eq(InteractionHand.MAIN_HAND),any());
        particles.verify(()->Particles.emit(world,Particle.BLOCK,8.5,64.5,8.5,20,.25,.25,.25,.05,data),times(2));
    }
    @Test void failedOrFalselyReportedMutationCannotProduceItems() {
        blocks.when(()->CraftEngineBlocks.place(block.getLocation(),half,false,false)).thenReturn(false);
        current.set(full);
        assertFalse(pick(full));blocks.when(()->CraftEngineBlocks.place(block.getLocation(),half,false,false)).thenReturn(true);
        assertFalse(pick(full));current.set(half);blocks.when(()->CraftEngineBlocks.remove(block,false)).thenReturn(true);
        current.set(half);
        assertFalse(pick(half));verify(cePlayer,never()).setItemInHand(any(),any());particles.verifyNoInteractions();
    }
    @Test void missingHarvestRetainsBothStages() {
        inventory.when(()->InventoryUtils.createOrEmpty(CHILI)).thenAnswer(c->item(CHILI,0));
        assertFalse(pick(full));assertSame(full,current.get());current.set(half);assertFalse(pick(half));assertSame(half,current.get());
    }
    @Test void staleCallbackCannotPickAReplacement() {
        current.set(half);assertFalse(pick(full));assertSame(half,current.get());inventory.verifyNoInteractions();
    }
    @Test void mutationReentryCannotHarvestSecondStage() {
        blocks.when(()->CraftEngineBlocks.place(block.getLocation(),half,false,false)).thenAnswer(c->{current.set(half);assertFalse(pick(half));collapse();assertSame(half,current.get());return true;});
        current.set(full);
        assertTrue(pick(full));assertSame(half,current.get());assertEquals(3,hand.get().count());verifyNoInteractions(ceWorld);
    }
    @Test void inventoryDeliveryReentryCannotConsumeSecondStage() {
        hand.set(item(CHILI,1));inventory.when(()->InventoryUtils.give(eq(cePlayer),any(Item.class))).thenAnswer(c->{assertSame(half,current.get());assertFalse(pick(half));return null;});
        assertTrue(pick(full));assertSame(half,current.get());
    }
    @Test void protectionCallbackChangingBlockOrHandIsRechecked() {
        protection.when(()->InteractGuard.canBreak(cePlayer,ceWorld,8,64,8)).thenAnswer(c->{current.set(half);return true;});
        assertFalse(pick(full));current.set(full);
        protection.when(()->InteractGuard.canBreak(cePlayer,ceWorld,8,64,8)).thenAnswer(c->{hand.set(item(Key.of("minecraft:stone"),1));return true;});
        current.set(full);
        assertFalse(pick(full));assertSame(full,current.get());inventory.verifyNoInteractions();
    }
    @Test void itemConstructionCallbackCannotBypassStateValidation() {
        inventory.when(()->InventoryUtils.createOrEmpty(CHILI)).thenAnswer(c->{current.set(half);return item(CHILI,1);});
        assertFalse(pick(full));verify(cePlayer,never()).setItemInHand(any(),any());
    }
    @Test void deniedSpectatorForeignRegionAndUnloadedChunkNeverHarvest() {
        protection.when(()->InteractGuard.canBreak(cePlayer,ceWorld,8,64,8)).thenReturn(false);assertFalse(pick(full));
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);assertFalse(pick(full));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);bukkit.when(()->Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);assertFalse(pick(full));
        bukkit.when(()->Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);when(world.isChunkLoaded(0,0)).thenReturn(false);assertFalse(pick(full));
        inventory.verifyNoInteractions();blocks.verifyNoInteractions();
    }
    @Test void unrelatedHeldItemPassesWithoutModification() {
        hand.set(item(Key.of("minecraft:stone"),1));assertFalse(pick(full));assertSame(full,current.get());inventory.verifyNoInteractions();
    }
    @Test void throwingConstructionReleasesGuard() {
        inventory.when(()->InventoryUtils.createOrEmpty(CHILI)).thenThrow(new IllegalStateException("builder failed"));
        assertThrows(IllegalStateException.class,()->pick(full));inventory.when(()->InventoryUtils.createOrEmpty(CHILI)).thenAnswer(c->item(CHILI,1));
        assertTrue(pick(full));
    }
    @Test void collapseDropsSixOrThreeAfterConfirmedRemoval() {
        collapse();assertNull(current.get());current.set(half);collapse();assertNull(current.get());
        ArgumentCaptor<Item> output=ArgumentCaptor.forClass(Item.class);verify(ceWorld,times(2)).dropItemNaturally(any(Vec3d.class),output.capture());
        assertEquals(java.util.List.of(6,3),output.getAllValues().stream().map(Item::count).toList());
    }
    @Test void restoredSupportOrFailedRemovalPreventsCollapseDrops() {
        HangingStringHarvest.collapse(block,full,sheared,CHILI,3,()->true);assertSame(full,current.get());
        blocks.when(()->CraftEngineBlocks.remove(block,false)).thenReturn(true);current.set(full);
        collapse();assertSame(full,current.get());verifyNoInteractions(ceWorld);
    }
    @Test void collapseHonorsDisabledTileDropsWithoutRequiringAnItem() {
        when(world.getGameRuleValue(Rules.DO_TILE_DROPS)).thenReturn(false);collapse();assertNull(current.get());
        inventory.verifyNoInteractions();verifyNoInteractions(ceWorld);
    }
    @Test void creativeStillGetsHarvestAndMushroomKeyIsAccepted() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);assertTrue(pick(full));assertEquals(3,hand.get().count());
        assertTrue(HangingStringHarvest.accepts(item(Key.of("minecraft:brown_mushroom"),1),Key.of("minecraft:brown_mushroom")));
        assertFalse(HangingStringHarvest.accepts(item(CHILI,1),Key.of("minecraft:brown_mushroom")));
    }
}
