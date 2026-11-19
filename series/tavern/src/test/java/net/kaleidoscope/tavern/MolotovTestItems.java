package net.kaleidoscope.tavern;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A component-aware value stack; no actual CraftEngine decoding is asserted by these unit tests. */
final class MolotovTestItems {
    private MolotovTestItems() {}
    static void initialize() {
        try (var access = mockStatic(io.papermc.paper.registry.RegistryAccess.class);
             var bridge = mockStatic(io.papermc.paper.InternalAPIBridge.class)) {
            bridge.when(io.papermc.paper.InternalAPIBridge::get).thenReturn(mock(io.papermc.paper.InternalAPIBridge.class));
            access.when(io.papermc.paper.registry.RegistryAccess::registryAccess)
                    .thenReturn(mock(io.papermc.paper.registry.RegistryAccess.class, call -> mock(Registry.class)));
            // Complete Registry's class initialization before opening any Mockito stubbing chain.
            // This fixture can be the first test in the JVM to touch the Paper registries.
            assertNotNull(Registry.SOUNDS);
            doAnswer(call -> mock(Sound.class)).when(Registry.SOUNDS).getOrThrow(any(net.kyori.adventure.key.Key.class));
            doAnswer(call -> mock(DataComponentType.Valued.class, withSettings().extraInterfaces(DataComponentType.NonValued.class)))
                    .when(Registry.DATA_COMPONENT_TYPE).getOrThrow(any(net.kyori.adventure.key.Key.class));
            doAnswer(call -> mock(org.bukkit.GameRule.class)).when(Registry.GAME_RULE).getOrThrow(any(net.kyori.adventure.key.Key.class));
            // Modern Material delegates isAir() to its memoized BlockType registry entry.
            // An unconfigured registry returned null and made AIR look solid in ignition tests.
            doAnswer(call -> {
                var type = mock(org.bukkit.block.BlockType.class);
                String key = call.getArgument(0, org.bukkit.NamespacedKey.class).getKey();
                when(type.isAir()).thenReturn(key.equals("air") || key.equals("cave_air") || key.equals("void_air"));
                return type;
            }).when(Registry.BLOCK).get(any(org.bukkit.NamespacedKey.class));
            assertNotNull(Sound.ENTITY_SNOWBALL_THROW);
            assertNotNull(DataComponentTypes.CONSUMABLE);
            assertNotNull(org.bukkit.GameRules.MOB_GRIEFING);
            org.junit.jupiter.api.Assertions.assertTrue(Material.AIR.isAir());
        }
    }

    static Stack bottle(int amount) { return new Stack(MolotovPhysics.ITEM, amount, "original"); }
    static Stack decode(byte[] bytes) {
        String[] fields = new String(bytes, StandardCharsets.UTF_8).split("\\|", -1);
        return new Stack(fields[0], Integer.parseInt(fields[1]), fields[2]);
    }

    static final class Stack extends ItemStack {
        final String id, components;
        private int amount;
        private final Map<DataComponentType, Object> data;
        Stack(String id, int amount, String components) { this(id, amount, components, new HashMap<>()); }
        private Stack(String id, int amount, String components, Map<DataComponentType, Object> data) {
            super(); this.id = id; this.amount = amount; this.components = components; this.data = data;
        }
        @Override public Material getType() { return Material.PAPER; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public boolean isEmpty() { return amount <= 0; }
        @Override public int getMaxStackSize() { return 16; }
        @Override public Stack clone() { return new Stack(id, amount, components, new HashMap<>(data)); }
        @Override public Stack asQuantity(int quantity) { Stack result = clone(); result.amount = quantity; return result; }
        @Override public boolean isSimilar(ItemStack item) { return item instanceof Stack other && id.equals(other.id)
                && components.equals(other.components) && data.equals(other.data); }
        @Override public boolean equals(Object item) { return item instanceof Stack other && amount == other.amount && isSimilar(other); }
        @Override public int hashCode() { return Objects.hash(id, amount, components, data); }
        @Override public <T> void setData(DataComponentType.Valued<T> type, T value) { data.put(type, value); }
        @SuppressWarnings("unchecked") @Override public <T> T getData(DataComponentType.Valued<T> type) { return (T) data.get(type); }
        @Override public byte[] serializeAsBytes() { return (id + "|" + amount + "|" + components).getBytes(StandardCharsets.UTF_8); }
    }
}
