package net.kaleidoscope.tavern;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BloodyMaryAttributionTest {
    @BeforeAll static void initializeAttributes() {
        try (var access = mockStatic(io.papermc.paper.registry.RegistryAccess.class);
             var bridge = mockStatic(io.papermc.paper.InternalAPIBridge.class)) {
            bridge.when(io.papermc.paper.InternalAPIBridge::get)
                    .thenReturn(mock(io.papermc.paper.InternalAPIBridge.class));
            var provider = mock(io.papermc.paper.registry.RegistryAccess.class, call -> mock(org.bukkit.Registry.class));
            access.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(provider);
            var registry = org.bukkit.Registry.ATTRIBUTE;
            doAnswer(call -> mock(Attribute.class)).when(registry)
                    .getOrThrow(any(net.kyori.adventure.key.Key.class));
            assertNotNull(Attribute.MAX_HEALTH);
        }
    }

    @Test void currentCausingPlayerHealsEvenWithoutCachedPlayerKillCredit() {
        try (var f = new Fixture()) {
            f.abilities.healAfterKill(f.death);

            assertEquals(1, f.heals.size());
            assertSame(f.player, f.heals.getFirst().getEntity());
            assertEquals(EntityRegainHealthEvent.RegainReason.MAGIC, f.heals.getFirst().getRegainReason());
            assertEquals(6, f.heals.getFirst().getAmount());
            verify(f.player).setHealth(10);
            verify(f.victim, never()).getKiller();
        }
    }

    @Test void projectileCausingPlayerWinsOverAnUnrelatedCachedPlayerKiller() {
        try (var f = new Fixture()) {
            Player previous = mock(Player.class);
            when(f.victim.getKiller()).thenReturn(previous);
            when(f.source.getDirectEntity()).thenReturn(mock(Projectile.class));
            when(f.source.isIndirect()).thenReturn(true);

            f.abilities.healAfterKill(f.death);

            verify(f.player).setHealth(10);
            verify(previous, never()).setHealth(anyDouble());
            assertSame(f.player, f.heals.getFirst().getEntity());
        }
    }

    @Test void environmentalDeathCannotRewardHistoricalPlayerDamage() {
        try (var f = new Fixture()) {
            when(f.victim.getKiller()).thenReturn(f.player);
            when(f.source.getCausingEntity()).thenReturn(null);

            f.abilities.healAfterKill(f.death);

            f.noHealing();
            verifyNoInteractions(f.effects);
        }
    }

    @Test void aNonPlayerFatalSourceCannotFallBackToEarlierPlayerKillCredit() {
        try (var f = new Fixture()) {
            when(f.victim.getKiller()).thenReturn(f.player);
            when(f.source.getCausingEntity()).thenReturn(mock(Mob.class));

            f.abilities.healAfterKill(f.death);

            f.noHealing();
            verifyNoInteractions(f.effects);
        }
    }

    @Test void previouslyCancelledDeathNeverGrantsHealing() throws Exception {
        try (var f = new Fixture()) {
            when(f.death.isCancelled()).thenReturn(true);

            f.abilities.healAfterKill(f.death);

            f.noHealing();
            verifyNoInteractions(f.source, f.effects);
            EventHandler handler = TavernAbilities.class.getMethod("healAfterKill", EntityDeathEvent.class)
                    .getAnnotation(EventHandler.class);
            assertEquals(EventPriority.MONITOR, handler.priority());
            assertTrue(handler.ignoreCancelled());
        }
    }

    @Test void selfInflictedDeathDoesNotHealTheDyingPlayer() {
        try (var f = new Fixture()) {
            when(f.death.getEntity()).thenReturn(f.player);

            f.abilities.healAfterKill(f.death);

            f.noHealing();
            verifyNoInteractions(f.effects);
        }
    }

    @Test void theActualCausingPlayerMustStillHaveTheEffect() {
        try (var f = new Fixture()) {
            when(f.effects.active(f.player, "bloody_mary")).thenReturn(false);

            f.abilities.healAfterKill(f.death);

            f.noHealing();
        }
    }

    @Test void aDeadCausingPlayerCannotBeRevivedByTheKill() {
        try (var f = new Fixture()) {
            when(f.player.isDead()).thenReturn(true);

            f.abilities.healAfterKill(f.death);

            f.noHealing();
        }
    }

    @Test void foreignRegionAttributionDoesNotReadOrMutatePlayerHealth() {
        try (var f = new Fixture()) {
            f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(f.player)).thenReturn(false);

            f.abilities.healAfterKill(f.death);

            f.noHealing();
            verifyNoInteractions(f.effects);
            verify(f.player, never()).isDead();
            verify(f.player, never()).getHealth();
            verify(f.player, never()).getAttribute(any());
            verify(f.plugin, never()).later(any(), any());
        }
    }

    @Test void aVictimWithLessThanThreeMaximumHealthDoesNotEmitZeroHealing() {
        try (var f = new Fixture()) {
            when(f.victimHealth.getValue()).thenReturn(2.9);

            f.abilities.healAfterKill(f.death);

            f.noHealing();
        }
    }

    @Test void aCancelledMagicHealingEventKeepsTheCurrentHealth() {
        try (var f = new Fixture()) {
            f.onHeal = event -> event.setCancelled(true);

            f.abilities.healAfterKill(f.death);

            assertEquals(1, f.heals.size());
            verify(f.player, never()).setHealth(anyDouble());
        }
    }

    @Test void aHealingListenerCanAdjustTheAmount() {
        try (var f = new Fixture()) {
            f.onHeal = event -> event.setAmount(2.5);

            f.abilities.healAfterKill(f.death);

            verify(f.player).setHealth(6.5);
        }
    }

    @Test void healingIsCappedAtTheMaximumAfterListenersHaveRun() {
        try (var f = new Fixture()) {
            f.onHeal = event -> {
                event.setAmount(100);
                when(f.playerHealth.getValue()).thenReturn(8.0);
            };

            f.abilities.healAfterKill(f.death);

            verify(f.player).setHealth(8);
        }
    }

    @Test void nonPositiveOrNonFiniteEventAmountsCannotDamageOrCorruptThePlayer() {
        for (double amount : new double[]{0, -1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            try (var f = new Fixture()) {
                f.onHeal = event -> event.setAmount(amount);

                f.abilities.healAfterKill(f.death);

                assertEquals(1, f.heals.size());
                verify(f.player, never()).setHealth(anyDouble());
            }
        }
    }

    @Test void aHealingListenerKillingThePlayerCannotBeUndone() {
        try (var f = new Fixture()) {
            f.onHeal = event -> when(f.player.isDead()).thenReturn(true);

            f.abilities.healAfterKill(f.death);

            assertEquals(1, f.heals.size());
            verify(f.player, never()).setHealth(anyDouble());
        }
    }

    @Test void aHealingListenerCannotCauseAForeignRegionHealthWrite() {
        try (var f = new Fixture()) {
            f.onHeal = event -> f.bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(f.player)).thenReturn(false);

            f.abilities.healAfterKill(f.death);

            assertEquals(1, f.heals.size());
            verify(f.player, never()).setHealth(anyDouble());
        }
    }

    @Test void disablingThePluginDuringTheHealingEventStopsTheHealthWrite() {
        try (var f = new Fixture()) {
            f.onHeal = event -> when(f.plugin.isEnabled()).thenReturn(false);

            f.abilities.healAfterKill(f.death);

            assertEquals(1, f.heals.size());
            verify(f.player, never()).setHealth(anyDouble());
        }
    }

    @Test void aPlayerLoggingOutDuringTheHealingEventIsNotMutatedAfterward() {
        try (var f = new Fixture()) {
            f.onHeal = event -> when(f.player.isOnline()).thenReturn(false);

            f.abilities.healAfterKill(f.death);

            assertEquals(1, f.heals.size());
            verify(f.player, never()).setHealth(anyDouble());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final TavernPlugin plugin = mock(TavernPlugin.class);
        final TavernEffects effects = mock(TavernEffects.class);
        final Player player = mock(Player.class);
        final LivingEntity victim = mock(LivingEntity.class);
        final AttributeInstance victimHealth = mock(AttributeInstance.class);
        final AttributeInstance playerHealth = mock(AttributeInstance.class);
        final EntityDeathEvent death = mock(EntityDeathEvent.class);
        final DamageSource source = mock(DamageSource.class);
        final PluginManager manager = mock(PluginManager.class);
        final List<EntityRegainHealthEvent> heals = new ArrayList<>();
        Consumer<EntityRegainHealthEvent> onHeal = event -> {};
        final TavernAbilities abilities;

        Fixture() {
            when(plugin.getName()).thenReturn("KaleidoscopeTavernPlugin");
            when(plugin.namespace()).thenReturn("kaleidoscopetavernplugin");
            when(plugin.isEnabled()).thenReturn(true);
            when(death.getEntity()).thenReturn(victim);
            when(death.getDamageSource()).thenReturn(source);
            when(source.getCausingEntity()).thenReturn(player);
            when(effects.active(player, "bloody_mary")).thenReturn(true);
            when(victim.getAttribute(Attribute.MAX_HEALTH)).thenReturn(victimHealth);
            when(victimHealth.getValue()).thenReturn(20.0);
            when(player.getAttribute(Attribute.MAX_HEALTH)).thenReturn(playerHealth);
            when(playerHealth.getValue()).thenReturn(20.0);
            when(player.getHealth()).thenReturn(4.0);
            when(player.isOnline()).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(true);
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            doAnswer(call -> {
                EntityRegainHealthEvent event = call.getArgument(0);
                heals.add(event);
                onHeal.accept(event);
                return null;
            }).when(manager).callEvent(any(EntityRegainHealthEvent.class));
            abilities = new TavernAbilities(plugin, effects);
        }

        void noHealing() {
            assertTrue(heals.isEmpty());
            verify(player, never()).setHealth(anyDouble());
        }

        @Override public void close() { bukkit.close(); }
    }
}
