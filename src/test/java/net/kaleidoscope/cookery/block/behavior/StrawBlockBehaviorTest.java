package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.nms.*;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class StrawBlockBehaviorTest {
    private StrawBlockBehavior behavior(Property<Direction.Axis> axis) throws Exception {
        var ctor=StrawBlockBehavior.class.getDeclaredConstructor(BlockDefinition.class,Property.class);ctor.setAccessible(true);
        return ctor.newInstance(mock(BlockDefinition.class),axis);
    }
    @Test void clickedFaceSelectsAllThreePillarAxes() throws Exception {
        Property<Direction.Axis> axis=mock(Property.class);var behavior=behavior(axis);
        var state=mock(ImmutableBlockState.class);var result=mock(ImmutableBlockState.class);var context=mock(BlockPlaceContext.class);
        for(Direction face:new Direction[]{Direction.UP,Direction.NORTH,Direction.EAST}) {
            when(context.getClickedFace()).thenReturn(face);when(state.with(axis,face.axis())).thenReturn(result);
            assertSame(result,behavior.updateStateForPlacement(context,state));verify(state).with(axis,face.axis());
        }
    }
    @Test void oldFloatAndNewDoubleFallDistancesReachTheSameLandingWithoutVanillaDamage() throws Exception {
        var behavior=behavior(mock(Property.class));var bridge=mock(NmsBridge.class);Object raw=new Object(),pos=new Object();
        var living=mock(LivingEntity.class);var world=mock(org.bukkit.World.class);var block=mock(org.bukkit.block.Block.class);
        when(bridge.bukkitEntity(raw)).thenReturn(living);when(living.getWorld()).thenReturn(world);when(world.getBlockAt(8,64,8)).thenReturn(block);
        try(var provider=mockStatic(NmsBridgeProvider.class);var location=mockStatic(LocationUtils.class);
            var landing=mockStatic(StrawLanding.class);var bukkit=mockStatic(Bukkit.class)) {
            provider.when(NmsBridgeProvider::bridge).thenReturn(bridge);location.when(()->LocationUtils.fromBlockPos(pos)).thenReturn(new BlockPos(8,64,8));
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(true);
            behavior.fallOn(null,new Object[]{null,null,pos,raw,15f});behavior.fallOn(null,new Object[]{null,null,pos,raw,15d});
            landing.verify(()->StrawLanding.land(eq(block),eq(living),eq(15d),any()),times(2));
            when(bridge.bukkitEntity(raw)).thenReturn(mock(org.bukkit.entity.Entity.class));
            behavior.fallOn(null,new Object[]{null,null,pos,raw,15d});landing.verifyNoMoreInteractions();
        }
    }
}
