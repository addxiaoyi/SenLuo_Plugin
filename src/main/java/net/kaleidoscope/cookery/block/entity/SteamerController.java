package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.item.SteamerItemContents;
import net.kaleidoscope.cookery.item.behavior.SteamerItemBehavior;

import net.kaleidoscope.cookery.block.behavior.SteamerBehavior;

import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.SlabType;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.kaleidoscope.cookery.recipe.ApplianceType;
import net.kaleidoscope.cookery.recipe.ApplianceFoodRegistry;
import net.kaleidoscope.cookery.recipe.FoodRecipeRegistry;
import net.kaleidoscope.cookery.recipe.FoodRecipeResult;
import net.kaleidoscope.cookery.recipe.CookingPlan;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.kaleidoscope.cookery.util.HeatSourceUtils;
import net.kaleidoscope.cookery.util.DropUtils;
import net.kaleidoscope.cookery.block.entity.render.Particles;
import net.kaleidoscope.cookery.block.entity.render.TrackedPlayers;
import org.bukkit.Particle;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.Random;

public class SteamerController extends BlockEntityController {
    public static final String DATA_KEY = "kaleidoscopecookery:steamer";
    private static final String K_BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String K_DATA_VERSION = "data_version";
    private static final String K_SEED = "seed";
    private static final String K_HAS_LID = "has_lid";
    private static final String K_LIT_LEVEL = "lit_level";
    private static final int MAX_LIT_LEVEL = 4;
    private static final int SLOTS = 8;
    private final SteamerBehavior behavior;
    private final int[] cookingProgress = new int[SLOTS];
    private final int[] cookingTime = new int[SLOTS];
    private final CookingPlan[] cookingPlans = new CookingPlan[SLOTS];
    private final boolean[] completionBlocked = new boolean[SLOTS];
    private final Item[] items = new Item[SLOTS];
    private final Random random = new Random();
    private int itemCount = 0;
    private final SteamerElement element;
    private boolean hasLid = false;
    private boolean wasCovered = false;
    private long seed = System.currentTimeMillis();
    private int tickCounter;
    private int particleTick = 0;
    private int dirtyTick;
    private boolean progressDirty;
    private boolean aboveSteamerCache;
    private boolean needsPlanHydration;
    private boolean environmentInitialized;
    private int litLevel = 0;
    private boolean fallingAway = false;
    private boolean skipFoodDrop = false;
    private boolean skipAllDrops;
    private boolean removing;
    private boolean removed;
    private CompoundTag rejectedState;
    private boolean renderDirty = true;

    public SteamerController(BlockEntity blockEntity, SteamerBehavior behavior) {
        super(blockEntity);
        this.behavior = behavior;
        this.dirtyTick = Math.floorMod(blockEntity.pos.x() * 31 + blockEntity.pos.z(), 20);
        this.particleTick = Math.floorMod(blockEntity.pos.x() * 31 + blockEntity.pos.z(), Math.max(1, behavior.particleInterval));
        Arrays.fill(this.items, Item.empty());
        this.element = new SteamerElement(this, new WorldPosition(
                null, (float) super.blockEntity.pos.x() + 0.5f,
                (float) super.blockEntity.pos.y() + 0.1f,
                (float) super.blockEntity.pos.z() + 0.5f
        ));
        SteamerItemBehavior.created(this);
        SteamerInteraction.created(this);
    }

    @Override
    public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(
            CEWorld world, ImmutableBlockState blockState) {
        return BlockEntityController.<C, SteamerController>createTickerHelper((w, pos, state, controller) -> controller.tick());
    }

    public void refreshDynamicElement(BiConsumer<SteamerElement, Player> consumer) {
        if (blockEntity.world == null) return;
        TrackedPlayers.forEach(super.blockEntity, trackedPlayer -> consumer.accept(this.element, trackedPlayer));
    }

    public void refreshElementState() {
        if (this.element != null) {
            this.element.prepareUpdate();
            refreshDynamicElement(SteamerElement::update);
        }
    }

    public void tick() {
        if (rejectedState != null || removing || removed || blockEntity.world == null || !blockEntity.isValid()
                || SteamerItemBehavior.placing(blockEntity) || SteamerInteraction.active(blockEntity)) return;
        hydrateLegacyPlans();
        if (!environmentInitialized || ++tickCounter >= 5) {
            tickCounter = environmentInitialized ? 0 : Math.floorMod(super.blockEntity.pos.x() * 31 + super.blockEntity.pos.z(), 5);
            environmentInitialized = true;
            aboveSteamerCache = isAboveSteamer();
            updateLitLevel();
        }
        boolean aboveSteamer = aboveSteamerCache;
        boolean currentlyCovered = hasLid() || aboveSteamer;
        if (renderDirty || currentlyCovered != wasCovered) {
            renderDirty = false;
            wasCovered = currentlyCovered;
            refreshElementState();
            flushProgress();
        }

        particleTick++;
        if (particleTick % behavior.particleInterval == 0) {
            for (int i = 0; i < SLOTS; i++) {
                if (cookingTime[i] == -1) {
                    makeRipeParticles();
                    break;
                }
            }
        }
        if (litLevel > 0) {
            cookingTick(aboveSteamer);
        } else {
            cooldownTick();
        }
        if (++dirtyTick >= 20) {
            dirtyTick = 0;
            if (progressDirty) {
                flushProgress();
            }
        }
    }

    private void hydrateLegacyPlans() {
        if (!needsPlanHydration) return;
        needsPlanHydration = false;
        boolean changed = false;
        for (int i = 0; i < SLOTS; i++) {
            if (cookingTime[i] > 0 && cookingPlans[i] == null && !items[i].isEmpty()) {
                cookingPlans[i] = FoodRecipeRegistry.instance()
                        .planAccurate(ApplianceType.STEAMER, items[i].id(), cookingTime[i])
                        .withWorkRequired(cookingTime[i]);
                changed = true;
            }
        }
        if (changed) super.blockEntity.world.blockEntityChanged(super.blockEntity.pos);
    }

    private void updateLitLevel() {
        int previousLitLevel = this.litLevel;
        Object level = super.blockEntity.world.world().minecraftWorld();
        Object belowPos = LocationUtils.below(LocationUtils.toBlockPos(super.blockEntity.pos));

        if (HeatSourceUtils.isHeatSource(level, belowPos)) {
            this.litLevel = MAX_LIT_LEVEL;
        } else {
            Object belowState = BlockGetterProxy.INSTANCE.getBlockState(level, belowPos);
            Optional<ImmutableBlockState> optionalCustomState = BlockStateUtils.getOptionalCustomBlockState(belowState);

            if (optionalCustomState.isPresent()) {
                ImmutableBlockState belowCustomState = optionalCustomState.get();
                if (belowCustomState.owner().value() == super.blockEntity.blockState.owner().value()) {
                    SteamerBehavior belowBehavior = belowCustomState.behavior().getFirst(SteamerBehavior.class);
                    if (belowBehavior != null) {
                        SlabType type = getSlabType(belowCustomState);

                        // 下层是双层蒸笼时 火力按层向上递减传导
                        if (type == SlabType.DOUBLE) {
                            BlockPos ceBelowPos = new BlockPos(
                                    super.blockEntity.pos.x, super.blockEntity.pos.y - 1, super.blockEntity.pos.z);
                            BlockEntity belowEntity = super.blockEntity.world.getBlockEntityAtIfLoaded(ceBelowPos);

                            if (belowEntity != null) {
                                SteamerController belowController = belowEntity.controller.get(SteamerController.class, belowBehavior.getControllerId());
                                if (belowController != null) {
                                    this.litLevel = Math.max(belowController.getLitLevel() - 1, 0);
                                    if (previousLitLevel > 0 && this.litLevel == 0) flushProgress();
                                    return;
                                }
                            }
                        }
                    }
                }
            }
            this.litLevel = 0;
        }
        if (previousLitLevel > 0 && this.litLevel == 0) flushProgress();
    }

    private void cookingTick(boolean aboveIsSteamer) {
        if (!aboveIsSteamer) {
            makeCookingParticles();
            if (!hasLid()) {
                return;
            }
        }

        boolean stateChanged = false;
        for (int i = 0; i < SLOTS; i++) {
            if (items[i].isEmpty() || cookingTime[i] <= 0 || completionBlocked[i]) {
                continue;
            }
            CookingPlan plan = cookingPlans[i];
            if (plan == null || !plan.valid()) {
                blockCompletion(i);
                stateChanged = true;
                continue;
            }
            cookingProgress[i]++;
            progressDirty = true;
            if (cookingProgress[i] >= cookingTime[i]) {
                Optional<FoodRecipeResult> recipeResult = plan.matched() ? plan.buildResult() : Optional.empty();
                if (plan.matched() && recipeResult.isEmpty()) {
                    blockCompletion(i);
                    stateChanged = true;
                    continue;
                }
                Item resultItem = recipeResult.map(fr -> fr.item().copyWithCount(fr.count())).orElse(items[i]);
                if (!resultItem.isEmpty()) {
                    items[i] = resultItem;
                    cookingTime[i] = -1;
                    cookingProgress[i] = 0;
                    stateChanged = true;
                }
            }
        }
        if (stateChanged) {
            this.refreshElementState();
            progressDirty = false;
            super.blockEntity.world.blockEntityChanged(super.blockEntity.pos);
        }
    }

    private void cooldownTick() {
        for (int i = 0; i < SLOTS; i++) {
            if (cookingProgress[i] > 0) {
                cookingProgress[i] = Math.max(0, cookingProgress[i] - 2);
                progressDirty = true;
            }
        }
    }

    private void flushProgress() {
        if (progressDirty) {
            progressDirty = false;
            super.blockEntity.world.blockEntityChanged(super.blockEntity.pos);
        }
    }

    private void blockCompletion(int slot) {
        completionBlocked[slot] = true;
        var plugin = KaleidoscopeCookeryPlugin.instance();
        if (plugin != null) plugin.getLogger().warning("Steamer cooking paused at " + blockEntity.pos
                + " (slot " + slot + "): saved recipe or result is unavailable; input retained.");
    }

    public int capacity() {
        SlabType type = getSlabType(super.blockEntity.blockState);
        return (type == SlabType.DOUBLE) ? SLOTS : SLOTS / 2;
    }

    public boolean hasSpace() {
        if (rejectedState != null || removing || removed || SteamerItemBehavior.placing(blockEntity) || SteamerInteraction.active(blockEntity)) return false;
        for (int i = 0; i < capacity(); i++) if (items[i].isEmpty()) return true;
        return false;
    }

    public boolean canSteam(Item food) {
        return ApplianceFoodRegistry.instance().isAllowed(ApplianceType.STEAMER, food.id());
    }

    public boolean tryAddOne(Item food) {
        if (!hasSpace()) return false;
        return FoodRecipeRegistry.instance().readSnapshot(() -> tryAddOneInSnapshot(food));
    }

    private boolean tryAddOneInSnapshot(Item food) {
        if (!hasSpace() || !canSteam(food)) {
            return false;
        }
        int slot = 0;
        while (slot < capacity() && !items[slot].isEmpty()) slot++;
        CookingPlan plan = FoodRecipeRegistry.instance().planAccurate(ApplianceType.STEAMER, food.id(), behavior.cookingTime);
        items[slot] = food.copyWithCount(1);
        cookingProgress[slot] = 0;
        cookingPlans[slot] = plan;
        cookingTime[slot] = plan.workRequired();
        completionBlocked[slot] = false;
        itemCount++;
        refreshElementState();
        super.blockEntity.world.blockEntityChanged(super.blockEntity.pos);
        return true;
    }

    public Item takeFood(Player player) {
        if (rejectedState != null || removing || removed || itemCount == 0 || SteamerItemBehavior.placing(blockEntity) || SteamerInteraction.active(blockEntity)) {
            return Item.empty();
        }
        int target = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (items[i].isEmpty()) {
                continue;
            }
            if (cookingTime[i] == -1) {
                target = i;
                break;
            }
            if (target == -1) {
                target = i;
            }
        }
        if (target == -1) {
            return Item.empty();
        }

        Item taken = items[target].copy();
        items[target] = Item.empty();
        cookingProgress[target] = 0;
        cookingTime[target] = 0;
        cookingPlans[target] = null;
        completionBlocked[target] = false;
        itemCount--;

        refreshElementState();
        super.blockEntity.world.blockEntityChanged(super.blockEntity.pos);
        return taken;
    }

    private void makeCookingParticles() {
        if (particleTick % behavior.particleInterval != 0) {
            return;
        }
        SlabType type = getSlabType(super.blockEntity.blockState);
        double yOffset = (type != SlabType.DOUBLE) ? 0.5 : 1.0;
        double x = super.blockEntity.pos.x + 0.5 + (random.nextDouble() / 2) * (random.nextBoolean() ? 1 : -1);
        double y = super.blockEntity.pos.y + yOffset + random.nextDouble() / 2;
        double z = super.blockEntity.pos.z + 0.5 + (random.nextDouble() / 2) * (random.nextBoolean() ? 1 : -1);
        Particles.emit(super.blockEntity.world, Particle.CLOUD, x, y, z, behavior.particleCount, 0.05, 0.05, 0.05, 0.05, null);
    }

    private void makeRipeParticles() {
        SlabType type = getSlabType(super.blockEntity.blockState);
        double yOffset = (type != SlabType.DOUBLE) ? 0.25 : 0.75;
        double x = super.blockEntity.pos.x + 0.5 + (random.nextDouble() / 1.25) * (random.nextBoolean() ? 1 : -1);
        double y = super.blockEntity.pos.y + yOffset + random.nextDouble() / 2;
        double z = super.blockEntity.pos.z + 0.5 + (random.nextDouble() / 1.25) * (random.nextBoolean() ? 1 : -1);
        Particles.emit(super.blockEntity.world, Particle.CLOUD, x, y, z, behavior.particleCount, 0.05, 0.05, 0.05, 0.05, null);
    }

    private static SlabType getSlabType(ImmutableBlockState state) {
        Property<SlabType> property = state.getProperty("type");
        return property != null ? state.get(property, SlabType.BOTTOM) : SlabType.BOTTOM;
    }

    public boolean isCovered() {
        return hasLid() || isAboveSteamer();
    }

    private boolean isAboveSteamer() {
        if (blockEntity.world == null) return false;
        Object level = super.blockEntity.world.world().minecraftWorld();
        Object abovePos = LocationUtils.toBlockPos(
                super.blockEntity.pos.x, super.blockEntity.pos.y + 1, super.blockEntity.pos.z
        );
        Object aboveState = BlockGetterProxy.INSTANCE.getBlockState(level, abovePos);
        Optional<ImmutableBlockState> opt = BlockStateUtils.getOptionalCustomBlockState(aboveState);
        return opt.isPresent() && opt.get().owner().value() == super.blockEntity.blockState.owner().value();
    }

    @Override
    public boolean hasElement() {
        return true;
    }

    @Override
    public void gatherElements(Consumer<BlockEntityElement> consumer) {
        consumer.accept(element);
    }

    public void markFallingAway() {
        this.fallingAway = true;
    }

    public void clearFallingAway() {
        this.fallingAway = false;
    }

    public void markSkipFoodDrop() {
        this.skipFoodDrop = true;
    }

    // 蒸笼装满且全部成品熟透
    public boolean isFullOfFinishedProducts() {
        if (rejectedState != null || itemCount < capacity()) {
            return false;
        }
        for (int i = 0; i < capacity(); i++) {
            if (items[i].isEmpty() || cookingTime[i] != -1) {
                return false;
            }
        }
        return true;
    }

    // 即将掉落的成品
    public List<ItemStack> finishedProductStacks() {
        List<ItemStack> products = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            if (!items[i].isEmpty()) {
                products.add(ItemStackUtils.getBukkitStack(items[i]));
            }
        }
        return products;
    }

    @Override
    public void onRemove() {
        if (removing || removed) return;
        if (rejectedState != null) throw new IllegalStateException("Refusing to discard unreadable steamer contents");
        if (SteamerItemBehavior.suppressRemoval(this) || SteamerInteraction.suppressRemoval(this)) {
            removed = true; clearInventory(); super.onRemove(); return;
        }
        boolean allowDrops = !skipAllDrops && (!behavior.portableContents || blockEntity.world == null || !Boolean.FALSE.equals(
                ((org.bukkit.World) blockEntity.world.world().platformWorld()).getGameRuleValue(net.kaleidoscope.compat.Rules.DO_TILE_DROPS)));
        List<Item> portable = behavior.portableContents && !fallingAway && allowDrops ? portableDrops(skipFoodDrop) : List.of();
        removing = true; removed = true;
        try {
            Item[] removed = items.clone();
            int oldCount = itemCount;
            clearInventory();
            // Drop callbacks may reenter removal. Ownership has already left this controller.
            if (behavior.portableContents && !fallingAway) {
                for (Item item : portable) DropUtils.dropOnRemove(blockEntity, item);
            } else if (!fallingAway && !skipFoodDrop && (!behavior.portableContents || allowDrops)) {
                for (Item item : removed) if (!item.isEmpty()) DropUtils.dropOnRemove(blockEntity, item);
            }
            if (oldCount > 0) refreshElementState();
            super.onRemove();
        } finally { removing = false; }
    }

    private void clearInventory() {
        itemCount = 0;
        Arrays.fill(items, Item.empty());
        Arrays.fill(cookingProgress, 0);
        Arrays.fill(cookingTime, 0);
        Arrays.fill(cookingPlans, null);
        Arrays.fill(completionBlocked, false);
        needsPlanHydration = false;
        progressDirty = false;
    }

    @Override
    public void loadCustomDataFromItem(Item item) {
        if (behavior.portableContents) {
            var slots = new ArrayList<>(SteamerItemContents.read(item));
            slots.addAll(java.util.Collections.nCopies(4, null));
            CompoundTag root = new CompoundTag();
            CookeryBlockStateEnvelope.put(root, DATA_KEY, SteamerItemContents.payload(slots));
            loadCustomData(root);
            return;
        }
        Object nmsItem = item.minecraftItem();
        Tag tag = ItemStackUtils.saveMinecraftItemStackAsTag(nmsItem);
        if (tag instanceof CompoundTag compoundTag && compoundTag.containsKey(K_BLOCK_ENTITY_TAG)) {
            loadCustomData(compoundTag.getCompound(K_BLOCK_ENTITY_TAG));
        }
    }

    @Override
    public void saveCustomData(CompoundTag tag) {
        if (rejectedState != null) {
            for (String key : new String[]{DATA_KEY, CookeryBlockStateEnvelope.sidecar(DATA_KEY)}) {
                Tag raw = rejectedState.get(key);
                if (raw == null) tag.remove(key); else tag.put(key, raw.deepClone());
            }
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putInt(K_DATA_VERSION, VersionHelper.WORLD_VERSION);
        data.putLong(K_SEED, this.seed);
        data.putBoolean(K_HAS_LID, hasLid());
        data.putInt(K_LIT_LEVEL, litLevel);
        SteamerInventory.write(data, items, cookingProgress, cookingTime, cookingPlans, completionBlocked);
        CookeryBlockStateEnvelope.put(tag, DATA_KEY, data);
    }

    @Override
    public void loadCustomData(CompoundTag tag) {
        try {
            if (!tag.containsKey(CookeryBlockStateEnvelope.sidecar(DATA_KEY))
                    && tag.containsKey(DATA_KEY) && !(tag.get(DATA_KEY) instanceof CompoundTag))
                throw new IllegalArgumentException("Invalid steamer payload");
            CompoundTag data = CookeryBlockStateEnvelope.payload(tag, DATA_KEY);
            List<SteamerInventory.Slot> slots = SteamerInventory.read(data, behavior.cookingTime);
            long restoredSeed = System.currentTimeMillis();
            boolean restoredLid = false;
            int restoredLit = 0;
            if (data != null) {
                if (data.containsKey(K_SEED) && !(data.get(K_SEED) instanceof net.momirealms.craftengine.libraries.nbt.LongTag)
                        || data.containsKey(K_HAS_LID) && !(data.get(K_HAS_LID) instanceof net.momirealms.craftengine.libraries.nbt.ByteTag)
                        || data.containsKey(K_LIT_LEVEL) && !(data.get(K_LIT_LEVEL) instanceof net.momirealms.craftengine.libraries.nbt.IntTag))
                    throw new IllegalArgumentException("Invalid steamer metadata");
                restoredSeed = data.getLong(K_SEED, restoredSeed);
                restoredLid = data.getBoolean(K_HAS_LID, false);
                restoredLit = data.getInt(K_LIT_LEVEL, 0);
                if (restoredLit < 0 || restoredLit > MAX_LIT_LEVEL) throw new IllegalArgumentException("Invalid steamer heat");
            }
            // Parse everything first. A bad item/plan never clears or shifts the live inventory.
            clearInventory();
            for (int i = 0; i < SLOTS; i++) {
                SteamerInventory.Slot slot = slots.get(i);
                if (slot == null) continue;
                items[i] = slot.item(); cookingProgress[i] = slot.progress(); cookingTime[i] = slot.time();
                cookingPlans[i] = slot.plan(); completionBlocked[i] = slot.blocked(); itemCount++;
                if (slot.time() > 0 && slot.plan() == null) needsPlanHydration = true;
            }
            seed = restoredSeed; hasLid = restoredLid; litLevel = restoredLit;
            environmentInitialized = false; wasCovered = false; renderDirty = true;
            rejectedState = null;
        } catch (RuntimeException failure) {
            rejectedState = tag.deepClone();
            throw failure;
        }
    }

    public boolean hasValidData() { return rejectedState == null; }
    public boolean removed() { return removed; }
    public void skipAllDrops() { skipAllDrops = true; }
    public SteamerBehavior behavior() { return behavior; }
    public List<SteamerInventory.Slot> snapshotSlots() {
        if (rejectedState != null) throw new IllegalStateException("Unreadable steamer inventory");
        List<SteamerInventory.Slot> slots = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) slots.add(items[i].isEmpty() ? null : new SteamerInventory.Slot(
                items[i].copy(), cookingProgress[i], cookingTime[i], cookingPlans[i], completionBlocked[i]));
        return slots;
    }
    public List<Item> portableDrops(boolean omitContents) {
        return SteamerItemContents.split(snapshotSlots(), capacity(), omitContents);
    }

    public boolean hasLid() {
        Property<Boolean> property = blockEntity.blockState.getProperty("has_lid");
        return property == null ? hasLid : blockEntity.blockState.get(property, hasLid);
    }

    public void setHasLid(boolean hasLid) {
        if (rejectedState != null) throw new IllegalStateException("Unreadable steamer state");
        this.hasLid = hasLid;
        this.wasCovered = isCovered();
        refreshElementState();
    }

    public Item[] getItems() {
        return items;
    }

    public int getItemCount() {
        return itemCount;
    }

    public int[] getCookingProgress() {
        return cookingProgress;
    }

    public int[] getCookingTime() {
        return cookingTime;
    }

    public int getLitLevel() {
        return litLevel;
    }

    public long seed() {
        return seed;
    }
}
