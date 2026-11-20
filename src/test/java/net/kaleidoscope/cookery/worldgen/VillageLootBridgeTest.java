package net.kaleidoscope.cookery.worldgen;

import net.kaleidoscope.cookery.recipe.ApplianceFoodRegistry;
import net.kaleidoscope.cookery.recipe.ApplianceType;
import net.kaleidoscope.cookery.recipe.FoodRecipeRegistry;
import net.kaleidoscope.cookery.recipe.PotRecipe;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VillageLootBridgeTest {
    private final VillageLootSpec spec = VillageLootSpec.bundled();

    @BeforeAll static void initializeMaterialRegistries() {
        VillageTestRegistries.initializeMaterials();
    }

    private ItemStack stack(String token, int count, int max) {
        ItemStack result = mock(ItemStack.class); ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class); AtomicInteger amount = new AtomicInteger(count);
        when(result.getType()).thenReturn(Material.PAPER); when(result.hasItemMeta()).thenReturn(true); when(result.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc); when(pdc.has(VillageLootBridge.TOKEN)).thenReturn(token != null);
        when(pdc.get(VillageLootBridge.TOKEN, PersistentDataType.STRING)).thenReturn(token);
        when(result.getAmount()).thenAnswer(ignored -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(result).setAmount(anyInt());
        when(result.getMaxStackSize()).thenReturn(max);
        when(result.clone()).thenAnswer(ignored -> stack(token, amount.get(), max));
        return result;
    }
    private LootGenerateEvent event(List<ItemStack> draw) {
        LootGenerateEvent event = mock(LootGenerateEvent.class); LootTable table = mock(LootTable.class);
        when(table.getKey()).thenReturn(NamespacedKey.fromString("kaleidoscopecookery:chest/village_chest"));
        when(event.getLootTable()).thenReturn(table); when(event.getLoot()).thenReturn(draw);
        return event;
    }

    @Test void cancelledAndForeignEventsAreNotConvertedOrArchived() {
        VillageLootBridge.Pending pending = mock(VillageLootBridge.Pending.class);
        Function<VillageLootSpec.Token, ItemStack> factory = mock(Function.class);
        var bridge = new VillageLootBridge(spec, factory, pending);
        LootGenerateEvent cancelled = mock(LootGenerateEvent.class); when(cancelled.isCancelled()).thenReturn(true);
        bridge.onLoot(cancelled); verify(cancelled, never()).getLootTable();
        LootGenerateEvent foreign = event(List.of(stack("v1|item|kaleidoscope_cookery:tomato", 1, 64)));
        when(foreign.getLootTable().getKey()).thenReturn(NamespacedKey.minecraft("chests/village/village_plains_house"));
        bridge.onLoot(foreign); verify(foreign, never()).setLoot(any());
        verifyNoInteractions(factory, pending);
    }

    @Test void conversionPreservesNonTokensAndSplitsByRealCeMaxStackWithoutMutatingTheDraw() {
        var source = stack("v1|item|kaleidoscope_cookery:tomato", 7, 64); var foreign = stack(null, 3, 64);
        var built = stack(null, 1, 3); var bridge = new VillageLootBridge(spec, token -> built, (event, draw, failure) -> fail());
        List<ItemStack> result = bridge.convert(VillageLootSpec.NORMAL_TABLE, List.of(foreign, source));
        assertSame(foreign, result.get(0)); assertEquals(List.of(3, 3, 1), result.subList(1, 4).stream().map(ItemStack::getAmount).toList());
        assertEquals(7, source.getAmount()); assertEquals(1, built.getAmount());
        for (int i = 1; i < 4; i++) assertNotSame(built, result.get(i));
    }

    @Test void alreadyConvertedResultIsIdempotentAndBlankRecipeChoiceStaysExplicit() {
        AtomicInteger calls = new AtomicInteger();
        var bridge = new VillageLootBridge(spec, token -> { calls.incrementAndGet(); assertNull(token.recipe().recipe()); return stack(null, 1, 1); },
                (event, draw, failure) -> fail());
        var result = bridge.convert(VillageLootSpec.NORMAL_TABLE, List.of(stack("v1|recipe|0", 1, 64)));
        assertEquals(1, result.size()); assertEquals(1, calls.get());
        assertSame(result.get(0), bridge.convert(VillageLootSpec.NORMAL_TABLE, result).get(0)); assertEquals(1, calls.get());
    }

    @Test void noPartialConversionIsCommittedAndTheExactFailedDrawIsArchivedBeforeCancellation() {
        ItemStack good = stack("v1|item|kaleidoscope_cookery:tomato", 7, 64), bad = stack("v1|recipe|37", 1, 64);
        var original = new ArrayList<>(List.of(good, bad)); LootGenerateEvent event = event(original);
        VillageLootBridge.Pending pending = mock(VillageLootBridge.Pending.class);
        var bridge = new VillageLootBridge(spec, token -> stack(null, 1, 64), pending);
        doAnswer(call -> { verify(event, never()).setCancelled(true); assertEquals(original, call.getArgument(1)); return null; })
                .when(pending).save(same(event), anyList(), any());
        bridge.onLoot(event);
        verify(pending).save(same(event), eq(original), any(IllegalArgumentException.class));
        verify(event).setCancelled(true); verify(event).setLoot(List.of());
        assertEquals(7, good.getAmount()); assertEquals(1, bad.getAmount());
    }

    @Test void crossTableTokenIsEscrowedInsteadOfMintingAForeignItem() {
        LootGenerateEvent event = event(List.of(stack("v1|item|kaleidoscope_cookery:slime_ball_meal", 1, 64)));
        VillageLootBridge.Pending pending = mock(VillageLootBridge.Pending.class);
        Function<VillageLootSpec.Token, ItemStack> factory = mock(Function.class);
        new VillageLootBridge(spec, factory, pending).onLoot(event);
        verifyNoInteractions(factory); verify(pending).save(same(event), anyList(), any(IllegalArgumentException.class));
        verify(event).setCancelled(true); verify(event).setLoot(List.of());
    }

    @Test void linkageFailureStillEscrowsAndCancelsBeforeNativeFillCanSeeTokens() {
        LootGenerateEvent event = event(List.of(stack("v1|recipe|0", 1, 64)));
        VillageLootBridge.Pending pending = mock(VillageLootBridge.Pending.class);
        new VillageLootBridge(spec, token -> { throw new NoClassDefFoundError("optional CE component"); }, pending).onLoot(event);
        verify(pending).save(same(event), anyList(), any(NoClassDefFoundError.class));
        verify(event).setCancelled(true); verify(event).setLoot(List.of());
    }

    @Test void pendingRecorderFailureStillCannotLeakNativeTokensIntoTheContainer() {
        LootGenerateEvent event = event(List.of(stack("v1|recipe|99", 1, 64)));
        var bridge = new VillageLootBridge(spec, token -> fail(), (ignored, draw, failure) -> { throw new IllegalStateException("disk and log failure"); });
        assertThrows(IllegalStateException.class, () -> bridge.onLoot(event));
        verify(event).setCancelled(true); verify(event).setLoot(List.of());
    }

    @Test void invalidCountMissingCeItemAndResidualTokenAreRejectedBeforeAnyInventoryWrite() {
        for (int count : new int[]{0, 4097}) {
            var bridge = new VillageLootBridge(spec, token -> stack(null, 1, 64), (event, draw, failure) -> fail());
            assertThrows(IllegalArgumentException.class, () -> bridge.convert(VillageLootSpec.NORMAL_TABLE, List.of(stack("v1|recipe|0", count, 64))));
        }
        var missing = new VillageLootBridge(spec, token -> null, (event, draw, failure) -> fail());
        assertThrows(IllegalArgumentException.class, () -> missing.convert(VillageLootSpec.NORMAL_TABLE, List.of(stack("v1|recipe|0", 1, 64))));
        var tokenFactory = new VillageLootBridge(spec, token -> stack("v1|recipe|0", 1, 64), (event, draw, failure) -> fail());
        assertThrows(IllegalArgumentException.class, () -> tokenFactory.convert(VillageLootSpec.NORMAL_TABLE, List.of(stack("v1|recipe|0", 1, 64))));
    }

    @Test void busyCeResourceOperationEscrowsTheExactDrawBeforeAnyConversionAndCanRetryAfterRelease() {
        var resources = new ResourceOperationCoordinator();
        Runnable preflight = mock(Runnable.class);
        Function<VillageLootSpec.Token, ItemStack> factory = mock(Function.class);
        when(factory.apply(any())).thenAnswer(ignored -> stack(null, 1, 64));
        VillageLootBridge.Pending pending = mock(VillageLootBridge.Pending.class);
        var bridge = new VillageLootBridge(spec, factory, pending, preflight, resources, () -> true);
        ItemStack token = stack("v1|item|kaleidoscope_cookery:tomato", 7, 64), ordinary = stack(null, 3, 64);
        var original = new ArrayList<>(List.of(ordinary, token));
        LootGenerateEvent event = event(original);
        try (var ignored = resources.acquire()) {
            bridge.onLoot(event);
            verifyNoInteractions(preflight, factory);
            ArgumentCaptor<List<ItemStack>> saved = ArgumentCaptor.forClass(List.class);
            verify(pending).save(same(event), saved.capture(), any(ResourceOperationCoordinator.BusyException.class));
            assertSame(ordinary, saved.getValue().get(0)); assertSame(token, saved.getValue().get(1));
            assertEquals(7, token.getAmount()); assertEquals(3, ordinary.getAmount());
            verify(event).setCancelled(true); verify(event).setLoot(List.of());
        }
        assertFalse(resources.isBusy());
        assertEquals(2, bridge.convert(VillageLootSpec.NORMAL_TABLE, original).size());
        verify(preflight).run(); verify(factory).apply(any());
    }

    @Test void preflightAndEveryTokenFactoryHoldTheSameRealCeLeaseAndFailuresReleaseIt() {
        var resources = new ResourceOperationCoordinator();
        AtomicInteger preflights = new AtomicInteger(), factories = new AtomicInteger();
        AtomicBoolean failFactory = new AtomicBoolean(true);
        Runnable held = () -> assertThrows(ResourceOperationCoordinator.BusyException.class, () -> {
            try (var unexpected = resources.acquire()) { fail("A second resource operation entered a token conversion"); }
        });
        var bridge = new VillageLootBridge(spec, token -> {
            held.run(); factories.incrementAndGet();
            if (failFactory.get()) throw new IllegalStateException("fixture build failure");
            return stack(null, 1, 64);
        }, (event, draw, failure) -> fail(), () -> { held.run(); preflights.incrementAndGet(); }, resources, () -> true);
        var draw = List.of(stack("v1|item|kaleidoscope_cookery:tomato", 1, 64),
                stack("v1|item|kaleidoscope_cookery:red_chili", 1, 64));
        assertThrows(IllegalStateException.class, () -> bridge.convert(VillageLootSpec.NORMAL_TABLE, draw));
        assertFalse(resources.isBusy(), "The failing conversion must not leave CE resource operations blocked");
        failFactory.set(false);
        assertEquals(2, bridge.convert(VillageLootSpec.NORMAL_TABLE, draw).size());
        assertEquals(2, preflights.get()); assertEquals(3, factories.get());
        assertFalse(resources.isBusy());
    }

    @Test void oneRealRecipeSnapshotSpansPreflightAndAllTokensDespiteAnInterveningApiPublication() {
        FoodRecipeRegistry registry = FoodRecipeRegistry.instance();
        List<PotRecipe> baseline = registry.potRecipes();
        Key recipeId = Key.of("test:village_snapshot_recipe"), input = Key.of("test:village_snapshot_input");
        var before = new PotRecipe(recipeId, PotRecipe.Mode.EXACT, List.of(Set.of(input)), Key.of("minecraft:bread"), 1, 1, 1, null);
        var after = new PotRecipe(recipeId, PotRecipe.Mode.EXACT, List.of(Set.of(input)), Key.of("minecraft:carrot"), 1, 1, 1, null);
        AtomicInteger calls = new AtomicInteger(); var observed = new ArrayList<PotRecipe>();
        try {
            registry.registerPot(before);
            var bridge = new VillageLootBridge(spec, token -> {
                if (calls.getAndIncrement() == 0) registry.atomicUpdate(() -> {
                    registry.clearPot(); baseline.forEach(registry::registerPot); registry.registerPot(after);
                });
                observed.add(registry.findPotById(recipeId));
                return stack(null, 1, 64);
            }, (event, draw, failure) -> fail(), () -> observed.add(registry.findPotById(recipeId)),
                    new ResourceOperationCoordinator(), () -> true);
            var result = bridge.convert(VillageLootSpec.NORMAL_TABLE, List.of(
                    stack("v1|item|kaleidoscope_cookery:tomato", 1, 64),
                    stack("v1|item|kaleidoscope_cookery:red_chili", 1, 64)));
            assertEquals(2, result.size());
            assertEquals(3, observed.size()); observed.forEach(value -> assertSame(before, value));
            assertSame(after, registry.findPotById(recipeId), "The API publication is visible after the complete draw");
        } finally {
            registry.atomicUpdate(() -> { registry.clearPot(); baseline.forEach(registry::registerPot); });
            ApplianceFoodRegistry.instance().unregister(ApplianceType.POT, input);
        }
    }

    @Test void lifecycleNotReadyEscrowsTokensButTokenFreeAndForeignEventsRemainUntouched() {
        var resources = new ResourceOperationCoordinator();
        Runnable preflight = mock(Runnable.class);
        Function<VillageLootSpec.Token, ItemStack> factory = mock(Function.class);
        VillageLootBridge.Pending pending = mock(VillageLootBridge.Pending.class);
        var bridge = new VillageLootBridge(spec, factory, pending, preflight, resources, () -> false);
        ItemStack token = stack("v1|item|kaleidoscope_cookery:tomato", 1, 64);
        LootGenerateEvent marked = event(List.of(token)); bridge.onLoot(marked);
        verify(pending).save(same(marked), eq(List.of(token)), any(IllegalArgumentException.class));
        verify(marked).setCancelled(true); verify(marked).setLoot(List.of());
        assertFalse(resources.isBusy());
        try (var ignored = resources.acquire()) {
            var ordinary = stack(null, 3, 64);
            assertSame(ordinary, bridge.convert(VillageLootSpec.NORMAL_TABLE, List.of(ordinary)).getFirst());
            LootGenerateEvent unmarked = event(List.of(ordinary)); bridge.onLoot(unmarked);
            verify(unmarked, never()).setLoot(any()); verify(unmarked, never()).setCancelled(anyBoolean());
            LootGenerateEvent foreign = event(List.of(token));
            when(foreign.getLootTable().getKey()).thenReturn(NamespacedKey.minecraft("chests/village/village_plains_house"));
            bridge.onLoot(foreign); verify(foreign, never()).setLoot(any());
        }
        verifyNoInteractions(preflight, factory); verifyNoMoreInteractions(pending);
    }
}
