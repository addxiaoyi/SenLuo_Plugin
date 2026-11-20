package net.kaleidoscope.cookery.block.listener;

import net.kaleidoscope.cookery.block.behavior.SteamerBehavior;
import net.kaleidoscope.cookery.block.entity.SteamerWorld;
import net.kaleidoscope.cookery.nms.*;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.bukkit.world.*;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SteamerFallingBlockListenerTest {
    private final List<AutoCloseable> handles=new ArrayList<>();
    private final FallingBlock falling=mock(FallingBlock.class);
    private final Object nativeEntity=new Object(),nativeLevel=new Object(),nativePos=new Object();
    private final Block block=mock(Block.class);
    private final EntityChangeBlockEvent event=mock(EntityChangeBlockEvent.class);
    private final SteamerFallingBlockListener listener=new SteamerFallingBlockListener();
    private MockedStatic<SteamerWorld> geometry;private MockedStatic<SteamerBehavior> behavior;
    private boolean cancelled;private int returned;
    private <T> MockedStatic<T> stat(Class<T> type){var s=mockStatic(type);handles.add(s);return s;}
    @BeforeEach void setup(){
        var bridge=mock(NmsBridge.class);stat(NmsBridgeProvider.class).when(NmsBridgeProvider::bridge).thenReturn(bridge);
        when(bridge.nmsHandle(falling)).thenReturn(nativeEntity);
        when(event.getEntity()).thenReturn(falling);when(event.getBlock()).thenReturn(block);
        when(event.isCancelled()).thenAnswer(c->cancelled);doAnswer(c->{cancelled=c.getArgument(0);return null;}).when(event).setCancelled(anyBoolean());
        var world=mock(World.class);var id=UUID.randomUUID();when(world.getUID()).thenReturn(id);when(block.getWorld()).thenReturn(world);
        var level=mock(BukkitWorld.class);var storage=mock(CEWorld.class);stat(BukkitAdaptor.class).when(()->BukkitAdaptor.adapt(world)).thenReturn(level);
        when(level.minecraftWorld()).thenReturn(nativeLevel);when(level.storageWorld()).thenReturn(storage);when(storage.world()).thenReturn(level);
        stat(LocationUtils.class).when(()->LocationUtils.toBlockPos(anyInt(),anyInt(),anyInt())).thenReturn(nativePos);
        geometry=stat(SteamerWorld.class);geometry.when(()->SteamerWorld.landingSupported(nativeLevel,nativePos)).thenReturn(true);
        SteamerBehavior.pendingData.put(nativeEntity,new SteamerBehavior.PendingData(null,null,0));
        behavior=stat(SteamerBehavior.class);behavior.when(()->SteamerBehavior.dropPendingSteamer(nativeLevel,nativePos,nativeEntity)).thenAnswer(c->{
            if(SteamerBehavior.pendingData.remove(nativeEntity)!=null)returned++;return null;});
    }
    @AfterEach void close()throws Exception{SteamerBehavior.pendingData.clear();for(int i=handles.size()-1;i>=0;i--)handles.get(i).close();}
    @Test void supportedLandingNeverOverridesAnotherPluginsCancellation(){
        cancelled=true;listener.onEntityChangeBlockLowest(event);listener.onEntityChangeBlock(event);
        assertTrue(cancelled);verify(event,never()).setCancelled(false);listener.onCancelledLanding(event);listener.onCancelledLanding(event);
        assertEquals(1,returned);verify(falling,times(1)).remove();verify(falling).setDropItem(false);
    }
    @Test void supportedOrdinaryLandingKeepsSnapshotForNativeOnLand(){
        listener.onEntityChangeBlockLowest(event);listener.onEntityChangeBlock(event);listener.onCancelledLanding(event);
        assertFalse(cancelled);assertTrue(SteamerBehavior.pendingData.containsKey(nativeEntity));assertEquals(0,returned);verify(falling,never()).remove();
    }
    @Test void unsupportedLandingCancelsAndReturnsSavedContentsOnce(){
        geometry.when(()->SteamerWorld.landingSupported(nativeLevel,nativePos)).thenReturn(false);
        listener.onEntityChangeBlockLowest(event);listener.onEntityChangeBlock(event);assertTrue(cancelled);
        listener.onCancelledLanding(event);assertEquals(1,returned);assertFalse(SteamerBehavior.pendingData.containsKey(nativeEntity));
    }
    @Test void cancellationAtLaterPriorityIsStillHonored(){
        listener.onEntityChangeBlockLowest(event);listener.onEntityChangeBlock(event);cancelled=true;listener.onCancelledLanding(event);
        assertEquals(1,returned);assertTrue(cancelled);
    }
}
