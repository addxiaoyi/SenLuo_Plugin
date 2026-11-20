package net.kaleidoscope.cookery.plugin;

import net.kaleidoscope.cookery.item.ItemNames;
import net.kaleidoscope.cookery.ui.RecipeMenuConfig;

import net.kaleidoscope.cookery.util.ConsoleMessages;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicReference;

public final class CraftEngineRegistryCheckListener implements Listener {
    private static final List<ExpectedBlock> EXPECTED_BLOCKS = List.of(
            block("kaleidoscopecookery:chopping_board", "custom/configuration/appliance/chopping_board.yml"),
            block("kaleidoscopecookery:enamel_basin", "custom/configuration/appliance/enamel_basin.yml"),
            block("kaleidoscopecookery:kitchenware_racks", "custom/configuration/appliance/kitchenware_racks.yml"),
            block("kaleidoscopecookery:pot", "custom/configuration/appliance/pot.yml"),
            block("kaleidoscopecookery:shawarma_spit", "custom/configuration/appliance/shawarma_spit.yml"),
            block("kaleidoscopecookery:steamer", "custom/configuration/appliance/steamer.yml"),
            block("kaleidoscopecookery:stockpot", "custom/configuration/appliance/stockpot.yml"),
            block("kaleidoscopecookery:stove", "custom/configuration/appliance/stove.yml"),
            block("kaleidoscopecookery:fruit_basket", "custom/configuration/furniture/fruit_basket.yml"),
            block("kaleidoscopecookery:teapot", "custom/configuration/furniture/teapot.yml"),
            block("kaleidoscopecookery:teacup_coaster", "custom/configuration/furniture/teapot.yml")
    );

    private final KaleidoscopeCookeryPlugin plugin;
    private final CeResourceReadGate sourceCropReadGate;
    private final BooleanSupplier ceReloading;
    private final AtomicLong reloadGeneration = new AtomicLong();

    private static final List<Key> SOURCE_CROP_KEYS = List.of(
            Key.of("kaleidoscopecookery:tomato_crop"), Key.of("kaleidoscopecookery:chili_crop"),
            Key.of("kaleidoscopecookery:lettuce_crop"));

    private record CropDefinition(BlockDefinition definition, List<ImmutableBlockState> states) {}
    private record CropGeneration(long reloadGeneration, Map<Key, CropDefinition> definitions) {}

    public CraftEngineRegistryCheckListener(KaleidoscopeCookeryPlugin plugin) {
        this(plugin, plugin.sourceCropReadGate(), () -> CraftEngine.instance().isReloading());
    }

    CraftEngineRegistryCheckListener(KaleidoscopeCookeryPlugin plugin, CeResourceReadGate sourceCropReadGate) {
        this(plugin, sourceCropReadGate, () -> CraftEngine.instance().isReloading());
    }

    CraftEngineRegistryCheckListener(KaleidoscopeCookeryPlugin plugin, CeResourceReadGate sourceCropReadGate,
                                     BooleanSupplier ceReloading) {
        this.plugin = plugin;
        this.sourceCropReadGate = sourceCropReadGate;
        this.ceReloading = ceReloading;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        if (sourceCropReadGate != null) sourceCropReadGate.invalidate();
        boolean firstReload = event.isFirstReload();
        ConsoleMessages.reloadFromDisk(plugin);
        // 物品显示名跟着配置走 重载后必须重算
        ItemNames.clearCache();
        // 顺带重读 config.yml 的菜单外观 改完图标标题不必重启
        plugin.reloadConfig();
        RecipeMenuConfig.load();
        plugin.reloadAdvancements();
        scheduleSourceCropRebind(firstReload);
    }

    void scheduleSourceCropRebind(boolean firstReload) {
        // Rebinding is a generation publication; no crop callback may join the
        // old generation while CE replaces definitions and their shape delegates.
        if (sourceCropReadGate != null) sourceCropReadGate.invalidate();
        long generation = reloadGeneration.incrementAndGet();
        long startedAt = System.nanoTime();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> lastShapeFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, retry -> {
            if (!plugin.isEnabled() || reloadGeneration.get() != generation) { retry.cancel(); return; }
            AtomicReference<Boolean> registryReady = new AtomicReference<>(false);
            AtomicReference<Map<Key, ?>> registrySnapshot = new AtomicReference<>();
            AtomicReference<Integer> installed = new AtomicReference<>(0);
            boolean published = false;
            try {
                if (!ceReloading.getAsBoolean()) {
                    published = sourceCropReadGate.publishWithLease(() -> {
                        if (ceReloading.getAsBoolean() || !plugin.isEnabled()
                                || reloadGeneration.get() != generation) return null;
                        Map<Key, ?> loadedBlocks = CraftEngineBlocks.loadedBlocks();
                        Map<Key, ?> snapshot = Map.copyOf(loadedBlocks);
                        registrySnapshot.set(snapshot);
                        Map<Key, CropDefinition> cropDefinitions = sourceCropDefinitions(loadedBlocks);
                        if (cropDefinitions == null) return null;
                        registryReady.set(true);
                        CropGeneration cropGeneration = new CropGeneration(generation, cropDefinitions);
                        return new CeResourceReadGate.Publication(cropGeneration,
                                () -> reloadGeneration.get() == generation && !ceReloading.getAsBoolean()
                                        && plugin.isEnabled() && sameSourceCropDefinitions(cropDefinitions),
                                () -> {
                                    int count = net.kaleidoscope.cookery.block.behavior.SourceCropBehavior.installShapes();
                                    installed.set(count);
                                    if (count != SOURCE_CROP_KEYS.size())
                                        throw new IllegalStateException("Expected 3 source-crop definitions, rebound " + count);
                                });
                    });
                    if (published) lastShapeFailure.set(null);
                }
            } catch (RuntimeException partialReload) {
                // A failed shape publication leaves the gate poisoned and closed.
                lastShapeFailure.set(partialReload);
            }
            if (published && registryReady.get() && installed.get() == SOURCE_CROP_KEYS.size()) {
                checkLoadedBlocks(firstReload, registrySnapshot.get());
                plugin.getLogger().info("Source crop native shapes rebound after CraftEngine reload: " + installed.get() + "/3");
                retry.cancel();
            } else if (System.nanoTime() - startedAt > TimeUnit.SECONDS.toNanos(30)) {
                if (registrySnapshot.get() != null) checkLoadedBlocks(firstReload, registrySnapshot.get());
                RuntimeException failure = lastShapeFailure.get();
                plugin.getLogger().severe("Source crop shapes were not rebound after CraftEngine reload: "
                        + installed.get() + "/3; registryReady=" + registryReady.get()
                        + (failure == null ? "" : "; last error=" + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage()));
                retry.cancel();
            }
        }, 1L, 1L);
    }

    private static Map<Key, CropDefinition> sourceCropDefinitions(Map<Key, ?> loadedBlocks) {
        Map<Key, CropDefinition> definitions = new java.util.LinkedHashMap<>();
        for (Key key : SOURCE_CROP_KEYS) {
            Object value = loadedBlocks.get(key);
            if (!(value instanceof BlockDefinition definition)) return null;
            definitions.put(key, new CropDefinition(definition,
                    List.copyOf(definition.variantProvider().states())));
        }
        return Map.copyOf(definitions);
    }

    private static boolean sameSourceCropDefinitions(Map<Key, CropDefinition> expected) {
        Map<Key, ?> current = CraftEngineBlocks.loadedBlocks();
        for (Key key : SOURCE_CROP_KEYS) {
            CropDefinition expectedDefinition = expected.get(key);
            if (current.get(key) != expectedDefinition.definition()) return false;
            List<ImmutableBlockState> states = List.copyOf(expectedDefinition.definition().variantProvider().states());
            if (states.size() != expectedDefinition.states().size()) return false;
            for (int i = 0; i < states.size(); i++)
                if (states.get(i) != expectedDefinition.states().get(i)) return false;
        }
        return true;
    }

    private void checkLoadedBlocks(boolean firstReload, Map<Key, ?> loadedBlocks) {
        List<ExpectedBlock> missing = new ArrayList<>();
        for (ExpectedBlock expected : EXPECTED_BLOCKS) {
            if (!loadedBlocks.containsKey(expected.key())) {
                missing.add(expected);
            }
        }

        String phase = ConsoleMessages.t(firstReload
                ? "craftengine.registry.phase.first"
                : "craftengine.registry.phase.reload");
        if (missing.isEmpty()) {
            plugin.getLogger().info(ConsoleMessages.t("craftengine.registry.blocks.ok",
                    phase, EXPECTED_BLOCKS.size(), EXPECTED_BLOCKS.size()));
            return;
        }

        plugin.getLogger().severe(ConsoleMessages.t("craftengine.registry.blocks.missing",
                phase, missing.size(), EXPECTED_BLOCKS.size()));
        for (ExpectedBlock expected : missing) {
            plugin.getLogger().severe(ConsoleMessages.t("craftengine.registry.blocks.missing.item",
                    expected.key().asString(), expected.configPath()));
        }
        plugin.getLogger().severe(ConsoleMessages.t("craftengine.registry.blocks.hint"));
    }

    private static ExpectedBlock block(String id, String configPath) {
        return new ExpectedBlock(Key.of(id), configPath);
    }

    private record ExpectedBlock(Key key, String configPath) {
    }
}
