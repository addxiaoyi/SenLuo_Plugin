package net.kaleidoscope.cookery.block.entity;

import net.momirealms.craftengine.core.item.Item;
import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.momirealms.craftengine.libraries.nbt.*;
import java.util.Arrays;

/** Source head exchanges and first-free-hand rules, independent of clicked hand side. */
final class ScarecrowEquipment {
    record Change(int slot, Item stored, Item hand, boolean taking, boolean placedHead) {}
    private ScarecrowEquipment() {}

    static Change plan(Item[] slots, boolean head, Item held, boolean skull, boolean creative) {
        if(slots.length!=3)throw new IllegalArgumentException("Scarecrow requires three equipment slots");
        if(held.isEmpty()) {
            int slot=head?0:!slots[1].isEmpty()?1:2;
            return slots[slot].isEmpty()?null:new Change(slot,Item.empty(),slots[slot].copy(),true,false);
        }
        if(held.count()<1||held.count()>held.maxStackSize())throw new IllegalArgumentException("Invalid scarecrow input");
        if(head) {
            if(!skull)return null;
            if(creative&&slots[0].isEmpty())return new Change(0,held.copyWithCount(1),held.copy(),false,true);
            if(held.count()>1) {
                if(!slots[0].isEmpty())return null;
                return new Change(0,held.copyWithCount(1),held.copyWithCount(held.count()-1),false,true);
            }
            // Creative only skips payment when the source head slot is empty.
            return new Change(0,held.copyWithCount(1),slots[0].copy(),false,true);
        }
        if(held.maxDamage()<=0)return null;
        int slot=slots[1].isEmpty()?1:slots[2].isEmpty()?2:-1;
        if(slot<0)return null;
        return new Change(slot,held.copyWithCount(1),held.copyWithCount(creative?held.count():held.count()-1),false,false);
    }

    static CompoundTag write(Item[] slots) {
        if(slots.length!=3)throw new IllegalArgumentException("Invalid scarecrow slots");
        CompoundTag data=BlockEntityNbt.newData();ListTag list=new ListTag();
        for(int slot=0;slot<3;slot++) {
            if(slots[slot].isEmpty())continue;
            if(slots[slot].count()!=1)throw new IllegalArgumentException("Scarecrow equipment must be one item");
            Tag raw=BlockEntityNbt.itemTag(slots[slot]);
            if(!(raw instanceof CompoundTag))throw new IllegalArgumentException("Unserializable scarecrow equipment");
            CompoundTag entry=new CompoundTag();entry.putInt("slot",slot);entry.put("item",raw);list.add(entry);
        }
        data.put("items",list);return data;
    }
    static Item[] read(CompoundTag data) {
        Item[] result=new Item[3];Arrays.fill(result,Item.empty());
        if(data==null)return result;
        if(!(data.get("items") instanceof ListTag list)||list.size()>3
                ||data.containsKey("data_version")&&!(data.get("data_version") instanceof IntTag))
            throw new IllegalArgumentException("Invalid scarecrow inventory");
        int version=BlockEntityNbt.dataVersion(data);boolean[] seen=new boolean[3];
        for(Tag raw:list) {
            if(!(raw instanceof CompoundTag entry)||!(entry.get("slot") instanceof IntTag)||!(entry.get("item") instanceof CompoundTag))
                throw new IllegalArgumentException("Invalid scarecrow equipment slot");
            int slot=entry.getInt("slot");
            if(slot<0||slot>=3||seen[slot])throw new IllegalArgumentException("Duplicate/out-of-range scarecrow slot");
            seen[slot]=true;
            Item parsed=BlockEntityNbt.getItem(entry,"item",version);
            if(parsed==null||parsed.isEmpty()||parsed.count()!=1)throw new IllegalArgumentException("Unreadable scarecrow equipment");
            result[slot]=parsed.copy();
        }
        return result;
    }
}
