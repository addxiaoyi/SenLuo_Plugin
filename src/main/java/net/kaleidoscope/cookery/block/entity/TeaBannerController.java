package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.TeaBannerBehavior;
import net.kaleidoscope.cookery.util.BlockEntityNbt;
import net.kaleidoscope.cookery.util.BlockStates;
import net.kaleidoscope.cookery.util.CookeryBlockStateEnvelope;
import net.kaleidoscope.cookery.util.DropUtils;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.kaleidoscope.cookery.util.PlacementGuard;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;

import java.util.List;

/** Persists tea-banner dye and pattern state across placement, saves and drops. */
public final class TeaBannerController extends BlockEntityController {
    public static final String DATA_KEY = "kaleidoscopecookery:tea_banner";
    private static final String ITEM_DATA_KEY = DATA_KEY + ":item";
    private static final Key BANNER = Key.of("kaleidoscopecookery:tea_banner");
    private static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final List<String> PATTERNS = List.of(
            "minecraft:creeper_banner_pattern", "minecraft:skull_banner_pattern",
            "minecraft:flower_banner_pattern", "minecraft:mojang_banner_pattern",
            "minecraft:globe_banner_pattern", "minecraft:piglin_banner_pattern");

    private final TeaBannerBehavior behavior;
    private String color = "red";
    private Item pattern = Item.empty();
    private int wave;

    public TeaBannerController(BlockEntity blockEntity, TeaBannerBehavior behavior) {
        super(blockEntity);
        this.behavior = behavior;
    }

    public InteractionResult use(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        Item held = player.getItemInHand(hand);
        String id = held.id().asString();
        if (id.startsWith("minecraft:") && id.endsWith("_dye")) {
            String next = id.substring("minecraft:".length(), id.length() - "_dye".length());
            if (!COLORS.contains(next) || next.equals(color)) {
                return InteractionResult.PASS;
            }
            color = next;
            InventoryUtils.shrinkHeld(player, held, 1);
            syncVisual();
            changed();
            play("minecraft:item.dye.use");
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (PATTERNS.contains(id)) {
            if (held.isSimilar(pattern)) {
                return InteractionResult.PASS;
            }
            Item previous = pattern;
            pattern = held.copyWithCount(1);
            InventoryUtils.shrinkHeld(player, held, 1);
            if (!previous.isEmpty()) {
                DropUtils.dropAtCenter(blockEntity, previous);
            }
            syncVisual();
            changed();
            play("minecraft:block.wool.place");
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (id.equals("minecraft:shears") && !pattern.isEmpty()) {
            Item previous = pattern;
            pattern = Item.empty();
            InventoryUtils.shrinkHeld(player, held, 1);
            InventoryUtils.giveOrHold(player, hand, previous);
            syncVisual();
            changed();
            play("minecraft:entity.sheep.shear");
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        return InteractionResult.PASS;
    }

    private void changed() {
        if (blockEntity.world != null) {
            blockEntity.world.blockEntityChanged(blockEntity.pos);
        }
    }

    private void play(String sound) {
        blockEntity.world.world().playSound(Vec3d.atCenterOf(blockEntity.pos), Key.of(sound), 1, 1,
                net.momirealms.craftengine.core.sound.SoundSource.BLOCK);
    }

    private Item item() {
        Item banner = InventoryUtils.createOrEmpty(BANNER);
        if (banner.isEmpty()) {
            return banner;
        }
        CompoundTag saved = new CompoundTag();
        saved.putString("color", color);
        BlockEntityNbt.putItem(saved, "pattern", pattern);
        banner.setSparrowTag(saved, ITEM_DATA_KEY);
        return banner;
    }

    @Override
    public void onRemove() {
        if (!PlacementGuard.isPlacing(blockEntity)) {
            DropUtils.dropOnRemove(blockEntity, item());
        }
        pattern = Item.empty();
        super.onRemove();
    }

    @Override
    public void loadCustomDataFromItem(Item item) {
        Tag tag = item.getSparrowTag(ITEM_DATA_KEY);
        if (tag instanceof CompoundTag saved) {
            color = saved.getString("color", "red");
            pattern = BlockEntityNbt.getItem(saved, "pattern", Config.itemDataFixerUpperFallbackVersion());
            validate();
            syncVisual();
        }
    }

    @Override
    public void onLoad() {
        wave = BlockStates.value(blockEntity.blockState, behavior.waveProperty(), 0);
        syncVisual();
    }

    @Override
    public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(CEWorld world,
                                                                                           ImmutableBlockState state) {
        return createTickerHelper((w, pos, current, controller) -> tick());
    }

    @Override
    public void saveCustomData(CompoundTag root) {
        CompoundTag saved = new CompoundTag();
        saved.putString("color", color);
        BlockEntityNbt.putItem(saved, "pattern", pattern);
        CookeryBlockStateEnvelope.put(root, DATA_KEY, saved);
    }

    @Override
    public void loadCustomData(CompoundTag root) {
        CompoundTag saved = CookeryBlockStateEnvelope.payload(root, DATA_KEY);
        if (saved == null) {
            return;
        }
        color = saved.getString("color", "red");
        pattern = BlockEntityNbt.getItem(saved, "pattern", BlockEntityNbt.dataVersion(saved));
        validate();
        syncVisual();
    }

    private void validate() {
        if (!COLORS.contains(color)) {
            color = "red";
        }
        if (!pattern.isEmpty() && !PATTERNS.contains(pattern.id().asString())) {
            pattern = Item.empty();
        }
    }

    private void tick() {
        if (blockEntity.world == null) {
            return;
        }
        long phase = Math.floorMod(world().getGameTime() + blockEntity.pos.asLong(), 18L);
        int next = phase < 6 ? 0 : phase < 12 ? 1 : 2;
        if (next == wave) {
            return;
        }
        wave = next;
        syncVisual();
    }

    private void syncVisual() {
        if (blockEntity.world == null) {
            return;
        }
        ImmutableBlockState state = blockEntity.blockState;
        int colorIndex = COLORS.indexOf(color);
        int patternIndex = pattern.isEmpty() ? 0 : PATTERNS.indexOf(pattern.id().asString()) + 1;
        ImmutableBlockState next = BlockStates.with(state, behavior.colorProperty(), colorIndex);
        next = BlockStates.with(next, behavior.patternProperty(), patternIndex);
        next = BlockStates.with(next, behavior.waveProperty(), wave);
        BlockStates.sync(blockEntity, next);
    }
}
