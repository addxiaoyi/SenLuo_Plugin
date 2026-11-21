package net.kaleidoscope.tavern;

import net.kaleidoscope.series.InteractiveFurniture;
import net.kaleidoscope.series.InventoryTradeEvent;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElement;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

final class SandwichBoardController extends InteractiveFurniture {
    static final String SAVE_KEY = "kaleidoscope_tavern:sandwich_board_v1";
    private final TavernPlugin tavern;
    private final SandwichBoardDisplay display = new SandwichBoardDisplay(this);
    private SandwichBoardState state;
    private boolean loaded;

    SandwichBoardController(Furniture furniture, TavernPlugin plugin) {
        super(furniture, plugin);
        tavern = plugin;
        state = SandwichBoardState.blank(SandwichBoardState.styleOf(furniture.id().asString()));
    }

    SandwichBoardState state() { return state; }

    @Override public void gatherElements(java.util.function.Consumer<FurnitureElement> elements) { elements.accept(display); }
    @Override public void onLoad() {
        loaded = true;
        tavern.boards.add(this);
        // CE invokes onLoad inside the spawn consumer before the metadata entity
        // is valid. Its onPlace callback runs after world insertion and restores
        // the variant; setVariant must not be called during the earlier phase.
        if (!blocked() && furniture().isValid()) refresh();
    }
    @Override public void onPlace(net.momirealms.craftengine.core.entity.player.Player player) { onLoad(); }
    @Override public void onUnload() { loaded = false; tavern.boards.remove(this); }
    @Override public void postRemove(net.momirealms.craftengine.core.entity.player.Player player) { onUnload(); }

    boolean canEdit(Player player, SandwichBoardState expected) {
        return loaded && operational() && state == expected && !state.waxed() && player.getGameMode() != GameMode.SPECTATOR && accessible(player)
                && tavern.boards.at(location()) == this && player.isOnline() && !player.isDead();
    }

    @Override protected void interact(Player player) {
        if (!operational() || player.getGameMode() == GameMode.SPECTATOR || !accessible(player)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        SandwichBoardState before = state;
        boolean vanilla = Items.id(held).equals(held.getType().getKey().toString());
        String pattern = vanilla ? SandwichBoardState.pattern(held.getType()) : null;
        // The upstream deliberately allows changing flowers even after waxing the text.
        if (pattern != null && !pattern.equals(before.style())) {
            exchange(player, held, before, before.pattern(pattern), "block.grass.place");
            return;
        }
        if (before.waxed()) { sound("block.sign.waxed_interact_fail"); return; }
        if (!vanilla) { openEditor(player, before); return; }
        Material type = held.getType();
        String dye = type.name().endsWith("_DYE") ? type.name().substring(0, type.name().length() - 4).toLowerCase(java.util.Locale.ROOT) : null;
        if (dye != null && SandwichBoardState.COLORS.containsKey(dye) && !dye.equals(before.color())) {
            exchange(player, held, before, before.dye(dye), "item.dye.use");
        } else if (type == Material.GLOW_INK_SAC && !before.glowing()) {
            exchange(player, held, before, before.glow(true), "item.glow_ink_sac.use");
        } else if (type == Material.INK_SAC && before.glowing()) {
            exchange(player, held, before, before.glow(false), "item.ink_sac.use");
        } else if (type == Material.HONEYCOMB) {
            if (exchange(player, held, before, before.wax(), "item.honeycomb.wax_on")) {
                location().getWorld().spawnParticle(Particle.WAX_ON, location().add(0, 1, 0), 10, .5, .2, .5, .1);
            }
        } else openEditor(player, before);
    }

    private boolean exchange(Player player, ItemStack held, SandwichBoardState before, SandwichBoardState next, String sound) {
        ItemStack snapshot = held.clone();
        var inventory = player.getInventory();
        int slot = inventory.getHeldItemSlot();
        ItemStack[] contents = java.util.Arrays.stream(inventory.getContents()).map(stack -> stack == null ? null : stack.clone()).toArray(ItemStack[]::new);
        GameMode mode = player.getGameMode();
        int cost = mode == GameMode.CREATIVE ? 0 : 1;
        var event = new InventoryTradeEvent(player, cost, List.of());
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !unchanged(player, before, snapshot, slot, contents, mode)) return false;
        boolean changedPattern = !next.style().equals(before.style());
        boolean committed = false;
        try {
            if (changedPattern && !selectVariant(next.style())) return false;
            if (!unchanged(player, before, snapshot, slot, contents, mode)) return false;
            if (cost != 0) inventory.setItemInMainHand(snapshot.getAmount() == 1 ? null : snapshot.asQuantity(snapshot.getAmount() - 1));
            state = next;
            changed();
            committed = true;
        } finally {
            // Only undo our own provisional visual; never overwrite a newer controller or committed state.
            if (!committed && changedPattern && loaded && state == before && furniture().isValid()
                    && org.bukkit.Bukkit.isOwnedByCurrentRegion(location()) && tavern.boards.at(location()) == this
                    && next.style().equals(furniture().currentVariant().name())
                    && !furniture().setVariant(before.style(), true)) {
                throw new IllegalStateException("Cannot restore provisional sandwich board pattern");
            }
        }
        refresh();
        sound(sound);
        return true;
    }

    private boolean unchanged(Player player, SandwichBoardState expected, ItemStack snapshot, int slot, ItemStack[] contents, GameMode mode) {
        ItemStack now = player.getInventory().getItemInMainHand();
        return loaded && operational() && state == expected && accessible(player) && player.isOnline() && !player.isDead()
                && tavern.boards.at(location()) == this && player.getInventory().getHeldItemSlot() == slot
                && java.util.Arrays.equals(contents, player.getInventory().getContents()) && now != null
                && now.getAmount() == snapshot.getAmount() && snapshot.isSimilar(now) && player.getGameMode() == mode;
    }

    private boolean selectVariant(String name) {
        // All patterns in this definition have identical hitboxes. CE's ordinary
        // placement collision preflight can reject their own existing hitbox;
        // restoring/changing the decoration must not perform a new placement.
        return name.equals(furniture().currentVariant().name()) || furniture().setVariant(name, true);
    }

    void openEditor(Player player, SandwichBoardState expected) { SandwichBoardEditor.open(this, tavern, player, expected); }

    boolean edit(Player player, SandwichBoardState expected, String text, String alignment) {
        if (!canEdit(player, expected)) return false;
        SandwichBoardState next;
        try {
            if (alignment == null) return false;
            next = expected.edit(text, SandwichBoardState.Alignment.valueOf(alignment));
        } catch (IllegalArgumentException exception) { return false; }
        state = next;
        changed();
        refresh();
        return true;
    }

    void sound(String key) { location().getWorld().playSound(location(), key, SoundCategory.BLOCKS, 1, 1); }

    private void refresh() {
        if (!selectVariant(state.style()))
            throw new IllegalStateException("Missing sandwich board variant: " + state.style());
        // Upstream drops a blank board of the selected pattern, not its block-entity text.
        furniture().setSourceItem(ceItem(Items.create(state.itemId())));
        display.rebuild();
    }

    net.momirealms.craftengine.core.item.Item ceItem(ItemStack stack) { return BukkitItemManager.instance().wrap(stack); }

    @Override public void saveCustomData(CompoundTag root) {
        if (preserveRejected(root)) return;
        root.put(SAVE_KEY, SavedState.envelope(SavedState.namespace(SAVE_KEY), 1, SavedState.SERIES_SOURCE, state.save()));
    }

    @Override public void loadCustomData(CompoundTag root) {
        restoreSafely(root, SAVE_KEY, () -> {
            if (!root.containsKey(SAVE_KEY)) return;
            state = SandwichBoardState.read(SavedState.payload(root.getCompound(SAVE_KEY), SavedState.namespace(SAVE_KEY), SavedState.SERIES_SOURCE, 1, null));
        });
    }
}
