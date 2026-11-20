package net.kaleidoscope.cookery.block.behavior;

import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.kaleidoscope.cookery.util.DropUtils;
import net.kaleidoscope.cookery.util.EventUtils;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.ItemMatcher;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.AbstractCanSurviveBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.BlockShape;
import net.momirealms.craftengine.core.block.DelegatingBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.event.CraftEventFactoryProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerLevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockAndLightGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelAccessorProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelWriterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.ScheduledTickAccessProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.AABBProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.shapes.ShapesProxy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Farmland;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Tomato/chili/lettuce: real CE states, inherited crop growth, and distinct source harvest paths. */
public final class SourceCropBehavior extends AbstractCanSurviveBlockBehavior
        implements RandomTickBlock, BonemealableBlock, HarvestableCrop {
    public static final BlockBehaviorFactory<SourceCropBehavior> FACTORY = new Factory();
    private static final Key CROP_BREAK = Key.of("minecraft:block.crop.break");
    private static final Key GREEN_CHILI = Key.of("kaleidoscopecookery:green_chili");
    private static final ItemMatcher SICKLES = ItemMatcher.of(List.of(
            "kaleidoscopecookery:sickle", "#kaleidoscopecookery:range_harvest_tool"));
    private static final Set<Block> ACTIVE = ConcurrentHashMap.newKeySet();

    enum Crop {
        TOMATO("tomato"), CHILI("red_chili"), LETTUCE("lettuce");
        final Key result;
        Crop(String result) { this.result = Key.of("kaleidoscopecookery:" + result); }
        Key pickResult(int roll) { return this == CHILI && roll == 0 ? GREEN_CHILI : result; }
        boolean canPick() { return this != LETTUCE; }
    }

    private final IntegerProperty age;
    private final Crop crop;

    private SourceCropBehavior(BlockDefinition block, IntegerProperty age, Crop crop) {
        // CE's delayed survival path destroys with loot instead of silently replacing with air.
        super(block, 1);
        this.age = age;
        this.crop = crop;
    }

    @Override public boolean canRandomlyTick(ImmutableBlockState state) {
        return state.get(age) < SourceCropGrowth.MAX_AGE;
    }

    @Override protected boolean canSurvive(Object thisBlock, Object state, Object level, Object rawPos) {
        if (!ServerLevelProxy.CLASS.isInstance(level)) return true;
        org.bukkit.World world = LevelProxy.INSTANCE.getWorld(level);
        BlockPos pos = LocationUtils.fromBlockPos(rawPos);
        // A boundary that this region does not own is unknown, never an invitation to load it.
        if (!owned(world, pos.x(), pos.z())) return true;
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        return pos.y() > world.getMinHeight()
                && world.getBlockAt(pos.x(), pos.y() - 1, pos.z()).getType() == Material.FARMLAND
                && (BlockAndLightGetterProxy.INSTANCE.getRawBrightness(level, rawPos, 0) >= SourceCropGrowth.SURVIVAL_LIGHT
                    || block.getLightFromSky() == 15);
    }

    @Override public void onPlace(Object thisBlock, Object[] args) {
        if (!queueSupport(args[1], args[2], 2)) super.onPlace(thisBlock, args);
    }

    @Override public Object updateShape(Object thisBlock, Object[] args) {
        Object level = args[updateShape$level];
        Object pos = args[updateShape$blockPos];
        if (!queueSupport(level, pos, delay)) {
            if (VersionHelper.isOrAbove1_21_2) {
                ScheduledTickAccessProxy.INSTANCE.scheduleTick$0(level, pos, thisBlock, delay);
            } else {
                LevelAccessorProxy.INSTANCE.scheduleTick$0(level, pos, thisBlock, delay);
            }
        }
        return args[0];
    }

    private boolean queueSupport(Object level, Object pos, int ticks) {
        if (!ServerLevelProxy.CLASS.isInstance(level)) return false;
        var plugin = KaleidoscopeCookeryPlugin.instance();
        var checks = plugin != null && plugin.isEnabled() ? plugin.sourceCropSupportChecks() : null;
        return checks != null && checks.schedule(LevelProxy.INSTANCE.getWorld(level), LocationUtils.fromBlockPos(pos),
                blockDefinition.id(), ticks);
    }

    @Override public void tick(Object thisBlock, Object[] args) {
        // Native fallback for older scheduled ticks and for checks that did not fit in the queue.
        Object level = args[1], pos = args[2];
        if (!ServerLevelProxy.CLASS.isInstance(level)) return;
        org.bukkit.World world = LevelProxy.INSTANCE.getWorld(level);
        BlockPos blockPos = LocationUtils.fromBlockPos(pos);
        if (!owned(world, blockPos.x(), blockPos.z())) return;
        try (var guard = KaleidoscopeCookeryPlugin.tryEnterSourceCropResources()) {
            if (guard != null) {
                recheckSupport(level, pos, blockDefinition.id());
            } else if (!queueSupport(level, pos, delay)) {
                var plugin = KaleidoscopeCookeryPlugin.instance();
                if (plugin != null && plugin.isEnabled()) {
                    LevelAccessorProxy.INSTANCE.scheduleTick$0(level, pos, thisBlock, delay);
                }
            }
        }
    }

    // Invoked by the owner tick with an active resource guard. Re-resolve the block
    // after a reload; neither the earlier native state nor its behavior can be reused.
    static void recheckSupport(org.bukkit.World world, BlockPos pos, Key definitionId) {
        if (!owned(world, pos.x(), pos.z())) return;
        recheckSupport(BukkitAdaptor.adapt(world).minecraftWorld(), LocationUtils.toBlockPos(pos), definitionId);
    }

    private static void recheckSupport(Object level, Object pos, Key definitionId) {
        Object raw = BlockGetterProxy.INSTANCE.getBlockStateIfLoaded(level, pos);
        if (raw == null) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(raw).orElse(null);
        SourceCropBehavior crop = supportBehavior(state, definitionId);
        if (crop != null) crop.nativeSupportTick(level, pos, raw);
    }

    static SourceCropBehavior supportBehavior(ImmutableBlockState state, Key definitionId) {
        if (state == null || state.isEmpty()) return null;
        BlockDefinition definition = state.owner().value();
        if (definition == null || !definitionId.equals(definition.id())) return null;
        SourceCropBehavior crop = state.behavior().getFirst(SourceCropBehavior.class);
        return crop != null && crop.blockDefinition == definition ? crop : null;
    }

    private void nativeSupportTick(Object level, Object pos, Object raw) {
        super.tick(BlockStateUtils.getBlockOwner(raw), new Object[]{raw, level, pos});
    }

    @Override public void randomTick(Object thisBlock, Object[] args) {
        try (var guard = KaleidoscopeCookeryPlugin.tryEnterSourceCropResources()) {
            if (guard == null) return;
            ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
            if (state == null || state.owner().value() != blockDefinition || !canRandomlyTick(state)) return;
            randomTickInReadEpoch(state, args);
        }
    }

    private void randomTickInReadEpoch(ImmutableBlockState state, Object[] args) {
        Object level = args[1], rawPos = args[2];
        org.bukkit.World world = LevelProxy.INSTANCE.getWorld(level);
        BlockPos pos = LocationUtils.fromBlockPos(rawPos);
        if (!owned(world, pos.x(), pos.z())
                || BlockAndLightGetterProxy.INSTANCE.getRawBrightness(level, rawPos, 0) < SourceCropGrowth.GROW_LIGHT) return;
        Float speed = growthSpeed(world, pos);
        // Revisit on a later random tick if a neighbouring Folia region is unavailable.
        if (speed == null || ThreadLocalRandom.current().nextInt(SourceCropGrowth.randomTickBound(speed)) != 0) return;
        if (!current(world.getBlockAt(pos.x(), pos.y(), pos.z()), state)) return;
        grow(level, rawPos, state, state.get(age) + 1);
    }

    private Float growthSpeed(org.bukkit.World world, BlockPos pos) {
        if (pos.y() <= world.getMinHeight()) return null;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!owned(world, pos.x() + dx, pos.z() + dz)) return null;
        }
        float speed = 1;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var soil = world.getBlockAt(pos.x() + dx, pos.y() - 1, pos.z() + dz).getBlockData();
            speed += SourceCropGrowth.soilContribution(soil instanceof Farmland,
                    soil instanceof Farmland farm ? farm.getMoisture() : 0, dx == 0 && dz == 0);
        }
        boolean eastWest = sameCrop(world, pos, 1, 0) || sameCrop(world, pos, -1, 0);
        boolean northSouth = sameCrop(world, pos, 0, 1) || sameCrop(world, pos, 0, -1);
        boolean diagonal = sameCrop(world, pos, 1, 1) || sameCrop(world, pos, 1, -1)
                || sameCrop(world, pos, -1, 1) || sameCrop(world, pos, -1, -1);
        return SourceCropGrowth.growthSpeed(speed, eastWest, northSouth, diagonal);
    }

    private boolean sameCrop(org.bukkit.World world, BlockPos pos, int dx, int dz) {
        ImmutableBlockState other = CraftEngineBlocks.getCustomBlockState(
                world.getBlockAt(pos.x() + dx, pos.y(), pos.z() + dz));
        return other != null && other.owner().value() == blockDefinition;
    }

    @Override public boolean isValidBonemealTarget(Object thisBlock, Object[] args) {
        try (var guard = KaleidoscopeCookeryPlugin.tryEnterSourceCropResources()) {
            if (guard == null) return false;
            ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[2]).orElse(null);
            return supportBehavior(state, blockDefinition.id()) == this && canRandomlyTick(state);
        }
    }

    @Override public boolean isBonemealSuccess(Object thisBlock, Object[] args) {
        try (var guard = KaleidoscopeCookeryPlugin.tryEnterSourceCropResources()) {
            return guard != null;
        }
    }

    @Override public void performBonemeal(Object thisBlock, Object[] args) {
        try (var guard = KaleidoscopeCookeryPlugin.tryEnterSourceCropResources()) {
            if (guard == null) return;
            Object level = args[0], rawPos = args[2];
            if (!ServerLevelProxy.CLASS.isInstance(level)) return;
            org.bukkit.World world = LevelProxy.INSTANCE.getWorld(level);
            BlockPos pos = LocationUtils.fromBlockPos(rawPos);
            if (!owned(world, pos.x(), pos.z())) return;
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (supportBehavior(state, blockDefinition.id()) != this || !canRandomlyTick(state)) return;
            int nextAge = SourceCropGrowth.bonemealAge(state.get(age), ThreadLocalRandom.current().nextInt(2, 6));
            if (grow(level, rawPos, state, nextAge)) {
                // Tripwire-backed clients do not emit native crop bonemeal particles themselves.
                Particles.emit(world, Particle.HAPPY_VILLAGER, pos.x() + .5, pos.y() + .5, pos.z() + .5,
                        15, .25, .25, .25, 0, null);
            }
        }
    }

    private boolean grow(Object level, Object pos, ImmutableBlockState state, int nextAge) {
        Object grown = state.with(age, nextAge).customBlockState().minecraftState();
        return VersionHelper.isOrAbove1_21_5
                ? CraftEventFactoryProxy.INSTANCE.handleBlockGrowEvent(level, pos, grown, UpdateFlags.UPDATE_CLIENTS)
                : CraftEventFactoryProxy.INSTANCE.handleBlockGrowEvent(level, pos, grown);
    }

    @Override public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (!crop.canPick()) return InteractionResult.PASS;
        Player player = context.getPlayer();
        if (player == null || player.isAdventureMode()
                || SICKLES.matches(context.getItem())) return InteractionResult.PASS;
        if (!harvest(context.getLevel(), context.getClickedPos(), state, player, true, context.getHand())) return InteractionResult.PASS;
        player.swingHand(context.getHand());
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    /** Sickle path: source block loot with an empty tool, reset to age zero. */
    @Override public boolean harvest(World world, BlockPos pos, ImmutableBlockState state, Player player) {
        return harvest(world, pos, state, player, InteractionHand.MAIN_HAND);
    }

    @Override public boolean harvest(World world, BlockPos pos, ImmutableBlockState state, Player player, InteractionHand hand) {
        return harvest(world, pos, state, player, false, hand);
    }

    private boolean harvest(World world, BlockPos pos, ImmutableBlockState state, Player player, boolean picking, InteractionHand hand) {
        // The caller supplies state; any lookup performed before this callback remains outside the gate.
        try (var guard = KaleidoscopeCookeryPlugin.tryEnterSourceCropResources()) {
            if (guard == null) return false;
            return harvestInReadEpoch(world, pos, state, player, picking, hand);
        }
    }

    private boolean harvestInReadEpoch(World world, BlockPos pos, ImmutableBlockState state, Player player, boolean picking, InteractionHand hand) {
        if (state == null || state.owner().value() != blockDefinition || state.get(age) != SourceCropGrowth.MAX_AGE
                || !(world.platformWorld() instanceof org.bukkit.World bukkit) || !owned(bukkit, pos.x(), pos.z())) return false;
        Block block = bukkit.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!ACTIVE.add(block)) return false;
        try {
            if (!ready(pos, block, state, player, picking, hand)) return false;
            SourceCropHarvestSource source = SourceCropHarvestSource.capture(player, hand);
            if (source == null || !eligible(world, pos, block, state, player, picking, source)) return false;
            WorldPosition position = new WorldPosition(world, Vec3d.atCenterOf(pos));
            boolean drops = !Boolean.FALSE.equals(bukkit.getGameRuleValue(GameRule.DO_TILE_DROPS));
            List<Item> output;
            if (!drops) output = List.of();
            else if (picking) {
                Item fruit = InventoryUtils.createOrEmpty(crop.pickResult(ThreadLocalRandom.current().nextInt(5)), player);
                if (ItemUtils.isEmpty(fruit)) return false;
                output = List.of(fruit.copyWithCount(1));
            } else {
                output = state.getDrops(sickleLootContext(position, state, player), world, player);
            }
            if (!eligible(world, pos, block, state, player, picking, source)) return false;
            if (player.platformPlayer() instanceof org.bukkit.entity.Player bukkitPlayer) EventUtils.logBlockBreak(block, bukkitPlayer);
            if (!ready(pos, block, state, player, picking, hand) || !source.matches(player)) return false;
            ImmutableBlockState next = state.with(age, picking ? SourceCropGrowth.PICK_RESET_AGE : 0);
            if (!LevelWriterProxy.INSTANCE.setBlock(world.minecraftWorld(), LocationUtils.toBlockPos(pos),
                    next.customBlockState().minecraftState(), picking ? UpdateFlags.UPDATE_CLIENTS : UpdateFlags.UPDATE_ALL)
                    || !current(block, next)) return false;
            // Source is checked before this commit. Once committed, finish its output even if
            // a native neighbour callback changes the player's hand; no inventory is debited.
            DropUtils.dropAll(world, position, output);
            if (picking) world.playBlockSound(position, CROP_BREAK, 1f, 1f);
            return true;
        } finally { ACTIVE.remove(block); }
    }

    static ContextHolder sickleLootContext(WorldPosition position, ImmutableBlockState state, Player player) {
        // Deliberately omit ITEM_IN_HAND. Source SickleItem calls playerDestroy with ItemStack.EMPTY.
        return ContextHolder.builder().withParameter(DirectContextParameters.POSITION, position)
                .withParameter(DirectContextParameters.CUSTOM_BLOCK_STATE, state)
                .withOptionalParameter(DirectContextParameters.PLAYER, player).build();
    }

    private boolean eligible(World world, BlockPos pos, Block block, ImmutableBlockState state, Player player,
                             boolean picking, SourceCropHarvestSource source) {
        return ready(pos, block, state, player, picking, source.hand()) && source.matches(player)
                && InteractGuard.canBreak(player, world, pos.x(), pos.y(), pos.z())
                && ready(pos, block, state, player, picking, source.hand()) && source.matches(player);
    }

    private boolean ready(BlockPos pos, Block block, ImmutableBlockState state, Player player, boolean picking, InteractionHand hand) {
        if (hand == null || player == null || player.isAdventureMode() || !(player.platformPlayer() instanceof org.bukkit.entity.Player bukkit)
                || !bukkit.isOnline() || !bukkit.isValid() || bukkit.isDead()
                || bukkit.getGameMode() == GameMode.SPECTATOR || bukkit.getWorld() != block.getWorld()
                || !Bukkit.isOwnedByCurrentRegion(bukkit) || !owned(block.getWorld(), pos.x(), pos.z())
                || (picking && SICKLES.matches(player.getItemInHand(hand))) || !current(block, state)) return false;
        return true;
    }

    private static boolean current(Block block, ImmutableBlockState state) { return CraftEngineBlocks.getCustomBlockState(block) == state; }

    static boolean owned(org.bukkit.World world, int x, int z) {
        return world != null && Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4) && world.isChunkLoaded(x >> 4, z >> 4);
    }

    /** CE applies its visual-state shapes after creating behaviors, so install after each pack reload. */
    public static void installShapes(Iterable<BlockDefinition> definitions) {
        for (BlockDefinition definition : definitions) {
            SourceCropBehavior behavior = definition.defaultState().behavior().getFirst(SourceCropBehavior.class);
            if (behavior == null) throw new IllegalStateException("Missing source crop behavior for " + definition.id());
            BlockShape shape = new CropShape(behavior.age);
            for (ImmutableBlockState state : definition.variantProvider().states()) {
                Object owner = BlockStateUtils.getBlockOwner(state.customBlockState().minecraftState());
                if (!(owner instanceof DelegatingBlock block)) throw new IllegalStateException("Source crop is not a CE delegating block");
                block.shapeDelegate().bindValue(shape);
            }
        }
    }

    private static final class CropShape implements BlockShape {
        private final IntegerProperty age;
        private final Object[] shapes = new Object[8];
        private final Object empty = ShapesProxy.INSTANCE.create(AABBProxy.INSTANCE.newInstance(0, 0, 0, 0, 0, 0));
        CropShape(IntegerProperty age) {
            this.age = age;
            for (int i = 0; i < shapes.length; i++) shapes[i] = ShapesProxy.INSTANCE.create(
                    AABBProxy.INSTANCE.newInstance(0, 0, 0, 1, SourceCropGrowth.shapeHeight(i), 1));
        }
        @Override public Object getShape(Object block, Object[] args) {
            return BlockStateUtils.getOptionalCustomBlockState(args[0]).map(s -> shapes[s.get(age)]).orElse(shapes[0]);
        }
        @Override public Object getCollisionShape(Object block, Object[] args) { return empty; }
        @Override public Object getSupportShape(Object block, Object[] args) { return empty; }
    }

    private static final class Factory implements BlockBehaviorFactory<SourceCropBehavior> {
        @Override public SourceCropBehavior create(BlockDefinition block, ConfigSection section) {
            IntegerProperty age = (IntegerProperty) BlockBehaviorFactory.getProperty(section.path(), block, "age", Integer.class);
            if (age.min != 0 || age.max != SourceCropGrowth.MAX_AGE) throw new IllegalArgumentException("source_crop requires age 0..7");
            return new SourceCropBehavior(block, age, Crop.valueOf(section.getNonEmptyString("crop").toUpperCase(Locale.ROOT)));
        }
    }
}
