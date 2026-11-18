package net.kaleidoscope.cookery.block.listener;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class OilBlockListenerTest {
    @Test void onlyFireAboveOilIsProtectedFromFading() {
        var fire=mock(Block.class);var oil=mock(Block.class);var world=mock(World.class);
        when(fire.getType()).thenReturn(Material.FIRE);when(fire.getRelative(BlockFace.DOWN)).thenReturn(oil);
        when(oil.getWorld()).thenReturn(world);var state=mock(ImmutableBlockState.class,RETURNS_DEEP_STUBS);
        when(state.owner().value().id()).thenReturn(Key.of("kaleidoscopecookery:oil_block"));
        var event=mock(BlockFadeEvent.class);when(event.getBlock()).thenReturn(fire);
        try(var bukkit=mockStatic(Bukkit.class);var blocks=mockStatic(CraftEngineBlocks.class)) {
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(true);
            blocks.when(()->CraftEngineBlocks.getCustomBlockState(oil)).thenReturn(state);
            var listener=new OilBlockListener();listener.fading(event);verify(event).setCancelled(true);
            clearInvocations(event);when(fire.getType()).thenReturn(Material.SNOW);listener.fading(event);verify(event,never()).setCancelled(true);
            when(fire.getType()).thenReturn(Material.FIRE);blocks.when(()->CraftEngineBlocks.getCustomBlockState(oil)).thenReturn(null);
            listener.fading(event);verify(event,never()).setCancelled(true);
        }
    }
    @Test void naturalSurfaceSpawnsAreRejectedButEggAndSpawnerRemainAvailable() {
        var block=mock(Block.class);var oil=mock(Block.class);var world=mock(World.class);
        when(block.getRelative(BlockFace.DOWN)).thenReturn(oil);when(oil.getWorld()).thenReturn(world);
        var location=mock(Location.class);when(location.getBlock()).thenReturn(block);
        var state=mock(ImmutableBlockState.class,RETURNS_DEEP_STUBS);when(state.owner().value().id()).thenReturn(Key.of("kaleidoscopecookery:oil_block"));
        var event=mock(CreatureSpawnEvent.class);when(event.getLocation()).thenReturn(location);
        try(var bukkit=mockStatic(Bukkit.class);var blocks=mockStatic(CraftEngineBlocks.class)) {
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(true);blocks.when(()->CraftEngineBlocks.getCustomBlockState(oil)).thenReturn(state);
            var listener=new OilBlockListener();
            for(var reason:new CreatureSpawnEvent.SpawnReason[]{CreatureSpawnEvent.SpawnReason.NATURAL,CreatureSpawnEvent.SpawnReason.PATROL,CreatureSpawnEvent.SpawnReason.RAID}) {
                clearInvocations(event);when(event.getSpawnReason()).thenReturn(reason);listener.spawning(event);verify(event).setCancelled(true);
            }
            for(var reason:new CreatureSpawnEvent.SpawnReason[]{CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,CreatureSpawnEvent.SpawnReason.SPAWNER,CreatureSpawnEvent.SpawnReason.CUSTOM}) {
                clearInvocations(event);when(event.getSpawnReason()).thenReturn(reason);listener.spawning(event);verify(event,never()).setCancelled(true);
            }
            when(event.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.NATURAL);
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(world,0,0)).thenReturn(false);blocks.clearInvocations();
            listener.spawning(event);blocks.verifyNoInteractions();verify(event,never()).setCancelled(true);
        }
    }
}
