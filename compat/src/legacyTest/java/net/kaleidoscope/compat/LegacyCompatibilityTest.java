package net.kaleidoscope.compat;

import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs with only Paper 1.21.1, so modern API linkage cannot hide regressions. */
class LegacyCompatibilityTest {
    @Test void usesLegacyAttributesAndGameRules() {
        assertSame(Attribute.GENERIC_MAX_HEALTH, Attributes.MAX_HEALTH);
        assertSame(Attribute.GENERIC_MOVEMENT_SPEED, Attributes.MOVEMENT_SPEED);
        assertSame(Attribute.GENERIC_STEP_HEIGHT, Attributes.STEP_HEIGHT);
        assertSame(Attribute.PLAYER_BLOCK_INTERACTION_RANGE, Attributes.BLOCK_INTERACTION_RANGE);
        assertSame(Attribute.PLAYER_ENTITY_INTERACTION_RANGE, Attributes.ENTITY_INTERACTION_RANGE);
        assertSame(GameRule.RANDOM_TICK_SPEED, Rules.RANDOM_TICK_SPEED);
        assertSame(GameRule.DO_TILE_DROPS, Rules.DO_TILE_DROPS);
    }

    @Test void chargeDoesNotNeedModernConsumableClasses() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.papermc.paper.datacomponent.item.Consumable"));
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        FoodComponent food = mock(FoodComponent.class);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getFood()).thenReturn(food);
        ItemComponents.use(stack, true, false);
        verify(food).setEatSeconds(3600f);
        verify(food).setCanAlwaysEat(true);
        verify(food).setNutrition(0);
        verify(meta).setFood(food);
        ItemComponents.use(stack, false, false);
        verify(meta).setFood(null);
    }

    @Test void foodEffectsAndContainerStayOnTheLegacyFoodComponent() {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        FoodComponent food = mock(FoodComponent.class);
        PotionEffect effect = mock(PotionEffect.class);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getFood()).thenReturn(food);
        when(meta.hasFood()).thenReturn(true);
        ItemComponents.food(stack, 1.6f, false, List.of(new ItemComponents.Effect(effect, .25f)));
        verify(food).setEatSeconds(1.6f);
        verify(food).setEffects(List.of());
        verify(food).addEffect(effect, .25f);
        assertFalse(ItemComponents.hasRemainder(stack));
        when(food.getUsingConvertsTo()).thenReturn(mock(ItemStack.class));
        assertTrue(ItemComponents.hasRemainder(stack));
    }

    @Test void cooldownFallsBackToMaterialWithoutNewOverloads() {
        Player player = mock(Player.class);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.IRON_HOE);
        when(player.hasCooldown(Material.IRON_HOE)).thenReturn(true);
        assertTrue(Cooldowns.has(player, stack));
        Cooldowns.set(player, stack, 12);
        verify(player).setCooldown(Material.IRON_HOE, 12);
    }
}
