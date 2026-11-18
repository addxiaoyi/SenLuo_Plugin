package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.LongBenchBehavior;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.entity.seat.BukkitSeat;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.entity.seat.SeatConfig;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LongBenchControllerTest {
    private final Player player = mock(Player.class);
    private final org.bukkit.entity.Player bukkitPlayer = mock(org.bukkit.entity.Player.class);
    private final org.bukkit.World bukkitWorld = mock(org.bukkit.World.class);
    private final org.bukkit.entity.Entity mount = mock(org.bukkit.entity.Entity.class);
    private final World world = mock(World.class);
    private final CEWorld storage = mock(CEWorld.class);
    private final LongBenchBehavior behavior = mock(LongBenchBehavior.class);
    private MockedStatic<InteractGuard> protection;
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<BukkitSeat> seats;
    private BukkitSeat seat;
    private SeatConfig config;
    private BlockEntity entity;
    private LongBenchController bench;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class); protection = mockStatic(InteractGuard.class);
        seats = mockConstruction(BukkitSeat.class, (mock, context) -> config = (SeatConfig) context.arguments().get(1));
        when(behavior.createBlockEntityController(any())).thenReturn(mock(BlockEntityController.class));
        ImmutableBlockState state = mock(ImmutableBlockState.class); when(state.behavior()).thenReturn(behavior);
        when(behavior.axis(any())).thenReturn(Direction.Axis.X);
        entity = new BlockEntity(new BlockPos(8, 64, 8), state); entity.world = storage; entity.setValid(true);
        when(storage.world()).thenReturn(world); when(world.platformWorld()).thenReturn(bukkitWorld);
        when(storage.getBlockEntityAtIfLoaded(entity.pos)).thenReturn(entity);
        when(player.platformPlayer()).thenReturn(bukkitPlayer);
        when(bukkitPlayer.getWorld()).thenReturn(bukkitWorld); when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(bukkitPlayer.isValid()).thenReturn(true); when(bukkitPlayer.isOnline()).thenReturn(true);
        when(bukkitPlayer.getVehicle()).thenReturn(mount); when(mount.isValid()).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitPlayer)).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(true);
        protection.when(() -> InteractGuard.canInteract(player, world, entity.pos)).thenReturn(true);
        bench = new LongBenchController(entity, behavior); seat = seats.constructed().getFirst();
        when(seat.spawnSeat(eq(player), any())).thenReturn(true); when(seat.getSeatEntity()).thenReturn(mount);
    }
    @AfterEach void close() { seats.close(); protection.close(); bukkit.close(); }

    @Test void bothAxesUseSourceFacingAndPassengerAnchorWithoutTouchingHeldItems() {
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            when(behavior.axis(any())).thenReturn(axis);
            assertEquals(InteractionResult.SUCCESS_AND_CANCEL, bench.interact(player));
            verify(seat).spawnSeat(player, new WorldPosition(world, 8.5, 64, 8.5, 0, axis == Direction.Axis.X ? 0 : -90));
        }
        assertEquals(.25, .6 + config.position().y, 1e-6); assertFalse(config.limitPlayerRotation());
        verify(player, never()).getItemInHand(any()); verify(player, never()).setItemInHand(any(), any());
        verify(seat, never()).destroy();
    }
    @Test void sneakingOrOccupiedBenchPassesWithoutCreatingAnotherSeat() {
        when(player.isSecondaryUseActive()).thenReturn(true);
        assertEquals(InteractionResult.PASS, bench.interact(player));
        when(player.isSecondaryUseActive()).thenReturn(false); when(seat.isOccupied()).thenReturn(true);
        assertEquals(InteractionResult.PASS, bench.interact(player));
        verify(seat, never()).spawnSeat(any(), any()); protection.verifyNoInteractions();
    }
    @Test void protectionDenialAndReplacementDuringProtectionPreventMount() {
        protection.when(() -> InteractGuard.canInteract(player, world, entity.pos)).thenReturn(false);
        assertEquals(InteractionResult.FAIL, bench.interact(player));
        protection.when(() -> InteractGuard.canInteract(player, world, entity.pos)).thenAnswer(call -> {
            when(storage.getBlockEntityAtIfLoaded(entity.pos)).thenReturn(null); return true;
        });
        assertEquals(InteractionResult.FAIL, bench.interact(player)); verify(seat, never()).spawnSeat(any(), any());
    }
    @Test void staleUnloadedForeignRegionAndInvalidPlayersCannotMount() {
        entity.setValid(false); assertEquals(InteractionResult.FAIL, bench.interact(player)); entity.setValid(true);
        when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SPECTATOR);
        assertEquals(InteractionResult.FAIL, bench.interact(player)); when(bukkitPlayer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(bukkitPlayer.isDead()).thenReturn(true); assertEquals(InteractionResult.FAIL, bench.interact(player)); when(bukkitPlayer.isDead()).thenReturn(false);
        when(bukkitPlayer.isOnline()).thenReturn(false); assertEquals(InteractionResult.FAIL, bench.interact(player)); when(bukkitPlayer.isOnline()).thenReturn(true);
        when(bukkitPlayer.getWorld()).thenReturn(null); assertEquals(InteractionResult.FAIL, bench.interact(player)); when(bukkitPlayer.getWorld()).thenReturn(bukkitWorld);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(false);
        assertEquals(InteractionResult.FAIL, bench.interact(player));
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitWorld, 0, 0)).thenReturn(true);
        bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(bukkitPlayer)).thenReturn(false);
        assertEquals(InteractionResult.FAIL, bench.interact(player));
        verify(seat, never()).spawnSeat(any(), any());
    }
    @Test void failedMountReturnsPassAndCleansUp() {
        when(seat.spawnSeat(any(), any())).thenReturn(false);
        assertEquals(InteractionResult.PASS, bench.interact(player)); verify(seat).destroy();
    }
    @Test void removalDuringMountCannotLeaveTheNewSeatBehind() {
        when(seat.spawnSeat(any(), any())).thenAnswer(call -> { bench.onRemove(); return true; });
        assertEquals(InteractionResult.FAIL, bench.interact(player)); verify(seat, times(2)).destroy();
        assertEquals(InteractionResult.FAIL, bench.interact(player)); verify(seat).spawnSeat(any(), any());
    }
    @Test void unloadAndReloadDuringMountInvalidatesTheAttemptButAllowsLaterUse() {
        doAnswer(call -> { bench.onUnload(); bench.onLoad(); return true; }).when(seat).spawnSeat(any(), any());
        assertEquals(InteractionResult.FAIL, bench.interact(player)); verify(seat, times(2)).destroy();
        doReturn(true).when(seat).spawnSeat(any(), any());
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL, bench.interact(player));
    }
    @Test void mountCallbacksCannotReenterOrReplaceTheBench() {
        when(seat.spawnSeat(any(), any())).thenAnswer(call -> {
            assertEquals(InteractionResult.FAIL, bench.interact(player));
            when(storage.getBlockEntityAtIfLoaded(entity.pos)).thenReturn(null); return true;
        });
        assertEquals(InteractionResult.FAIL, bench.interact(player)); verify(seat).spawnSeat(any(), any()); verify(seat).destroy();
    }
    @Test void mountRedirectedToAnotherVehicleIsNotAccepted() {
        when(bukkitPlayer.getVehicle()).thenReturn(null);
        assertEquals(InteractionResult.FAIL, bench.interact(player)); verify(seat).destroy();
    }
    @Test void exceptionClearsInteractionGuardAndAttemptsSeatCleanup() {
        doThrow(new IllegalStateException("mount failure")).when(seat).spawnSeat(any(), any());
        assertThrows(IllegalStateException.class, () -> bench.interact(player)); verify(seat).destroy();
        doReturn(true).when(seat).spawnSeat(any(), any());
        assertEquals(InteractionResult.SUCCESS_AND_CANCEL, bench.interact(player));
    }
    @Test void unloadClosesTheSeatUntilLoadedAndRemoveCannotBeReopened() {
        bench.onUnload(); assertEquals(InteractionResult.FAIL, bench.interact(player));
        bench.onLoad(); assertEquals(InteractionResult.SUCCESS_AND_CANCEL, bench.interact(player));
        bench.onRemove(); bench.onLoad(); assertEquals(InteractionResult.FAIL, bench.interact(player));
        verify(seat, times(2)).destroy();
        CompoundTag tag = new CompoundTag(); bench.saveSeatEntityData(tag);
        assertEquals("seat_block_entity", tag.getString("type"));
    }
    @Test void removalAndUnloadCallbacksMayReenterWithoutRecursivelyDestroyingTheSeat() {
        doAnswer(call -> { bench.onRemove(); bench.onUnload(); return null; }).when(seat).destroy();
        bench.onRemove(); bench.onRemove(); bench.onUnload();
        verify(seat).destroy();
    }
}
