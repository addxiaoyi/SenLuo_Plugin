package net.kaleidoscope.cookery.block.entity;

import net.momirealms.craftengine.core.item.Item;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ScarecrowEquipmentTest {
    private MockedStatic<Item> empty;
    static Item equipment(String name,String components,int count,int durability) {
        Item item=TrashCanInventoryTest.item(name,components,count);
        when(item.maxDamage()).thenReturn(durability);
        var id=item.id();when(item.vanillaId()).thenReturn(id);
        when(item.minecraftItem()).thenReturn(name);
        when(item.copy()).thenAnswer(call->equipment(name,components,count,durability));
        when(item.copyWithCount(anyInt())).thenAnswer(call->equipment(name,components,call.getArgument(0),durability));
        return item;
    }
    @BeforeEach void setup(){Item air=equipment("air","",0,0);empty=mockStatic(Item.class);empty.when(Item::empty).thenReturn(air);}
    @AfterEach void close(){empty.close();}
    private Item[] emptySlots(){return new Item[]{Item.empty(),Item.empty(),Item.empty()};}

    @Test void durableItemsFillBothHandsThenRejectWithoutReplacing() {
        Item[] slots=emptySlots();Item pick=equipment("iron_pickaxe","enchanted",1,250);
        var first=ScarecrowEquipment.plan(slots,false,pick,false,false);
        assertEquals(1,first.slot());assertTrue(first.hand().isEmpty());slots[1]=first.stored();
        var second=ScarecrowEquipment.plan(slots,false,pick,false,false);
        assertEquals(2,second.slot());slots[2]=second.stored();
        assertNull(ScarecrowEquipment.plan(slots,false,pick,false,false));
        assertNull(ScarecrowEquipment.plan(emptySlots(),false,equipment("lantern","",1,0),false,false));
    }
    @Test void takeUsesMainHandFirstRegardlessOfClickedSideAndKeepsComponents() {
        Item[] slots={Item.empty(),equipment("iron_pickaxe","first",1,250),equipment("bow","second",1,384)};
        var first=ScarecrowEquipment.plan(slots,false,Item.empty(),false,false);
        assertEquals(1,first.slot());assertEquals("first",first.hand().customNameJson().orElseThrow().getAsString());
        slots[1]=first.stored();assertEquals(2,ScarecrowEquipment.plan(slots,false,Item.empty(),false,false).slot());
    }
    @Test void creativeFreeEmptyHeadButOccupiedHeadSwapsOneAndRejectsStacks() {
        Item skull=equipment("player_head","new",1,0);Item[] slots=emptySlots();
        var emptyHead=ScarecrowEquipment.plan(slots,true,skull,true,true);
        assertEquals(1,emptyHead.hand().count());slots[0]=equipment("skeleton_skull","old",1,0);
        var swap=ScarecrowEquipment.plan(slots,true,skull,true,true);
        assertEquals("old",swap.hand().customNameJson().orElseThrow().getAsString());assertEquals("new",swap.stored().customNameJson().orElseThrow().getAsString());
        assertNull(ScarecrowEquipment.plan(slots,true,equipment("player_head","new",2,0),true,true));
        assertNull(ScarecrowEquipment.plan(slots,true,equipment("pumpkin","",1,0),false,false));
    }
    @Test void survivalHeadStackConsumesOneAndCreativeHandsDoNotPay() {
        var head=ScarecrowEquipment.plan(emptySlots(),true,equipment("player_head","",5,0),true,false);
        assertEquals(4,head.hand().count());assertEquals(1,head.stored().count());
        var tool=ScarecrowEquipment.plan(emptySlots(),false,equipment("bow","",1,384),false,true);
        assertEquals(1,tool.hand().count());assertEquals(1,tool.stored().count());
    }
}
