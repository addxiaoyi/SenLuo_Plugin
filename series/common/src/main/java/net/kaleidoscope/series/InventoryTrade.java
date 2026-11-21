package net.kaleidoscope.series;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Plans the entire exchange before changing a live inventory. */
public final class InventoryTrade {
    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
    private InventoryTrade() {}

    public static boolean exchange(PlayerInventory inventory, Predicate<ItemStack> payment,
                                   int cost, List<ItemStack> rewards) {
        return exchange(inventory, payment, cost, rewards, () -> true);
    }

    /** Commit associated state after cancellation and drift checks, immediately before the inventory write. */
    public static boolean exchange(PlayerInventory inventory, Predicate<ItemStack> payment,
                                   int cost, List<ItemStack> rewards, BooleanSupplier beforeCommit) {
        return exchange(inventory, payment, cost, rewards, beforeCommit, -1);
    }

    /** Consume only the captured storage slot; identical stacks elsewhere are not substitutes. */
    public static boolean exchangeSlot(PlayerInventory inventory, int slot, Predicate<ItemStack> payment,
                                       int cost, List<ItemStack> rewards, BooleanSupplier beforeCommit) {
        if (slot < 0 || slot >= inventory.getStorageContents().length)
            throw new IllegalArgumentException("Invalid payment slot");
        return exchange(inventory, payment, cost, rewards, beforeCommit, slot);
    }

    /** A block interaction pays from the actual selected hand, never an earlier identical stack. */
    public static boolean exchangeHeld(PlayerInventory inventory, Predicate<ItemStack> payment,
                                       int cost, List<ItemStack> rewards, BooleanSupplier beforeCommit) {
        java.util.Objects.requireNonNull(beforeCommit, "beforeCommit");
        int selected = inventory.getHeldItemSlot();
        if (selected < 0 || selected > 8) throw new IllegalArgumentException("Invalid selected hotbar slot");
        return exchange(inventory, payment, cost, rewards,
                () -> inventory.getHeldItemSlot() == selected && beforeCommit.getAsBoolean()
                        && inventory.getHeldItemSlot() == selected, selected);
    }

    private static boolean exchange(PlayerInventory inventory, Predicate<ItemStack> payment,
                                    int cost, List<ItemStack> rewards, BooleanSupplier beforeCommit, int paymentSlot) {
        if (cost < 0) throw new IllegalArgumentException("Negative cost");
        java.util.Objects.requireNonNull(beforeCommit, "beforeCommit");
        Player player = inventory.getHolder() instanceof Player p ? p : null;
        if (player != null && !available(player)) return false;
        UUID playerId = player == null ? null : player.getUniqueId();
        if (playerId != null && !ACTIVE.add(playerId)) return false;
        try {
            return exchangeOnce(inventory, player, payment, cost, rewards, beforeCommit, paymentSlot);
        } finally {
            if (playerId != null) ACTIVE.remove(playerId);
        }
    }

    private static boolean exchangeOnce(PlayerInventory inventory, Player player,
                                        Predicate<ItemStack> payment, int cost, List<ItemStack> rewards,
                                        BooleanSupplier beforeCommit, int paymentSlot) {
        ItemStack[] original = inventory.getStorageContents().clone();
        ItemStack[] slots = original.clone();
        for (int i = 0; i < original.length; i++) {
            if (original[i] != null) original[i] = original[i].clone();
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) slots[i] = slots[i].clone();
        }
        int unpaid = cost;
        for (int i = 0; i < slots.length && unpaid > 0; i++) {
            if (paymentSlot >= 0 && i != paymentSlot) continue;
            ItemStack stack = slots[i];
            if (stack == null || stack.isEmpty() || !payment.test(stack)) continue;
            int paid = Math.min(stack.getAmount(), unpaid);
            stack.subtract(paid);
            unpaid -= paid;
            if (stack.isEmpty()) slots[i] = null;
        }
        if (unpaid != 0) return false;
        for (ItemStack reward : rewards) {
            if (reward == null || reward.isEmpty()) throw new IllegalArgumentException("Empty reward");
            int remaining = reward.getAmount();
            int limit = Math.min(reward.getMaxStackSize(), inventory.getMaxStackSize());
            for (ItemStack slot : slots) {
                if (slot == null || !slot.isSimilar(reward)) continue;
                int added = Math.min(remaining, Math.max(0, limit - slot.getAmount()));
                slot.add(added);
                remaining -= added;
            }
            for (int i = 0; i < slots.length && remaining > 0; i++) {
                if (slots[i] != null && !slots[i].isEmpty()) continue;
                slots[i] = reward.asQuantity(Math.min(remaining, limit));
                remaining -= slots[i].getAmount();
            }
            if (remaining > 0) return false;
        }
        if (player != null) {
            InventoryTradeEvent event = new InventoryTradeEvent(player, cost, rewards);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            if (!available(player)) return false;
            if (!sameInventory(original, inventory.getStorageContents())) return false;
        }
        if (!beforeCommit.getAsBoolean()) return false;
        inventory.setStorageContents(slots);
        return true;
    }

    private static boolean available(Player player) {
        return Bukkit.isOwnedByCurrentRegion(player) && player.isOnline() && !player.isDead();
    }

    private static boolean sameInventory(ItemStack[] expected, ItemStack[] actual) {
        if (expected.length != actual.length) return false;
        for (int i = 0; i < expected.length; i++) {
            ItemStack left = expected[i];
            ItemStack right = actual[i];
            if (left == null || left.isEmpty()) {
                if (right != null && !right.isEmpty()) return false;
            } else if (right == null || !left.isSimilar(right) || left.getAmount() != right.getAmount()) {
                return false;
            }
        }
        return true;
    }

    public static boolean give(PlayerInventory inventory, ItemStack item) {
        return exchange(inventory, stack -> false, 0, List.of(item));
    }
}
