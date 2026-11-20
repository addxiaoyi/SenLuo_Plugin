package net.kaleidoscope.cookery.nms;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeSickleObserverInitializationTest {
    @Test void observerRetainsEvenCanceledEventsBeforeLaterPriorityHandlersRun() throws NoSuchMethodException {
        var handler = NativeSickleDamageObserver.class.getMethod("onDamage", PlayerItemDamageEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(handler); assertEquals(EventPriority.LOWEST, handler.priority()); assertFalse(handler.ignoreCancelled());
    }

    @ParameterizedTest @ValueSource(strings = {"1.21.11", "1.21.11-R0.1-SNAPSHOT"})
    void verifiedTargetRegistersOnlyTheCommonObserverWithoutLoadingTheCompanion(String version) {
        var plugin = mock(JavaPlugin.class);
        try (var bukkit = mockStatic(Bukkit.class); var observer = mockStatic(NativeSickleDamageObserver.class);
             var provider = mockStatic(NativeSickleAccessProvider.class)) {
            bukkit.when(Bukkit::getBukkitVersion).thenReturn(version);
            provider.when(() -> NativeSickleAccessProvider.registerObserver(plugin)).thenCallRealMethod();
            provider.when(() -> NativeSickleAccessProvider.supportedVersion(version)).thenCallRealMethod();
            provider.when(NativeSickleAccessProvider::access).thenThrow(new AssertionError("No native companion on enable"));
            NativeSickleAccessProvider.registerObserver(plugin);
            observer.verify(() -> NativeSickleDamageObserver.register(plugin), times(1));
            provider.verify(NativeSickleAccessProvider::access, never());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.8", "1.21.10", "26.1.2", "26.2"})
    void oldBridgeVersionsDoNotRegisterTheObserverOrLoadR7(String version) {
        var plugin = mock(JavaPlugin.class);
        try (var bukkit = mockStatic(Bukkit.class); var observer = mockStatic(NativeSickleDamageObserver.class);
             var provider = mockStatic(NativeSickleAccessProvider.class)) {
            bukkit.when(Bukkit::getBukkitVersion).thenReturn(version);
            provider.when(() -> NativeSickleAccessProvider.registerObserver(plugin)).thenCallRealMethod();
            provider.when(() -> NativeSickleAccessProvider.supportedVersion(version)).thenCallRealMethod();
            provider.when(NativeSickleAccessProvider::access).thenThrow(new AssertionError("R7 cannot load on another bridge"));
            NativeSickleAccessProvider.registerObserver(plugin);
            observer.verifyNoInteractions(); provider.verify(NativeSickleAccessProvider::access, never());
        }
    }

    private static final class Registration implements AutoCloseable {
        final JavaPlugin owner = mock(JavaPlugin.class);
        final Server server = mock(Server.class);
        final PluginManager manager = mock(PluginManager.class);
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final AtomicReference<NativeSickleDamageObserver> listener = new AtomicReference<>();
        Registration() {
            when(owner.isEnabled()).thenAnswer(call -> enabled.get());
            when(owner.getServer()).thenReturn(server); when(server.getPluginManager()).thenReturn(manager);
            doAnswer(call -> {
                Listener value = call.getArgument(0);
                var observer = (NativeSickleDamageObserver) value; listener.set(observer);
                PlayerItemDamageEvent.getHandlerList().register(new RegisteredListener(value,
                        (ignored, event) -> observer.onDamage((PlayerItemDamageEvent) event),
                        EventPriority.LOWEST, owner, false));
                PlayerItemBreakEvent.getHandlerList().register(new RegisteredListener(value,
                        (ignored, event) -> observer.onBreak((PlayerItemBreakEvent) event),
                        EventPriority.LOWEST, owner, false));
                return null;
            }).when(manager).registerEvents(any(Listener.class), same(owner));
        }
        @Override public void close() { enabled.set(false); HandlerList.unregisterAll(owner); }
    }

    @Test void repeatedEnableHookDoesNotInstallDuplicateHandlersAndActualRegistrationMakesSessionsAvailable() {
        try (var registration = new Registration()) {
            NativeSickleDamageObserver.register(registration.owner);
            NativeSickleDamageObserver.register(registration.owner);
            verify(registration.manager).registerEvents(any(Listener.class), same(registration.owner));
            var actor = mock(Player.class); var mirror = mock(ItemStack.class);
            try (var session = NativeSickleDamageObserver.begin(actor, 2, event -> event.getItem() == mirror)) {
                assertNotNull(session);
                var event = new PlayerItemDamageEvent(actor, mirror, 1, 2);
                registration.listener.get().onDamage(event);
                event.setDamage(6);
                assertEquals(6, session.finish().resolvedWear());
            }
        }
    }

    @Test void disablingTheOwnerRejectsNewSessionsAndInvalidatesInflightAttribution() {
        try (var registration = new Registration()) {
            NativeSickleDamageObserver.register(registration.owner);
            var actor = mock(Player.class); var mirror = mock(ItemStack.class);
            try (var session = NativeSickleDamageObserver.begin(actor, 2, event -> event.getItem() == mirror)) {
                assertNotNull(session);
                registration.listener.get().onDamage(new PlayerItemDamageEvent(actor, mirror, 2, 2));
                registration.enabled.set(false);
                assertFalse(session.finish().valid());
            }
            assertNull(NativeSickleDamageObserver.begin(actor, 2, event -> true));
        }
    }

    @Test void unregisteringTheActualHandlerIsDetectedEvenIfPluginStillReportsEnabled() {
        try (var registration = new Registration()) {
            NativeSickleDamageObserver.register(registration.owner);
            var actor = mock(Player.class);
            try (var session = NativeSickleDamageObserver.begin(actor, 2, event -> true)) {
                assertNotNull(session);
                HandlerList.unregisterAll(registration.owner);
                assertFalse(session.finish().valid());
            }
            assertTrue(registration.owner.isEnabled());
            assertNull(NativeSickleDamageObserver.begin(actor, 2, event -> true));
        }
    }

    @Test void changingAnEnabledObserverOwnerIsRejectedInsteadOfRebindingAnInflightSession() {
        try (var first = new Registration(); var second = new Registration()) {
            NativeSickleDamageObserver.register(first.owner);
            assertThrows(IllegalStateException.class, () -> NativeSickleDamageObserver.register(second.owner));
            verify(second.manager, never()).registerEvents(any(), any());
        }
    }
}
