package net.kaleidoscope.cookery.nms;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.function.Predicate;

/** Registered explicitly on enable; it does not load any version-specific NMS implementation. */
public final class NativeSickleDamageObserver implements Listener {
    private static volatile NativeSickleDamageObserver instance;
    private final JavaPlugin owner;
    private final NativeMiningDamageTracker tracker;

    private NativeSickleDamageObserver(JavaPlugin owner) {
        this.owner = owner; this.tracker = new NativeMiningDamageTracker(this::available);
    }

    public static synchronized void register(JavaPlugin owner) {
        if (instance != null && instance.owner == owner && instance.available()) return;
        if (instance != null && instance.owner != owner && instance.owner.isEnabled())
            throw new IllegalStateException("Native sickle observer owner changed while enabled");
        NativeSickleDamageObserver observer = new NativeSickleDamageObserver(owner);
        owner.getServer().getPluginManager().registerEvents(observer, owner);
        instance = observer;
    }

    private boolean available() {
        return owner.isEnabled() && registered(PlayerItemDamageEvent.getHandlerList())
                && registered(PlayerItemBreakEvent.getHandlerList());
    }

    private boolean registered(org.bukkit.event.HandlerList list) {
        return Arrays.stream(list.getRegisteredListeners()).anyMatch(listener -> listener.getPlugin() == owner && listener.getListener() == this);
    }

    public static NativeMiningDamageTracker.Session begin(Player player, int baseWear,
                                                          Predicate<PlayerItemDamageEvent> belongs,
                                                          Predicate<PlayerItemBreakEvent> belongsBreak) {
        NativeSickleDamageObserver observer = instance;
        return observer == null ? null : observer.tracker.begin(player, baseWear, belongs, belongsBreak);
    }

    public static NativeMiningDamageTracker.Session begin(Player player, int baseWear,
                                                          Predicate<PlayerItemDamageEvent> belongs) {
        return begin(player, baseWear, belongs, ignored -> false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(PlayerItemDamageEvent event) { tracker.observe(event); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBreak(PlayerItemBreakEvent event) { tracker.observe(event); }
}
