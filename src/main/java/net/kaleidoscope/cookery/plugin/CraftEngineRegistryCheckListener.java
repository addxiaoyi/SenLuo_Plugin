package net.kaleidoscope.cookery.plugin;

import net.kaleidoscope.cookery.item.ItemNames;
import net.kaleidoscope.cookery.ui.RecipeMenuConfig;

import net.kaleidoscope.cookery.util.ConsoleMessages;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
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
import java.util.function.Supplier;

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
    private final BooleanSupplier ceReloading;
    private final Supplier<ResourceOperationCoordinator> resourceOperations;
    private final AtomicLong reloadGeneration = new AtomicLong();

    public CraftEngineRegistryCheckListener(KaleidoscopeCookeryPlugin plugin) {
        this(plugin, () -> CraftEngine.instance().isReloading(), () -> CraftEngine.instance().resourceOperations());
    }

    CraftEngineRegistryCheckListener(KaleidoscopeCookeryPlugin plugin, BooleanSupplier ceReloading,
                                     Supplier<ResourceOperationCoordinator> resourceOperations) {
        this.plugin = plugin;
        this.ceReloading = ceReloading;
        this.resourceOperations = resourceOperations;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
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
        // CE fires this event after manager delayed-load tasks, but its completion
        // flag clears after event dispatch. Its resource lease also excludes a new
        // reload between checking that flag and reading/binding the registry.
        long generation = reloadGeneration.incrementAndGet();
        long startedAt = System.nanoTime();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> lastShapeFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, retry -> {
            if (!plugin.isEnabled() || reloadGeneration.get() != generation) { retry.cancel(); return; }
            boolean registryReady = false;
            int installed = 0;
            Map<Key, ?> registrySnapshot = null;
            try {
                if (!ceReloading.getAsBoolean()) {
                    ResourceOperationCoordinator.Lease lease = null;
                    try {
                        lease = resourceOperations.get().acquire();
                    } catch (ResourceOperationCoordinator.BusyException busy) {
                        // Resource operations share the same exclusion as CE reloads.
                    }
                    if (lease != null) {
                        try {
                            if (!ceReloading.getAsBoolean()) {
                                Map<Key, ?> loadedBlocks = CraftEngineBlocks.loadedBlocks();
                                registrySnapshot = Map.copyOf(loadedBlocks);
                                registryReady = List.of("tomato_crop", "chili_crop", "lettuce_crop").stream()
                                        .allMatch(name -> loadedBlocks.containsKey(Key.of("kaleidoscopecookery:" + name)));
                                if (registryReady) {
                                    installed = net.kaleidoscope.cookery.block.behavior.SourceCropBehavior.installShapes();
                                    lastShapeFailure.set(null);
                                }
                            }
                        } finally {
                            lease.close();
                        }
                    }
                }
            } catch (RuntimeException partialReload) {
                // A failed or partial registry remains retryable within the deadline.
                lastShapeFailure.set(partialReload);
            }
            if (registryReady && installed == 3) {
                checkLoadedBlocks(firstReload, registrySnapshot);
                plugin.getLogger().info("Source crop native shapes rebound after CraftEngine reload: " + installed + "/3");
                retry.cancel();
            } else if (System.nanoTime() - startedAt > TimeUnit.SECONDS.toNanos(30)) {
                if (registrySnapshot != null) checkLoadedBlocks(firstReload, registrySnapshot);
                RuntimeException failure = lastShapeFailure.get();
                plugin.getLogger().severe("Source crop shapes were not rebound after CraftEngine reload: "
                        + installed + "/3; registryReady=" + registryReady
                        + (failure == null ? "" : "; last error=" + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage()));
                retry.cancel();
            }
        }, 1L, 1L);
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
