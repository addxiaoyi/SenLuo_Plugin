package net.kaleidoscope.deco;

import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Set;

/** The fixed Deco pack's chair and cook-stool families. */
final class WoodenSeats implements Listener {
    static final String PREFIX = "kaleidoscope_deco:";
    static final List<String> WOODS = List.of("acacia", "bamboo", "birch", "cherry", "crimson", "dark_oak",
            "jungle", "mangrove", "oak", "pale_oak", "spruce", "warped");
    static final Set<String> COLORS = Set.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    static final String EMPTY = "empty";

    record Seat(boolean chair, String wood) {
        String id() { return PREFIX + (chair ? "chair/" : "cook_stool/") + wood; }
    }

    static Seat parse(String id) {
        if (id == null) throw new IllegalArgumentException("Missing Deco wooden seat ID");
        boolean chair = id.startsWith(PREFIX + "chair/");
        String prefix = PREFIX + (chair ? "chair/" : "cook_stool/");
        if (!id.startsWith(prefix) || !WOODS.contains(id.substring(prefix.length())))
            throw new IllegalArgumentException("Unknown Deco wooden seat: " + id);
        return new Seat(chair, id.substring(prefix.length()));
    }

    static String carpetColor(String itemId) {
        if (itemId == null || !itemId.startsWith("minecraft:") || !itemId.endsWith("_carpet")) return null;
        String color = itemId.substring("minecraft:".length(), itemId.length() - "_carpet".length());
        return COLORS.contains(color) ? color : null;
    }

    static String variant(String color) {
        if (EMPTY.equals(color)) return "ground";
        if (color == null || !COLORS.contains(color)) throw new IllegalArgumentException("Unknown Deco carpet: " + color);
        return "cushion/" + color;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breaking(FurnitureBreakEvent event) {
        WoodenSeatController controller = event.furniture().controller.get(WoodenSeatController.class, 0);
        if (controller != null && controller.blocksRemoval()) {
            event.setCancelled(true);
            event.player().sendMessage("椅子正在操作或存档无法读取，已保留原状。");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void acceptedBreak(FurnitureBreakEvent event) {
        WoodenSeatController controller = event.furniture().controller.get(WoodenSeatController.class, 0);
        if (controller != null) controller.acceptPlayerBreak(event);
    }
}
