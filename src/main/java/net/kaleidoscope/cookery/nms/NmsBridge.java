package net.kaleidoscope.cookery.nms;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public interface NmsBridge {
    Object litBlockStateProperty();

    Object bukkitEntity(Object nmsEntity);

    Object nmsHandle(Entity entity);

    boolean isSpectatePacket(Object packet);

    boolean isPerformRespawnPacket(Object packet);

    BambooTrayWeather bambooTrayWeather(Object level, int x, int y, int z);

    Object createRiceBowlRecipe(net.kaleidoscope.cookery.recipe.RiceBowlRecipe recipe);

    /** Check the world border, build height, collision shape and item-use permission. */
    boolean canPlaceKitchenPart(Object level, int x, int y, int z, Object player, Object item);

    default Player bukkitPlayer(Object nmsEntity) {
        Object bukkit = bukkitEntity(nmsEntity);
        return bukkit instanceof Player player ? player : null;
    }
}
