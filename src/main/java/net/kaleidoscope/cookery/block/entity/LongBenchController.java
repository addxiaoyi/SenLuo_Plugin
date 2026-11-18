package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.LongBenchBehavior;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.entity.seat.BukkitSeat;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.entity.seat.SeatConfig;
import net.momirealms.craftengine.core.entity.seat.SeatOwner;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.joml.Vector3f;

/** One transient seat per bench, with synchronous ownership checks around mount callbacks. */
public final class LongBenchController extends BlockEntityController implements SeatOwner {
    private final LongBenchBehavior behavior;
    private final BukkitSeat<LongBenchController> seat;
    private boolean interacting, removed, unloaded;
    private long generation;

    public LongBenchController(BlockEntity entity, LongBenchBehavior behavior) {
        super(entity); this.behavior = behavior;
        // CE adds .6 to the display position; source passenger anchor is .5 - .25.
        seat = new BukkitSeat<>(this, new SeatConfig(new Vector3f(0, -.35f, 0), 0, false));
    }
    public InteractionResult interact(Player player) {
        if (player == null || player.isSecondaryUseActive()) return InteractionResult.PASS;
        if (interacting || !owned(player)) return InteractionResult.FAIL;
        if (seat.isOccupied()) return InteractionResult.PASS;
        interacting = true;
        try {
            long expectedGeneration = generation;
            if (!InteractGuard.canInteract(player, blockEntity.world.world(), blockEntity.pos)
                    || generation != expectedGeneration || !owned(player)) return InteractionResult.FAIL;
            if (seat.isOccupied() || player.isSecondaryUseActive()) return InteractionResult.PASS;
            Direction.Axis expectedAxis = behavior.axis(blockEntity.blockState());
            WorldPosition origin = new WorldPosition(blockEntity.world.world(), blockEntity.pos.x() + .5,
                    blockEntity.pos.y(), blockEntity.pos.z() + .5, 0, expectedAxis == Direction.Axis.X ? 0 : -90);
            boolean committed = false;
            try {
                if (!seat.spawnSeat(player, origin)) return InteractionResult.PASS;
                // addPassenger fires plugin events before CE stores its seat reference.
                // Removal/unload/replacement during that event needs another cleanup here.
                if (generation != expectedGeneration || !owned(player)
                        || behavior.axis(blockEntity.blockState()) != expectedAxis) return InteractionResult.FAIL;
                var mounted = seat.getSeatEntity();
                var bukkitPlayer = (org.bukkit.entity.Player) player.platformPlayer();
                if (mounted == null || !mounted.isValid() || bukkitPlayer.getVehicle() != mounted)
                    return InteractionResult.FAIL;
                committed = true;
                return InteractionResult.SUCCESS_AND_CANCEL;
            } finally {
                if (!committed) seat.destroy();
            }
        } finally { interacting = false; }
    }
    private boolean owned(Player player) {
        if (removed || unloaded || blockEntity.world == null || !blockEntity.isValid()
                || !(player.platformPlayer() instanceof org.bukkit.entity.Player bukkitPlayer)) return false;
        var world = (org.bukkit.World) blockEntity.world.world().platformWorld();
        return Bukkit.isOwnedByCurrentRegion(bukkitPlayer)
                && Bukkit.isOwnedByCurrentRegion(world, blockEntity.pos.x() >> 4, blockEntity.pos.z() >> 4)
                && bukkitPlayer.isValid() && bukkitPlayer.isOnline() && !bukkitPlayer.isDead()
                && bukkitPlayer.getGameMode() != GameMode.SPECTATOR && bukkitPlayer.getWorld() == world
                && blockEntity.world.getBlockEntityAtIfLoaded(blockEntity.pos) == blockEntity;
    }
    @Override public void saveSeatEntityData(CompoundTag tag) { tag.putString("type", "seat_block_entity"); }
    @Override public void onRemove() {
        if (removed) return;
        removed = true; generation++; seat.destroy();
    }
    @Override public void onUnload() {
        if (removed || unloaded) return;
        unloaded = true; generation++; seat.destroy();
    }
    @Override public void onLoad() { unloaded = false; generation++; }
}
