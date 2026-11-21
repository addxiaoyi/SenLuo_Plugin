package net.kaleidoscope.cookery.worldgen;

import net.kaleidoscope.cookery.item.ItemKeys;
import net.kaleidoscope.cookery.recipe.FoodRecipeRegistry;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.RecipeUtils;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.Objects;

/** Converts the already rolled native loot list once; it never owns a random source or fills an inventory. */
public final class VillageLootBridge implements Listener {
    static final NamespacedKey TOKEN = NamespacedKey.fromString(VillageLootSpec.TOKEN_KEY);
    @FunctionalInterface interface Pending { void save(LootGenerateEvent event, List<ItemStack> draw, Throwable failure); }
    private final VillageLootSpec spec;
    private final Function<VillageLootSpec.Token, ItemStack> factory;
    private final Pending pending;
    private final Runnable preflight;
    private final ResourceOperationCoordinator resources;
    private final BooleanSupplier lifecycleReady;

    public static void register(JavaPlugin plugin) {
        VillageLootSpec spec = VillageLootSpec.bundled();
        var recovery = new VillageLootPending(plugin, spec);
        CraftEngine ce = CraftEngine.instance();
        var bridge = new VillageLootBridge(spec, token -> buildSnapshot(spec, token), recovery::save,
                () -> preflightSnapshot(spec), ce.resourceOperations(),
                () -> !ce.isDisabled() && !ce.isStopping() && !ce.isEnabling() && !ce.isReloading());
        recovery.converter(bridge::convert);
        plugin.getServer().getPluginManager().registerEvents(bridge, plugin);
        plugin.getServer().getPluginManager().registerEvents(recovery, plugin);
        Objects.requireNonNull(plugin.getCommand("kcvillageloot"), "Missing kcvillageloot command").setExecutor(recovery);
    }
    VillageLootBridge(VillageLootSpec spec, Function<VillageLootSpec.Token, ItemStack> factory, Pending pending) {
        this(spec, factory, pending, () -> {}, new ResourceOperationCoordinator(), () -> true);
    }
    VillageLootBridge(VillageLootSpec spec, Function<VillageLootSpec.Token, ItemStack> factory, Pending pending,
                      Runnable preflight, ResourceOperationCoordinator resources, BooleanSupplier lifecycleReady) {
        this.spec = spec; this.factory = factory; this.pending = pending; this.preflight = preflight;
        this.resources = resources; this.lifecycleReady = lifecycleReady;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        if (event.isCancelled() || !spec.tables().contains(event.getLootTable().getKey().toString())) return;
        List<ItemStack> original = new ArrayList<>(event.getLoot());
        try {
            if (original.stream().noneMatch(VillageLootBridge::hasToken)) return;
            event.setLoot(convert(event.getLootTable().getKey().toString(), original));
        }
        catch (Throwable failure) {
            // Native RandomizableContainer cleared its LootTable before this callback.
            // Escrow this exact draw before cancellation; recovery never invokes a loot table.
            try { pending.save(event, original, failure); }
            finally { event.setCancelled(true); event.setLoot(List.of()); }
        }
    }

    List<ItemStack> convert(String table, List<ItemStack> original) {
        VillageLootSpec.require(spec.tables().contains(table) && original.size() <= 4096, "Invalid village draw");
        if (original.stream().noneMatch(VillageLootBridge::hasToken)) return new ArrayList<>(original);
        // CE's lease excludes a complete intervening resource reload, including item
        // definitions. One outer recipe snapshot also pins API recipe edits across
        // preflight and every token, rather than taking a fresh generation per item.
        try (var ignored = resources.acquire()) {
            FoodRecipeRegistry registry = FoodRecipeRegistry.instance();
            VillageLootSpec.require(lifecycleReady.getAsBoolean() && !registry.isConfigurationLoading(),
                    "Village loot dependencies are still loading");
            return registry.readSnapshot(() -> convertSnapshot(table, original));
        }
    }

    private List<ItemStack> convertSnapshot(String table, List<ItemStack> original) {
        preflight.run();
        var result = new ArrayList<ItemStack>();
        for (ItemStack stack : original) {
            if (!hasToken(stack)) { result.add(stack); continue; }
            VillageLootSpec.Token token = spec.token(stack.getItemMeta().getPersistentDataContainer().get(TOKEN, PersistentDataType.STRING), table);
            VillageLootSpec.require(stack.getType() == Material.PAPER && stack.getAmount() > 0 && stack.getAmount() <= 4096, "Invalid token carrier/count");
            ItemStack built = factory.apply(token);
            VillageLootSpec.require(built != null && !built.getType().isAir() && !hasToken(built), "Village conversion produced an invalid/token item");
            int limit = built.getMaxStackSize();
            VillageLootSpec.require(limit > 0 && limit <= 99, "Invalid CE max stack size");
            for (int remaining = stack.getAmount(); remaining > 0;) {
                ItemStack copy = built.clone(); int count = Math.min(remaining, limit); copy.setAmount(count);
                result.add(copy); remaining -= count;
            }
        }
        return result;
    }

    private static boolean hasToken(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(TOKEN);
    }

    /** Validate every possible conversion before accepting any result from a native draw. */
    private static void preflightSnapshot(VillageLootSpec spec) {
        var candidates = new ArrayList<VillageLootSpec.Token>();
        spec.itemMappings().keySet().forEach(source -> candidates.add(new VillageLootSpec.Token(source, null)));
        spec.recipes().forEach(choice -> candidates.add(new VillageLootSpec.Token(null, choice)));
        for (var token : candidates) {
            ItemStack item = buildSnapshot(spec, token);
            VillageLootSpec.require(item != null && !item.getType().isAir() && !hasToken(item)
                    && item.getMaxStackSize() > 0 && item.getMaxStackSize() <= 99, "Invalid village conversion dependency");
        }
    }

    private static ItemStack buildSnapshot(VillageLootSpec spec, VillageLootSpec.Token token) {
        Key key = token.recipe() == null ? Key.of(spec.itemMappings().get(token.sourceItem()))
                : token.recipe().recipe() == null ? ItemKeys.RECIPE_ITEM_NO_RECIPE : ItemKeys.RECIPE_ITEM_HAS_RECIPE;
        var item = InventoryUtils.createOrEmpty(key);
        if (ItemUtils.isEmpty(item)) throw new IllegalStateException("Unavailable village loot item: " + key);
        ItemStack result = InventoryUtils.bukkitCopy(item);
        if (token.recipe() != null && token.recipe().recipe() != null) {
            var recipe = token.recipe().recipe();
            var inputs = recipe.ingredients().stream().map(Key::of).toList();
            // A reload may change recipes independently of the pinned source; do not mint a stale functional record.
            if (recipe.type().equals("pot")) {
                var current = FoodRecipeRegistry.instance().findPotById(Key.of(recipe.id()));
                VillageLootSpec.require(current != null && current.matches(inputs) && current.result().asString().equals(recipe.result())
                        && current.resultCount() == recipe.resultCount(), "Village pot recipe no longer matches source: " + recipe.id());
            } else {
                var current = FoodRecipeRegistry.instance().findStockpotById(Key.of(recipe.id()));
                VillageLootSpec.require(current != null && current.matches(inputs, Key.of(recipe.soupBase()))
                        && current.result().asString().equals(recipe.result()) && current.resultCount() == recipe.resultCount(),
                        "Village stockpot recipe no longer matches source: " + recipe.id());
            }
            RecipeUtils.setRecipeItem(result, Key.of(recipe.id()), recipe.type(), inputs,
                    recipe.soupBase() == null ? null : Key.of(recipe.soupBase()));
            if (!RecipeUtils.hasRecipe(result)) throw new IllegalStateException("Recipe item record was not written: " + recipe.id());
        }
        return result;
    }
}
