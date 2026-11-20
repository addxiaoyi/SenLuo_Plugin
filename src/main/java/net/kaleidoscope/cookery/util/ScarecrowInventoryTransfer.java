package net.kaleidoscope.cookery.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import java.util.Arrays;

/** Forge giveItemToPlayer's merge-then-empty order. Empty main hand guarantees room for one equipped item. */
public final class ScarecrowInventoryTransfer {
    private ScarecrowInventoryTransfer() {}
    public static ItemStack[] plan(PlayerInventory inventory,ItemStack reward) {
        ItemStack[] source=inventory.getStorageContents();
        ItemStack[] result=Arrays.stream(source).map(item->item==null?null:item.clone()).toArray(ItemStack[]::new);
        int remaining=reward.getAmount();
        int maximum=Math.min(inventory.getMaxStackSize(),reward.getMaxStackSize());
        for(int i=0;i<result.length&&remaining>0;i++) {
            ItemStack item=result[i];
            if(item==null||item.isEmpty()||!item.isSimilar(reward))continue;
            int moved=Math.min(remaining,Math.max(0,maximum-item.getAmount()));
            item.setAmount(item.getAmount()+moved);remaining-=moved;
        }
        for(int i=0;i<result.length&&remaining>0;i++) {
            if(result[i]!=null&&!result[i].isEmpty())continue;
            int moved=Math.min(remaining,maximum);result[i]=reward.clone();result[i].setAmount(moved);remaining-=moved;
        }
        return remaining==0?result:null;
    }
}
