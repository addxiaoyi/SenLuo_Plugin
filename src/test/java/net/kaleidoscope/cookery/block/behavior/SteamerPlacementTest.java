package net.kaleidoscope.cookery.block.behavior;

import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SteamerPlacementTest {
    private final Key key=Key.of("kaleidoscopecookery:steamer");
    private SteamerBehavior behavior() throws ReflectiveOperationException {
        var definition=mock(BlockDefinition.class); when(definition.id()).thenReturn(key);
        var constructor=SteamerBehavior.class.getDeclaredConstructor(BlockDefinition.class); constructor.setAccessible(true);
        return constructor.newInstance(definition);
    }
    @Test void stackedPlacementUsesRegisteredBehaviorAndPropagatesCancellationWithoutExtraDebit() throws Exception {
        var behavior=behavior(); var context=mock(BlockPlaceContext.class); var item=mock(Item.class);
        var definition=mock(ItemDefinition.class); var placer=mock(BlockItemBehavior.class);
        when(context.getItem()).thenReturn(item); when(item.getDefinition()).thenReturn(Optional.of(definition));
        when(definition.behavior()).thenReturn(placer); when(placer.getFirst(BlockItemBehavior.class)).thenReturn(placer);
        when(placer.block()).thenReturn(key);
        for(var result:new InteractionResult[]{InteractionResult.FAIL,InteractionResult.PASS,InteractionResult.SUCCESS}) {
            when(placer.place(context)).thenReturn(result); assertEquals(result,behavior.placeSteamer(context));
        }
        verify(placer,times(3)).place(context); verify(item,never()).count(anyInt());
        verify(context,never()).getLevel(); verify(context,never()).getPlayer();
    }
    @Test void mismatchedOrMissingBlockBehaviorCannotPlace() throws Exception {
        var behavior=behavior(); var context=mock(BlockPlaceContext.class); var item=mock(Item.class);
        when(context.getItem()).thenReturn(item); when(item.getDefinition()).thenReturn(Optional.empty());
        assertEquals(InteractionResult.FAIL,behavior.placeSteamer(context));
        var definition=mock(ItemDefinition.class); var placer=mock(BlockItemBehavior.class);
        when(item.getDefinition()).thenReturn(Optional.of(definition)); when(definition.behavior()).thenReturn(placer);
        when(placer.getFirst(BlockItemBehavior.class)).thenReturn(placer); when(placer.block()).thenReturn(Key.of("minecraft:stone"));
        assertEquals(InteractionResult.FAIL,behavior.placeSteamer(context)); verify(placer,never()).place(any());
    }
    @Test void ordinaryNonheatSupportAllowsOppositeFacingPlacement() throws Exception {
        var behavior=behavior();var state=mock(net.momirealms.craftengine.core.block.ImmutableBlockState.class);
        var type=mock(net.momirealms.craftengine.core.block.property.Property.class);
        var facing=mock(net.momirealms.craftengine.core.block.property.Property.class);
        var base=mock(net.momirealms.craftengine.core.block.property.Property.class);
        for(var pair:java.util.Map.of("typeProperty",type,"facingProperty",facing,"hasBaseProperty",base).entrySet()){
            var field=SteamerBehavior.class.getDeclaredField(pair.getKey());field.setAccessible(true);field.set(behavior,pair.getValue());
        }
        var context=mock(BlockPlaceContext.class);var world=mock(net.momirealms.craftengine.core.world.World.class);
        var pos=new net.momirealms.craftengine.core.world.BlockPos(8,64,8);var existing=mock(net.momirealms.craftengine.core.world.ExistingBlock.class);
        when(context.getLevel()).thenReturn(world);when(context.getClickedPos()).thenReturn(pos);when(world.getBlock(pos)).thenReturn(existing);
        when(context.getHorizontalDirection()).thenReturn(net.momirealms.craftengine.core.util.Direction.NORTH);
        when(state.with(type,net.momirealms.craftengine.core.block.property.type.SlabType.BOTTOM)).thenReturn(state);
        when(state.with(facing,net.momirealms.craftengine.core.util.Direction.SOUTH)).thenReturn(state);when(state.with(base,false)).thenReturn(state);
        Object nativeLevel=new Object(),nativePos=new Object(),below=new Object();when(world.minecraftWorld()).thenReturn(nativeLevel);
        try(var geometry=mockStatic(net.kaleidoscope.cookery.block.entity.SteamerWorld.class);
            var positions=mockStatic(net.momirealms.craftengine.bukkit.util.LocationUtils.class)){
            geometry.when(()->net.kaleidoscope.cookery.block.entity.SteamerWorld.accessible(world,pos.below())).thenReturn(true);
            positions.when(()->net.momirealms.craftengine.bukkit.util.LocationUtils.toBlockPos(pos)).thenReturn(nativePos);
            positions.when(()->net.momirealms.craftengine.bukkit.util.LocationUtils.below(nativePos)).thenReturn(below);
            geometry.when(()->net.kaleidoscope.cookery.block.entity.SteamerWorld.hasBase(nativeLevel,below,behavior.block())).thenReturn(false);
            assertSame(state,behavior.updateStateForPlacement(context,state));verify(state).with(facing,net.momirealms.craftengine.core.util.Direction.SOUTH);verify(state).with(base,false);
            geometry.when(()->net.kaleidoscope.cookery.block.entity.SteamerWorld.accessible(world,pos.below())).thenReturn(false);
            assertNull(behavior.updateStateForPlacement(context,state));
        }
    }
}
