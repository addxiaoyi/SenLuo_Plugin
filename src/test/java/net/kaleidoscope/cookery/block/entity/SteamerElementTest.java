package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.entity.render.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SteamerElementTest {
    @Test void displaysFollowSparseLayerPositionsAndCoverHidesAllSlots() {
        var controller=mock(SteamerController.class); var empty=mock(Item.class); when(empty.isEmpty()).thenReturn(true);
        Item[] items=new Item[8]; java.util.Arrays.fill(items,empty); items[6]=mock(Item.class);
        when(controller.getItems()).thenReturn(items); when(controller.getItemCount()).thenReturn(1);
        var packets=mock(ItemDisplayPackets.class,RETURNS_SELF);
        try(var builders=mockStatic(ItemDisplayPackets.class); var sets=mockConstruction(ItemDisplaySet.class)) {
            builders.when(()->ItemDisplayPackets.at(any())).thenReturn(packets);
            var element=new SteamerElement(controller,new WorldPosition(null,0,0,0)); var display=sets.constructed().getFirst();
            verify(display).setPackets(eq(6),any(),any()); verify(display).clear(0);
            verify(packets).translation(eq(-.15f),floatThat(y->Math.abs(y-.67f)<1e-5),eq(.15f));
            clearInvocations(display,packets); when(controller.isCovered()).thenReturn(true); element.prepareUpdate();
            for(int i=0;i<8;i++)verify(display).clear(i);
            verify(display,never()).setPackets(anyInt(),any(),any());
            clearInvocations(display); when(controller.isCovered()).thenReturn(false); element.prepareUpdate();
            verify(display).setPackets(eq(6),any(),any());
            var player=mock(Player.class); element.show(player); element.update(player); element.hide(player);
            verify(display).show(player); verify(display).update(player); verify(display).hide(player);
        }
    }
}
