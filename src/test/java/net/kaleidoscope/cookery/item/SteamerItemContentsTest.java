package net.kaleidoscope.cookery.item;

import net.kaleidoscope.cookery.block.entity.SteamerInventory;
import net.kaleidoscope.cookery.recipe.*;
import net.kaleidoscope.cookery.util.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.*;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class SteamerItemContentsTest {
    public static Item item(Key id, int count, Map<Key,Tag> components) {
        Item item=mock(Item.class); AtomicInteger amount=new AtomicInteger(count);
        when(item.id()).thenReturn(id); when(item.count()).thenAnswer(c->amount.get()); when(item.isEmpty()).thenAnswer(c->amount.get()<=0);
        when(item.count(anyInt())).thenAnswer(c->{amount.set(c.getArgument(0));return item;});
        when(item.maxStackSize()).thenReturn(64);
        when(item.getComponentAsSparrowTag(any(Key.class))).thenAnswer(c->components.get(c.getArgument(0)));
        doAnswer(c->{components.put(c.getArgument(0),c.getArgument(1));return null;}).when(item).setSparrowTagComponent(any(Key.class),any(Tag.class));
        when(item.copyWithCount(anyInt())).thenAnswer(c->{ Map<Key,Tag> copy=new HashMap<>(); components.forEach((k,v)->copy.put(k,v.deepClone())); return item(id,c.getArgument(0),copy); });
        when(item.copy()).thenAnswer(c->item.copyWithCount(amount.get()));
        when(item.isSimilar(any())).thenAnswer(c->id.equals(((Item)c.getArgument(0)).id())
            && Objects.equals(item.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA),((Item)c.getArgument(0)).getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA)));
        return item;
    }
    public static Item item(String id,int count) {return item(Key.of(id),count,new HashMap<>());}
    public static void codec(MockedStatic<BlockEntityNbt> nbt) {
        nbt.when(()->BlockEntityNbt.itemTag(any())).thenAnswer(c->{ Item item=c.getArgument(0); CompoundTag t=new CompoundTag();
            t.putString("id",item.id().asString()); t.putInt("count",item.count());
            Tag custom=item.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA); if(custom!=null)t.put("custom",custom.deepClone()); return t; });
        nbt.when(()->BlockEntityNbt.getItem(any(),eq("item"),anyInt())).thenAnswer(c->{CompoundTag t=((CompoundTag)c.getArgument(0)).getCompound("item");
            Map<Key,Tag> data=new HashMap<>(); if(t.get("custom")!=null)data.put(SteamerItemContents.CUSTOM_DATA,t.get("custom").deepClone());
            return item(Key.of(t.getString("id")),t.getInt("count"),data);});
    }
    private MockedStatic<Item> empties;
    private MockedStatic<BlockEntityNbt> nbt;
    private MockedStatic<InventoryUtils> builds;
    @BeforeEach void setup() {
        Item empty=item("minecraft:air",0); empties=mockStatic(Item.class); empties.when(Item::empty).thenReturn(empty);
        nbt=mockStatic(BlockEntityNbt.class); codec(nbt);
        builds=mockStatic(InventoryUtils.class); builds.when(()->InventoryUtils.createOrEmpty(any(Key.class))).thenAnswer(c->item((Key)c.getArgument(0),1,new HashMap<>()));
    }
    @AfterEach void close(){builds.close();nbt.close();empties.close();}
    private List<SteamerInventory.Slot> slots(){
        List<SteamerInventory.Slot> list=new ArrayList<>(Collections.nCopies(4,null));
        list.set(1,new SteamerInventory.Slot(item("minecraft:bread",3),37,-1,null,false));
        list.set(3,new SteamerInventory.Slot(item("kaleidoscopecookery:raw_dough",1),410,1200,CookingPlan.unmatched(1200),true));return list;
    }
    @Test void sparseSlotsProgressPlansCountsAndPlayerMetadataRoundTrip() {
        Item base=item(SteamerItemContents.ID,4,new HashMap<>()); CompoundTag custom=new CompoundTag();custom.putString("owner","Alice");
        base.setSparrowTagComponent(SteamerItemContents.CUSTOM_DATA,custom); Item output=SteamerItemContents.withContents(base,slots());
        var loaded=SteamerItemContents.read(output);assertNull(loaded.get(0));assertNull(loaded.get(2));
        assertEquals(3,loaded.get(1).item().count());assertEquals(-1,loaded.get(1).time());assertEquals(410,loaded.get(3).progress());
        assertEquals(1200,loaded.get(3).plan().workRequired());assertTrue(loaded.get(3).blocked());assertEquals(1,output.count());
        assertEquals(1,((IntTag)output.getComponentAsSparrowTag(Key.of("minecraft:max_stack_size"))).getAsInt());
        assertEquals("Alice",((CompoundTag)output.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA)).getString("owner"));
        assertFalse(custom.containsKey(SteamerItemContents.DATA));assertEquals(4,base.count());
    }
    @Test void decodedItemsAndEncodedTagsAreDetached() {
        Item output=SteamerItemContents.build(slots());var first=SteamerItemContents.read(output);first.get(1).item().count(60);
        assertEquals(3,SteamerItemContents.read(output).get(1).item().count());
        first.set(0,first.get(1));assertNull(SteamerItemContents.read(output).get(0));
    }
    @Test void emptySteamerHasNoPayloadAndStacksNormally() {
        Item output=SteamerItemContents.build(Collections.nCopies(4,null));assertTrue(SteamerItemContents.read(output).stream().allMatch(Objects::isNull));
        assertEquals(64,((IntTag)output.getComponentAsSparrowTag(Key.of("minecraft:max_stack_size"))).getAsInt());
        assertFalse(((CompoundTag)output.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA)).containsKey(SteamerItemContents.DATA));
    }
    @Test void stackedFilledItemOrUpperSlotIsRejectedWithoutMutation() {
        Item output=SteamerItemContents.build(slots()); output.count(2); assertThrows(IllegalArgumentException.class,()->SteamerItemContents.read(output));
        output.count(1);CompoundTag data=((CompoundTag)output.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA)).getCompound(SteamerItemContents.DATA);
        data.getList("slots").getCompound(0).putInt("slot",5);assertThrows(IllegalArgumentException.class,()->SteamerItemContents.read(output));
    }
    @Test void foreignAndLegacyPayloadsCannotBecomeEmptyContainers() {
        Item output=item(SteamerItemContents.ID,1,new HashMap<>()); CompoundTag data=new CompoundTag();data.putString("BlockEntityTag","old");
        output.setSparrowTagComponent(SteamerItemContents.CUSTOM_DATA,data);assertThrows(IllegalArgumentException.class,()->SteamerItemContents.read(output));
        data.remove("BlockEntityTag");data.putString(SteamerItemContents.DATA,"bad");assertThrows(IllegalArgumentException.class,()->SteamerItemContents.read(output));
        output.setSparrowTagComponent(SteamerItemContents.CUSTOM_DATA,new StringTag("bad"));assertThrows(IllegalArgumentException.class,()->SteamerItemContents.read(output));
    }
    @Test void mergingPreservesBothLayersAndDoesNotOverwriteExistingUpperSlots() {
        var lower=new ArrayList<>(slots());lower.addAll(Collections.nCopies(4,null));var top=slots();
        var result=SteamerItemContents.merge(lower,top);assertEquals(lower.subList(0,4),result.subList(0,4));assertEquals(top,result.subList(4,8));
        assertNull(lower.get(5));assertThrows(IllegalArgumentException.class,()->SteamerItemContents.merge(result,top));
    }
    @Test void bothSourceRecipesBuildExactlyFourIndependentRawItemsAt1200Ticks() {
        for(String name:List.of("raw_dough","stuffed_dough_food")) {
            Key raw=Key.of("kaleidoscopecookery:"+name);Item base=item(SteamerItemContents.ID,1,new HashMap<>());
            var result=new FilledSteamerResult(raw).process(base,null);var contents=SteamerItemContents.read(result);
            for(var slot:contents){assertEquals(raw,slot.item().id());assertEquals(1,slot.item().count());assertEquals(0,slot.progress());assertEquals(1200,slot.time());}
            assertNull(base.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA));
        }
    }
    @Test void invalidIngredientOrFailedSerializationLeavesBaseUntouched() {
        assertThrows(IllegalArgumentException.class,()->new FilledSteamerResult(Key.of("minecraft:diamond")));
        Item base=item(SteamerItemContents.ID,1,new HashMap<>());nbt.when(()->BlockEntityNbt.itemTag(any())).thenReturn(null);
        assertThrows(IllegalArgumentException.class,()->SteamerItemContents.withContents(base,slots()));
        assertNull(base.getComponentAsSparrowTag(SteamerItemContents.CUSTOM_DATA));
    }
    @Test void modernAndLegacyPayloadsTogetherAreRejectedBeforeReadOrRewrite() {
        Item output=SteamerItemContents.build(slots());
        output.setSparrowTagComponent(Key.of("minecraft:block_entity_data"),new CompoundTag());
        assertThrows(IllegalArgumentException.class,()->SteamerItemContents.read(output));
        assertThrows(IllegalArgumentException.class,()->SteamerItemContents.withContents(output,slots()));
    }
    @Test void splittingHalfWithUpperContentsFailsInsteadOfSilentlyDiscardingThem() {
        var inventory=new ArrayList<>(Collections.<SteamerInventory.Slot>nCopies(4,null));inventory.addAll(slots());
        assertThrows(IllegalArgumentException.class,()->SteamerItemContents.split(inventory,4,false));
        var drops=SteamerItemContents.split(inventory,8,false);assertEquals(2,drops.size());
        assertTrue(SteamerItemContents.read(drops.get(0)).stream().allMatch(Objects::isNull));
        assertEquals(3,SteamerItemContents.read(drops.get(1)).get(1).item().count());
    }
    @Test void invalidProgressCannotBePersistedByBlockPayloadWriter() {
        var inventory=new ArrayList<>(Collections.<SteamerInventory.Slot>nCopies(8,null));
        inventory.set(0,new SteamerInventory.Slot(item("minecraft:bread",1),-2,1200,null,false));
        assertThrows(IllegalArgumentException.class,()->SteamerItemContents.payload(inventory));
    }
}
