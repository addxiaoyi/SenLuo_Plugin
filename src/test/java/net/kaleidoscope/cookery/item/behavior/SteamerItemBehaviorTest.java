package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.block.behavior.SteamerBehavior;
import net.kaleidoscope.cookery.block.entity.*;
import net.kaleidoscope.cookery.block.entity.render.TrackedPlayers;
import net.kaleidoscope.cookery.item.*;
import net.kaleidoscope.cookery.nms.*;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.bukkit.api.*;
import net.momirealms.craftengine.bukkit.api.event.*;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.SlabType;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.context.EventTrigger;
import net.momirealms.craftengine.core.util.*;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static net.kaleidoscope.cookery.item.SteamerItemContentsTest.item;

class SteamerItemBehaviorTest {
    @BeforeAll static void initializeGameRules() {
        try(var access=mockStatic(io.papermc.paper.registry.RegistryAccess.class);
            var bridge=mockStatic(io.papermc.paper.InternalAPIBridge.class)) {
            bridge.when(io.papermc.paper.InternalAPIBridge::get).thenReturn(mock(io.papermc.paper.InternalAPIBridge.class));
            var provider=mock(io.papermc.paper.registry.RegistryAccess.class,c->mock(org.bukkit.Registry.class));
            access.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(provider);
            var registry=org.bukkit.Registry.GAME_RULE;
            doAnswer(c->mock(org.bukkit.GameRule.class)).when(registry).getOrThrow(any(net.kyori.adventure.key.Key.class));
            assertNotNull(net.kaleidoscope.compat.Rules.DO_TILE_DROPS);
        }
    }
    private static final BlockPos POS=new BlockPos(8,70,8);
    private static final Key ID=SteamerItemContents.ID;
    private final World world=mock(World.class);
    private final BukkitWorld level=mock(BukkitWorld.class);
    private final CEWorld storage=mock(CEWorld.class);
    private final SteamerBehavior behavior=mock(SteamerBehavior.class);
    private final BlockDefinition definition=mock(BlockDefinition.class);
    private final ImmutableBlockState bottom=mock(ImmutableBlockState.class,RETURNS_DEEP_STUBS), full=mock(ImmutableBlockState.class,RETURNS_DEEP_STUBS);
    private final Property<SlabType> type=mock(Property.class);
    private final List<AutoCloseable> handles=new ArrayList<>();
    private final PluginManager events=mock(PluginManager.class);
    private final Player player=mock(Player.class);
    private final org.bukkit.entity.Player actor=mock(org.bukkit.entity.Player.class);
    private final BlockPlaceContext context=mock(BlockPlaceContext.class);
    private Item held;
    private SteamerItemBehavior placer;
    private BlockEntity entity;
    private final Block block=mock(Block.class);
    private BlockData data=mock(BlockData.class);
    private MockedStatic<CraftEngineBlocks> ce;
    private MockedStatic<InteractGuard> guard;
    private MockedStatic<DropUtils> drops;
    private Consumer<Event> callback=e->{};
    private java.util.function.Supplier<Boolean> writer;
    private int writes,rollbacks;
    private boolean stack;
    private <T> MockedStatic<T> stat(Class<T> c){var m=mockStatic(c);handles.add(m);return m;}
    @BeforeEach void setup() {
        handles.add(mockConstruction(SteamerElement.class)); stat(TrackedPlayers.class);drops=stat(DropUtils.class);
        Item empty=item("minecraft:air",0);stat(Item.class).when(Item::empty).thenReturn(empty);
        var nbt=stat(BlockEntityNbt.class);SteamerItemContentsTest.codec(nbt);
        var inv=stat(InventoryUtils.class);inv.when(()->InventoryUtils.createOrEmpty(any(Key.class))).thenAnswer(c->item(((Key)c.getArgument(0)).asString(),1));
        var bukkitStack=mock(org.bukkit.inventory.ItemStack.class);when(bukkitStack.clone()).thenReturn(bukkitStack);
        inv.when(()->InventoryUtils.bukkitCopy(any())).thenReturn(bukkitStack);
        behavior.portableContents=true;behavior.cookingTime=200;behavior.particleInterval=20;
        when(definition.id()).thenReturn(ID);when(behavior.block()).thenReturn(definition);when(behavior.getFirst(SteamerBehavior.class)).thenReturn(behavior);
        when(behavior.getTypeProperty()).thenReturn(type);when(behavior.createBlockEntityController(any())).thenAnswer(c->new SteamerController(c.getArgument(0),behavior));
        for(var state:List.of(bottom,full)){
            when(state.behavior()).thenReturn(behavior);when(state.owner().value()).thenReturn(definition);
            when(state.<SlabType>getProperty("type")).thenReturn(type);when(state.<Boolean>getProperty("has_lid")).thenReturn(null);
            doReturn(state==full?SlabType.DOUBLE:SlabType.BOTTOM).when(state).get(type);
            doReturn(state==full?SlabType.DOUBLE:SlabType.BOTTOM).when(state).get(eq(type),any(SlabType.class));
        }
        when(definition.eventFunctions(EventTrigger.PLACE)).thenReturn(List.of());
        when(level.platformWorld()).thenReturn(world);when(level.storageWorld()).thenReturn(storage);when(storage.world()).thenReturn(level);
        when(storage.getBlockEntityAtIfLoaded(POS)).thenAnswer(c->entity);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block);when(block.getBlockData()).thenAnswer(c->data);
        when(block.getState()).thenAnswer(c->{BlockState before=mock(BlockState.class);BlockData old=data;BlockEntity prior=entity;
            when(before.getBlockData()).thenReturn(old);when(before.getBlock()).thenReturn(block);
            when(before.update(true,false)).thenAnswer(call->{rollbacks++;if(entity!=null&&entity!=prior)entity.controller.onRemove();
                if(prior==null)entity=null;else if(prior.controller.get(SteamerController.class,0).removed())add(bottom);else {entity=prior;entity.blockState=bottom;}
                data=old;return true;});return before;});
        var existing=mock(ExistingBlock.class);when(existing.canBeReplaced(context)).thenReturn(true);when(level.getBlock(POS)).thenReturn(existing);
        var bukkit=stat(Bukkit.class);bukkit.when(()->Bukkit.isOwnedByCurrentRegion(eq(world),anyInt(),anyInt())).thenReturn(true);
        bukkit.when(()->Bukkit.isOwnedByCurrentRegion(actor)).thenReturn(true);bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        doAnswer(c->{callback.accept(c.getArgument(0));return null;}).when(events).callEvent(any());
        ce=stat(CraftEngineBlocks.class);ce.when(()->CraftEngineBlocks.byId(ID)).thenReturn(definition);
        ce.when(()->CraftEngineBlocks.place(any(Location.class),any(ImmutableBlockState.class),eq(UpdateFlags.UPDATE_ALL),eq(false)))
            .thenAnswer(c->{writes++;return writer.get();});
        writer=()->{if(entity!=null)entity.blockState=full;else add(bottom);data=mock(BlockData.class);return true;};
        guard=stat(InteractGuard.class);guard.when(()->InteractGuard.canPlace(player,level,POS)).thenReturn(true);
        var bridge=mock(NmsBridge.class);stat(NmsBridgeProvider.class).when(NmsBridgeProvider::bridge).thenReturn(bridge);
        when(bridge.canPlaceEightImmortalsTable(any(),anyInt(),anyInt(),anyInt(),any(),any())).thenReturn(true);
        when(player.platformPlayer()).thenReturn(actor);when(actor.getWorld()).thenReturn(world);when(actor.isOnline()).thenReturn(true);
        when(actor.isValid()).thenReturn(true);when(actor.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(context.getPlayer()).thenReturn(player);when(context.getLevel()).thenReturn(level);when(context.getClickedPos()).thenReturn(POS);
        when(context.getAgainstPos()).thenReturn(POS.below());when(context.getClickedFace()).thenReturn(Direction.UP);
        when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);when(context.canPlace()).thenReturn(true);
        held=SteamerItemContents.build(incoming());when(context.getItem()).thenAnswer(c->held);when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenAnswer(c->held);
        placer=new SteamerItemBehavior(ID) {
            @Override protected ImmutableBlockState getPlacementState(BlockPlaceContext ctx,BlockDefinition def){return stack?full:bottom;}
            @Override protected void feedback(BlockPlaceContext ctx){}
        };
    }
    @AfterEach void close() throws Exception {for(int i=handles.size()-1;i>=0;i--)handles.get(i).close();}
    private SteamerController add(ImmutableBlockState state){entity=new BlockEntity(POS,state);entity.world=storage;entity.setValid(true);return current();}
    private SteamerController current(){return entity==null?null:entity.controller.get(SteamerController.class,0);}
    private List<SteamerInventory.Slot> incoming(){var list=new ArrayList<SteamerInventory.Slot>(Collections.nCopies(4,null));list.set(2,new SteamerInventory.Slot(item("kaleidoscopecookery:raw_dough",1),410,1200,null,false));return list;}
    private CompoundTag saved(SteamerController c){var tag=new CompoundTag();c.saveCustomData(tag);return tag;}
    private void lower(){stack=true;var controller=add(bottom);var slots=new ArrayList<SteamerInventory.Slot>(Collections.nCopies(8,null));slots.set(1,new SteamerInventory.Slot(item("minecraft:bread",3),35,-1,null,false));
        var root=new CompoundTag();CookeryBlockStateEnvelope.put(root,SteamerController.DATA_KEY,SteamerItemContents.payload(slots));controller.loadCustomData(root);}
    @Test void filledPlacementLoadsSparseDataAndDebitsOnlyAfterEvents(){
        callback=e->assertEquals(1,held.count());assertEquals(InteractionResult.SUCCESS,placer.place(context));assertEquals(0,held.count());
        assertEquals(410,current().getCookingProgress()[2]);assertEquals(1200,current().getCookingTime()[2]);assertTrue(current().getItems()[0].isEmpty());
    }
    @Test void mergingMovesOnlyIncomingFourSlotsIntoUpperLayer(){lower();assertEquals(InteractionResult.SUCCESS,placer.place(context));
        assertEquals(3,current().getItems()[1].count());assertEquals(35,current().getCookingProgress()[1]);assertEquals(410,current().getCookingProgress()[6]);assertEquals(2,current().getItemCount());}
    @Test void cancelledStackRestoresStateAndExactLowerInventory(){lower();CompoundTag before=saved(current());callback=e->{if(e instanceof BlockPlaceEvent place)place.setCancelled(true);};
        assertEquals(InteractionResult.FAIL,placer.place(context));assertSame(bottom,entity.blockState);assertEquals(before,saved(current()));assertEquals(1,held.count());assertEquals(1,rollbacks);}
    @Test void cancelledNewPlacementDropsNothingAndKeepsSource(){callback=e->{if(e instanceof CustomBlockPlaceEvent p)p.setCancelled(true);};
        assertEquals(InteractionResult.FAIL,placer.place(context));assertNull(entity);assertEquals(1,held.count());verify(level,never()).dropItemNaturally(any(),any());}
    @Test void creativeStackKeepsSourceMetadataAndCount(){lower();when(player.isCreativeMode()).thenReturn(true);var before=held.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA).deepClone();
        assertEquals(InteractionResult.SUCCESS,placer.place(context));assertEquals(1,held.count());assertEquals(before,held.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA));}
    @Test void cancelledAttemptAndDeniedProtectionWriteNothing(){callback=e->{if(e instanceof CustomBlockAttemptPlaceEvent p)p.setCancelled(true);};assertEquals(InteractionResult.FAIL,placer.place(context));assertEquals(0,writes);
        callback=e->{};guard.when(()->InteractGuard.canPlace(player,level,POS)).thenReturn(false);assertEquals(InteractionResult.FAIL,placer.place(context));assertEquals(0,writes);}
    @Test void reentrantPlacementAndInventoryAccessAreBlocked(){lower();callback=e->{assertEquals(InteractionResult.FAIL,placer.place(context));assertFalse(current().hasSpace());assertTrue(current().takeFood(null).isEmpty());};
        assertEquals(InteractionResult.SUCCESS,placer.place(context));assertEquals(1,writes);}
    @Test void sourceCountChangeOrOfflineCallbackRollsBack(){callback=e->{if(e instanceof CustomBlockPlaceEvent)held.count(2);};assertEquals(InteractionResult.FAIL,placer.place(context));assertNull(entity);assertEquals(2,held.count());
        held.count(1);callback=e->{if(e instanceof CustomBlockPlaceEvent)when(actor.isOnline()).thenReturn(false);};assertEquals(InteractionResult.FAIL,placer.place(context));assertNull(entity);}
    @Test void writeThatMutatesThenThrowsStillRestoresLower(){lower();CompoundTag before=saved(current());writer=()->{entity.blockState=full;throw new IllegalStateException("write failed");};
        assertThrows(IllegalStateException.class,()->placer.place(context));assertEquals(before,saved(current()));assertSame(bottom,entity.blockState);assertEquals(1,held.count());}
    @Test void replacingControllerDuringWriteCanRestoreLowerAfterCancellation(){lower();CompoundTag before=saved(current());writer=()->{current().onRemove();add(full);return true;};
        callback=e->{if(e instanceof BlockPlaceEvent p)p.setCancelled(true);};assertEquals(InteractionResult.FAIL,placer.place(context));assertEquals(before,saved(current()));assertSame(bottom,entity.blockState);}
    @Test void callbackReplacementIsKeptAndOriginalLowerIsReturnedOnce(){lower();callback=e->{if(e instanceof CustomBlockPlaceEvent){current().onRemove();entity=null;}};
        assertEquals(InteractionResult.FAIL,placer.place(context));assertNull(entity);assertEquals(1,held.count());
        verify(level,times(1)).dropItemNaturally(any(),argThat(drop->SteamerItemContents.read(drop).get(1).item().count()==3));}
    @Test void badDataAndSideFaceAreRejectedBeforeWorldWrites(){held.count(2);assertEquals(InteractionResult.FAIL,placer.place(context));assertEquals(0,writes);
        held.count(1);when(context.getClickedFace()).thenReturn(Direction.NORTH);assertEquals(InteractionResult.FAIL,placer.place(context));assertEquals(0,writes);}
    @Test void breakingDoubleSteamerReturnsTwoIndependentPortableLayersOnce(){
        lower();assertEquals(InteractionResult.SUCCESS,placer.place(context));var controller=current();var output=new ArrayList<Item>();
        drops.when(()->DropUtils.dropOnRemove(eq(entity),any())).thenAnswer(c->{output.add(c.getArgument(1));controller.onRemove();return null;});
        controller.onRemove();controller.onRemove();assertEquals(2,output.size());assertEquals(0,controller.getItemCount());
        var bottomItems=SteamerItemContents.read(output.get(0));var topItems=SteamerItemContents.read(output.get(1));
        assertEquals(3,bottomItems.get(1).item().count());assertEquals(35,bottomItems.get(1).progress());assertEquals(-1,bottomItems.get(1).time());
        assertNull(bottomItems.get(2));assertNull(topItems.get(1));assertEquals(410,topItems.get(2).progress());assertEquals(1200,topItems.get(2).time());
        var lowerSlots=new ArrayList<>(bottomItems);lowerSlots.addAll(Collections.nCopies(4,null));
        var restored=SteamerItemContents.merge(lowerSlots,topItems);
        assertEquals(3,restored.get(1).item().count());assertEquals(410,restored.get(6).progress());
    }
    @Test void creativeAndDisabledBlockDropsClearWithoutEmittingContainersOrFood(){
        lower();var creative=current();creative.skipAllDrops();creative.onRemove();assertEquals(0,creative.getItemCount());drops.verifyNoInteractions();
        lower();when(world.getGameRuleValue(net.kaleidoscope.compat.Rules.DO_TILE_DROPS)).thenReturn(false);
        current().onRemove();assertEquals(0,current().getItemCount());drops.verifyNoInteractions();
    }
    @Test void fallingTransfersOwnershipWithoutEmittingItems(){
        lower();current().markFallingAway();current().onRemove();drops.verifyNoInteractions();assertEquals(0,current().getItemCount());
    }
    @Test void cancelledSpecialFoodDropStillReturnsBothBareContainers(){
        lower();assertEquals(InteractionResult.SUCCESS,placer.place(context));current().markSkipFoodDrop();var output=new ArrayList<Item>();
        drops.when(()->DropUtils.dropOnRemove(eq(entity),any())).thenAnswer(c->{output.add(c.getArgument(1));return null;});
        current().onRemove();assertEquals(2,output.size());for(var item:output)assertTrue(SteamerItemContents.read(item).stream().allMatch(Objects::isNull));
    }
    @Test void missingContainerDefinitionFailsBeforeAnyContentsLeaveTheBlock(){
        lower();var before=saved(current());var captured=current();
        try(var contents=mockStatic(SteamerItemContents.class,CALLS_REAL_METHODS)){
            contents.when(()->SteamerItemContents.build(anyList())).thenThrow(new IllegalStateException("missing container"));
            assertThrows(IllegalStateException.class,captured::onRemove);assertEquals(before,saved(captured));assertFalse(captured.removed());drops.verifyNoInteractions();
        }
    }
    @Test void nativeWriteReturningFalseRestoresLower(){
        lower();var before=saved(current());writer=()->{entity.blockState=full;return false;};
        assertEquals(InteractionResult.FAIL,placer.place(context));assertEquals(before,saved(current()));assertSame(bottom,entity.blockState);assertEquals(1,held.count());
    }
    @Test void nativeWriteCannotSilentlyOverwriteChangedLowerInventory(){
        lower();var before=saved(current());writer=()->{entity.blockState=full;current().getItems()[1].count(7);return true;};
        assertEquals(InteractionResult.FAIL,placer.place(context));assertEquals(before,saved(current()));assertSame(bottom,entity.blockState);assertEquals(1,held.count());
    }
}
