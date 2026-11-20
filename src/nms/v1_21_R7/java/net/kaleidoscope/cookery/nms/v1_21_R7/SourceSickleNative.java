package net.kaleidoscope.cookery.nms.v1_21_R7;

import net.kaleidoscope.cookery.nms.NativeSickleAccess;
import net.kaleidoscope.cookery.nms.NativeSickleCropTransaction;
import net.kaleidoscope.cookery.nms.NativeSickleDamageObserver;
import net.kaleidoscope.cookery.nms.NativeMiningDamageTracker;
import net.kaleidoscope.cookery.nms.NativeToolWear;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_21_R7.block.CraftBlock;
import org.bukkit.craftbukkit.v1_21_R7.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_21_R7.inventory.CraftItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** 1.20.1 BushBlock corresponds to 1.21.11 VegetationBlock, not its new concrete BushBlock. */
public final class SourceSickleNative implements NativeSickleAccess {
    private static final Class<?>[] DESTROY_PARAMETERS = {Level.class, net.minecraft.world.entity.player.Player.class,
            BlockPos.class, BlockState.class, BlockEntity.class, ItemStack.class, boolean.class, boolean.class};
    private static final MethodHandle STATS_ONLY = statsOnlyHandle();
    private static final MethodHandle RAW_COPY = rawCopyHandle();
    private static final MethodHandle MIRROR_HANDLE = mirrorHandle();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Set<Block> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> VERIFIED_CROPS = ConcurrentHashMap.newKeySet();

    private record State(ServerLevel level, BlockPos pos, BlockState state, Kind kind) implements Snapshot {}
    private record ToolSource(ItemStack identity, ItemStack before, int slot, int baseWear) {}

    private static MethodHandle rawCopyHandle() {
        try { return MethodHandles.publicLookup().findVirtual(ItemStack.class, "copy", MethodType.methodType(ItemStack.class, boolean.class)); }
        catch (NoSuchMethodException | IllegalAccessException failure) { throw new IllegalStateException("Missing verified raw native stack copy", failure); }
    }

    private static MethodHandle mirrorHandle() {
        try { return MethodHandles.publicLookup().findGetter(CraftItemStack.class, "handle", ItemStack.class); }
        catch (NoSuchFieldException | IllegalAccessException failure) { throw new IllegalStateException("Missing verified CraftItemStack mirror handle", failure); }
    }

    private static ItemStack mirror(org.bukkit.inventory.ItemStack item) {
        if (!(item instanceof CraftItemStack craft)) return null;
        try { return (ItemStack) MIRROR_HANDLE.invokeExact(craft); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot identify native item mirror", failure); }
    }

    private static ItemStack rawCopy(ItemStack item) {
        try { return (ItemStack) RAW_COPY.invokeExact(item, true); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Cannot preserve broken native stack components", failure); }
    }

    private static boolean miningFrame() {
        return WALKER.walk(frames -> frames.anyMatch(frame -> frame.getDeclaringClass() == net.minecraft.world.item.Item.class
                && frame.getMethodName().equals("mineBlock")));
    }

    private static ToolSource captureTool(ServerPlayer actor, org.bukkit.entity.Player player, State state) {
        ItemStack held = actor.getMainHandItem();
        ItemStack before = held.copy();
        var tool = held.get(DataComponents.TOOL);
        int base = !actor.preventsBlockDrops() && tool != null
                && state.state().getDestroySpeed(state.level(), state.pos()) != 0 ? Math.max(0, tool.damagePerBlock()) : 0;
        return new ToolSource(held, before, player.getInventory().getHeldItemSlot(), base);
    }

    private static Outcome certifyTool(ServerPlayer actor, org.bukkit.entity.Player player, ToolSource source,
                                       NativeMiningDamageTracker.Decision decision, Outcome outcome) {
        if (!decision.valid()) return Outcome.partial(decision.reason());
        if (player.getInventory().getHeldItemSlot() != source.slot()) return Outcome.partial("native break changed selected slot");
        ItemStack current = actor.getMainHandItem();
        if (source.before().isEmpty()) {
            return current.isEmpty() && !decision.observedEvent() ? outcome : Outcome.partial("empty native main-hand source changed");
        }
        ItemStack afterRaw = rawCopy(source.identity());
        int count = afterRaw.getCount(); afterRaw.setCount(1);
        ItemStack expected = source.before().copyWithCount(1);
        int beforeDamage = expected.getDamageValue(), maxDamage = expected.getMaxDamage();
        int resolved = decision.resolvedWear();
        long sum = (long) beforeDamage + resolved;
        if (sum > Integer.MAX_VALUE || sum < Integer.MIN_VALUE) return Outcome.partial("event-adjusted native damage overflow is unsupported");
        int expectedDamage = resolved == 0 ? beforeDamage : Math.max(0, Math.min(maxDamage, (int) sum));
        boolean broke = decision.observedEvent() && resolved > 0 && expectedDamage >= maxDamage;
        int expectedCount = source.before().getCount() - (broke ? 1 : 0);
        if (broke && source.before().getCount() == 1 && !decision.observedBreak())
            return Outcome.partial("native single-tool break lacks its own real break event");
        if (!broke && decision.observedBreak()) return Outcome.partial("unexplained native tool break event");
        if (decision.observedEvent() && resolved != 0) expected.setDamageValue(expectedDamage);
        if (count != expectedCount || !ItemStack.isSameItemSameComponents(expected, afterRaw)
                || (expectedCount > 0 ? current != source.identity() : !current.isEmpty()))
            return Outcome.partial("native main-hand identity/count/non-damage components differ from the certified mining transition");
        if (!decision.observedEvent()) return outcome;
        return outcome.withWear(new NativeToolWear(source.identity(), BukkitItemManager.instance().wrap(source.before().copyWithCount(1)),
                source.slot(), source.before().getCount(), expectedCount, beforeDamage, resolved, expectedDamage, maxDamage, broke));
    }

    private static MethodHandle statsOnlyHandle() {
        // Paper and Folia retain a public overload whose first flag suppresses loot and
        // whose second suppresses XP. Its body still uses native statistics/exhaustion.
        try {
            return MethodHandles.publicLookup().findVirtual(net.minecraft.world.level.block.Block.class,
                    "playerDestroy", MethodType.methodType(void.class, DESTROY_PARAMETERS));
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException("Missing verified native playerDestroy statistics-only overload", failure);
        }
    }

    @Override public Snapshot inspect(Block block) {
        if (!owned(block)) throw new IllegalStateException("Native sickle snapshot requires an owned loaded chunk");
        if (!(block instanceof CraftBlock craft) || !(craft.getHandle() instanceof ServerLevel level))
            throw new IllegalArgumentException("Native sickle requires a server CraftBlock");
        BlockState state = craft.getNMS();
        var type = state.getBlock();
        Kind kind = type instanceof CropBlock crop ? crop.isMaxAge(state) ? Kind.MATURE_CROP : Kind.IMMATURE_CROP
                : type instanceof VegetationBlock ? Kind.VEGETATION : Kind.OTHER;
        return new State(level, craft.getPosition().immutable(), state, kind);
    }

    private static State requireState(Snapshot snapshot, Kind kind) {
        if (!(snapshot instanceof State state) || state.kind() != kind)
            throw new IllegalArgumentException("Wrong native sickle snapshot/category");
        return state;
    }

    private static boolean owned(Block block) {
        return block != null && Bukkit.isOwnedByCurrentRegion(block.getWorld(), block.getX() >> 4, block.getZ() >> 4)
                && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    @Override public boolean matches(Block block, Snapshot snapshot) {
        if (!(snapshot instanceof State state) || !owned(block) || !(block instanceof CraftBlock craft)) return false;
        return craft.getHandle() == state.level() && craft.getPosition().equals(state.pos())
                && craft.getNMS() == state.state();
    }

    @Override public boolean blacklisted(Snapshot snapshot) {
        if (!(snapshot instanceof State state)) throw new IllegalArgumentException("Wrong native sickle snapshot");
        return state.state().is(TagKey.create(Registries.BLOCK, Identifier.parse("kaleidoscope_cookery:sickle_harvest_blacklist")))
                || state.state().is(TagKey.create(Registries.BLOCK, Identifier.parse("kaleidoscopecookery:sickle_harvest_blacklist")));
    }

    private static ServerPlayer actor(org.bukkit.entity.Player player, State state) {
        if (!(player instanceof CraftPlayer craft)) throw new IllegalArgumentException("Native sickle requires a CraftPlayer");
        ServerPlayer result = craft.getHandle();
        if (result.level() != state.level() || !Bukkit.isOwnedByCurrentRegion(player)
                || !player.isOnline() || !player.isValid() || player.isDead()) return null;
        return result;
    }

    private static void verifyBaseStatistics(CropBlock crop) {
        Class<?> type = crop.getClass();
        if (VERIFIED_CROPS.contains(type)) return;
        try {
            if (type.getMethod("playerDestroy", DESTROY_PARAMETERS).getDeclaringClass()
                    != net.minecraft.world.level.block.Block.class)
                throw new IllegalArgumentException("Native crop overrides playerDestroy; a dedicated source adapter is required: " + type.getName());
        } catch (NoSuchMethodException failure) {
            throw new IllegalArgumentException("Unsupported native crop playerDestroy", failure);
        }
        VERIFIED_CROPS.add(type);
    }

    private static void statistics(CropBlock block, State state, ServerPlayer player) {
        try {
            STATS_ONLY.invokeExact((net.minecraft.world.level.block.Block) block, (Level) state.level(),
                    (net.minecraft.world.entity.player.Player) player, state.pos(), state.state(),
                    (BlockEntity) null, ItemStack.EMPTY, false, false);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Native sickle statistics invocation failed", failure); }
    }

    @Override public Outcome harvestCrop(Block block, org.bukkit.entity.Player player, Snapshot snapshot,
                                         BooleanSupplier sourceUnchanged) {
        State state = requireState(snapshot, Kind.MATURE_CROP);
        CropBlock crop = (CropBlock) state.state().getBlock();
        verifyBaseStatistics(crop);
        ServerPlayer actor = actor(player, state);
        if (actor == null || !matches(block, state) || !sourceUnchanged.getAsBoolean() || !ACTIVE.add(block)) return Outcome.rejected();
        try {
            if (!matches(block, state) || !sourceUnchanged.getAsBoolean()) return Outcome.rejected();
            return NativeSickleCropTransaction.commit(new NativeSickleCropTransaction.Operations<ItemStack>() {
                @Override public boolean originalBlockPresent() { return matches(block, state); }
                @Override public void statisticsAndExhaustion() { statistics(crop, state, actor); }
                @Override public List<ItemStack> calculateLootInOriginalWorld() {
                    return net.minecraft.world.level.block.Block.getDrops(state.state(), state.level(),
                            state.pos(), null, actor, ItemStack.EMPTY).stream().map(ItemStack::copy).toList();
                }
                @Override public boolean commitReset() {
                    BlockState reset = crop.getStateForAge(0);
                    if (reset.hasProperty(BlockStateProperties.WATERLOGGED) && state.state().hasProperty(BlockStateProperties.WATERLOGGED))
                        reset = reset.setValue(BlockStateProperties.WATERLOGGED, state.state().getValue(BlockStateProperties.WATERLOGGED));
                    return state.level().setBlock(state.pos(), reset, net.minecraft.world.level.block.Block.UPDATE_ALL)
                            && state.level().getBlockState(state.pos()) == reset;
                }
                @Override public void publish(ItemStack drop) {
                    net.minecraft.world.level.block.Block.popResource(state.level(), state.pos(), drop);
                }
                @Override public void afterBreak() {
                    state.state().spawnAfterBreak(state.level(), state.pos(), ItemStack.EMPTY, true);
                    state.level().levelEvent(2001, state.pos(), net.minecraft.world.level.block.Block.getId(state.state()));
                }
            }, sourceUnchanged);
        } finally { ACTIVE.remove(block); }
    }

    @Override public Outcome destroyVegetation(Block block, org.bukkit.entity.Player player, Snapshot snapshot,
                                               BooleanSupplier sourceUnchanged) {
        State state = requireState(snapshot, Kind.VEGETATION);
        ServerPlayer actor = actor(player, state);
        if (actor == null || !matches(block, state) || !sourceUnchanged.getAsBoolean() || !ACTIVE.add(block)) return Outcome.rejected();
        try {
            if (!matches(block, state) || !sourceUnchanged.getAsBoolean()) return Outcome.rejected();
            ToolSource tool = captureTool(actor, player, state);
            try (var damage = NativeSickleDamageObserver.begin(player, tool.baseWear(),
                    event -> mirror(event.getItem()) == tool.identity() && miningFrame(),
                    event -> mirror(event.getBrokenItem()) == tool.identity() && miningFrame())) {
                if (damage == null) return Outcome.rejected();
                // Do not duplicate BlockBreakEvent, impose EMPTY or reset a pitcher half.
                boolean destroyed = actor.gameMode.destroyBlock(state.pos());
                NativeMiningDamageTracker.Decision decision = damage.finish();
                if (!destroyed && decision.observedEvent()) return Outcome.partial("native break reported no commit after tool damage");
                return certifyTool(actor, player, tool, decision, destroyed ? Outcome.complete() : Outcome.rejected());
            } catch (LinkageError failure) {
                return Outcome.failed("ordinary native vegetation linkage failed after entry",
                        new IllegalStateException("Native vegetation linkage failure", failure));
            } catch (RuntimeException failure) {
                return Outcome.failed("ordinary native vegetation break failed after entry; side effects may be partial", failure);
            }
        } finally { ACTIVE.remove(block); }
    }
}
