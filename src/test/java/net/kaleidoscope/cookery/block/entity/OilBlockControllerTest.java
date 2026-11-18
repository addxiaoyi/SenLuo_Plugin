package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;
import java.util.random.RandomGenerator;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OilBlockControllerTest {
    @Test void sourceParticleProbabilityPositionAndVelocityArePreserved() throws Exception {
        BlockEntity entity=mock(BlockEntity.class);entity.world=mock(CEWorld.class);
        var pos=BlockEntity.class.getDeclaredField("pos");pos.setAccessible(true);
        pos.set(entity,new net.momirealms.craftengine.core.world.BlockPos(8,64,8));
        when(entity.isValid()).thenReturn(true);
        var world=mock(org.bukkit.World.class);var ceWorld=mock(net.momirealms.craftengine.core.world.World.class);
        when(entity.world.world()).thenReturn(ceWorld);when(ceWorld.platformWorld()).thenReturn(world);
        when(entity.world.getBlockEntityAtIfLoaded(entity.pos)).thenReturn(entity);
        RandomGenerator random=mock(RandomGenerator.class);when(random.nextInt(50)).thenReturn(0);
        when(random.nextDouble()).thenReturn(.3);when(random.nextBoolean()).thenReturn(true,false);
        try(var bukkit=mockStatic(Bukkit.class);var particles=mockStatic(Particles.class)) {
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(true);
            var controller=new OilBlockController(entity);controller.animate(random);
            particles.verify(()->Particles.emit(world,Particle.WAX_OFF,8.6,65.1,8.4,0,.3,.1,.3,1,null));
            particles.clearInvocations();when(random.nextInt(50)).thenReturn(1);controller.animate(random);particles.verifyNoInteractions();
            when(random.nextInt(50)).thenReturn(0);when(entity.world.getBlockEntityAtIfLoaded(entity.pos)).thenReturn(null);
            controller.animate(random);particles.verifyNoInteractions();
            when(entity.world.getBlockEntityAtIfLoaded(entity.pos)).thenReturn(entity);
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(false);controller.animate(random);particles.verifyNoInteractions();
            when(entity.isValid()).thenReturn(false);controller.animate(random);particles.verifyNoInteractions();
        }
    }
}
