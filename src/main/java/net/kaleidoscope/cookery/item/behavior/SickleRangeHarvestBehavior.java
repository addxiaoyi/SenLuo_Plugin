package net.kaleidoscope.cookery.item.behavior;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.kaleidoscope.cookery.api.event.SickleHarvestEvent;
import net.kaleidoscope.cookery.block.behavior.GenericAgeCrop;
import net.kaleidoscope.cookery.block.behavior.HarvestableCrop;
import net.kaleidoscope.cookery.item.CookeryEnchantments;
import net.kaleidoscope.cookery.nms.NativeSickleAccess;
import net.kaleidoscope.cookery.nms.NativeSickleAccessProvider;
import net.kaleidoscope.cookery.nms.NativeToolWear;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.kaleidoscope.cookery.util.BehaviorConfig;
import net.kaleidoscope.cookery.util.EventUtils;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Effect;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

// 镰刀范围收割
public final class SickleRangeHarvestBehavior extends ItemBehavior {
    public static final ItemBehaviorFactory<SickleRangeHarvestBehavior> FACTORY = new Factory();

    private static final int DEFAULT_HORIZONTAL_RADIUS = 2;
    private static final int MAX_HORIZONTAL_RADIUS = 8;
    private static final int DEFAULT_HEIGHT = 2;
    private static final int DEFAULT_COOLDOWN_TICKS = 10;
    private static final float SWEEP_VOLUME = 1.0f;
    private static final float SWEEP_PITCH = 1.0f;
    private static final String SWEEP_SOUND = "minecraft:entity.player.attack.sweep";
    private static final Set<Block> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Logger LOGGER = Logger.getLogger(SickleRangeHarvestBehavior.class.getName());
    private static final Set<Key> SOURCE_BLACKLIST = Set.of(Key.of("minecraft:melon_stem"), Key.of("minecraft:pumpkin_stem"),
            Key.of("minecraft:attached_melon_stem"), Key.of("minecraft:attached_pumpkin_stem"));

    enum VanillaPolicy { LEGACY_AGEABLE, SOURCE_CLASSIFIED }

    private final int horizontalRadius;
    private final int height;
    private final int cooldownTicks;
    private final boolean damageItem;
    private final int durabilityPerUse;
    private final Set<Key> crops;
    private final Set<Key> bushes;
    private final Set<Key> blacklist;
    private final VanillaPolicy vanillaPolicy;
    private final Set<Key> nativeAllowlist;
    private final NativeSickleAccess nativeAccess;

    private SickleRangeHarvestBehavior(int horizontalRadius, int height, int cooldownTicks, boolean damageItem,
                                       int durabilityPerUse, Set<Key> crops, Set<Key> bushes, Set<Key> blacklist,
                                       VanillaPolicy vanillaPolicy, Set<Key> nativeAllowlist, NativeSickleAccess nativeAccess) {
        this.horizontalRadius = horizontalRadius;
        this.height = height;
        this.cooldownTicks = cooldownTicks;
        this.damageItem = damageItem;
        this.durabilityPerUse = durabilityPerUse;
        this.crops = crops;
        this.bushes = bushes;
        this.blacklist = blacklist;
        this.vanillaPolicy = vanillaPolicy;
        this.nativeAllowlist = nativeAllowlist;
        this.nativeAccess = nativeAccess;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        // Keep both classification paths and the resulting tool wear in the same resource epoch.
        try (var guard = KaleidoscopeCookeryPlugin.tryEnterSourceCropResources()) {
            return guard == null ? InteractionResult.PASS : sweep(context);
        }
    }

    private InteractionResult sweep(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        org.bukkit.entity.Player bukkitPlayer = (org.bukkit.entity.Player) player.platformPlayer();
        Item held = context.getItem();
        InteractionHand hand = context.getHand();
        if (hand == null || ItemUtils.isEmpty(held) || held.count() <= 0) {
            return InteractionResult.PASS;
        }
        HarvestSource source = new HarvestSource(hand,
                hand == InteractionHand.MAIN_HAND ? bukkitPlayer.getInventory().getHeldItemSlot() : 40,
                held.copyWithCount(1), held.count());
        if (!source.matches(player, bukkitPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack heldStack = held instanceof BukkitItem bukkitItem ? bukkitItem.getBukkitItem() : null;
        if (heldStack != null && net.kaleidoscope.compat.Cooldowns.has(bukkitPlayer, heldStack)) {
            return InteractionResult.PASS;
        }

        Sweep sweep = new Sweep(context.getLevel(), (org.bukkit.World) context.getLevel().platformWorld(),
                player, bukkitPlayer, hand, heldStack, source, new LongOpenHashSet(), new NativeProblem());
        ItemStack cooldownStack = heldStack == null ? null : heldStack.clone();
        BlockPos origin = context.getClickedPos();
        int sweepLevel = CookeryEnchantments.sweepLevel(held);
        int radius = Math.min(MAX_HORIZONTAL_RADIUS, CookeryEnchantments.sweepRadius(this.horizontalRadius, sweepLevel));
        int durabilityCost = CookeryEnchantments.sweepDurability(this.durabilityPerUse, sweepLevel);
        int harvested = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                harvested += harvestColumn(sweep, origin.x() + x, origin.y(), origin.z() + z);
            }
        }

        if (harvested == 0) {
            return InteractionResult.PASS;
        }

        sweep.world.playSound(bukkitPlayer.getLocation(), SWEEP_SOUND,
                SoundCategory.PLAYERS, SWEEP_VOLUME, SWEEP_PITCH);
        player.swingHand(context.getHand());
        // 按次扣不按格扣 照格扣一挥就是几十点耐久
        if (this.damageItem && held != null && !source.exhausted && !player.canInstabuild()) {
            held.hurtAndBreak(durabilityCost, player, null);
        }
        if (this.cooldownTicks > 0 && cooldownStack != null) {
            net.kaleidoscope.compat.Cooldowns.set(bukkitPlayer, cooldownStack, this.cooldownTicks);
        }
        if (sweep.nativeProblem().outcome != null && sweep.nativeProblem().outcome.failure() != null)
            throw new IllegalStateException("Native source sickle partially committed; per-swing durability policy was applied",
                    sweep.nativeProblem().outcome.failure());
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    // 一次挥砍的上下文 逐格传六个参数会让每个方法签名都糊成一片
    // stalks 记已收过的根部 多段作物一刀会同时命中同一株的两段
    private record Sweep(World level, org.bukkit.World world, Player player,
                         org.bukkit.entity.Player bukkitPlayer, InteractionHand hand, ItemStack sickle,
                         HarvestSource source, LongSet stalks, NativeProblem nativeProblem) {
        boolean sourceMatches() { return source.matches(player, bukkitPlayer); }
    }

    private static final class NativeProblem { private NativeSickleAccess.Outcome outcome; }

    /** Lock the actual interaction hand before protection or takeover callbacks can replace it. */
    private static final class HarvestSource {
        private final InteractionHand hand;
        private final int slot;
        private Item unit;
        private int count;
        private boolean exhausted;
        private HarvestSource(InteractionHand hand, int slot, Item unit, int count) {
            this.hand = hand; this.slot = slot; this.unit = unit; this.count = count;
        }
        boolean matches(Player player, org.bukkit.entity.Player actor) {
            if (exhausted || player.platformPlayer() != actor || ItemUtils.isEmpty(unit)
                    || (hand == InteractionHand.MAIN_HAND && actor.getInventory().getHeldItemSlot() != slot)) return false;
            Item current = player.getItemInHand(hand);
            return !ItemUtils.isEmpty(current) && current.count() == count && current.isSimilar(unit);
        }

        boolean acceptNativeWear(NativeToolWear wear, Player player, org.bukkit.entity.Player actor) {
            if (hand != InteractionHand.MAIN_HAND) return matches(player, actor);
            if (exhausted || slot != wear.selectedSlot() || count != wear.beforeCount()
                    || !unit.isSimilar(wear.beforeUnit()) || unit.damage().orElse(0) != wear.beforeDamage()
                    || unit.maxDamage() != wear.maxDamage()) return false;
            Item current = player.getItemInHand(hand);
            if (wear.afterCount() == 0) {
                if (!wear.brokeOne() || !ItemUtils.isEmpty(current)) return false;
                exhausted = true; count = 0; return true;
            }
            Item expected = unit.copyWithCount(1).damage(wear.afterDamage());
            if (ItemUtils.isEmpty(current) || current.minecraftItem() != wear.nativeIdentity()
                    || current.count() != wear.afterCount() || !current.isSimilar(expected)) return false;
            unit = expected; count = wear.afterCount();
            // A broken stacked tool is unusual and still terminates this originating use.
            exhausted = wear.brokeOne();
            return true;
        }
    }

    // 收割一根竖列 返回这列收到的数量
    private int harvestColumn(Sweep sweep, int x, int baseY, int z) {
        // A larger sweep must not read another Folia region or synchronously load its chunks.
        if (sweep.nativeProblem().outcome != null || !sweep.sourceMatches() || !Bukkit.isOwnedByCurrentRegion(sweep.world, x >> 4, z >> 4)
                || !sweep.world.isChunkLoaded(x >> 4, z >> 4)) {
            return 0;
        }
        int firstY = Math.max(baseY, sweep.world.getMinHeight());
        int lastY = (int) Math.min((long) baseY + this.height, sweep.world.getMaxHeight());
        int harvested = 0;
        for (int y = firstY; y < lastY; y++) {
            if (sweep.nativeProblem().outcome != null || !sweep.sourceMatches()) return harvested;
            Block block = sweep.world.getBlockAt(x, y, z);
            // 空气格先筛掉 领地查询比读方块贵得多 一刀几十格全查会把它变成热点
            if (block.getType().isAir()) {
                continue;
            }
            if (!ACTIVE.add(block)) continue;
            try {
                BlockData original = vanillaPolicy == VanillaPolicy.SOURCE_CLASSIFIED ? block.getBlockData() : null;
                // Capture before protection callbacks and reject their replacement native source.
                if (!InteractGuard.canBreak(sweep.player, sweep.level, x, y, z) || !sweep.sourceMatches()
                        || (original != null && !original.equals(block.getBlockData()))) continue;
                if (harvestOne(sweep, block)) harvested++;
            } finally { ACTIVE.remove(block); }
        }
        return harvested;
    }

    private boolean harvestOne(Sweep sweep, Block block) {
        // CE 自定义方块的 getType 返回的是视觉方块 绊线甘蔗之类 拿它查白名单既割不到自定义作物
        // 也会被视觉方块误判成原版作物 所以必须先把自定义方块分流出去
        ImmutableBlockState customState = CraftEngineBlocks.getCustomBlockState(block);
        if (customState != null && !customState.isEmpty()) {
            Boolean taken = fireHarvestEvent(sweep, block);
            if (!sweep.sourceMatches()) return false;
            if (taken != null) {
                return taken;
            }
            return harvestCustomCrop(sweep, block, customState);
        }
        Key id = blockKey(block);
        if (vanillaPolicy == VanillaPolicy.SOURCE_CLASSIFIED) return harvestSourceNative(sweep, block, id);
        if (blacklist.contains(id)) {
            return false;
        }
        // 先给监听器接管的机会 取消后是否仍算耐久由监听器自己定
        Boolean taken = fireHarvestEvent(sweep, block);
        if (!sweep.sourceMatches()) return false;
        if (taken != null) {
            return taken;
        }
        // 只认白名单里的作物 火焰 甘蔗 竹子 仙人掌 海带在 bukkit 里都是 Ageable
        // 用 instanceof 泛判会把火重置成 age 0 烧不完 也会让甘蔗白掉一个产物
        if (crops.contains(id)) {
            if (!(block.getBlockData() instanceof Ageable ageable)
                    || ageable.getAge() < ageable.getMaximumAge()) {
                return false;
            }
            return harvestVanillaCrop(sweep, block, ageable);
        }
        // 草丛灌木直接破坏 走 CE 的破坏以便自定义方块也能正确掉落
        if (bushes.contains(id)) {
            sweep.player.breakBlock(block.getX(), block.getY(), block.getZ());
            return true;
        }
        return false;
    }

    private boolean harvestSourceNative(Sweep sweep, Block block, Key id) {
        if (SOURCE_BLACKLIST.contains(id) || blacklist.contains(id)
                || (nativeAllowlist != null && !nativeAllowlist.contains(id))) return false;
        NativeSickleAccess.Snapshot snapshot = nativeAccess.inspect(block);
        if (nativeAccess.blacklisted(snapshot) || net.kaleidoscope.cookery.api.BlockTags.instance().matches(
                Key.of("kaleidoscopecookery:sickle_harvest_blacklist"), block)
                || net.kaleidoscope.cookery.api.BlockTags.instance().matches(
                Key.of("kaleidoscope_cookery:sickle_harvest_blacklist"), block)) return false;
        Boolean taken = fireHarvestEvent(sweep, block);
        if (!sweep.sourceMatches() || !nativeAccess.matches(block, snapshot)) return false;
        if (taken != null) return taken;
        if (snapshot.kind() == NativeSickleAccess.Kind.IMMATURE_CROP || snapshot.kind() == NativeSickleAccess.Kind.OTHER)
            return false;
        if (!nativeReady(sweep, block, snapshot)
                || !InteractGuard.canBreak(sweep.player(), sweep.level(), block.getX(), block.getY(), block.getZ())
                || !nativeReady(sweep, block, snapshot)) return false;
        if (snapshot.kind() == NativeSickleAccess.Kind.MATURE_CROP) {
            // Match the existing crop logging contract; this is not a second protection system.
            EventUtils.logBlockBreak(block, sweep.bukkitPlayer());
            if (!nativeReady(sweep, block, snapshot)) return false;
        }
        NativeSickleAccess.Outcome outcome = snapshot.kind() == NativeSickleAccess.Kind.MATURE_CROP
                ? nativeAccess.harvestCrop(block, sweep.bukkitPlayer(), snapshot, () -> nativeReady(sweep, block, snapshot))
                : nativeAccess.destroyVegetation(block, sweep.bukkitPlayer(), snapshot, () -> nativeReady(sweep, block, snapshot));
        if (outcome.wear() != null && !sweep.source().acceptNativeWear(outcome.wear(), sweep.player(), sweep.bukkitPlayer()))
            outcome = new NativeSickleAccess.Outcome(NativeSickleAccess.Status.PARTIAL,
                    "native wear receipt cannot update the original complete interaction source; prior outcome: " + outcome.detail(),
                    outcome.failure());
        if (outcome.status() == NativeSickleAccess.Status.PARTIAL) {
            sweep.nativeProblem().outcome = outcome;
            LOGGER.log(outcome.failure() == null ? java.util.logging.Level.WARNING : java.util.logging.Level.SEVERE,
                    "SOURCE_SICKLE_PARTIAL world=" + block.getWorld().getName() + " position="
                            + block.getX() + "," + block.getY() + "," + block.getZ() + ": " + outcome.detail(), outcome.failure());
        }
        return outcome.committed();
    }

    private boolean nativeReady(Sweep sweep, Block block, NativeSickleAccess.Snapshot snapshot) {
        org.bukkit.entity.Player actor = sweep.bukkitPlayer();
        return sweep.sourceMatches() && Bukkit.isOwnedByCurrentRegion(actor) && actor.isOnline() && actor.isValid()
                && !actor.isDead() && actor.getGameMode() != GameMode.ADVENTURE && actor.getGameMode() != GameMode.SPECTATOR
                && actor.getWorld() == sweep.world() && block.getWorld() == sweep.world()
                && Bukkit.isOwnedByCurrentRegion(sweep.world(), block.getX() >> 4, block.getZ() >> 4)
                && sweep.world().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && nativeAccess.matches(block, snapshot);
    }

    // 监听器接管了就返回它给的耐久决定 没接管返回 null 表示继续走默认收割
    // 没人监听时连事件对象都不建 一刀几十格全建纯属浪费
    private static Boolean fireHarvestEvent(Sweep sweep, Block block) {
        if (!EventUtils.hasListeners(SickleHarvestEvent.getHandlerList())) {
            return null;
        }
        SickleHarvestEvent event = new SickleHarvestEvent(sweep.bukkitPlayer(), sweep.sickle(), block);
        return EventUtils.fireAndCheckCancel(event) ? event.costDurability() : null;
    }

    private boolean harvestVanillaCrop(Sweep sweep, Block block, Ageable ageable) {
        if (!sweep.player().canInstabuild()) {
            ItemStack tool = sweep.bukkitPlayer().getInventory().getItemInMainHand();
            Location dropAt = block.getLocation().add(0.5, 0.5, 0.5);
            block.getDrops(tool, sweep.bukkitPlayer()).forEach(drop -> block.getWorld().dropItemNaturally(dropAt, drop));
        }
        Ageable reset = (Ageable) ageable.clone();
        reset.setAge(0);
        // 水稻这类泡在水里的作物要保住含水状态 否则重置后水会消失
        if (reset instanceof Waterlogged resetWater && block.getBlockData() instanceof Waterlogged current) {
            resetWater.setWaterlogged(current.isWaterlogged());
        }
        EventUtils.logBlockBreak(block, sweep.bukkitPlayer());
        block.setBlockData(reset, true);
        block.getWorld().playEffect(block.getLocation(), Effect.STEP_SOUND, block.getType());
        return true;
    }

    // CE 作物一律委托给方块行为
    private boolean harvestCustomCrop(Sweep sweep, Block block, ImmutableBlockState state) {
        HarvestableCrop crop = state.behavior().getFirst(HarvestableCrop.class);
        if (crop == null) {
            crop = GenericAgeCrop.of(state);
        }
        if (crop == null) {
            return false;
        }
        World world = BukkitAdaptor.adapt(block.getWorld());
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        // 一株只收一次 一刀的扫描范围会同时命中同一株的两段
        BlockPos root = crop.rootPos(world, pos, state);
        if (!sweep.stalks().add(packed(root.x(), root.y(), root.z()))) {
            return false;
        }
        if (!crop.harvest(world, pos, state, sweep.player(), sweep.hand())) {
            return false;
        }
        block.getWorld().playEffect(block.getLocation(), Effect.STEP_SOUND, block.getType());
        return true;
    }

    private static long packed(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    private static final Key[] BLOCK_KEYS = new Key[Material.values().length];

    private static Key blockKey(Block block) {
        Material material = block.getType();
        Key cached = BLOCK_KEYS[material.ordinal()];
        if (cached == null) {
            cached = Key.of(material.getKey().toString());
            BLOCK_KEYS[material.ordinal()] = cached;
        }
        return cached;
    }

    private static final List<String> DEFAULT_CROPS = List.of(
            "minecraft:wheat",
            "minecraft:carrots",
            "minecraft:potatoes",
            "minecraft:beetroots",
            "minecraft:nether_wart",
            "minecraft:cocoa",
            "minecraft:sweet_berry_bush",
            "minecraft:torchflower_crop",
            "minecraft:pitcher_crop"
    );

    private static final List<String> DEFAULT_BUSHES = List.of(
            "minecraft:grass",
            "minecraft:short_grass",
            "minecraft:tall_grass",
            "minecraft:fern",
            "minecraft:large_fern",
            "minecraft:short_dry_grass",
            "minecraft:tall_dry_grass",
            "minecraft:seagrass",
            "minecraft:tall_seagrass",
            "minecraft:dead_bush",
            "minecraft:bush"
    );

    private static final class Factory implements ItemBehaviorFactory<SickleRangeHarvestBehavior> {
        private static final String[] RADIUS_KEYS = {"radius", "horizontal_radius", "horizontal-radius"};
        private static final String[] HEIGHT_KEYS = {"height"};
        private static final String[] COOLDOWN_KEYS = {"cooldown", "cooldown_ticks", "cooldown-ticks"};
        private static final String[] DAMAGE_KEYS = {"damage_item", "damage-item"};
        private static final String[] DURABILITY_KEYS = {"durability_per_use", "durability-per-use"};
        private static final int DEFAULT_DURABILITY_PER_USE = 1;
        private static final String[] CROP_KEYS = {"crops", "crop"};
        private static final String[] BUSH_KEYS = {"bushes", "bush"};
        private static final String[] BLACKLIST_KEYS = {"blacklist", "black_list", "black-list"};
        private static final String[] POLICY_KEYS = {"vanilla-policy", "vanilla_policy"};
        private static final String[] NATIVE_ALLOW_KEYS = {"vanilla-allowlist", "vanilla_allowlist"};

        @Override
        public SickleRangeHarvestBehavior create(Pack pack, Path path, Key key, ConfigSection section) {
            int durability = BehaviorConfig.getInt(section, DEFAULT_DURABILITY_PER_USE, DURABILITY_KEYS);
            if (durability < 0 || durability > Integer.MAX_VALUE / 8) {
                throw new IllegalArgumentException(key + ": durability-per-use cannot support sweep III: " + durability);
            }
            String policyValue = BehaviorConfig.getString(section, "legacy-ageable", POLICY_KEYS);
            VanillaPolicy policy = switch (policyValue == null ? "legacy-ageable" : policyValue.trim()) {
                case "legacy-ageable" -> VanillaPolicy.LEGACY_AGEABLE;
                case "source-classified" -> VanillaPolicy.SOURCE_CLASSIFIED;
                default -> throw new IllegalArgumentException(key + ": unknown vanilla-policy: " + policyValue);
            };
            if (policy == VanillaPolicy.SOURCE_CLASSIFIED && (section.containsKey(CROP_KEYS) || section.containsKey(BUSH_KEYS)))
                throw new IllegalArgumentException(key + ": source-classified cannot silently reinterpret crops/bushes; "
                        + "choose vanilla-policy: legacy-ageable or migrate to vanilla-allowlist");
            if (policy == VanillaPolicy.LEGACY_AGEABLE && section.containsKey(NATIVE_ALLOW_KEYS))
                throw new IllegalArgumentException(key + ": vanilla-allowlist requires vanilla-policy: source-classified");
            Set<Key> nativeAllowed = section.containsKey(NATIVE_ALLOW_KEYS)
                    ? BehaviorConfig.getKeySet(section, List.of(), NATIVE_ALLOW_KEYS) : null;
            return new SickleRangeHarvestBehavior(
                    Math.max(0, Math.min(MAX_HORIZONTAL_RADIUS, BehaviorConfig.getInt(section, DEFAULT_HORIZONTAL_RADIUS, RADIUS_KEYS))),
                    Math.max(1, BehaviorConfig.getInt(section, DEFAULT_HEIGHT, HEIGHT_KEYS)),
                    BehaviorConfig.getInt(section, DEFAULT_COOLDOWN_TICKS, COOLDOWN_KEYS),
                    BehaviorConfig.getBoolean(section, true, DAMAGE_KEYS),
                    durability,
                    BehaviorConfig.getKeySet(section, DEFAULT_CROPS, CROP_KEYS),
                    BehaviorConfig.getKeySet(section, DEFAULT_BUSHES, BUSH_KEYS),
                    BehaviorConfig.getKeySet(section, List.of(), BLACKLIST_KEYS), policy, nativeAllowed,
                    policy == VanillaPolicy.SOURCE_CLASSIFIED ? Objects.requireNonNull(NativeSickleAccessProvider.access(), "native sickle access") : null);
        }
    }
}
