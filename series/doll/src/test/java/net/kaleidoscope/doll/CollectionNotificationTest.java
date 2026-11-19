package net.kaleidoscope.doll;

import net.kaleidoscope.doll.api.DollCollectedEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionNotificationTest {
    @Test void collectingAnotherDollFromTheNotificationPreservesBothEntries() throws Exception {
        DollPlugin plugin = mock(DollPlugin.class);
        Player player = mock(Player.class);
        Server server = mock(Server.class);
        PluginManager events = mock(PluginManager.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        NamespacedKey key = new NamespacedKey("fixture", "collection");
        AtomicReference<String> stored = new AtomicReference<>();
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(events);
        when(player.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(key, PersistentDataType.STRING)).thenAnswer(call -> stored.get());
        doAnswer(call -> { stored.set(call.getArgument(2)); return null; })
                .when(pdc).set(eq(key), eq(PersistentDataType.STRING), anyString());
        field(plugin, "collectionKey", key);
        field(plugin, "catalog", new DollCatalog(List.of("doll:a", "doll:b"), List.of(),
                List.of(List.of("doll:a"), List.of("doll:a"), List.of("doll:b")),
                List.of("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond"), Map.of()));
        doCallRealMethod().when(plugin).collected(any(Player.class), anyString());
        doAnswer(call -> {
            DollCollectedEvent event = call.getArgument(0);
            if (event.dollId().equals("doll:a")) {
                assertEquals("doll:a", stored.get(), "The first collection must already be visible to listeners");
                plugin.collected(player, "doll:b");
            }
            return null;
        }).when(events).callEvent(any(DollCollectedEvent.class));
        plugin.collected(player, "doll:a");
        assertEquals("doll:a\ndoll:b", stored.get());
    }

    private static void field(DollPlugin plugin, String name, Object value) throws Exception {
        Field field = DollPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
