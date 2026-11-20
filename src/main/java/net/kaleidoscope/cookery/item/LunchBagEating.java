package net.kaleidoscope.cookery.item;

import net.kaleidoscope.cookery.api.KaleidoscopeAdvancements;

import net.kaleidoscope.cookery.util.FoliaUtil;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.ItemUtils;
import org.bukkit.Sound;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;

// 饭袋进食态的生命周期
// 原版收纳袋没有进食动画 所以右键时把手里的物品临时换成可食用的进食态 由原版驱动动画与中断 吃完再换回收纳态
public final class LunchBagEating {
    private LunchBagEating() {}

    private static final int MAX_FOOD_LEVEL = 20;
    private static final float DROP_VOLUME = 1.0f;
    private static final float DROP_PITCH = 1.0f;
    // 换成进食态后客户端要隔一两 tick 才会重新发起使用 超过这个宽限还没抬手就说明只是点了一下 换回收纳态
    private static final int START_GRACE_TICKS = 20;
    private static final java.util.Map<java.util.UUID, Object> STARTS = new java.util.concurrent.ConcurrentHashMap<>();
    // 换成进食态 玩家按住右键时客户端会自行重新发起使用 原版据 consumable 组件播放进食动画
    public static void begin(Player player, InteractionHand hand, Item bag) {
        if (bag.count() != 1 || LunchBagContents.isEmpty(bag)) return;
        Item eating = LunchBagContents.toEatingForm(bag);
        if (ItemUtils.isEmpty(eating)) {
            return;
        }
        player.setItemInHand(hand, eating);

        org.bukkit.entity.Player bukkitPlayer = (org.bukkit.entity.Player) player.platformPlayer();
        Object token = new Object();
        STARTS.put(bukkitPlayer.getUniqueId(), token);
        FoliaUtil.runLater(
                () -> {
                    if (STARTS.remove(bukkitPlayer.getUniqueId(), token)) cancelIfNotEating(bukkitPlayer);
                }, () -> STARTS.remove(bukkitPlayer.getUniqueId(), token), START_GRACE_TICKS, bukkitPlayer);
    }

    private static final java.util.Set<java.util.UUID> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // One 32-tick use eats food until full, then still visits potion slots.
    // Each unit commits the remaining bag before vanilla callbacks can re-enter.
    public static void finish(org.bukkit.entity.Player player, EquipmentSlot hand, Item eating) {
        if (!ACTIVE.add(player.getUniqueId())) return;
        try {
            ItemStack expected = InventoryUtils.bukkitCopy(eating);
            if (!sameHeld(player, hand, expected)) return;
            STARTS.remove(player.getUniqueId());
            List<Item> remaining = LunchBagContents.read(eating);
            for (Item item : remaining) CookeryFoodItems.read(InventoryUtils.bukkitCopy(item));
            Item bag = LunchBagContents.toBagForm(eating);
            if (ItemUtils.isEmpty(bag)) return;
            int consumed = 0;
            for (int slot = 0; slot < remaining.size(); slot++) {
                Item stored = remaining.get(slot);
                while (!ItemUtils.isEmpty(stored) && player.isOnline() && !player.isDead()) {
                    ItemStack candidate = InventoryUtils.bukkitCopy(stored.copyWithCount(1));
                    boolean edible = candidate.getItemMeta().hasFood();
                    if ((edible && player.getFoodLevel() >= MAX_FOOD_LEVEL)
                            || (!edible && candidate.getType() != org.bukkit.Material.POTION)) break;
                    if (!sameHeld(player, hand, expected)) return;
                    var event = new org.bukkit.event.player.PlayerItemConsumeEvent(player, candidate, hand);
                    org.bukkit.Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) break;
                    if (!sameHeld(player, hand, expected)) {
                        event.setCancelled(true);
                        return;
                    }
                    Item actual = InventoryUtils.wrap(event.getItem().asQuantity(1));
                    if (!LunchBagContents.canAdd(actual)) {
                        event.setCancelled(true);
                        break;
                    }
                    stored = stored.copyWithCount(stored.count() - 1);
                    remaining.set(slot, stored);
                    LunchBagContents.write(bag, remaining);
                    expected = InventoryUtils.bukkitCopy(bag);
                    player.getInventory().setItem(hand, expected.clone());
                    Object leftover;
                    try {
                        leftover = net.kaleidoscope.cookery.nms.NmsBridgeProvider.bridge()
                                .finishConsumingItem(actual.minecraftItem(), player);
                    } catch (RuntimeException failure) {
                        event.setCancelled(true);
                        throw failure;
                    }
                    consumed++;
                    if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                        ItemStack replacement = event.getReplacement();
                        Item result = replacement != null ? InventoryUtils.wrap(replacement)
                                : InventoryUtils.wrapNative(leftover);
                        if (!ItemUtils.isEmpty(result)) InventoryUtils.give(player, result);
                    }
                }
            }
            if (sameHeld(player, hand, expected)) {
                player.getInventory().setItem(hand, InventoryUtils.bukkitCopy(bag));
            }
            if (consumed > 0) KaleidoscopeAdvancements.recordEvent(player, "use_transmutation_lunch_bag");
            player.updateInventory();
        } finally {
            ACTIVE.remove(player.getUniqueId());
        }
    }

    private static boolean sameHeld(org.bukkit.entity.Player player, EquipmentSlot hand, ItemStack expected) {
        ItemStack current = player.getInventory().getItem(hand);
        return current != null && current.getAmount() == expected.getAmount() && current.isSimilar(expected);
    }

    // 把玩家身上所有进食态换回收纳态 松手 切槽 重进服都走这里
    public static void restore(org.bukkit.entity.Player bukkitPlayer) {
        STARTS.remove(bukkitPlayer.getUniqueId());
        PlayerInventory inventory = bukkitPlayer.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean changed = false;

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            Item wrapped = BukkitItemManager.instance().wrap(stack);
            if (!LunchBagContents.isEatingForm(wrapped)) {
                continue;
            }
            Item bag;
            try { bag = LunchBagContents.toBagForm(wrapped); }
            catch (IllegalArgumentException exception) { continue; }
            if (ItemUtils.isEmpty(bag)) {
                continue;
            }
            inventory.setItem(slot, ItemStackUtils.getBukkitStack(bag));
            changed = true;
        }

        if (changed) {
            bukkitPlayer.updateInventory();
        }
    }

    // 判据只能是手有没有抬起 宽限期 20t 短于进食 32t 这个兜底必然在进食中途触发一次
    // 再加附加条件一旦判失败就当场 restore 打断进食 残留交给 onStopUsing/onHeldChange/onDrop/onJoin 兜底
    private static void cancelIfNotEating(org.bukkit.entity.Player bukkitPlayer) {
        if (bukkitPlayer.isOnline() && !bukkitPlayer.isHandRaised()) {
            restore(bukkitPlayer);
        }
    }

    // 倒出全部 供潜行左键用
    public static void dropContents(Player player, InteractionHand hand, Item bag) {
        List<Item> contents = LunchBagContents.removeAll(bag);
        if (contents.isEmpty()) {
            return;
        }
        // 内容物已经发给玩家了 袋子必须写回 否则倒一次刷一次
        player.setItemInHand(hand, bag);
        contents.forEach(item -> InventoryUtils.give(player, item));
        org.bukkit.entity.Player bukkitPlayer = (org.bukkit.entity.Player) player.platformPlayer();
        bukkitPlayer.playSound(bukkitPlayer.getLocation(), Sound.ITEM_BUNDLE_DROP_CONTENTS, DROP_VOLUME, DROP_PITCH);
    }
}
