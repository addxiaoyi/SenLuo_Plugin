package net.kaleidoscope.tavern;

import com.google.gson.Gson;
import net.kaleidoscope.series.InteractiveFurniture;
import net.kaleidoscope.series.FurnitureTickers;
import net.kaleidoscope.series.InventoryTrade;
import net.kaleidoscope.series.Items;
import net.kaleidoscope.series.SavedState;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.tick.FurnitureTicker;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TavernPersistenceTest {
    private static final String PRESSING = "kaleidoscope_tavern:pressing_v1";
    private static final String BARREL = "kaleidoscope_tavern:barrel_v1";
    private static final String BOTTLE = "kaleidoscope_tavern:bottle_v1";
    private static final String SHAKER = "kaleidoscope_tavern:shaker_v1";
    private static final String JUICE = "kaleidoscope_tavern:glow_berries_juice";

    @Test void newFurnitureAcceptsAnEmptyCraftEngineDataCompound() {
        try (var fixture = new Fixture()) {
            List<InteractiveFurniture> controllers = List.of(
                    new BarrelController(fixture.furniture, fixture.plugin),
                    new PressingController(fixture.furniture, fixture.plugin),
                    new BottleController(fixture.furniture, fixture.plugin),
                    new ShakerController(fixture.furniture, fixture.plugin),
                    new GrapeController(fixture.furniture, fixture.plugin),
                    new TapController(fixture.furniture, fixture.plugin));
            for (var controller : controllers) {
                controller.loadCustomData(new CompoundTag());
                assertFalse(controller.blocked(), controller.getClass().getSimpleName());
                assertFalse(controller.hasContents(), controller.getClass().getSimpleName());
            }
        }
    }

    @Test void aPresentButWronglyTypedSaveIsBlockedAndPreserved() {
        try (var fixture = new Fixture()) {
            Map<String, InteractiveFurniture> controllers = Map.of(
                    BARREL, new BarrelController(fixture.furniture, fixture.plugin),
                    PRESSING, new PressingController(fixture.furniture, fixture.plugin),
                    BOTTLE, new BottleController(fixture.furniture, fixture.plugin),
                    SHAKER, new ShakerController(fixture.furniture, fixture.plugin));
            controllers.forEach((key, controller) -> {
                var original = new CompoundTag();
                original.putString(key, "opaque damaged data");
                controller.loadCustomData(original);
                assertTrue(controller.blocked(), key);
                var recovery = new CompoundTag();
                controller.saveCustomData(recovery);
                assertEquals("opaque damaged data", recovery.getString(key));
            });
        }
    }

    @Test void pressingIngredientsCanBeRecoveredAfterRestartAndDoNotReturnOnTheNextRestart() {
        try (var fixture = new Fixture(); var trades = mockStatic(InventoryTrade.class)) {
            ItemStack berries = fixture.stack(1);
            var restored = new PressingController(fixture.furniture, fixture.plugin);
            restored.loadCustomData(root(PRESSING, saved -> {
                saved.putInt("amount", 0);
                saved.putByteArray("ingredients", berries.serializeAsBytes());
            }));
            assertFalse(restored.blocked());
            assertTrue(restored.hasContents());
            when(fixture.player.isSneaking()).thenReturn(true);
            fixture.hold(fixture.stack(0));
            var rewards = new AtomicReference<List<ItemStack>>();
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(0), anyList(), any()))
                    .thenAnswer(call -> {
                        rewards.set(call.getArgument(3));
                        return call.getArgument(4, BooleanSupplier.class).getAsBoolean();
                    });

            restored.interact(fixture.player);

            assertEquals(List.of(berries), rewards.get());
            assertFalse(restored.hasContents());
            var checkpoint = save(restored);
            assertFalse(payload(checkpoint, PRESSING).containsKey("ingredients"));
            var afterRestart = new PressingController(fixture.furniture, fixture.plugin);
            afterRestart.loadCustomData(checkpoint);
            assertFalse(afterRestart.blocked());
            assertFalse(afterRestart.hasContents());
        }
    }

    @Test void aFullJuiceTubAfterRestartExchangesOneBucketAndSavesEmpty() {
        try (var fixture = new Fixture(); var ids = mockStatic(Items.class); var trades = mockStatic(InventoryTrade.class)) {
            var restored = new PressingController(fixture.furniture, fixture.plugin);
            restored.loadCustomData(root(PRESSING, saved -> {
                saved.putInt("amount", 1000);
                saved.putString("fluid", JUICE);
            }));
            ItemStack emptyBucket = fixture.stack(1), fullBucket = fixture.stack(1);
            fixture.hold(emptyBucket);
            ids.when(() -> Items.id(emptyBucket)).thenReturn("minecraft:bucket");
            ids.when(() -> Items.create("kaleidoscope_tavern:glow_berries_bucket")).thenReturn(fullBucket);
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(1), eq(List.of(fullBucket)), any()))
                    .thenAnswer(call -> call.getArgument(4, BooleanSupplier.class).getAsBoolean());

            restored.interact(fixture.player);

            assertFalse(restored.blocked());
            assertFalse(restored.hasContents());
            var saved = payload(save(restored), PRESSING);
            assertEquals(0, saved.getInt("amount"));
            assertFalse(saved.containsKey("fluid"));
            trades.verify(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(1), eq(List.of(fullBucket)), any()));
        }
    }

    @Test void aSavedBarrelReturnsItsLastWaterBucketWithoutRevivingItAfterRestart() {
        try (var fixture = new Fixture(); var ids = mockStatic(Items.class); var trades = mockStatic(InventoryTrade.class)) {
            var restored = new BarrelController(fixture.furniture, fixture.plugin);
            restored.loadCustomData(root(BARREL, saved -> {
                saved.putBoolean("open", true);
                saved.putInt("buckets", 1);
                saved.putString("fluid", "minecraft:water");
                saved.putInt("check_ticks", 0);
            }));
            ItemStack emptyBucket = fixture.stack(1), fullBucket = fixture.stack(1);
            fixture.hold(emptyBucket);
            ids.when(() -> Items.id(emptyBucket)).thenReturn("minecraft:bucket");
            ids.when(() -> Items.create("minecraft:water_bucket")).thenReturn(fullBucket);
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(1), eq(List.of(fullBucket)), any()))
                    .thenAnswer(call -> call.getArgument(4, BooleanSupplier.class).getAsBoolean());

            restored.interact(fixture.player);

            assertFalse(restored.blocked());
            assertFalse(restored.hasContents());
            var checkpoint = save(restored);
            assertEquals(0, payload(checkpoint, BARREL).getInt("buckets"));
            assertFalse(payload(checkpoint, BARREL).containsKey("fluid"));
            var afterRestart = new BarrelController(fixture.furniture, fixture.plugin);
            afterRestart.loadCustomData(checkpoint);
            assertFalse(afterRestart.blocked());
            assertFalse(afterRestart.hasContents());
        }
    }

    @Test void savedBarrelBatchKeepsItsServingsMaturityAndCheckBoundary() {
        try (var fixture = new Fixture(); var tickers = mockStatic(FurnitureTickers.class)) {
            tickers.when(() -> FurnitureTickers.current(eq(BarrelController.class), any())).thenAnswer(call -> {
                Consumer<BarrelController> action = call.getArgument(1);
                return (FurnitureTicker<BarrelController>) (furniture, controller) -> action.accept(controller);
            });
            var recipe = new BrewRecipe("kaleidoscope_tavern:barrel/test", "minecraft:water", List.of(),
                    List.of("kaleidoscope_tavern:empty_bottle"), "kaleidoscope_tavern:wine", 2400);
            var restored = new BarrelController(fixture.furniture, fixture.plugin);
            restored.loadCustomData(root(BARREL, saved -> {
                saved.putBoolean("open", false);
                saved.putInt("buckets", 0);
                saved.putInt("check_ticks", BrewBatch.CHECK_INTERVAL - 1);
                saved.putString("recipe", new Gson().toJson(recipe));
                saved.putInt("servings", 7);
                saved.putInt("level", 3);
                saved.putLong("remaining", 100);
            }));

            restored.<BarrelController>createFurnitureTicker().tick(fixture.furniture, restored);

            assertFalse(restored.blocked());
            var saved = payload(save(restored), BARREL);
            assertEquals(7, saved.getInt("servings"));
            assertEquals(3, saved.getInt("level"));
            assertEquals(3, saved.getLong("remaining"));
            assertEquals(0, saved.getInt("check_ticks"));
        }
    }

    @Test void aSavedFilledBottlePaysOutOnceAndTheNextRestartKeepsItEmpty() {
        try (var fixture = new Fixture(); var ids = mockStatic(Items.class); var trades = mockStatic(InventoryTrade.class)) {
            ItemStack wine = fixture.stack(1), emptyBottle = fixture.stack(1);
            ids.when(() -> Items.id(wine)).thenReturn("kaleidoscope_tavern:wine");
            ids.when(() -> Items.id(emptyBottle)).thenReturn("kaleidoscope_tavern:empty_bottle");
            fixture.hold(emptyBottle);
            var restored = new BottleController(fixture.furniture, fixture.plugin);
            restored.loadCustomData(root(BOTTLE, saved -> saved.putByteArray("filled", wine.serializeAsBytes())));
            assertFalse(restored.blocked());
            assertTrue(restored.hasContents());
            trades.when(() -> InventoryTrade.exchange(eq(fixture.inventory), any(), eq(1), eq(List.of(wine)), any()))
                    .thenAnswer(call -> call.getArgument(4, BooleanSupplier.class).getAsBoolean());

            restored.interact(fixture.player);

            assertFalse(restored.hasContents());
            verify(fixture.furniture).setVariant("ground");
            var afterRestart = new BottleController(fixture.furniture, fixture.plugin);
            afterRestart.loadCustomData(save(restored));
            assertFalse(afterRestart.blocked());
            assertFalse(afterRestart.hasContents());
        }
    }

    @Test void lateShakerItemRestoreRefreshesTheSourceAfterCraftEnginePlacementCallbacks() {
        try (var fixture = new ShakerFixture()) {
            fixture.controller.onLoad();
            fixture.controller.onPlace(null);
            clearInvocations(fixture.base.furniture);
            fixture.encoded.set(null);
            ItemStack ingredient = fixture.base.stack(1);
            var carriedContents = new ShakerContents(new ItemStack[]{ingredient, null, null}, null);
            when(fixture.shakers.contents(fixture.carried)).thenReturn(carriedContents);

            fixture.controller.loadCustomDataFromItem(fixture.held);

            assertFalse(fixture.controller.blocked());
            assertTrue(fixture.controller.hasContents());
            assertSame(ingredient, fixture.encoded.get().slots[0]);
            assertFalse(fixture.encoded.get().empty());
            verify(fixture.base.furniture).setSourceItem(fixture.preparedSource);
            assertArrayEquals(ingredient.serializeAsBytes(), payload(save(fixture.controller), SHAKER).getByteArray("slot_0"));
        }
    }

    @Test void corruptCarriedShakerIsPreservedWithoutReplacingItsSource() {
        try (var fixture = new ShakerFixture()) {
            when(fixture.shakers.contents(fixture.carried)).thenThrow(new IllegalArgumentException("corrupt item"));

            fixture.controller.loadCustomDataFromItem(fixture.held);

            assertTrue(fixture.controller.blocked());
            assertFalse(fixture.controller.hasContents());
            verify(fixture.base.furniture, never()).setSourceItem(any());
            assertArrayEquals(fixture.carried.serializeAsBytes(), save(fixture.controller).getCompound(SHAKER).getByteArray("source_item"));
        }
    }

    @Test void sourceEncodingFailureDuringLateRestorePreservesTheCarriedItemBeforeMutatingContents() {
        try (var fixture = new ShakerFixture()) {
            ItemStack ingredient = fixture.base.stack(1);
            var carriedContents = new ShakerContents(new ItemStack[]{ingredient, null, null}, null);
            when(fixture.shakers.contents(fixture.carried)).thenReturn(carriedContents);
            when(fixture.shakers.pack(eq(fixture.sourceStack), any(ShakerContents.class)))
                    .thenThrow(new IllegalArgumentException("oversized source"));

            fixture.controller.loadCustomDataFromItem(fixture.held);

            assertTrue(fixture.controller.blocked());
            assertFalse(fixture.controller.hasContents());
            verify(fixture.base.furniture, never()).setSourceItem(any());
            assertArrayEquals(fixture.carried.serializeAsBytes(), save(fixture.controller).getCompound(SHAKER).getByteArray("source_item"));
        }
    }

    @Test void aSavedShakerCocktailCanBeTakenAfterRestartAndBothStateAndSourceBecomeEmpty() {
        try (var fixture = new ShakerFixture(); var ids = mockStatic(Items.class); var trades = mockStatic(InventoryTrade.class)) {
            ItemStack ingredient = fixture.base.stack(1), cocktail = fixture.base.stack(1), glass = fixture.base.stack(1);
            fixture.controller.loadCustomData(root(SHAKER, saved -> {
                for (int slot = 0; slot < 3; slot++) saved.putByteArray("slot_" + slot, ingredient.serializeAsBytes());
                saved.putByteArray("cocktail", cocktail.serializeAsBytes());
            }));
            fixture.controller.onLoad();
            assertSame(cocktail, fixture.encoded.get().cocktail);
            fixture.base.hold(glass);
            ids.when(() -> Items.id(glass)).thenReturn("kaleidoscope_tavern:empty_glassware");
            trades.when(() -> InventoryTrade.exchange(eq(fixture.base.inventory), any(), eq(1), eq(List.of(cocktail)), any()))
                    .thenAnswer(call -> call.getArgument(4, BooleanSupplier.class).getAsBoolean());

            fixture.controller.interact(fixture.base.player);

            assertFalse(fixture.controller.blocked());
            assertFalse(fixture.controller.hasContents());
            assertTrue(fixture.encoded.get().empty());
            var checkpoint = save(fixture.controller);
            var saved = payload(checkpoint, SHAKER);
            assertFalse(saved.containsKey("slot_0"));
            assertFalse(saved.containsKey("cocktail"));
            var afterRestart = new ShakerController(fixture.base.furniture, fixture.base.plugin);
            afterRestart.loadCustomData(checkpoint);
            assertFalse(afterRestart.blocked());
            assertFalse(afterRestart.hasContents());
        }
    }

    private static CompoundTag root(String key, Consumer<CompoundTag> fill) {
        var saved = new CompoundTag();
        saved.putInt("version", 1);
        fill.accept(saved);
        var root = new CompoundTag();
        root.put(key, SavedState.envelope(SavedState.namespace(key), 1, SavedState.SERIES_SOURCE, saved));
        return root;
    }

    private static CompoundTag save(InteractiveFurniture controller) {
        var root = new CompoundTag();
        controller.saveCustomData(root);
        return root;
    }

    private static CompoundTag payload(CompoundTag root, String key) {
        return SavedState.payload(root.getCompound(key), SavedState.namespace(key), SavedState.SERIES_SOURCE, 1, null);
    }

    private static final class Fixture implements AutoCloseable {
        final TavernPlugin plugin = mock(TavernPlugin.class);
        final Furniture furniture = mock(Furniture.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final Map<Integer, ItemStack> serialized = new HashMap<>();

        Fixture() {
            try {
                var unsafe = mock(UnsafeValues.class);
                bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
                when(unsafe.deserializeItem(any(byte[].class))).thenAnswer(call -> serialized.get((int) call.getArgument(0, byte[].class)[0]));
                when(plugin.isEnabled()).thenReturn(true);
                when(plugin.getLogger()).thenReturn(mock(Logger.class));
                var catalog = mock(TavernCatalog.class);
                when(plugin.catalog()).thenReturn(catalog);
                when(catalog.buckets()).thenReturn(Map.of("minecraft:water", "minecraft:water_bucket", JUICE, "kaleidoscope_tavern:glow_berries_bucket"));
                when(catalog.effects()).thenReturn(Map.of("kaleidoscope_tavern:wine", List.of()));
                when(furniture.id()).thenReturn(Key.of("kaleidoscope_tavern:empty_bottle"));
                when(furniture.isValid()).thenReturn(true);
                when(furniture.setVariant(anyString())).thenReturn(true);
                var world = mock(net.momirealms.craftengine.core.world.World.class);
                when(world.platformWorld()).thenReturn(mock(World.class));
                when(furniture.position()).thenReturn(new WorldPosition(world, 0, 64, 0));
                when(player.getInventory()).thenReturn(inventory);
            } catch (RuntimeException | Error exception) {
                bukkit.close();
                throw exception;
            }
        }

        ItemStack stack(int amount) {
            int id = serialized.size() + 1;
            var stack = mock(ItemStack.class);
            when(stack.getAmount()).thenReturn(amount);
            when(stack.getMaxStackSize()).thenReturn(64);
            when(stack.isEmpty()).thenReturn(amount == 0);
            when(stack.clone()).thenReturn(stack);
            when(stack.serializeAsBytes()).thenReturn(new byte[]{(byte) id});
            serialized.put(id, stack);
            return stack;
        }

        void hold(ItemStack held) { when(inventory.getItemInMainHand()).thenReturn(held); }
        @Override public void close() { bukkit.close(); }
    }

    private static final class ShakerFixture implements AutoCloseable {
        final Fixture base;
        final HandheldShaker shakers;
        final Item source, held, preparedSource;
        final ItemStack sourceStack, carried, packed;
        final AtomicReference<ShakerContents> encoded = new AtomicReference<>();
        final ShakerController controller;

        ShakerFixture() {
            base = new Fixture();
            try {
                shakers = mock(HandheldShaker.class);
                source = mock(Item.class);
                held = mock(Item.class);
                preparedSource = mock(Item.class);
                sourceStack = base.stack(1);
                carried = base.stack(1);
                packed = base.stack(1);
                when(base.plugin.shakers()).thenReturn(shakers);
                when(base.furniture.sourceItem()).thenReturn(source);
                when(shakers.pack(eq(sourceStack), any(ShakerContents.class))).thenAnswer(call -> {
                    encoded.set(call.getArgument(1));
                    return packed;
                });
                controller = spy(new ShakerController(base.furniture, base.plugin));
                doReturn(sourceStack).when(controller).bukkitStack(source);
                doReturn(carried).when(controller).bukkitStack(held);
                doReturn(preparedSource).when(controller).ceItem(packed);
            } catch (RuntimeException | Error exception) {
                base.close();
                throw exception;
            }
        }

        @Override public void close() {
            base.close();
        }
    }
}
