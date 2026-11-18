package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.SteamerBehavior;
import net.kaleidoscope.cookery.block.entity.render.TrackedPlayers;
import net.kaleidoscope.cookery.item.*;
import net.kaleidoscope.cookery.recipe.*;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.SlabType;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.*;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import java.util.function.*;
import static net.kaleidoscope.cookery.item.SteamerItemContentsTest.item;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SteamerInteractionTest {
    private static final BlockPos POS=new BlockPos(8,70,8);
    private static final Key RAW=Key.of("kaleidoscopecookery:raw_dough");
    private final List<AutoCloseable> handles=new ArrayList<>();
    private final BukkitWorld level=mock(BukkitWorld.class);
    private final CEWorld storage=mock(CEWorld.class);
    private final org.bukkit.World world=mock(org.bukkit.World.class);
    private final Player player=mock(Player.class);
    private final org.bukkit.entity.Player actor=mock(org.bukkit.entity.Player.class);
    private final UseOnContext context=mock(UseOnContext.class);
    private final Block block=mock(Block.class);
    private final BlockState before=mock(BlockState.class);
    private final BlockDefinition definition=mock(BlockDefinition.class);
    private final Property<SlabType> type=mock(Property.class);
    private final Property<Boolean> lid=mock(Property.class);
    private final ImmutableBlockState bottom=mock(ImmutableBlockState.class),full=mock(ImmutableBlockState.class),closed=mock(ImmutableBlockState.class);
    private SteamerBehavior behavior;private BlockEntity entity;private Item held;private boolean air;
    private final List<Item> given=new ArrayList<>();private Runnable delivering=()->{};
    private Consumer<BlockBreakEvent> breaking=e->{};
    private Function<ImmutableBlockState,Boolean> writer;
    private MockedStatic<SteamerWorld> geometry;private MockedStatic<InteractGuard> guard;
    private MockedStatic<InventoryUtils> inventory;private MockedStatic<DropUtils> drops;
    private <T> MockedStatic<T> stat(Class<T> cls){var result=mockStatic(cls);handles.add(result);return result;}
    private SteamerController current(){return entity==null?null:entity.controller.get(SteamerController.class,0);}
    private void add(ImmutableBlockState state){entity=new BlockEntity(POS,state);entity.world=storage;entity.setValid(true);air=false;}
    @BeforeEach void setup() throws Exception {
        Item empty=item("minecraft:air",0);stat(Item.class).when(Item::empty).thenReturn(empty);
        handles.add(mockConstruction(SteamerElement.class));stat(TrackedPlayers.class);drops=stat(DropUtils.class);
        SteamerItemContentsTest.codec(stat(BlockEntityNbt.class));
        var constructor=SteamerBehavior.class.getDeclaredConstructor(BlockDefinition.class);constructor.setAccessible(true);behavior=constructor.newInstance(definition);
        for(var pair:Map.of("typeProperty",type,"hasLidProperty",lid).entrySet()){
            var field=SteamerBehavior.class.getDeclaredField(pair.getKey());field.setAccessible(true);field.set(behavior,pair.getValue());
        }
        behavior.portableContents=true;behavior.cookingTime=200;when(definition.id()).thenReturn(SteamerItemContents.ID);
        for(var state:List.of(bottom,full,closed)){
            when(state.behavior()).thenReturn(behavior);when(state.<SlabType>getProperty("type")).thenReturn(type);
            when(state.<Boolean>getProperty("has_lid")).thenReturn(lid);
            when(state.get(type)).thenReturn(state==full?SlabType.DOUBLE:SlabType.BOTTOM);
            when(state.get(eq(type),any(SlabType.class))).thenReturn(state==full?SlabType.DOUBLE:SlabType.BOTTOM);
            when(state.get(lid)).thenReturn(state==closed);when(state.get(eq(lid),anyBoolean())).thenReturn(state==closed);
        }
        when(bottom.with(lid,true)).thenReturn(closed);when(closed.with(lid,false)).thenReturn(bottom);when(full.with(type,SlabType.BOTTOM)).thenReturn(bottom);
        when(level.platformWorld()).thenReturn(world);when(level.storageWorld()).thenReturn(storage);when(storage.world()).thenReturn(level);
        when(storage.getBlockEntityAtIfLoaded(POS)).thenAnswer(c->entity);when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block);
        when(block.getState()).thenReturn(before);when(block.isEmpty()).thenAnswer(c->air);
        when(before.update(true,false)).thenAnswer(c->{if(entity==null || current().removed())add(bottom);else entity.blockState=bottom;return true;});
        when(context.getPlayer()).thenReturn(player);when(context.getLevel()).thenReturn(level);when(context.getClickedPos()).thenReturn(POS);
        when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);when(player.getItemInHand(any())).thenAnswer(c->held);
        when(player.platformPlayer()).thenReturn(actor);when(actor.isValid()).thenReturn(true);when(actor.isOnline()).thenReturn(true);
        when(actor.getWorld()).thenReturn(world);when(actor.getGameMode()).thenReturn(GameMode.SURVIVAL);
        var bukkit=stat(Bukkit.class);bukkit.when(()->Bukkit.isOwnedByCurrentRegion(actor)).thenReturn(true);
        var manager=mock(PluginManager.class);bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        doAnswer(c->{breaking.accept(c.getArgument(0));return null;}).when(manager).callEvent(any(BlockBreakEvent.class));
        geometry=stat(SteamerWorld.class);geometry.when(()->SteamerWorld.accessible(eq(level),any())).thenReturn(true);
        guard=stat(InteractGuard.class);guard.when(()->InteractGuard.canInteract(player,level,POS)).thenReturn(true);
        guard.when(()->InteractGuard.canBreak(player,level,8,70,8)).thenReturn(true);
        inventory=stat(InventoryUtils.class);inventory.when(()->InventoryUtils.createOrEmpty(any(Key.class))).thenAnswer(c->item(((Key)c.getArgument(0)).asString(),1));
        inventory.when(()->InventoryUtils.giveOrHold(eq(player),eq(InteractionHand.MAIN_HAND),any())).thenAnswer(c->{given.add(c.getArgument(2));delivering.run();return null;});
        var foods=mock(ApplianceFoodRegistry.class);stat(ApplianceFoodRegistry.class).when(ApplianceFoodRegistry::instance).thenReturn(foods);
        when(foods.isAllowed(ApplianceType.STEAMER,RAW)).thenReturn(true);
        var recipes=mock(FoodRecipeRegistry.class);stat(FoodRecipeRegistry.class).when(FoodRecipeRegistry::instance).thenReturn(recipes);
        when(recipes.readSnapshot(any(Supplier.class))).thenAnswer(c->((Supplier<?>)c.getArgument(0)).get());
        when(recipes.planAccurate(eq(ApplianceType.STEAMER),eq(RAW),anyInt())).thenReturn(new CookingPlan(true,true,Key.of("test:raw"),1200,1,null,
                List.of(new CookingPlan.Output(Key.of("minecraft:bread"),1,List.of(),null)),List.of()));
        writer=state->{if(state==null){current().onRemove();entity=null;air=true;}else entity.blockState=state;return true;};
        var ce=stat(CraftEngineBlocks.class);ce.when(()->CraftEngineBlocks.place(any(),any(ImmutableBlockState.class),eq(UpdateFlags.UPDATE_ALL),eq(false)))
                .thenAnswer(c->writer.apply(c.getArgument(1)));
        ce.when(()->CraftEngineBlocks.remove(block,false)).thenAnswer(c->writer.apply(null));
        held=empty;add(bottom);
    }
    @AfterEach void close() throws Exception{for(int i=handles.size()-1;i>=0;i--)handles.get(i).close();}
    private InteractionResult use(){return behavior.useOnBlock(context,entity==null?bottom:entity.blockState);}
    private CompoundTag saved(){var tag=new CompoundTag();current().saveCustomData(tag);return tag;}
    private void contents(boolean doubleLayer){
        if(doubleLayer)entity.blockState=full;
        var slots=new ArrayList<>(Collections.<SteamerInventory.Slot>nCopies(8,null));
        slots.set(1,new SteamerInventory.Slot(item("minecraft:bread",3),35,-1,null,false));
        slots.set(doubleLayer?6:3,new SteamerInventory.Slot(item(RAW.asString(),1),410,1200,null,false));
        var root=new CompoundTag();CookeryBlockStateEnvelope.put(root,SteamerController.DATA_KEY,SteamerItemContents.payload(slots));current().loadCustomData(root);
    }
    @Test void extractsAllPhysicalSlotsAndDoesNotDismantleUntilNextClick(){
        contents(true);assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertEquals(2,given.size());assertEquals(3,given.get(0).count());
        assertEquals(0,current().getItemCount());assertSame(full,entity.blockState);assertArrayEquals(new int[8],current().getCookingTime());
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertSame(bottom,entity.blockState);assertEquals(SteamerItemContents.ID,given.get(2).id());drops.verifyNoInteractions();
    }
    @Test void finalEmptyLayerIsRemovedWithExactlyOneContainer(){
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertNull(entity);assertEquals(1,given.size());drops.verifyNoInteractions();
    }
    @Test void nonfoodHeldItemStillAllowsSourceTakeFallback(){contents(false);held=item("minecraft:stone",9);assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertEquals(9,held.count());assertEquals(2,given.size());}
    @Test void fillsOnlyClickedLayerAndPreservesOtherSlots(){contents(false);held=item(RAW.asString(),9);assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());
        assertEquals(7,held.count());assertEquals(4,current().getItemCount());assertEquals(1200,current().getCookingTime()[0]);assertEquals(410,current().getCookingProgress()[3]);
        verify(storage,never()).getBlockEntityAtIfLoaded(POS.below());assertTrue(given.isEmpty());}
    @Test void creativeInsertsConsumeTheRawStackLikeSourceSplit(){when(player.canInstabuild()).thenReturn(true);when(player.isCreativeMode()).thenReturn(true);
        held=item(RAW.asString(),6);assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertEquals(2,held.count());assertEquals(4,current().getItemCount());}
    @Test void offhandInsertsUseActualHandAndTakeReturnsToSelectedMainSlot(){when(context.getHand()).thenReturn(InteractionHand.OFF_HAND);
        held=item(RAW.asString(),2);assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertEquals(0,held.count());
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertEquals(2,given.size());verify(player,times(2)).swingHand(InteractionHand.OFF_HAND);}
    @Test void solidBlockAbovePreventsInventoryOrLayerChanges(){contents(false);var before=saved();geometry.when(()->SteamerWorld.faceSturdy(level,POS.above(),Direction.DOWN)).thenReturn(true);
        assertEquals(InteractionResult.PASS,use());assertEquals(before,saved());assertTrue(given.isEmpty());}
    @Test void coveredEmptyLayerIsNotDismantled(){geometry.when(()->SteamerWorld.sameBlock(level,POS.above(),definition)).thenReturn(true);
        assertEquals(InteractionResult.PASS,use());assertNotNull(entity);entity.blockState=closed;assertEquals(InteractionResult.PASS,use());assertTrue(given.isEmpty());}
    @Test void lidTogglesOnlyClickedLayerAndKeepsExactContents(){contents(false);var slots=SteamerItemContents.payload(current().snapshotSlots());when(player.isSecondaryUseActive()).thenReturn(true);
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertSame(closed,entity.blockState);assertEquals(slots,SteamerItemContents.payload(current().snapshotSlots()));
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertSame(bottom,entity.blockState);assertTrue(given.isEmpty());}
    @Test void cannotCloseLowerLidUnderAnotherSteamer(){when(player.isSecondaryUseActive()).thenReturn(true);geometry.when(()->SteamerWorld.sameBlock(level,POS.above(),definition)).thenReturn(true);
        assertEquals(InteractionResult.PASS,use());assertSame(bottom,entity.blockState);}
    @Test void cancelledBreakOrProtectionKeepsTheEmptyLayer(){breaking=e->e.setCancelled(true);assertEquals(InteractionResult.FAIL,use());assertNotNull(entity);assertTrue(given.isEmpty());
        breaking=e->{};guard.when(()->InteractGuard.canBreak(player,level,8,70,8)).thenReturn(false);assertEquals(InteractionResult.FAIL,use());assertNotNull(entity);}
    @Test void reentrantDeliveryCannotDuplicateOrDismantle(){contents(true);delivering=()->{assertEquals(InteractionResult.FAIL,use());assertFalse(current().hasSpace());assertTrue(current().takeFood(player).isEmpty());};
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL,use());assertEquals(2,given.size());assertSame(full,entity.blockState);}
    @Test void callbackSourceChangeOrOfflineActorPreventsDismantling(){breaking=e->{held=item("minecraft:stone",1);};assertEquals(InteractionResult.FAIL,use());assertNotNull(entity);assertTrue(given.isEmpty());
        held=item("minecraft:air",0);breaking=e->when(actor.isOnline()).thenReturn(false);assertEquals(InteractionResult.FAIL,use());assertNotNull(entity);}
    @Test void nativeWriteMutationThenFailureRestoresStateAndContents(){contents(false);var before=saved();when(player.isSecondaryUseActive()).thenReturn(true);
        writer=state->{entity.blockState=state;throw new IllegalStateException("native failure");};assertThrows(IllegalStateException.class,this::use);
        assertSame(bottom,entity.blockState);assertEquals(before,saved());assertTrue(given.isEmpty());}
    @Test void refusedNativeRemovalRestoresTheOriginalLayer(){writer=state->{current().onRemove();entity=null;air=true;return false;};
        assertEquals(InteractionResult.FAIL,use());assertNotNull(entity);assertFalse(current().removed());assertTrue(given.isEmpty());drops.verifyNoInteractions();}
    @Test void missingContainerFailsBeforeWorldMutation(){inventory.when(()->InventoryUtils.createOrEmpty(SteamerItemContents.ID)).thenAnswer(c->Item.empty());
        assertThrows(IllegalStateException.class,this::use);assertNotNull(entity);assertTrue(given.isEmpty());}
    @Test void unavailableAboveOrSpectatorNeverAccessesContents(){contents(false);var before=saved();geometry.when(()->SteamerWorld.accessible(level,POS.above())).thenReturn(false);
        assertEquals(InteractionResult.FAIL,use());assertEquals(before,saved());when(actor.getGameMode()).thenReturn(GameMode.SPECTATOR);assertEquals(InteractionResult.PASS,use());}
}
