package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.SteamerBehavior;
import net.kaleidoscope.cookery.util.*;
import net.kaleidoscope.cookery.block.entity.render.TrackedPlayers;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.libraries.nbt.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SteamerControllerTest {
    private static final String DATA = SteamerController.DATA_KEY;
    private MockedConstruction<SteamerElement> visuals;
    private MockedStatic<BlockEntityNbt> nbt;
    private MockedStatic<TrackedPlayers> tracked;
    private MockedStatic<DropUtils> drops;
    private MockedStatic<Item> emptyItems;
    private BlockEntity entity;
    private SteamerBehavior behavior;
    private SteamerController steamer;
    private final CEWorld world = mock(CEWorld.class);

    private Item item(String id, int count) {
        Item item = mock(Item.class);
        when(item.id()).thenReturn(Key.of(id)); when(item.count()).thenReturn(count);
        when(item.maxStackSize()).thenReturn(64); when(item.isEmpty()).thenReturn(count == 0);
        when(item.copy()).thenAnswer(call -> item(id, count));
        when(item.copyWithCount(anyInt())).thenAnswer(call -> item(id, call.getArgument(0)));
        return item;
    }
    private CompoundTag itemTag(String id, int count) {
        CompoundTag tag = new CompoundTag(); tag.putString("id", id); tag.putInt("count", count); return tag;
    }
    @BeforeEach void setup() {
        Item empty = item("minecraft:air",0); emptyItems = mockStatic(Item.class); emptyItems.when(Item::empty).thenReturn(empty);
        visuals = mockConstruction(SteamerElement.class); tracked = mockStatic(TrackedPlayers.class);
        drops = mockStatic(DropUtils.class); nbt = mockStatic(BlockEntityNbt.class);
        behavior = mock(SteamerBehavior.class); behavior.cookingTime = 200; behavior.particleInterval = 20;
        when(behavior.createBlockEntityController(any())).thenReturn(mock(BlockEntityController.class));
        ImmutableBlockState state = mock(ImmutableBlockState.class); when(state.behavior()).thenReturn(behavior);
        entity = new BlockEntity(new BlockPos(8, 64, 8), state); entity.world = world; entity.setValid(true);
        nbt.when(() -> BlockEntityNbt.getItem(any(), eq("item"), anyInt())).thenAnswer(call -> {
            CompoundTag raw = ((CompoundTag)call.getArgument(0)).getCompound("item");
            return raw.containsKey("bad") ? Item.empty() : item(raw.getString("id"), raw.getInt("count"));
        });
        nbt.when(() -> BlockEntityNbt.itemTag(any())).thenAnswer(call -> {
            Item item = call.getArgument(0); return itemTag(item.id().asString(), item.count());
        });
        // Exercise the old decoder as well, so before/after regression evidence is meaningful.
        nbt.when(() -> BlockEntityNbt.loadItems(any(ListTag.class), anyInt(), any(Item[].class))).thenAnswer(call -> {
            ListTag list = call.getArgument(0); Item[] target = call.getArgument(2); java.util.Arrays.fill(target,Item.empty());
            for(int i=0;i<list.size();i++) { CompoundTag raw=list.getCompound(i); target[i]=item(raw.getString("id"),raw.getInt("count")); }
            return list.size();
        });
        steamer = new SteamerController(entity, behavior);
    }
    @AfterEach void close() { nbt.close(); drops.close(); tracked.close(); visuals.close(); emptyItems.close(); }
    private CompoundTag payload(CompoundTag root) { return CookeryBlockStateEnvelope.payload(root, DATA); }
    private CompoundTag root(boolean sparse) {
        CompoundTag root = new CompoundTag(), data = new CompoundTag();
        ListTag entries = new ListTag();
        for(int i=0;i<2;i++) {
            CompoundTag raw=itemTag(i==0 ? "minecraft:apple" : "minecraft:bread", i==0 ? 3 : 1);
            if(sparse) { CompoundTag entry=new CompoundTag(); entry.putInt("slot",i==0?1:6); entry.put("item",raw); entries.add(entry); }
            else entries.add(raw);
        }
        if(sparse) {
            data.putInt("inventory_schema",1); data.putInt("data_version",0);
            data.putIntArray("completion_blocked",new int[8]); data.put("cooking_plans",new CompoundTag());
        }
        data.put(sparse?"slots":"items",entries);
        int[] progress=new int[8], time=new int[8];
        progress[sparse?1:0]=35; time[sparse?1:0]=-1;
        progress[sparse?6:1]=90; time[sparse?6:1]=1200;
        data.putIntArray("cooking_progress",progress); data.putIntArray("cooking_time",time);
        CookeryBlockStateEnvelope.put(root,DATA,data); return root;
    }
    private CompoundTag saved() { CompoundTag root=new CompoundTag(); steamer.saveCustomData(root); return root; }
    @Test void oldDenseItemsRetainTheirMatchingProgress() {
        steamer.loadCustomData(root(false)); assertEquals(2,steamer.getItemCount());
        assertEquals(35,steamer.getCookingProgress()[0]); assertEquals(90,steamer.getCookingProgress()[1]);
        assertEquals(1200,steamer.getCookingTime()[1]);
    }
    @Test void sparseSlotsSurviveSaveAndLoadWithoutMovingLayers() {
        steamer.loadCustomData(root(true)); assertEquals(2,steamer.getItemCount());
        assertTrue(steamer.getItems()[0].isEmpty()); assertEquals(Key.of("minecraft:bread"),steamer.getItems()[6].id());
        CompoundTag saved=saved(); SteamerController restored=new SteamerController(entity,behavior); restored.loadCustomData(saved);
        assertEquals(90,restored.getCookingProgress()[6]); assertEquals(1200,restored.getCookingTime()[6]);
        assertEquals(1,payload(saved).getList("slots").getCompound(0).getInt("slot"));
    }
    @Test void takingAResultReturnsItsWholeStackAndDoesNotMoveOtherSlots() {
        steamer.loadCustomData(root(false)); Item taken=steamer.takeFood(null);
        assertEquals(3,taken.count()); assertTrue(steamer.getItems()[0].isEmpty());
        assertEquals(Key.of("minecraft:bread"),steamer.getItems()[1].id()); assertEquals(90,steamer.getCookingProgress()[1]);
        assertEquals(1,steamer.getItemCount());
    }
    @Test void savedArraysAreDetachedFromLaterTicksAndCallerChanges() {
        steamer.loadCustomData(root(true)); CompoundTag saved=saved();
        steamer.getCookingProgress()[6]=120; assertEquals(90,payload(saved).getIntArray("cooking_progress")[6]);
        payload(saved).getIntArray("cooking_time")[6]=30; assertEquals(1200,steamer.getCookingTime()[6]);
    }
    @Test void duplicateSlotsRejectAndPreserveExactBadPayload() {
        CompoundTag bad=root(true); payload(bad).getList("slots").getCompound(1).putInt("slot",1);
        assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(bad)); assertEquals(bad,saved());
    }
    @Test void badItemNeverShiftsTheNextItemsProgress() {
        CompoundTag bad=root(false); payload(bad).getList("items").getCompound(0).putBoolean("bad",true);
        assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(bad)); assertEquals(bad,saved());
    }
    @Test void malformedArraysAndFutureSchemasArePreserved() {
        for(String field:new String[]{"cooking_progress","cooking_time","completion_blocked","inventory_schema"}) {
            CompoundTag bad=root(true); payload(bad).putString(field,"corrupt");
            assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(bad),field); assertEquals(bad,saved());
        }
        for(String field:new String[]{"data_version","slots","cooking_progress","cooking_time","completion_blocked","cooking_plans"}) {
            CompoundTag bad=root(true); payload(bad).remove(field);
            assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(bad),field); assertEquals(bad,saved());
        }
    }
    @Test void malformedPlanDoesNotBecomeANewBatch() {
        CompoundTag bad=root(true), plans=new CompoundTag(); plans.put("6",new CompoundTag()); payload(bad).put("cooking_plans",plans);
        assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(bad)); assertEquals(bad,saved());
    }
    @Test void rejectedLoadKeepsExistingInventoryAndBlocksMutationAndDrops() {
        steamer.loadCustomData(root(false)); CompoundTag bad=root(true); payload(bad).putString("slots","bad");
        assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(bad));
        assertEquals(2,steamer.getItemCount()); assertTrue(steamer.takeFood(null).isEmpty());
        assertFalse(steamer.tryAddOne(item("minecraft:apple",1))); assertThrows(IllegalStateException.class,steamer::onRemove);
        assertDoesNotThrow(steamer::tick); drops.verifyNoInteractions();
        assertEquals(bad,saved());
    }
    @Test void emptyLoadResetsAllPreviousSlotsAndMetadata() {
        CompoundTag data=root(false); payload(data).putBoolean("has_lid",true); payload(data).putInt("lit_level",4);
        steamer.loadCustomData(data); steamer.loadCustomData(new CompoundTag());
        assertEquals(0,steamer.getItemCount()); assertFalse(steamer.hasLid()); assertEquals(0,steamer.getLitLevel());
        assertTrue(java.util.Arrays.stream(steamer.getItems()).allMatch(Item::isEmpty));
    }
    @Test void removeClearsBeforeReentrantDropsAndOnlyDropsEachSlotOnce() {
        steamer.loadCustomData(root(false));
        drops.when(()->DropUtils.dropOnRemove(eq(entity),any())).thenAnswer(call->{steamer.onRemove();return null;});
        steamer.onRemove(); steamer.onRemove();
        drops.verify(()->DropUtils.dropOnRemove(eq(entity),any()),times(2)); assertEquals(0,steamer.getItemCount());
    }
    @Test void tickerUsesTheSuppliedControllerAfterReload() {
        SteamerController replacement=spy(new SteamerController(entity,behavior)); doNothing().when(replacement).tick();
        steamer.<SteamerController>createBlockEntityTicker(world,entity.blockState).tick(world,entity.pos,entity.blockState,replacement);
        verify(replacement).tick();
    }
    @Test void firstVacantPhysicalSlotIsFilledWithoutMovingUpperLayer() {
        steamer.loadCustomData(root(true));
        var foods=mock(net.kaleidoscope.cookery.recipe.ApplianceFoodRegistry.class);
        var recipes=mock(net.kaleidoscope.cookery.recipe.FoodRecipeRegistry.class);
        try(var foodStatic=mockStatic(net.kaleidoscope.cookery.recipe.ApplianceFoodRegistry.class);
            var recipeStatic=mockStatic(net.kaleidoscope.cookery.recipe.FoodRecipeRegistry.class)) {
            foodStatic.when(net.kaleidoscope.cookery.recipe.ApplianceFoodRegistry::instance).thenReturn(foods);
            recipeStatic.when(net.kaleidoscope.cookery.recipe.FoodRecipeRegistry::instance).thenReturn(recipes);
            when(foods.isAllowed(any(),any(Key.class))).thenReturn(true);
            when(recipes.readSnapshot(any(java.util.function.Supplier.class))).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get());
            when(recipes.planAccurate(any(),any(),anyInt())).thenReturn(net.kaleidoscope.cookery.recipe.CookingPlan.unmatched(1200));
            assertTrue(steamer.tryAddOne(item("minecraft:carrot",8)));
            assertEquals(Key.of("minecraft:carrot"),steamer.getItems()[0].id()); assertEquals(1,steamer.getItems()[0].count());
            assertEquals(1200,steamer.getCookingTime()[0]); assertEquals(90,steamer.getCookingProgress()[6]);
            assertEquals(Key.of("minecraft:bread"),steamer.getItems()[6].id());
        }
    }
    @Test void blockStateLidTakesPrecedenceOverStaleSavedLid() {
        CompoundTag saved=root(false); payload(saved).putBoolean("has_lid",true); steamer.loadCustomData(saved);
        net.momirealms.craftengine.core.block.property.Property<Boolean> property=mock(net.momirealms.craftengine.core.block.property.Property.class);
        when(entity.blockState.<Boolean>getProperty("has_lid")).thenReturn(property);
        when(entity.blockState.get(eq(property),anyBoolean())).thenReturn(false);
        assertFalse(steamer.hasLid()); assertFalse(payload(saved()).getBoolean("has_lid"));
    }
    @Test void failedSerializationDoesNotPublishPartialPayload() {
        steamer.loadCustomData(root(false)); CompoundTag output=new CompoundTag(); output.putString("other","keep");
        nbt.when(()->BlockEntityNbt.itemTag(any())).thenReturn(null);
        assertThrows(IllegalArgumentException.class,()->steamer.saveCustomData(output));
        assertEquals(java.util.Set.of("other"),output.keySet()); assertEquals(2,steamer.getItemCount());
    }
    @Test void invalidPayloadTypeAndMetadataRemainRecoverable() {
        CompoundTag root=new CompoundTag(); root.putString(DATA,"broken");
        assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(root)); assertEquals(root,saved());
        for(String field:new String[]{"has_lid","seed","lit_level"}) {
            CompoundTag bad=root(false); payload(bad).putString(field,"broken");
            assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(bad)); assertEquals(bad,saved());
        }
    }
    @Test void orphanPlanAndOutOfRangeSlotAreRejected() {
        CompoundTag bad=root(true); payload(bad).getList("slots").getCompound(1).putInt("slot",8);
        assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(bad)); assertEquals(bad,saved());
        CompoundTag orphan=root(true), plans=new CompoundTag();
        plans.put("0",net.kaleidoscope.cookery.recipe.CookingPlan.unmatched(1200).save()); payload(orphan).put("cooking_plans",plans);
        assertThrows(IllegalArgumentException.class,()->steamer.loadCustomData(orphan)); assertEquals(orphan,saved());
    }

}
