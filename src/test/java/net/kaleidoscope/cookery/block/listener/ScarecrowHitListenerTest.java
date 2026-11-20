package net.kaleidoscope.cookery.block.listener;

import net.kaleidoscope.cookery.block.entity.ScarecrowController;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ScarecrowHitListenerTest {
    @Test void earlierCancellationNeverPrimesTheSourceWindow() {
        var event=mock(FurnitureBreakEvent.class);var controller=mock(ScarecrowController.class);
        when(event.isCancelled()).thenReturn(true);ScarecrowHitListener.decide(event,controller);
        verifyNoInteractions(controller);
    }
    @Test void firstStrikeCancelsBreakAndSecondLeavesProtectionEventIntact() {
        var event=mock(FurnitureBreakEvent.class);var controller=mock(ScarecrowController.class);
        var player=mock(org.bukkit.entity.Player.class);when(event.getPlayer()).thenReturn(player);
        when(controller.playerStrike(player)).thenReturn(false,true);
        ScarecrowHitListener.decide(event,controller);verify(event).setCancelled(true);
        clearInvocations(event);ScarecrowHitListener.decide(event,controller);verify(event,never()).setCancelled(anyBoolean());
    }
}
