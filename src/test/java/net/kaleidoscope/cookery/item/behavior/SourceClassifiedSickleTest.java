package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.api.event.SickleHarvestEvent;
import net.kaleidoscope.cookery.nms.NativeSickleAccess;
import net.kaleidoscope.cookery.nms.NativeSickleAccessProvider;
import net.kaleidoscope.cookery.nms.NativeToolWear;
import net.kaleidoscope.cookery.util.EventUtils;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Main-layer orchestration tests; actual native class and loot behavior require the R7/runtime gates. */
class SourceClassifiedSickleTest {
    private final World level = mock(World.class);
    private final org.bukkit.World world = mock(org.bukkit.World.class);
    private final Player player = mock(Player.class);
    private final org.bukkit.entity.Player actor = mock(org.bukkit.entity.Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Item tool = mock(Item.class), unit = mock(Item.class);
    private final ItemStack nativeMainHand = mock(ItemStack.class);
    private final UseOnContext context = mock(UseOnContext.class);
    private final Block block = mock(Block.class);
    private final Ageable mature = mock(Ageable.class), reset = mock(Ageable.class);
    private final AtomicReference<BlockData> current = new AtomicReference<>(mature);
    private final Map<String, Object> values = new HashMap<>();
    private final ConfigSection config = mock(ConfigSection.class);
    private final FakeNative nativeAccess = new FakeNative();
    private Runnable protectionCallback = () -> {}, loggingCallback = () -> {};
    private Consumer<SickleHarvestEvent> sickleCallback = event -> {};
    private int logCalls;

    private record State(NativeSickleAccess.Kind kind, BlockData original) implements NativeSickleAccess.Snapshot {}

    private final class FakeNative implements NativeSickleAccess {
        Kind kind = Kind.MATURE_CROP;
        Outcome outcome = Outcome.complete();
        RuntimeException precommitFailure;
        Runnable onAction = () -> {};
        int inspections, crops, vegetation;
        @Override public Snapshot inspect(Block ignored) { inspections++; return new State(kind, current.get()); }
        @Override public boolean matches(Block ignored, Snapshot snapshot) { return ((State) snapshot).original() == current.get(); }
        @Override public Outcome harvestCrop(Block ignored, org.bukkit.entity.Player actor, Snapshot snapshot, BooleanSupplier source) {
            crops++; assertEquals(Kind.MATURE_CROP, snapshot.kind()); assertTrue(source.getAsBoolean());
            if (precommitFailure != null) throw precommitFailure;
            onAction.run();
            if (outcome.status() == Status.COMMITTED) { current.set(reset); kind = Kind.IMMATURE_CROP; }
            return outcome;
        }
        @Override public Outcome destroyVegetation(Block ignored, org.bukkit.entity.Player actor, Snapshot snapshot, BooleanSupplier source) {
            vegetation++; assertEquals(Kind.VEGETATION, snapshot.kind()); assertTrue(source.getAsBoolean());
            onAction.run(); return outcome;
        }
    }

    SourceClassifiedSickleTest() {
        values.put("vanilla-policy", "source-classified"); values.put("radius", 0); values.put("height", 1);
        when(context.getPlayer()).thenReturn(player); when(context.getLevel()).thenReturn(level);
        when(context.getItem()).thenReturn(tool); when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(context.getClickedPos()).thenReturn(new BlockPos(8, 64, 8));
        when(level.platformWorld()).thenReturn(world); when(player.platformPlayer()).thenReturn(actor);
        when(actor.getInventory()).thenReturn(inventory); when(inventory.getHeldItemSlot()).thenReturn(2);
        when(actor.getWorld()).thenReturn(world); when(actor.isOnline()).thenReturn(true); when(actor.isValid()).thenReturn(true);
        when(actor.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(inventory.getItemInMainHand()).thenReturn(nativeMainHand);
        when(player.getItemInHand(any(InteractionHand.class))).thenReturn(tool);
        when(tool.count()).thenReturn(1); when(tool.copyWithCount(1)).thenReturn(unit); when(tool.isSimilar(unit)).thenReturn(true);
        when(tool.enchantments()).thenReturn(Optional.empty());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320); when(world.getName()).thenReturn("native-sickle-test");
        when(world.getBlockAt(8, 64, 8)).thenReturn(block);
        when(block.getX()).thenReturn(8); when(block.getY()).thenReturn(64); when(block.getZ()).thenReturn(8);
        when(block.getWorld()).thenReturn(world); when(block.getType()).thenReturn(Material.WHEAT);
        when(block.getLocation()).thenReturn(new Location(world, 8, 64, 8));
        when(block.getBlockData()).thenAnswer(call -> current.get());
        when(mature.getAge()).thenReturn(7); when(mature.getMaximumAge()).thenReturn(7); when(mature.clone()).thenReturn(reset);
        when(reset.getMaximumAge()).thenReturn(7);
        when(block.getDrops(same(nativeMainHand), same(actor))).thenReturn(List.of());
        doAnswer(call -> { current.set(call.getArgument(0)); return null; }).when(block).setBlockData(any(BlockData.class), anyBoolean());
        when(config.containsKey(any(String[].class))).thenAnswer(call -> has(call.getArgument(0)));
        when(config.getInt(any(String[].class), anyInt())).thenAnswer(call -> value(call.getArgument(0), call.getArgument(1)));
        when(config.getBoolean(any(String[].class), anyBoolean())).thenAnswer(call -> value(call.getArgument(0), call.getArgument(1)));
        when(config.getString(any(String[].class), nullable(String.class))).thenAnswer(call -> value(call.getArgument(0), call.getArgument(1)));
        when(config.getStringList(any(String[].class), anyList())).thenAnswer(call -> value(call.getArgument(0), call.getArgument(1)));
    }

    private boolean has(String[] keys) { for (String key : keys) if (values.containsKey(key)) return true; return false; }
    private Object value(String[] keys, Object fallback) { for (String key : keys) if (values.containsKey(key)) return values.get(key); return fallback; }
    private SickleRangeHarvestBehavior behavior() {
        try (var providers = mockStatic(NativeSickleAccessProvider.class)) {
            providers.when(NativeSickleAccessProvider::access).thenReturn(nativeAccess);
            return SickleRangeHarvestBehavior.FACTORY.create(null, null, Key.of("test:source_sickle"), config);
        }
    }

    private final class Scope implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<CraftEngineBlocks> blocks = mockStatic(CraftEngineBlocks.class);
        final MockedStatic<InteractGuard> protection = mockStatic(InteractGuard.class);
        final MockedStatic<EventUtils> events = mockStatic(EventUtils.class);
        Scope() {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 0, 0)).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(actor)).thenReturn(true);
            protection.when(() -> InteractGuard.canBreak(eq(player), eq(level), anyInt(), anyInt(), anyInt()))
                    .thenAnswer(call -> { protectionCallback.run(); return true; });
            events.when(() -> EventUtils.hasListeners(SickleHarvestEvent.getHandlerList())).thenReturn(true);
            events.when(() -> EventUtils.fireAndCheckCancel(any(SickleHarvestEvent.class))).thenAnswer(call -> {
                SickleHarvestEvent event = call.getArgument(0); sickleCallback.accept(event); return event.isCancelled();
            });
            events.when(() -> EventUtils.logBlockBreak(block, actor)).thenAnswer(call -> {
                logCalls++; loggingCallback.run(); return null;
            });
        }
        @Override public void close() { events.close(); protection.close(); blocks.close(); bukkit.close(); }
    }

    @ParameterizedTest @EnumSource(InteractionHand.class)
    void matureCropDelegatesToTheEmptyToolNativePathWithoutReadingEitherBukkitHand(InteractionHand hand) {
        when(context.getHand()).thenReturn(hand); var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context));
            assertEquals(1, nativeAccess.crops); assertEquals(0, nativeAccess.vegetation); assertEquals(1, logCalls);
            verify(inventory, never()).getItemInMainHand(); verify(inventory, never()).getItemInOffHand();
            verify(block, never()).getDrops(any(ItemStack.class), same(actor)); verify(tool).hurtAndBreak(1, player, null);
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context));
            assertEquals(1, nativeAccess.crops); verify(tool, times(1)).hurtAndBreak(1, player, null);
        }
    }

    @Test void immatureCropDoesNotFallThroughToTheVegetationSuperclass() {
        when(block.getType()).thenReturn(Material.TORCHFLOWER_CROP);
        when(mature.getAge()).thenReturn(1); when(mature.getMaximumAge()).thenReturn(1);
        nativeAccess.kind = NativeSickleAccess.Kind.IMMATURE_CROP; var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context));
            assertEquals(0, nativeAccess.crops); assertEquals(0, nativeAccess.vegetation);
            verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
        }
    }

    @Test void sourceCreativeCropStillDelegatesNativeLootButDoesNotDamageTheTool() {
        when(player.canInstabuild()).thenReturn(true); when(actor.getGameMode()).thenReturn(GameMode.CREATIVE);
        var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context));
            assertEquals(1, nativeAccess.crops); verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
        }
    }

    @Test void cocoaLikeOtherCategoryIsNotHarvestedJustBecauseItIsAgeable() {
        when(block.getType()).thenReturn(Material.COCOA); nativeAccess.kind = NativeSickleAccess.Kind.OTHER;
        var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context));
            assertEquals(0, nativeAccess.crops); assertEquals(0, nativeAccess.vegetation);
            verify(block, never()).setBlockData(any(), anyBoolean()); verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
        }
    }

    @ParameterizedTest @EnumSource(InteractionHand.class)
    void vegetationUsesNormalNativeBreakWithoutSyntheticLoggingOrForcedLootTool(InteractionHand hand) {
        when(context.getHand()).thenReturn(hand); when(block.getType()).thenReturn(Material.NETHER_WART);
        nativeAccess.kind = NativeSickleAccess.Kind.VEGETATION; var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context));
            assertEquals(1, nativeAccess.vegetation); assertEquals(0, nativeAccess.crops); assertEquals(0, logCalls);
            verify(block, never()).getDrops(any(ItemStack.class), same(actor)); verify(block, never()).setBlockData(any(), anyBoolean());
        }
    }

    @ParameterizedTest @CsvSource({"NETHER_WART,0", "NETHER_WART,3", "SWEET_BERRY_BUSH,0", "SWEET_BERRY_BUSH,3",
            "PITCHER_CROP,0", "PITCHER_CROP,4", "TORCHFLOWER,0"})
    void vegetationDoesNotAcquireAnInventedAgeableMaturityRequirement(Material material, int age) {
        when(block.getType()).thenReturn(material); when(mature.getAge()).thenReturn(age);
        nativeAccess.kind = NativeSickleAccess.Kind.VEGETATION; var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context));
            assertEquals(1, nativeAccess.vegetation); assertEquals(0, nativeAccess.crops);
        }
    }

    @Test void nativeVegetationCancellationReturnsNoCommitAndDoesNotChargeTheSickle() {
        nativeAccess.kind = NativeSickleAccess.Kind.VEGETATION; nativeAccess.outcome = NativeSickleAccess.Outcome.rejected();
        var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context)); assertEquals(1, nativeAccess.vegetation);
            verify(tool, never()).hurtAndBreak(anyInt(), any(), any()); assertEquals(0, logCalls);
        }
    }

    private Item fullUnit(int damage, int limit, String components) {
        Item result = mock(Item.class);
        when(result.count()).thenReturn(1); when(result.damage()).thenReturn(Optional.of(damage)); when(result.maxDamage()).thenReturn(limit);
        when(result.copyWithCount(1)).thenAnswer(call -> fullUnit(damage, limit, components));
        when(result.damage(any(Integer.class))).thenAnswer(call -> fullUnit(call.getArgument(0), limit, components));
        when(result.isSimilar(any(Item.class))).thenAnswer(call -> {
            Item other = call.getArgument(0);
            return other.damage().orElse(0) == damage && other.maxDamage() == limit && components.equals(other.toString());
        });
        when(result.toString()).thenReturn(components);
        return result;
    }

    @ParameterizedTest @CsvSource({"MAIN_HAND,1", "MAIN_HAND,7", "OFF_HAND,1", "OFF_HAND,7", "MAIN_HAND,0", "OFF_HAND,0"})
    void normalNativeMainHandWearIncludingEventAdjustedAmountsDoesNotStopALegalMultiplePlantSweep(
            InteractionHand hand, int finalWear) {
        values.put("radius", 1); when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(context.getHand()).thenReturn(hand); nativeAccess.kind = NativeSickleAccess.Kind.VEGETATION;
        AtomicInteger actualDamage = new AtomicInteger(0); Object raw = new Object();
        when(tool.copyWithCount(1)).thenAnswer(call -> fullUnit(actualDamage.get(), 2000, "original-components"));
        when(tool.damage()).thenAnswer(call -> Optional.of(actualDamage.get())); when(tool.maxDamage()).thenReturn(2000);
        when(tool.minecraftItem()).thenReturn(raw);
        when(tool.isSimilar(any(Item.class))).thenAnswer(call -> {
            Item expected = call.getArgument(0);
            return expected.damage().orElse(0) == actualDamage.get() && "original-components".equals(expected.toString());
        });
        nativeAccess.onAction = () -> {
            int before = hand == InteractionHand.MAIN_HAND ? actualDamage.get() : (nativeAccess.vegetation - 1) * finalWear;
            int after = before + finalWear;
            if (hand == InteractionHand.MAIN_HAND) actualDamage.set(after);
            nativeAccess.outcome = NativeSickleAccess.Outcome.complete().withWear(new NativeToolWear(raw,
                    fullUnit(before, 2000, "original-components"), 2, 1, 1, before, finalWear, after, 2000, false));
        };
        var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context));
            assertEquals(9, nativeAccess.vegetation, "Certified native wear must not be confused with callback replacement");
            verify(tool).hurtAndBreak(1, player, null);
            assertEquals(hand == InteractionHand.MAIN_HAND ? finalWear * 9 : 0, actualDamage.get());
        }
    }

    @ParameterizedTest @EnumSource(InteractionHand.class)
    void nativeToolBreakStopsAnExhaustedMainHandButDoesNotLoseTheOffhandSickle(InteractionHand hand) {
        values.put("radius", 1); when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(context.getHand()).thenReturn(hand); nativeAccess.kind = NativeSickleAccess.Kind.VEGETATION;
        AtomicInteger count = new AtomicInteger(1); Object raw = new Object();
        when(tool.count()).thenAnswer(call -> count.get()); when(tool.damage()).thenReturn(Optional.of(1999)); when(tool.maxDamage()).thenReturn(2000);
        when(tool.copyWithCount(1)).thenAnswer(call -> fullUnit(1999, 2000, "original-components"));
        when(tool.isSimilar(any(Item.class))).thenAnswer(call -> {
            Item expected = call.getArgument(0);
            return expected.damage().orElse(0) == 1999 && "original-components".equals(expected.toString());
        });
        when(tool.minecraftItem()).thenReturn(raw);
        when(player.getItemInHand(hand)).thenAnswer(call -> count.get() == 0 ? null : tool);
        nativeAccess.onAction = () -> {
            if (nativeAccess.vegetation == 1) {
                nativeAccess.outcome = NativeSickleAccess.Outcome.complete().withWear(new NativeToolWear(raw,
                        fullUnit(1999, 2000, "original-components"), 2, 1, 0, 1999, 2, 2000, 2000, true));
                if (hand == InteractionHand.MAIN_HAND) count.set(0);
            } else nativeAccess.outcome = NativeSickleAccess.Outcome.complete();
        };
        var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context));
            assertEquals(hand == InteractionHand.MAIN_HAND ? 1 : 9, nativeAccess.vegetation);
            if (hand == InteractionHand.MAIN_HAND) verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
            else verify(tool).hurtAndBreak(1, player, null);
        }
    }

    @ParameterizedTest @EnumSource(value = Material.class, names = {"MELON_STEM", "PUMPKIN_STEM", "ATTACHED_MELON_STEM", "ATTACHED_PUMPKIN_STEM"})
    void fixedSourceStemBlacklistWinsBeforeNativeInspectionOrTakeover(Material material) {
        when(block.getType()).thenReturn(material); var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context)); assertEquals(0, nativeAccess.inspections);
            scope.events.verifyNoInteractions(); verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
        }
    }

    @Test void sourceModeHonorsItsSeparateExplicitAllowlist() {
        values.put("vanilla-allowlist", List.of("minecraft:nether_wart")); var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context)); assertEquals(0, nativeAccess.inspections);
            verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
        }
    }

    @Test void sourceModeKeepsExplicitAdditionalBlacklistRestrictions() {
        values.put("blacklist", List.of("minecraft:wheat")); var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context)); assertEquals(0, nativeAccess.inspections);
            verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
        }
    }

    @ParameterizedTest @CsvSource({"protection,source", "event,source", "logging,source", "protection,block", "event,block", "logging,block"})
    void allPrecommitCallbacksRejectAChangedOriginalBlockOrFullInteractionSource(String phase, String field) {
        Runnable drift = () -> { if (field.equals("source")) when(tool.isSimilar(unit)).thenReturn(false); else current.set(mock(BlockData.class)); };
        if (phase.equals("protection")) protectionCallback = drift;
        if (phase.equals("event")) sickleCallback = event -> drift.run();
        if (phase.equals("logging")) loggingCallback = drift;
        var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context));
            assertEquals(0, nativeAccess.crops); assertEquals(0, nativeAccess.vegetation);
            verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
        }
    }

    @Test void takeoverCancellationStopsBeforeAnyNativeStatisticsOrMutation() {
        sickleCallback = event -> event.setCancelled(true); var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.PASS, behavior.useOnBlock(context));
            assertEquals(0, nativeAccess.crops); verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
        }
    }

    @Test void ownNativeCallbackReentryCannotEnterTheSameBlockOrChargeTwice() {
        var behavior = behavior();
        nativeAccess.onAction = () -> assertEquals(InteractionResult.PASS, behavior.useOnBlock(context));
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context));
            assertEquals(1, nativeAccess.crops); verify(tool).hurtAndBreak(1, player, null);
        }
    }

    @Test void nativePostcommitPartialIsConsumedOnceAndDoesNotContinueTheSweep() {
        values.put("radius", 1); values.put("height", 2);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        nativeAccess.outcome = NativeSickleAccess.Outcome.partial("native block changed during statistics/exhaustion");
        var behavior = behavior();
        try (var scope = new Scope()) {
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context));
            assertEquals(1, nativeAccess.crops); assertSame(mature, current.get()); verify(tool).hurtAndBreak(1, player, null);
        }
    }

    @Test void nativePostcommitExceptionChargesOnceButIsRethrownInsteadOfReportedAsGreenSuccess() {
        var failure = new IllegalArgumentException("native postcommit error");
        nativeAccess.outcome = NativeSickleAccess.Outcome.failed("partially committed", failure); var behavior = behavior();
        try (var scope = new Scope()) {
            var thrown = assertThrows(IllegalStateException.class, () -> behavior.useOnBlock(context));
            assertSame(failure, thrown.getCause()); assertTrue(thrown.getMessage().contains("partially committed"));
            assertEquals(1, nativeAccess.crops); verify(tool).hurtAndBreak(1, player, null);
        }
    }

    @Test void rejectedWearReceiptCannotEraseAnAlreadyReportedNativeFailure() {
        var failure = new IllegalArgumentException("native failure with an uncertifiable wear receipt");
        nativeAccess.outcome = NativeSickleAccess.Outcome.failed("already partially committed", failure)
                .withWear(new NativeToolWear(new Object(), mock(Item.class), 2, 1, 1, 0, 1, 1, 2000, false));
        var behavior = behavior();
        try (var scope = new Scope()) {
            var thrown = assertThrows(IllegalStateException.class, () -> behavior.useOnBlock(context));
            assertSame(failure, thrown.getCause()); verify(tool).hurtAndBreak(1, player, null);
        }
    }

    @Test void invalidNativeArgumentsBeforeCommitRemainAnExceptionWithoutDurabilitySideEffects() {
        var failure = new IllegalArgumentException("invalid native source"); nativeAccess.precommitFailure = failure;
        var behavior = behavior();
        try (var scope = new Scope()) {
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> behavior.useOnBlock(context)));
            verify(tool, never()).hurtAndBreak(anyInt(), any(), any());
        }
    }

    @Test void legacyModeStillAllowsAnExplicitAgeableCocoaListWithoutLoadingTheCompanion() {
        values.remove("vanilla-policy"); values.put("crops", List.of("minecraft:cocoa"));
        when(block.getType()).thenReturn(Material.COCOA);
        try (var providers = mockStatic(NativeSickleAccessProvider.class)) {
            var behavior = SickleRangeHarvestBehavior.FACTORY.create(null, null, Key.of("test:legacy_sickle"), config);
            providers.verifyNoInteractions();
            try (var scope = new Scope()) {
                assertEquals(InteractionResult.SUCCESS_AND_CANCEL, behavior.useOnBlock(context));
                verify(block).getDrops(same(nativeMainHand), same(actor)); assertEquals(0, nativeAccess.inspections);
            }
        }
    }

    @ParameterizedTest @CsvSource({"crops", "bushes", "crop", "bush"})
    void sourceModeCannotSilentlyIgnoreOrReinterpretAnExplicitLegacyList(String key) {
        values.put(key, List.of("minecraft:cocoa"));
        var failure = assertThrows(IllegalArgumentException.class, this::behavior);
        assertTrue(failure.getMessage().contains("legacy-ageable")); assertTrue(failure.getMessage().contains("vanilla-allowlist"));
    }

    @Test void legacyModeCannotSilentlyIgnoreASourceAllowlist() {
        values.put("vanilla-policy", "legacy-ageable"); values.put("vanilla-allowlist", List.of("minecraft:wheat"));
        assertThrows(IllegalArgumentException.class, this::behavior);
    }

    @Test void unknownPolicyAndMissingNativeCompanionFailClosedRatherThanFallingBackToAgeable() {
        values.put("vanilla-policy", "unknown"); assertThrows(IllegalArgumentException.class, this::behavior);
        values.put("vanilla-policy", "source-classified");
        var failure = new IllegalStateException("native companion unavailable");
        try (var providers = mockStatic(NativeSickleAccessProvider.class)) {
            providers.when(NativeSickleAccessProvider::access).thenThrow(failure);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> SickleRangeHarvestBehavior.FACTORY.create(null, null, Key.of("test:sickle"), config)));
        }
    }
}
