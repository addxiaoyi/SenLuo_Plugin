package net.kaleidoscope.deco;

import net.kaleidoscope.series.InteractiveFurniture;
import net.kaleidoscope.series.InventoryTrade;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.ItemState;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class DecoController extends InteractiveFurniture {
    private static final String SAVE_KEY = "kaleidoscope_deco:state_v1";
    private static final String SOFA = "kaleidoscope_deco:kaleidoscope_bar/sofa";
    private static final List<String> MODES = List.of("single", "left", "right", "middle");
    private static final Set<String> COLORS = Set.of("none", "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
            "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private final DecoPlugin deco;
    private String state;
    private String color = "none";
    private String mode = "single";
    private boolean transitioning;

    DecoController(Furniture furniture, DecoPlugin plugin) {
        super(furniture, plugin); deco = plugin; state = furniture.id().asString();
    }
    private boolean sofa() { return state.equals(SOFA); }
    private String variant(String nextState) {
        return nextState.replace(':', '/') + (SOFA.equals(nextState) ? "/style/" + color + "/" + mode : "");
    }

    private DecoRule rule(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        String id = Items.id(held);
        return deco.entry(state).rules().stream().filter(rule -> rule.matches(id, player.isSneaking(), name -> {
            if (!id.startsWith("minecraft:")) return false;
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.fromString(name), Material.class);
            return tag != null && tag.isTagged(held.getType());
        })).findFirst().orElse(null);
    }

    private boolean sofaAction(Player player) {
        if (!sofa()) return false;
        String id = Items.id(player.getInventory().getItemInMainHand());
        return id.equals("kaleidoscope_deco:state_tool") || id.equals("minecraft:water_bucket")
                || id.startsWith("minecraft:") && id.endsWith("_dye") && COLORS.contains(id.substring(10, id.length() - 4));
    }

    @Override protected boolean consumesInteraction(Player player) { return transitioning || sofaAction(player) || rule(player) != null; }

    @Override protected void interact(Player player) {
        if (transitioning) return;
        if (sofaAction(player)) { style(player); return; }
        DecoRule rule = rule(player);
        if (rule == null) return;
        if (rule.pending()) { player.sendMessage("这件家具的特殊事件尚未移植，当前状态已保留。"); return; }
        if (!rule.target().isEmpty() && !transition(player, rule)) return;
        for (String sound : rule.sounds()) location().getWorld().playSound(location(), sound.replace("kaleidoscope:", "kdeco_kaleidoscope:"), SoundCategory.BLOCKS, 1, 1);
    }

    private boolean transition(Player player, DecoRule rule) {
        transitioning = true;
        Item drop = null;
        boolean committed = false;
        try {
            var inventory = player.getInventory();
            int heldSlot = inventory.getHeldItemSlot();
            ItemStack held = inventory.getItemInMainHand().clone();
            String target = rule.target();
            String nextVariant = variant(target);
            if (!furniture().config().variants().containsKey(nextVariant)) {
                throw new IllegalStateException("Missing Deco variant: " + target);
            }
            var source = sourceItem(target);
            ItemStack remaining = null;
            if (rule.consume() && player.getGameMode() != GameMode.CREATIVE) {
                if (held.isEmpty()) return false;
                remaining = held.clone().subtract(1);
            }
            if (!rule.drops().isEmpty()) {
                String id = rule.drops().get(ThreadLocalRandom.current().nextInt(rule.drops().size()));
                ItemStack reward = Items.create(id);
                var at = location();
                // The spawn hook can deny the return. Keep the decorated board in that case.
                drop = at.getWorld().dropItem(at, reward.clone(), item -> item.setPickupDelay(10));
                if (!drop.isValid() || !reward.equals(drop.getItemStack()) || !accessible(player)
                        || inventory.getHeldItemSlot() != heldSlot || !sameItem(held, inventory.getItemInMainHand())) return false;
            }
            if (!furniture().setVariant(nextVariant)) {
                return false;
            }
            state = target;
            furniture().setSourceItem(source);
            changed();
            if (remaining != null) inventory.setItemInMainHand(remaining);
            committed = true;
            return true;
        } finally {
            if (!committed && drop != null && drop.isValid()) drop.remove();
            transitioning = false;
        }
    }

    private void style(Player player) {
        transitioning = true;
        try {
            styleOnce(player);
        } finally {
            transitioning = false;
        }
    }

    private void styleOnce(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        String id = Items.id(held), nextColor = color, nextMode = mode;
        int cost = 0;
        if (id.equals("kaleidoscope_deco:state_tool")) nextMode = MODES.get((MODES.indexOf(mode) + 1) % MODES.size());
        else if (id.equals("minecraft:water_bucket")) nextColor = "none";
        else { nextColor = id.substring(10, id.length() - 4); cost = player.getGameMode() == GameMode.CREATIVE ? 0 : 1; }
        if (color.equals(nextColor) && mode.equals(nextMode)) return;
        String nextVariant = state.replace(':', '/') + "/style/" + nextColor + "/" + nextMode;
        // Validate the generated visual before taking a dye.
        if (!deco.styles().contains(nextVariant) || !furniture().config().variants().containsKey(nextVariant))
            throw new IllegalStateException("Missing sofa style: " + nextVariant);
        var source = sourceItem(state, nextColor, nextMode);
        String committedColor = nextColor, committedMode = nextMode;
        int heldSlot = player.getInventory().getHeldItemSlot();
        ItemStack expected = held.clone();
        if (!InventoryTrade.exchange(player.getInventory(), held::isSimilar, cost, List.of(), () -> {
            if (!accessible(player) || blocked() || player.getInventory().getHeldItemSlot() != heldSlot
                    || !sameItem(expected, player.getInventory().getItemInMainHand())) return false;
            if (!furniture().setVariant(nextVariant)) return false;
            color = committedColor; mode = committedMode;
            furniture().setSourceItem(source);
            changed();
            return true;
        })) return;
        location().getWorld().playSound(location(), "minecraft:entity.llama.swag", 1, 1);
    }

    private static boolean sameItem(ItemStack expected, ItemStack actual) {
        return actual != null && expected.getAmount() == actual.getAmount() && expected.isSimilar(actual);
    }

    private void saveSource() {
        if (!blocked()) furniture().setSourceItem(sourceItem(state));
    }

    private net.momirealms.craftengine.core.item.Item sourceItem(String nextState) {
        return sourceItem(nextState, color, mode);
    }

    net.momirealms.craftengine.core.item.Item sourceItem(String nextState, String nextColor, String nextMode) {
        CompoundTag saved = new CompoundTag();
        saved.put(SAVE_KEY, savedState(nextState, nextColor, nextMode));
        ItemStack original = furniture().sourceItem() == null ? Items.create(furniture().id().asString())
                : bukkitStack(furniture().sourceItem()).asQuantity(1);
        ItemStack item = ItemState.write(original, deco.stateKey(), saved);
        return BukkitItemManager.instance().wrap(item);
    }

    ItemStack bukkitStack(net.momirealms.craftengine.core.item.Item item) {
        return ItemStackUtils.getBukkitStack(item);
    }

    @Override public void onLoad() { if (!blocked()) { furniture().setVariant(variant(state)); saveSource(); } }
    @Override public void onPlace(net.momirealms.craftengine.core.entity.player.Player player) { onLoad(); }
    @Override public void loadCustomDataFromItem(net.momirealms.craftengine.core.item.Item item) {
        ItemStack original = bukkitStack(item);
        CompoundTag root = new CompoundTag(), rejected = new CompoundTag();
        net.kaleidoscope.series.SavedItems.put(rejected, "source_item", original);
        root.put(SAVE_KEY, rejected);
        restoreSafely(root, SAVE_KEY, () -> {
            CompoundTag saved = ItemState.read(original, deco.stateKey());
            if (saved != null) {
                loadCustomData(saved);
                // CE restores held-item data after onLoad/onPlace; refresh the visual and future drop now.
                onLoad();
            }
        });
    }
    @Override public void saveCustomData(CompoundTag root) {
        if (preserveRejected(root)) return;
        root.put(SAVE_KEY, savedState(state));
    }

    private CompoundTag savedState(String nextState) {
        return savedState(nextState, color, mode);
    }

    private CompoundTag savedState(String nextState, String nextColor, String nextMode) {
        CompoundTag saved = new CompoundTag(); saved.putString("state", nextState);
        saved.putString("color", nextColor); saved.putString("mode", nextMode);
        return SavedState.envelope(SavedState.namespace(SAVE_KEY), 1, SavedState.SERIES_SOURCE, saved);
    }
    @Override public void loadCustomData(CompoundTag root) {
        restoreSafely(root, SAVE_KEY, () -> {
            if (!root.containsKey(SAVE_KEY)) return;
            CompoundTag saved = SavedState.payload(root.getCompound(SAVE_KEY), SavedState.namespace(SAVE_KEY),
                    SavedState.SERIES_SOURCE, 1, null);
            if (saved == null) throw new IllegalArgumentException("Invalid Deco save");
            state = SavedState.text(saved, "state"); color = SavedState.text(saved, "color"); mode = SavedState.text(saved, "mode");
            if (!COLORS.contains(color) || !MODES.contains(mode) || !deco.reachable(furniture().id().asString(), state))
                throw new IllegalArgumentException("Unknown or unrelated Deco state");
        });
    }
}
