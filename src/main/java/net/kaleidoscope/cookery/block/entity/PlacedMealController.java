package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.PlacedMealBehavior;
import net.kaleidoscope.cookery.catalog.PlacedMealDefinition;
import net.kaleidoscope.cookery.catalog.PlacedMealLayout;
import net.kaleidoscope.cookery.catalog.PlacedMealState;
import net.kaleidoscope.cookery.catalog.QualityProfile;
import net.kaleidoscope.cookery.item.CookeryFoodItems;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.kaleidoscope.cookery.util.BlockStates;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.util.DropUtils;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.PlacementGuard;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.IntTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import net.momirealms.craftengine.libraries.nbt.StringTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/** All cells carry one generation ID; only its anchor owns consumption and drops. */
public final class PlacedMealController extends BlockEntityController {
    private static final String SAVE = "kaleidoscopecookery:placed_meal";
    private static final BooleanSupplier NORMAL_DROPS = () -> true;
    private final PlacedMealBehavior behavior;
    private PlacedMealState state;
    private Item whole;
    private List<Item> extras;
    private UUID group = UUID.randomUUID();
    private boolean removed;
    private boolean busy;
    private boolean assembled;
    private boolean loaded;
    private BooleanSupplier dropDecision = NORMAL_DROPS;
    private long dropDecisionTick = Long.MIN_VALUE;

    public PlacedMealController(BlockEntity entity, PlacedMealBehavior behavior) {
        super(entity);
        this.behavior = behavior;
        var definition = behavior.definition();
        state = new PlacedMealState(1, net.kaleidoscope.cookery.catalog.CookeryCatalog.SOURCE_COMMIT,
                definition.itemId(), definition.maxBites(), behavior.bites(entity.blockState), QualityProfile.BASE);
        whole = build(definition.itemId());
        extras = definition.drops().stream().map(this::build).toList();
        assembled = definition.layout() == PlacedMealLayout.SINGLE;
        net.kaleidoscope.cookery.item.behavior.PlacedMealItemBehavior.created(this);
    }

    private Item build(String id) {
        Item item = InventoryUtils.createOrEmpty(Key.of(id));
        if (item == null || item.isEmpty()) throw invalid("missing item " + id);
        return item.copyWithCount(1);
    }
    public PlacedMealState state() { return state; }
    public UUID group() { return group; }
    public PlacedMealBehavior behavior() { return behavior; }
    public List<BlockPos> footprint() { return positions(); }
    public void dropDecision(BooleanSupplier decision) {
        var root = isAnchor() ? this : at(anchor());
        if (root != null && root.group.equals(group)) {
            root.dropDecision = decision;
            root.dropDecisionTick = world().getGameTime();
            Bukkit.getRegionScheduler().runDelayed(KaleidoscopeCookeryPlugin.instance(), world(),
                    root.blockEntity.pos.x() >> 4, root.blockEntity.pos.z() >> 4, task -> {
                        if (root.dropDecision == decision) root.dropDecision = NORMAL_DROPS;
                    }, 1);
        }
    }
    private World world() { return (World) blockEntity.world.world().platformWorld(); }
    private BlockPos anchor() { return behavior.anchor(blockEntity.pos, blockEntity.blockState); }
    private boolean isAnchor() { return blockEntity.pos.equals(anchor()); }
    private boolean owns(BlockPos pos) { return Bukkit.isOwnedByCurrentRegion(world(), pos.x() >> 4, pos.z() >> 4); }
    private PlacedMealController at(BlockPos pos) {
        if (!owns(pos)) return null;
        BlockEntity entity = blockEntity.world.getBlockEntityAtIfLoaded(pos);
        return entity == null ? null : entity.controller.get(PlacedMealController.class, 0);
    }
    private List<BlockPos> positions() {
        return behavior.cells(anchor(), blockEntity.blockState).stream()
                .map(cell -> new BlockPos(cell.pos().x(), cell.pos().y(), cell.pos().z())).toList();
    }
    private List<PlacedMealController> members(boolean requireGroup) {
        List<PlacedMealController> members = new ArrayList<>();
        for (var cell : behavior.cells(anchor(), blockEntity.blockState)) {
            BlockPos pos = new BlockPos(cell.pos().x(), cell.pos().y(), cell.pos().z());
            PlacedMealController member = at(pos);
            if (member == null || member.removed || !member.behavior.id().equals(behavior.id())
                    || !member.anchor().equals(anchor()) || !member.behavior.part(member.blockEntity.blockState).equals(cell.part())
                    || member.behavior.facing(member.blockEntity.blockState) != behavior.facing(blockEntity.blockState)
                    || requireGroup && !member.group.equals(group)) return List.of();
            members.add(member);
        }
        return members;
    }
    public boolean complete() { return !removed && assembled && !members(true).isEmpty(); }

    /** Called after every placement cell exists, before normal interaction or item consumption. */
    public boolean assemble() {
        if (removed || !isAnchor()) return false;
        List<PlacedMealController> members = members(false);
        if (members.isEmpty() || members.stream().anyMatch(member -> member.loaded)) return false;
        for (var member : members) {
            member.group = group;
            member.state = state;
            member.assembled = true;
            member.markChanged();
        }
        return true;
    }
    @Override public void loadCustomDataFromItem(Item held) {
        if (!isAnchor() || held == null || held.isEmpty()) return;
        if (!held.id().asString().equals(behavior.definition().itemId())) throw invalid("placement item differs");
        var baked = CookeryFoodItems.read(ItemStackUtils.getBukkitStack(held));
        QualityProfile quality = baked == null || behavior.definition().layout() == PlacedMealLayout.NINE
                ? QualityProfile.BASE : baked.quality();
        PlacedMealState fresh = PlacedMealState.fresh(behavior.definition(), quality);
        Item recovered = CookeryFoodItems.bake(held.copyWithCount(1), quality);
        List<PlacedMealController> members = members(true);
        if (members.isEmpty()) throw invalid("incomplete placement");
        for (var member : members) {
            member.whole = recovered.copy();
            member.state = fresh;
            member.markChanged();
        }
    }

    @FunctionalInterface public interface Consumer {
        boolean consume(PlacedMealDefinition.BiteFood food, BooleanSupplier commit);
    }
    public boolean consume(Consumer consumer) {
        if (busy || PlacementGuard.isPlacing(blockEntity)
                || net.kaleidoscope.cookery.item.behavior.PlacedMealItemBehavior.placing(blockEntity)
                || !isAnchor() || !complete() || state.empty()) return false;
        busy = true;
        boolean[] active = {true};
        try {
            PlacedMealState expected = state;
            return consumer.consume(behavior.definition().bite(expected.quality()), () -> {
                if (!active[0] || !busy || removed || state != expected || !complete()) return false;
                PlacedMealState next = expected.consumeOne();
                List<PlacedMealController> members = members(true);
                for (var member : members) { member.state = next; member.markChanged(); }
                for (var member : members) {
                    BlockStates.sync(member.blockEntity, behavior.state(member.blockEntity.blockState,
                            member.behavior.part(member.blockEntity.blockState), next.consumed()));
                }
                return true;
            });
        } finally { active[0] = false; busy = false; }
    }
    public boolean removeMeal() {
        if (busy || PlacementGuard.isPlacing(blockEntity)
                || net.kaleidoscope.cookery.item.behavior.PlacedMealItemBehavior.placing(blockEntity)
                || !isAnchor() || !complete()) return false;
        return CraftEngineBlocks.remove(world().getBlockAt(blockEntity.pos.x(), blockEntity.pos.y(), blockEntity.pos.z()),
                null, false, true, true);
    }

    @Override public void onRemove() {
        if (removed) return;
        if (PlacementGuard.isPlacing(blockEntity)
                || net.kaleidoscope.cookery.item.behavior.PlacedMealItemBehavior.placing(blockEntity)) {
            removed = true;
            super.onRemove();
            return;
        }
        if (!isAnchor()) {
            removed = true;
            BlockPos anchor = anchor();
            UUID expectedGroup = group;
            Runnable removeAnchor = () -> {
                var root = at(anchor);
                if (root != null && root.group.equals(expectedGroup)) root.destroyGroup();
            };
            if (owns(anchor)) removeAnchor.run();
            else Bukkit.getRegionScheduler().execute(KaleidoscopeCookeryPlugin.instance(), world(), anchor.x() >> 4, anchor.z() >> 4, removeAnchor);
            super.onRemove();
            return;
        }
        destroyGroup();
        super.onRemove();
    }
    private void destroyGroup() {
        if (removed) return;
        removed = true;
        boolean dropItems = dropDecisionTick != world().getGameTime() || dropDecision.getAsBoolean();
        List<Item> drops = !dropItems ? List.of()
                : state.untouched() ? List.of(whole.copy()) : extras.stream().map(Item::copy).toList();
        UUID expectedGroup = group;
        for (BlockPos pos : positions()) {
            Runnable clear = () -> {
                var member = at(pos);
                if (member == null || !member.group.equals(expectedGroup)) return;
                member.removed = true;
                CraftEngineBlocks.remove(world().getBlockAt(pos.x(), pos.y(), pos.z()), null, false, false, false);
            };
            if (owns(pos)) clear.run();
            else Bukkit.getRegionScheduler().execute(KaleidoscopeCookeryPlugin.instance(), world(), pos.x() >> 4, pos.z() >> 4, clear);
        }
        for (Item drop : drops) DropUtils.dropOnRemove(blockEntity, drop);
    }
    private void markChanged() { if (blockEntity.world != null) blockEntity.world.blockEntityChanged(blockEntity.pos); }

    @Override public void saveCustomData(CompoundTag root) {
        CompoundTag saved = BlockEntityNbt.newData();
        saved.putInt("schema", 1);
        saved.putString("state", state.encode());
        saved.putString("group", group.toString());
        saved.put("whole", encoded(whole));
        ListTag drops = new ListTag();
        for (Item item : extras) { CompoundTag entry = new CompoundTag(); entry.put("item", encoded(item)); drops.add(entry); }
        saved.put("extras", drops);
        CookeryBlockStateEnvelope.put(root, SAVE, saved);
    }
    private Tag encoded(Item item) {
        Tag encoded = BlockEntityNbt.itemTag(item);
        if (encoded == null) throw invalid("cannot encode " + item.id());
        return encoded;
    }
    @Override public void loadCustomData(CompoundTag root) {
        if (!root.containsKey(CookeryBlockStateEnvelope.sidecar(SAVE)) && root.containsKey(SAVE)
                && !(root.get(SAVE) instanceof CompoundTag)) throw invalid("invalid payload");
        CompoundTag saved = CookeryBlockStateEnvelope.payload(root, SAVE);
        if (saved == null) return;
        if (!(saved.get("schema") instanceof IntTag) || saved.getInt("schema") != 1
                || !(saved.get("data_version") instanceof IntTag) || !(saved.get("state") instanceof StringTag)
                || !(saved.get("group") instanceof StringTag) || !(saved.get("extras") instanceof ListTag)) throw invalid("invalid fields");
        var next = PlacedMealState.decode(saved.getString("state"), behavior.definition());
        if (next.consumed() != behavior.bites(blockEntity.blockState)) throw invalid("state and saved bites differ");
        UUID nextGroup = UUID.fromString(saved.getString("group"));
        int version = BlockEntityNbt.dataVersion(saved);
        Item food = readItem(saved, "whole", version);
        if (!food.id().asString().equals(behavior.definition().itemId())) throw invalid("wrong whole item");
        ListTag rawDrops = saved.getList("extras");
        if (rawDrops.size() > 64) throw invalid("too many drops");
        List<Item> drops = new ArrayList<>();
        for (Tag entry : rawDrops) {
            if (!(entry instanceof CompoundTag tag)) throw invalid("invalid drop entry");
            drops.add(readItem(tag, "item", version));
        }
        Item copy = food.copy();
        List<Item> dropCopies = drops.stream().map(Item::copy).toList();
        state = next; group = nextGroup; whole = copy; extras = dropCopies; loaded = true; assembled = true;
    }
    private Item readItem(CompoundTag saved, String key, int version) {
        if (!saved.containsKey(key)) throw invalid("missing " + key);
        Item item = BlockEntityNbt.getItem(saved, key, version);
        if (item == null || item.isEmpty() || item.count() != 1) throw invalid("invalid " + key);
        return item;
    }
    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Placed meal " + message + " at " + blockEntity.pos); }

    @Override public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(CEWorld world, ImmutableBlockState state) {
        if (behavior.definition().animation() == null || !isAnchor()) return null;
        return createTickerHelper((w, pos, current, controller) -> animate());
    }
    private void animate() {
        if (removed || state.empty() || Math.floorMod(world().getGameTime() + blockEntity.pos.asLong(), 20) != 0) return;
        var random = ThreadLocalRandom.current();
        String animation = behavior.definition().animation();
        int chance = animation.equals("SUSPICIOUS_STIR_FRY_ANIMATE_TICK") ? 3 : 2;
        if (random.nextInt(chance) != 0) return;
        double x = blockEntity.pos.x() + .5 + random.nextDouble(-.2, .2);
        double y = blockEntity.pos.y() + .5 + random.nextDouble(.25);
        double z = blockEntity.pos.z() + .5 + random.nextDouble(-.2, .2);
        if (animation.equals("SUSPICIOUS_STIR_FRY_ANIMATE_TICK")) world().spawnParticle(Particle.ENTITY_EFFECT, x, y, z, 1, Color.fromRGB(104, 54, 128));
        else world().spawnParticle(animation.equals("DARK_CUISINE_ANIMATE_TICK") ? Particle.SMOKE : Particle.CLOUD,
                x, y, z, 0, 0, .03, 0, 1);
    }
}
