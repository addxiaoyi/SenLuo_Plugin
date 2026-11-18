package net.kaleidoscope.cookery.block.listener;

import net.kaleidoscope.cookery.block.entity.ScarecrowController;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import org.bukkit.event.*;

/** Pack hit_times=1 passes each accepted attack through CE's cancellable break event. */
public final class ScarecrowHitListener implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breaking(FurnitureBreakEvent event) {
        ScarecrowController controller = event.furniture().controller.get(ScarecrowController.class, 0);
        if (controller != null) decide(event, controller);
    }

    static void decide(FurnitureBreakEvent event, ScarecrowController controller) {
        if (!event.isCancelled() && !controller.playerStrike(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void accepted(FurnitureBreakEvent event) {
        ScarecrowController controller = event.furniture().controller.get(ScarecrowController.class, 0);
        if (controller != null) controller.acceptPlayerBreak(event);
    }
}
