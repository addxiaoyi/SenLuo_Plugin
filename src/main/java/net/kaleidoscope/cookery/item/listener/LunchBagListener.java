package net.kaleidoscope.cookery.item.listener;

import net.kaleidoscope.cookery.util.FoliaUtil;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import net.kaleidoscope.cookery.item.LunchBagContents;
import net.kaleidoscope.cookery.item.LunchBagEating;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.util.ItemUtils;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;


public class LunchBagListener implements Listener {

    // 原版收纳袋右键会把内容物扔出去 无条件压掉
    // 不能只靠行为返回 SUCCESS_AND_CANCEL 准星指实体时 CE 的 onInteractAir 会被挡掉不派发
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (isLunchBag(event.getItem())) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    // 进食态被原版吃完 扣内容物并换回收纳态 不走原版扣物品
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        Item eating = wrap(event.getItem());
        if (!LunchBagContents.isEatingForm(eating)) {
            return;
        }
        event.setCancelled(true);
        try {
            LunchBagEating.finish(event.getPlayer(), event.getHand(), eating);
        } catch (IllegalArgumentException exception) {
            event.getPlayer().sendMessage("便当袋内的食物保存数据有误，已保留内容，请联系管理员。");
            net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin.instance().getLogger().log(
                    java.util.logging.Level.WARNING, "Invalid lunch bag food for " + event.getPlayer().getUniqueId(), exception);
            LunchBagEating.restore(event.getPlayer());
        }
    }

    // 没吃完就松手
    @EventHandler(ignoreCancelled = true)
    public void onStopUsing(PlayerStopUsingItemEvent event) {
        if (LunchBagContents.isEatingForm(wrap(event.getItem()))) {
            LunchBagEating.restore(event.getPlayer());
        }
    }

    // 吃到一半切物品栏 滚轮是高频事件 只看原来那一格别全背包扫
    @EventHandler(ignoreCancelled = true)
    public void onHeldChange(PlayerItemHeldEvent event) {
        ItemStack previous = event.getPlayer().getInventory().getItem(event.getPreviousSlot());
        if (LunchBagContents.isEatingForm(wrap(previous))) {
            LunchBagEating.restore(event.getPlayer());
        }
    }

    // 进食态一律不许离手 取消掉落让它退回背包再换回收纳态
    // 若改成把掉落物换成收纳态 会和 restore 各自生成一个 直接刷物品
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!LunchBagContents.isEatingForm(wrap(event.getItemDrop().getItemStack()))) {
            return;
        }
        event.setCancelled(true);
        // The server reinserts the cancelled drop only after this event returns.
        // Restore on the player's scheduler once that original stack is back.
        FoliaUtil.runLater(() -> LunchBagEating.restore(event.getPlayer()), null, 1L, event.getPlayer());
    }

    // 硬崩溃时进食态会跟着背包落盘 重进服换回收纳态 内容物在组件里不会丢
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        LunchBagEating.restore(event.getPlayer());
    }

    // Own bundle exchanges on normal storage slots; special machine slots have
    // native mayPlace/mayPickup rules which a direct Bukkit write would bypass.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.isCancelled()) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean bagInSlot = isLunchBag(current);
        boolean bagOnCursor = isLunchBag(cursor);
        if (!bagInSlot && !bagOnCursor) {
            return;
        }
        if (event.getClick() != ClickType.RIGHT) return;
        // Vanilla bundle clicks impose a one-stack weight limit; own both right-click
        // directions so the source's 24 full slots work on every supported version.
        event.setCancelled(true);
        if ((bagInSlot && bagOnCursor) || event.getClickedInventory() == null
                || event.getWhoClicked().getGameMode() == org.bukkit.GameMode.SPECTATOR
                || !isStorageInventory(event.getClickedInventory().getType())
                || event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.RESULT
                || event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR
                || event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.FUEL) return;
        ItemStack originalBag = bagInSlot ? current : cursor;
        if (originalBag.getAmount() != 1) return;
        Item bag = wrap(originalBag.clone());
        ItemStack incoming = bagInSlot ? cursor : current;
        try {
            ItemStack exchanged;
            if (incoming == null || incoming.getType().isAir()) {
                Item extracted = LunchBagContents.removeFirstStack(bag);
                if (ItemUtils.isEmpty(extracted)) return;
                // The cloned bag is not committed on rejection; retain the whole
                // stack if a custom inventory imposes a smaller slot limit.
                if (!bagInSlot && extracted.count() > event.getClickedInventory().getMaxStackSize()) return;
                exchanged = net.kaleidoscope.cookery.util.InventoryUtils.bukkitCopy(extracted);
            } else {
                int inserted = LunchBagContents.add(bag, wrap(incoming.clone()));
                if (inserted <= 0) return;
                exchanged = incoming.clone();
                exchanged.setAmount(incoming.getAmount() - inserted);
            }
            ItemStack changed = net.kaleidoscope.cookery.util.InventoryUtils.bukkitCopy(bag);
            event.setCurrentItem(bagInSlot ? changed : exchanged);
            event.getWhoClicked().setItemOnCursor(bagInSlot ? exchanged : changed);
        } catch (IllegalArgumentException exception) {
            event.getWhoClicked().sendMessage("便当袋保存数据无法读取，物品已保留，请联系管理员。");
        }
    }

    private static boolean isStorageInventory(org.bukkit.event.inventory.InventoryType type) {
        if (type == null) return false;
        return switch (type) {
            case PLAYER, CHEST, ENDER_CHEST, BARREL, SHULKER_BOX, HOPPER, DISPENSER, DROPPER -> true;
            default -> false;
        };
    }

    // 右键拖拽也能往收纳袋里塞
    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isLunchBag(event.getOldCursor()) || isLunchBag(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        for (ItemStack slot : event.getNewItems().values()) {
            if (isLunchBag(slot)) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleSanitize(event.getWhoClicked());
    }

    // 原版塞入路径不止一条 逐个事件拦容易漏 这里事后扫一遍把不该在袋里的挑出来还给玩家
    // 延后一 tick 等原版把本次改动落到物品上
    private void scheduleSanitize(HumanEntity human) {
        if (!(human instanceof org.bukkit.entity.Player bukkitPlayer)) {
            return;
        }
        FoliaUtil.runLater(
                () -> LunchBagContents.sanitizeInventory(bukkitPlayer), null, 1L, bukkitPlayer);
    }

    // 潜行左键倒出全部
    @EventHandler(ignoreCancelled = true)
    public void onSneakLeftClick(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (!event.getPlayer().isSneaking() || !isLunchBag(event.getItem())) {
            return;
        }
        org.bukkit.entity.Player bukkitPlayer = event.getPlayer();
        InteractionHand hand = event.getHand() == EquipmentSlot.OFF_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        LunchBagEating.dropContents(BukkitAdaptor.adapt(bukkitPlayer), hand, wrap(event.getItem()));
    }

    private boolean isLunchBag(ItemStack stack) {
        return LunchBagContents.isLunchBag(wrap(stack));
    }

    private Item wrap(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return Item.empty();
        }
        Item item = net.kaleidoscope.cookery.util.InventoryUtils.wrap(stack);
        return ItemUtils.isEmpty(item) ? Item.empty() : item;
    }
}
