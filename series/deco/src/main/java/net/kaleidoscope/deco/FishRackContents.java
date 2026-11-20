package net.kaleidoscope.deco;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Three one-item slots in insertion order; callers commit only after payment or delivery succeeds. */
final class FishRackContents {
    private List<ItemStack> fish = List.of();

    static String visual(Material material) {
        return switch (material) {
            case COD, COOKED_COD -> "cod";
            case SALMON, COOKED_SALMON -> "salmon";
            case TROPICAL_FISH -> "tropical";
            default -> throw new IllegalArgumentException("Unsupported fishrack item: " + material);
        };
    }

    static boolean accepts(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        return switch (item.getType()) {
            case COD, COOKED_COD, SALMON, COOKED_SALMON, TROPICAL_FISH -> true;
            default -> false;
        };
    }

    static String variant(List<ItemStack> slots) {
        validate(slots);
        return "fishrack/" + (slots.isEmpty() ? "empty" : String.join("/", slots.stream()
                .map(item -> visual(item.getType())).toList()));
    }

    static void validate(List<ItemStack> slots) {
        if (slots.size() > 3 || slots.stream().anyMatch(item -> !accepts(item) || item.getAmount() != 1))
            throw new IllegalArgumentException("Invalid fishrack contents");
    }

    List<ItemStack> snapshot() { return fish.stream().map(ItemStack::clone).toList(); }
    int size() { return fish.size(); }
    ItemStack last() { return fish.isEmpty() ? null : fish.getLast().clone(); }
    void replace(List<ItemStack> next) { validate(next); fish = next.stream().map(ItemStack::clone).toList(); }
    List<ItemStack> adding(ItemStack item) {
        if (!accepts(item) || fish.size() == 3) return null;
        var next = new ArrayList<>(snapshot()); next.add(item.asQuantity(1)); return next;
    }
    List<ItemStack> removing() { return fish.isEmpty() ? null : snapshot().subList(0, fish.size() - 1); }
}
