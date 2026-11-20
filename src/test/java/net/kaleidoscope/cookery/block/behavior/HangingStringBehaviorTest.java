package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.nms.*;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.*;
import org.bukkit.damage.*;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HangingStringBehaviorTest {
    private final BlockDefinition definition=mock(BlockDefinition.class);
    private HangingStringBehavior behavior(boolean damage) throws Exception {
        var ctor=HangingStringBehavior.class.getDeclaredConstructor(BlockDefinition.class,Property.class,Property.class,Key.class,int.class,Key.class,boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(definition,mock(Property.class),mock(Property.class),Key.of("kaleidoscopecookery:red_chili"),3,
                Key.of("minecraft:block.sweet_berry_bush.pick_berries"),damage);
    }
    @BeforeAll static void initializeDamageTypes() {
        try(var access=mockStatic(io.papermc.paper.registry.RegistryAccess.class)) {
            var provider=mock(io.papermc.paper.registry.RegistryAccess.class,c->
                    mock(org.bukkit.Registry.class,r->c.getArgument(0) == io.papermc.paper.registry.RegistryKey.DAMAGE_TYPE
                            && r.getMethod().getName().equals("getOrThrow") ? mock(DamageType.class):null));
            access.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(provider);
            assertNotNull(DamageType.MAGIC);
        }
    }
    @Test void successfulMainHandHarvestRetainsSourcePassResultAndOffhandDoesNothing() throws Exception {
        var behavior=behavior(true);var context=mock(UseOnContext.class);var player=mock(Player.class);
        var world=mock(net.momirealms.craftengine.core.world.World.class);var bukkitWorld=mock(World.class);var block=mock(org.bukkit.block.Block.class);
        var state=mock(ImmutableBlockState.class);var held=mock(Item.class);when(held.isEmpty()).thenReturn(true);
        when(context.getPlayer()).thenReturn(player);when(context.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(context.getItem()).thenReturn(held);when(context.getLevel()).thenReturn(world);when(context.getClickedPos()).thenReturn(new BlockPos(8,64,8));
        when(world.platformWorld()).thenReturn(bukkitWorld);when(bukkitWorld.getBlockAt(8,64,8)).thenReturn(block);
        try(var bukkit=mockStatic(Bukkit.class);var harvest=mockStatic(HangingStringHarvest.class)) {
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(bukkitWorld,0,0)).thenReturn(true);
            harvest.when(()->HangingStringHarvest.accepts(any(),any())).thenReturn(true);
            harvest.when(()->HangingStringHarvest.pick(any(),any(),any(),any(),any(),anyInt(),any())).thenReturn(true);
            assertEquals(InteractionResult.PASS,behavior.useOnBlock(context,state));
            when(context.getHand()).thenReturn(InteractionHand.OFF_HAND);assertEquals(InteractionResult.PASS,behavior.useOnBlock(context,state));
            harvest.verify(()->HangingStringHarvest.pick(eq(block),eq(player),eq(state),any(),any(),eq(3),any()),times(1));
            verify(player,never()).swingHand(any());
        }
    }
    @Test void onlyUndeadMobsInsideCurrentChiliTakeTwoMagicDamage() throws Exception {
        var chili=behavior(true);var mushroom=behavior(false);var bridge=mock(NmsBridge.class);var mob=mock(Mob.class);
        var world=mock(World.class);var block=mock(org.bukkit.block.Block.class);var state=mock(ImmutableBlockState.class,RETURNS_DEEP_STUBS);
        var damage=mock(DamageSource.class);var builder=mock(DamageSource.Builder.class);
        Object raw=new Object(),pos=new Object();when(bridge.bukkitEntity(raw)).thenReturn(mob);
        when(mob.isValid()).thenReturn(true);when(mob.getWorld()).thenReturn(world);when(mob.getType()).thenReturn(EntityType.ZOMBIE);
        when(world.isChunkLoaded(0,0)).thenReturn(true);
        when(world.getBlockAt(8,64,8)).thenReturn(block);when(state.owner().value()).thenReturn(definition);when(builder.build()).thenReturn(damage);
        try(var provider=mockStatic(NmsBridgeProvider.class);var bukkit=mockStatic(Bukkit.class);var locations=mockStatic(LocationUtils.class);
            var blocks=mockStatic(CraftEngineBlocks.class);var sources=mockStatic(DamageSource.class)) {
            var undead=mock(org.bukkit.Tag.class);when(undead.isTagged(EntityType.ZOMBIE)).thenReturn(true);
            provider.when(NmsBridgeProvider::bridge).thenReturn(bridge);locations.when(()->LocationUtils.fromBlockPos(pos)).thenReturn(new BlockPos(8,64,8));
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(mob)).thenReturn(true);bukkit.when(()->Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(true);
            bukkit.when(()->Bukkit.getTag("entity_types",NamespacedKey.minecraft("sensitive_to_smite"),EntityType.class)).thenReturn(undead);
            blocks.when(()->CraftEngineBlocks.getCustomBlockState(block)).thenReturn(state);sources.when(()->DamageSource.builder(DamageType.MAGIC)).thenReturn(builder);
            chili.entityInside(null,new Object[]{null,null,pos,raw});mushroom.entityInside(null,new Object[]{null,null,pos,raw});
            // Modern callbacks append an InsideBlockEffectApplier; entity index stays stable.
            chili.entityInside(null,new Object[]{null,null,pos,raw,new Object()});verify(mob,times(2)).damage(2,damage);
            when(undead.isTagged(EntityType.ZOMBIE)).thenReturn(false);chili.entityInside(null,new Object[]{null,null,pos,raw});
            when(undead.isTagged(EntityType.ZOMBIE)).thenReturn(true);blocks.when(()->CraftEngineBlocks.getCustomBlockState(block)).thenReturn(null);
            chili.entityInside(null,new Object[]{null,null,pos,raw});
            when(bridge.bukkitEntity(raw)).thenReturn(mock(org.bukkit.entity.Player.class));chili.entityInside(null,new Object[]{null,null,pos,raw});
            verify(mob,times(2)).damage(anyDouble(),any(DamageSource.class));
        }
    }
}
