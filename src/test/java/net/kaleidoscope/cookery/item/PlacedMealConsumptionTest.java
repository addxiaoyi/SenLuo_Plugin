package net.kaleidoscope.cookery.item;

import net.kaleidoscope.cookery.catalog.ConsumeEffectDefinition;
import net.kaleidoscope.cookery.catalog.PlacedMealDefinition.BiteFood;
import net.kaleidoscope.cookery.catalog.PlacedMealDefinition.Effect;
import net.kaleidoscope.cookery.effect.CookeryEffects;
import net.kaleidoscope.cookery.effect.EffectKind;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlacedMealConsumptionTest {
    private static final String SOURCE = "kaleidoscopecookery:braised_beef";
    private static final Effect VIGOR = new Effect(EffectKind.VIGOR.id(), 120, 1, 1);
    private final Player player = mock(Player.class);
    private final PluginManager events = mock(PluginManager.class);
    private final CookeryEffects effects = mock(CookeryEffects.class);
    private final BooleanSupplier commit = mock(BooleanSupplier.class);
    private final List<Effect> vanillaApplied = new ArrayList<>();

    @BeforeEach void playerCanEat() {
        when(player.isOnline()).thenReturn(true);
        when(player.getHealth()).thenReturn(20d);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getFoodLevel()).thenReturn(10);
        when(player.getSaturation()).thenReturn(2f);
        when(player.getWorld()).thenReturn(mock(World.class));
        when(commit.getAsBoolean()).thenReturn(true);
    }

    private MockedStatic<Bukkit> bukkit() {
        var bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
        bukkit.when(Bukkit::getPluginManager).thenReturn(events);
        return bukkit;
    }

    private PlacedMealConsumption service(DoubleSupplier chance) {
        return new PlacedMealConsumption(effects, (target, effect) -> () -> vanillaApplied.add(effect), chance);
    }

    private static BiteFood food(boolean alwaysEat, Effect... effects) {
        return new BiteFood(4, 6.4f, alwaysEat, List.of(effects));
    }

    @Test void cancelledFoodEventDoesNotCommitFeedOrApplyEffects() {
        try (var bukkit = bukkit()) {
            doAnswer(call -> { ((FoodLevelChangeEvent) call.getArgument(0)).setCancelled(true); return null; })
                    .when(events).callEvent(any());
            assertFalse(service(() -> 0).consume(player, food(true, VIGOR), SOURCE, commit));
            verifyNoInteractions(commit, effects);
            verify(player, never()).setFoodLevel(anyInt());
            verify(player, never()).setSaturation(anyFloat());
        }
    }

    @Test void foodEventReceivesProspectiveLevelWithoutAConsumedHandItem() {
        try (var bukkit = bukkit()) {
            doAnswer(call -> {
                var event = (FoodLevelChangeEvent) call.getArgument(0);
                assertSame(player, event.getEntity());
                assertEquals(14, event.getFoodLevel());
                assertNull(event.getItem());
                event.setFoodLevel(18);
                return null;
            }).when(events).callEvent(any());
            assertTrue(service(() -> 0).consume(player, food(true, VIGOR), SOURCE, commit));
            var order = inOrder(events, commit, player, effects);
            order.verify(events).callEvent(any(FoodLevelChangeEvent.class));
            order.verify(commit).getAsBoolean();
            order.verify(player).setFoodLevel(18);
            order.verify(player).setSaturation(8.4f);
            order.verify(effects).apply(player, new ConsumeEffectDefinition(VIGOR.id(), 120, 1, 1), SOURCE);
            verify(commit, times(1)).getAsBoolean();
            verify(player, never()).getInventory();
        }
    }

    @Test void loweredFoodEventCapsExistingAndNewSaturation() {
        try (var bukkit = bukkit()) {
            when(player.getSaturation()).thenReturn(9f);
            doAnswer(call -> { ((FoodLevelChangeEvent) call.getArgument(0)).setFoodLevel(3); return null; })
                    .when(events).callEvent(any());
            assertTrue(service(() -> 0).consume(player, food(true), SOURCE, commit));
            verify(player).setFoodLevel(3);
            verify(player).setSaturation(3f);
        }
    }

    @Test void excessiveEventLevelIsClampedToVanillaLimit() {
        try (var bukkit = bukkit()) {
            doAnswer(call -> { ((FoodLevelChangeEvent) call.getArgument(0)).setFoodLevel(500); return null; })
                    .when(events).callEvent(any());
            assertTrue(service(() -> 0).consume(player, new BiteFood(4, 50, true, List.of()), SOURCE, commit));
            verify(player).setFoodLevel(20);
            verify(player).setSaturation(20f);
        }
    }

    @Test void staleControllerCommitLeavesPlayerUntouched() {
        try (var bukkit = bukkit()) {
            when(commit.getAsBoolean()).thenReturn(false);
            assertFalse(service(() -> 0).consume(player, food(true, VIGOR), SOURCE, commit));
            verify(commit, times(1)).getAsBoolean();
            verify(player, never()).setFoodLevel(anyInt());
            verify(player, never()).setSaturation(anyFloat());
            verifyNoInteractions(effects);
        }
    }

    @Test void commitFailureDoesNotGrantNutritionOrEffects() {
        try (var bukkit = bukkit()) {
            when(commit.getAsBoolean()).thenThrow(new IllegalStateException("plate replaced"));
            assertThrows(IllegalStateException.class, () -> service(() -> 0).consume(player, food(true, VIGOR), SOURCE, commit));
            verify(player, never()).setFoodLevel(anyInt());
            verifyNoInteractions(effects);
        }
    }

    @Test void playerRegionIsRequiredBeforeAnyPlayerStateRead() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            clearInvocations(player);
            assertThrows(IllegalStateException.class, () -> service(() -> 0).consume(player, food(true), SOURCE, commit));
            verifyNoInteractions(player, commit, events, effects);
        }
    }

    @Test void ownershipChangeDuringEventPreventsCommit() {
        try (var bukkit = bukkit()) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true, false);
            assertThrows(IllegalStateException.class, () -> service(() -> 0).consume(player, food(true), SOURCE, commit));
            verifyNoInteractions(commit);
            verify(player, never()).setFoodLevel(anyInt());
        }
    }

    @Test void deathDuringFoodEventPreventsCommit() {
        try (var bukkit = bukkit()) {
            doAnswer(call -> { when(player.getHealth()).thenReturn(0d); return null; }).when(events).callEvent(any());
            assertFalse(service(() -> 0).consume(player, food(true, VIGOR), SOURCE, commit));
            verifyNoInteractions(commit, effects);
        }
    }

    @Test void disconnectDuringFoodEventPreventsCommit() {
        try (var bukkit = bukkit()) {
            doAnswer(call -> { when(player.isOnline()).thenReturn(false); return null; }).when(events).callEvent(any());
            assertFalse(service(() -> 0).consume(player, food(true), SOURCE, commit));
            verifyNoInteractions(commit);
        }
    }

    @Test void worldChangeDuringFoodEventPreventsCommit() {
        try (var bukkit = bukkit()) {
            doAnswer(call -> { when(player.getWorld()).thenReturn(mock(World.class)); return null; }).when(events).callEvent(any());
            assertFalse(service(() -> 0).consume(player, food(true), SOURCE, commit));
            verifyNoInteractions(commit);
        }
    }

    @Test void fullSurvivalPlayerRequiresAlwaysEat() {
        try (var bukkit = bukkit()) {
            when(player.getFoodLevel()).thenReturn(20);
            assertFalse(service(() -> 0).consume(player, food(false), SOURCE, commit));
            verifyNoInteractions(events, commit);
            assertTrue(service(() -> 0).consume(player, food(true), SOURCE, commit));
            verify(player).setFoodLevel(20);
        }
    }

    @Test void creativeCanEatAtFullFoodButSpectatorCannot() {
        try (var bukkit = bukkit()) {
            when(player.getFoodLevel()).thenReturn(20);
            when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
            assertTrue(service(() -> 0).consume(player, food(false), SOURCE, commit));
            when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);
            assertFalse(service(() -> 0).consume(player, food(true), SOURCE, commit));
            verify(commit, times(1)).getAsBoolean();
        }
    }

    @Test void zeroNutritionBiteCanStillConsumeAtFullFood() {
        try (var bukkit = bukkit()) {
            when(player.getFoodLevel()).thenReturn(20);
            assertTrue(service(() -> 0).consume(player, new BiteFood(0, 0, true, List.of()), SOURCE, commit));
            verify(commit).getAsBoolean();
            verify(player).setFoodLevel(20);
            verify(player).setSaturation(2f);
        }
    }

    @Test void effectsRollIndependentlyOnEveryBite() {
        try (var bukkit = bukkit()) {
            Effect speed = new Effect("minecraft:speed", 40, 0, .5);
            Effect custom = new Effect(VIGOR.id(), 80, 2, .5);
            double[] rolls = {.1, .8, .8, .1};
            AtomicInteger index = new AtomicInteger();
            var service = service(() -> rolls[index.getAndIncrement()]);
            assertTrue(service.consume(player, food(true, speed, custom), SOURCE, commit));
            assertTrue(service.consume(player, food(true, speed, custom), SOURCE, commit));
            assertEquals(List.of(speed), vanillaApplied);
            verify(effects, times(1)).apply(player, new ConsumeEffectDefinition(custom.id(), 80, 2, .5), SOURCE);
            assertEquals(4, index.get());
        }
    }

    @Test void zeroDurationVanillaEffectReachesBridgeWithoutInventedTick() {
        try (var bukkit = bukkit()) {
            Effect instant = new Effect("minecraft:instant_health", 0, 1, 1);
            assertTrue(service(() -> 0).consume(player, food(true, instant), SOURCE, commit));
            assertEquals(List.of(instant), vanillaApplied);
        }
    }

    @Test void zeroDurationCustomEffectDoesNotCreatePersistentState() {
        try (var bukkit = bukkit()) {
            Effect expired = new Effect(VIGOR.id(), 0, 0, 1);
            assertTrue(service(() -> 0).consume(player, food(true, expired), SOURCE, commit));
            verifyNoInteractions(effects);
        }
    }

    @Test void unsupportedPotionBridgeFailsBeforeEventAndCommit() {
        try (var bukkit = bukkit()) {
            var service = new PlacedMealConsumption(effects);
            assertThrows(IllegalStateException.class,
                    () -> service.consume(player, food(true, new Effect("minecraft:speed", 40, 0, 1)), SOURCE, commit));
            verifyNoInteractions(events, commit, effects);
            verify(player, never()).setFoodLevel(anyInt());
        }
    }

    @Test void unknownCustomEffectFailsBeforeCommit() {
        try (var bukkit = bukkit()) {
            assertThrows(IllegalArgumentException.class,
                    () -> service(() -> 0).consume(player, food(true, new Effect("other:unknown", 20, 0, 1)), SOURCE, commit));
            verifyNoInteractions(events, commit, effects);
        }
    }

    @Test void cancelledCustomEffectDoesNotRestoreAnAlreadyConsumedBite() {
        try (var bukkit = bukkit()) {
            when(effects.apply(any(), any(), any())).thenReturn(false);
            assertTrue(service(() -> 0).consume(player, food(true, VIGOR), SOURCE, commit));
            verify(commit, times(1)).getAsBoolean();
            verify(effects, times(1)).apply(any(), any(), eq(SOURCE));
        }
    }
}
