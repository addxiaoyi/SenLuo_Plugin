package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.momirealms.craftengine.bukkit.util.LevelUtils;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class ScarecrowHitEffects {
    private ScarecrowHitEffects() {}

    static void hit(Location at, Player actor) {
        at.getWorld().playSound(at, "minecraft:entity.armor_stand.hit", SoundCategory.NEUTRAL, .3f, 1);
        LevelUtils.sendGameEvent(at.getWorld(), actor, GameEvent.ENTITY_DAMAGE,
                new Vector(at.getX(), at.getY(), at.getZ()));
    }

    static void broken(Location at) {
        // Source getY(2/3), width/4, height/4 and ten oak-plank fragments.
        Particles.emit(at.getWorld(), Particle.BLOCK, at.getX(), at.getY() + 2.375 * 2 / 3, at.getZ(),
                10, .125, 2.375 / 4, .125, .05, Material.OAK_PLANKS.createBlockData());
    }
}
