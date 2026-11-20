package net.kaleidoscope.deco;

import net.kaleidoscope.series.InteractiveFurniture;
import net.kaleidoscope.series.InventoryTrade;
import net.kaleidoscope.series.SavedItems;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Source fishstack: preserve complete one-item stacks, add at the end and take from the end. */
public final class FishRackController extends InteractiveFurniture {
    private static final String SAVE_KEY = "kaleidoscope_deco:fishrack_v1";
    private final FishRackContents contents = new FishRackContents();
    private boolean busy;
    private boolean removed;

    FishRackController(Furniture furniture, DecoPlugin plugin) { super(furniture, plugin); }

    @Override public boolean hasContents() { return contents.size() > 0 || busy; }
    @Override public boolean allowsRemovalWithContents() { return !busy; }
    public List<ItemStack> contents() { return contents.snapshot(); }

    private boolean usable() {
        return operational() && !removed && furniture().isValid() && Bukkit.isOwnedByCurrentRegion(location());
    }

    @Override protected boolean consumesInteraction(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        return held.isEmpty() ? contents.size() > 0 : FishRackContents.accepts(held) && contents.size() < 3;
    }

    @Override protected void interact(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand().clone();
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (held.isEmpty()) {
            if (take(player.getLocation(), creative, () -> accessible(player)))
                location().getWorld().playSound(location(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 1, 1);
        } else if (FishRackContents.accepts(held)) {
            int slot = player.getInventory().getHeldItemSlot();
            if (insert(held, () -> InventoryTrade.exchangeSlot(player.getInventory(), slot, held::isSimilar,
                    creative ? 0 : 1, List.of(), () -> usable() && accessible(player)
                            && player.getInventory().getHeldItemSlot() == slot
                            && held.equals(player.getInventory().getItemInMainHand()))))
                location().getWorld().playSound(location(), Sound.ENTITY_ITEM_FRAME_ADD_ITEM, SoundCategory.BLOCKS, 1, 1);
        }
    }

    /** Owning-region API for insertion, including source-stack consumption. */
    public boolean insert(ItemStack source) {
        if (source == null) return false;
        ItemStack original = source.clone();
        return insert(original, () -> {
            if (!usable() || !source.equals(original)) return false;
            source.subtract(1);
            return true;
        });
    }

    private boolean insert(ItemStack source, BooleanSupplier pay) {
        List<ItemStack> next = contents.adding(source);
        if (next == null || !usable() || busy) return false;
        // Reject an unpersistable stack before changing visuals or consuming it.
        try { savedContents(next); }
        catch (IllegalArgumentException error) { return false; }
        String previous = furniture().currentVariant().name();
        busy = true;
        boolean committed = false;
        try {
            if (!setVariant(FishRackContents.variant(next))) return false;
            if (!pay.getAsBoolean()) return false;
            contents.replace(next);
            committed = true;
            changed();
            return true;
        } finally {
            if (!committed && furniture().isValid()) setVariant(previous);
            busy = false;
        }
    }

    /** Survival drops the exact last fish; creative removes it without a reward, as upstream does. */
    public boolean take(Location destination, boolean creative) { return take(destination, creative, this::usable); }

    private boolean take(Location destination, boolean creative, BooleanSupplier allowed) {
        if (destination == null || !usable() || busy || contents.size() == 0 || destination.getWorld() != location().getWorld()
                || !Bukkit.isOwnedByCurrentRegion(destination)) return false;
        String previous = furniture().currentVariant().name();
        List<ItemStack> next = contents.removing();
        ItemStack reward = contents.last();
        org.bukkit.entity.Item dropped = null;
        boolean committed = false;
        busy = true;
        try {
            if (!setVariant(FishRackContents.variant(next)) || !allowed.getAsBoolean()) return false;
            if (!creative) {
                dropped = destination.getWorld().dropItem(destination, reward);
                if (!dropped.isValid() || !dropped.getItemStack().equals(reward)) return false;
            }
            if (!usable() || !allowed.getAsBoolean()) return false;
            contents.replace(next);
            committed = true;
            changed();
            return true;
        } finally {
            if (!committed) {
                if (dropped != null && dropped.isValid()) dropped.remove();
                if (furniture().isValid()) setVariant(previous);
            }
            busy = false;
        }
    }

    private boolean setVariant(String variant) {
        return furniture().currentVariant().name().equals(variant) || furniture().setVariant(variant);
    }

    @Override public void onLoad() {
        if (blocked()) return;
        if (furniture().isValid()) { restoreDisplay(); return; }
        // CE invokes onLoad inside Bukkit's pre-spawn consumer. The entity is
        // not valid until that consumer returns. onPlace handles new placement
        // immediately; this entity-owned task also covers chunk restoration.
        var entity = (org.bukkit.entity.Entity) furniture().metaDataEntity.platformEntity();
        entity.getScheduler().run(plugin, task -> {
            var loaded = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (loaded != null && loaded.isValid() && loaded.controller.get(FishRackController.class, 0) == this
                    && operational() && !removed) restoreDisplay();
        }, null);
    }

    private void restoreDisplay() {
        if (!setVariant(FishRackContents.variant(contents.snapshot())))
            throw new IllegalStateException("Cannot restore fishrack display: valid=" + furniture().isValid()
                    + " current=" + furniture().currentVariant().name() + " at=" + location());
    }
    @Override public void onPlace(net.momirealms.craftengine.core.entity.player.Player player) {
        if (!blocked()) restoreDisplay();
    }

    @Override public void saveCustomData(CompoundTag root) {
        if (preserveRejected(root)) return;
        root.put(SAVE_KEY, savedContents(contents.snapshot()));
    }

    private static CompoundTag savedContents(List<ItemStack> slots) {
        CompoundTag data = new CompoundTag();
        data.putInt("count", slots.size());
        for (int i = 0; i < slots.size(); i++) SavedItems.put(data, "fish_" + i, slots.get(i));
        return SavedState.envelope(SavedState.namespace(SAVE_KEY), 1, SavedState.SERIES_SOURCE, data);
    }

    @Override public void loadCustomData(CompoundTag root) {
        restoreSafely(root, SAVE_KEY, () -> {
            if (!root.containsKey(SAVE_KEY)) { contents.replace(List.of()); return; }
            CompoundTag data = SavedState.payload(root.getCompound(SAVE_KEY), SavedState.namespace(SAVE_KEY),
                    SavedState.SERIES_SOURCE, 1, null);
            if (data == null) throw new IllegalArgumentException("Invalid fishrack save");
            int count = SavedState.integer(data, "count");
            if (count < 0 || count > 3) throw new IllegalArgumentException("Invalid fishrack slot count");
            List<ItemStack> restored = new ArrayList<>();
            for (int i = 0; i < count; i++) restored.add(SavedItems.get(data, "fish_" + i, 1));
            for (int i = count; i < 3; i++)
                if (data.containsKey("fish_" + i)) throw new IllegalArgumentException("Fish outside declared slot count");
            contents.replace(restored);
        });
    }

    @Override protected void beforeRemoval(net.momirealms.craftengine.core.entity.player.Player player) {
        // CE also removes the furniture when preRemove throws. Recover poisoned or
        // concurrently changing state before that irreversible removal.
        if (blocked() || busy) plugin.recoverFurniture(this);
    }

    @Override public void postRemove(net.momirealms.craftengine.core.entity.player.Player player) {
        if (removed) return;
        removed = true;
        if (blocked() || busy) return;
        busy = true;
        try {
            // Removal is confirmed here, so a canceled break cannot create free fish.
            while (contents.size() > 0) {
                ItemStack fish = contents.last();
                org.bukkit.entity.Item dropped = location().getWorld().dropItem(location(), fish);
                if (!dropped.isValid() || !dropped.getItemStack().equals(fish)) {
                    if (dropped.isValid()) dropped.remove();
                    break;
                }
                contents.replace(contents.removing());
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Fishrack drop failed; preserving remaining contents", error);
        } finally {
            busy = false;
            if (contents.size() > 0) plugin.recoverFurniture(this);
        }
    }
}
