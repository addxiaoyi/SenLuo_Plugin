package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.EightImmortalsTableBehavior;
import net.kaleidoscope.cookery.item.behavior.EightImmortalsTableItemBehavior;
import net.kaleidoscope.cookery.util.FoliaUtil;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.world.*;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.Bukkit;
import java.util.UUID;

/** Generation identities protect replacements during rollback and deferred part removal. */
public final class EightImmortalsTableController extends BlockEntityController {
    private static final String SAVE = "kaleidoscopecookery:eight_immortals_table";
    private final EightImmortalsTableBehavior behavior;
    private UUID group = UUID.randomUUID();
    private boolean removed, assembled, loaded;
    private Tag rejected;
    public EightImmortalsTableController(BlockEntity entity, EightImmortalsTableBehavior behavior) {
        super(entity); this.behavior = behavior;
        EightImmortalsTableItemBehavior.created(this);
    }
    public EightImmortalsTableBehavior behavior() { return behavior; }
    public UUID group() { return group; }
    public boolean removed() { return removed; }
    public void assemble(UUID group) {
        if (removed || loaded || assembled || rejected != null) throw new IllegalStateException("Table part is not fresh");
        this.group = group; assembled = true;
        if (blockEntity.world != null) blockEntity.world.blockEntityChanged(blockEntity.pos);
    }
    private org.bukkit.World world() { return (org.bukkit.World) blockEntity.world.world().platformWorld(); }
    private boolean accessible(BlockPos pos) {
        return blockEntity.world != null && accessible(world(), pos);
    }
    private static boolean accessible(org.bukkit.World world, BlockPos pos) {
        return Bukkit.isOwnedByCurrentRegion(world, pos.x() >> 4, pos.z() >> 4)
                && world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }
    public EightImmortalsTableController at(BlockPos pos) {
        if (!accessible(pos)) return null;
        var entity = blockEntity.world.getBlockEntityAtIfLoaded(pos);
        return entity == null ? null : entity.controller.get(EightImmortalsTableController.class, 0);
    }
    public boolean matches(EightImmortalsTableController member, String part) {
        return member != null && !member.removed && group.equals(member.group)
                && member.behavior.block().id().equals(behavior.block().id())
                && member.behavior.facing(member.blockEntity.blockState) == behavior.facing(blockEntity.blockState)
                && member.behavior.part(member.blockEntity.blockState).equals(part);
    }
    @Override public void onRemove() {
        if (removed) return;
        removed = true;
        if (!assembled || rejected != null || blockEntity.world == null || EightImmortalsTableItemBehavior.placing(blockEntity)) return;
        CEWorld storage = blockEntity.world;
        org.bukkit.World platform = world();
        var expectedFacing = behavior.facing(blockEntity.blockState);
        var expectedType = behavior.block().id();
        UUID expectedGroup = group;
        BlockPos anchor = behavior.anchor(blockEntity.pos, blockEntity.blockState);
        for (String part : EightImmortalsTableBehavior.PARTS) {
            BlockPos pos = EightImmortalsTableBehavior.cell(anchor, expectedFacing, part);
            if (pos.equals(blockEntity.pos)) continue;
            Runnable clear = () -> {
                // The removed entity can be detached before this runs in another region.
                if (!Bukkit.isOwnedByCurrentRegion(platform, pos.x() >> 4, pos.z() >> 4)
                        || !platform.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return;
                var entity = storage.getBlockEntityAtIfLoaded(pos);
                var member = entity == null ? null : entity.controller.get(EightImmortalsTableController.class, 0);
                if (member != null && !member.removed && expectedGroup.equals(member.group)
                        && member.behavior.block().id().equals(expectedType)
                        && member.behavior.facing(entity.blockState) == expectedFacing
                        && member.behavior.part(entity.blockState).equals(part)) member.removeWithoutDrop();
            };
            if (accessible(platform, pos)) clear.run();
            else FoliaUtil.run(clear, storage.world(), pos.x() >> 4, pos.z() >> 4);
        }
    }
    public void removeWithoutDrop() {
        if (removed || rejected != null || !accessible(blockEntity.pos) || at(blockEntity.pos) != this) return;
        // The removed flag also suppresses this member's recursive group cleanup.
        removed = true;
        CEWorld storage = blockEntity.world;
        org.bukkit.World platform = world();
        boolean success = false;
        try {
            var block = platform.getBlockAt(blockEntity.pos.x(), blockEntity.pos.y(), blockEntity.pos.z());
            CraftEngineBlocks.remove(block, false);
            // Native removal can detach this entity before returning to the controller.
            if (blockEntity.world != storage) success = true;
            else if (accessible(platform, blockEntity.pos)) {
                var current = storage.getBlockEntityAtIfLoaded(blockEntity.pos);
                success = current == null || current.controller.get(EightImmortalsTableController.class, 0) != this;
            }
        } finally { if (!success) removed = false; }
    }
    /** Reconcile partial groups after a neighbor was unloaded during removal. No drops here. */
    void reconcile() {
        if (!assembled || removed || rejected != null || blockEntity.world == null || EightImmortalsTableItemBehavior.placing(blockEntity)) return;
        BlockPos anchor = behavior.anchor(blockEntity.pos, blockEntity.blockState);
        boolean complete = true;
        for (String part : EightImmortalsTableBehavior.PARTS) {
            BlockPos pos = EightImmortalsTableBehavior.cell(anchor, behavior.facing(blockEntity.blockState), part);
            if (!accessible(pos)) return;
            if (!matches(at(pos), part)) complete = false;
        }
        if (!complete) removeWithoutDrop();
    }
    @Override public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(CEWorld world, ImmutableBlockState state) {
        return createTickerHelper((w, pos, current, controller) -> {
            var active = w.getBlockEntityAtIfLoaded(pos);
            if (active != null && Math.floorMod(w.world().time() + pos.asLong(), 20) == 0) {
                var table = active.controller.get(EightImmortalsTableController.class, 0);
                if (table != null) table.reconcile();
            }
        });
    }
    @Override public void saveCustomData(CompoundTag tag) {
        if (rejected != null) { tag.put(SAVE, rejected.deepClone()); return; }
        if (!assembled) return;
        var data = new CompoundTag(); data.putInt("schema", 1); data.putString("group", group.toString()); tag.put(SAVE, data);
    }
    @Override public void loadCustomData(CompoundTag tag) {
        if (!tag.containsKey(SAVE)) return;
        try {
            if (!(tag.get(SAVE) instanceof CompoundTag data) || data.getInt("schema", -1) != 1)
                throw new IllegalArgumentException("Invalid eight immortals table data");
            UUID next = UUID.fromString(data.getString("group"));
            group = next; assembled = true; loaded = true; rejected = null;
        } catch (RuntimeException exception) {
            rejected = tag.get(SAVE).deepClone(); throw exception;
        }
    }
}
