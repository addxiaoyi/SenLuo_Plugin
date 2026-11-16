package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.TeaBannerBehavior;
import net.kaleidoscope.cookery.util.BlockEntityNbt;
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
import java.util.Set;

/** Persists tea-banner dye and pattern state across placement, saves and drops. */
public final class TeaBannerController extends BlockEntityController {
    public static final String DATA_KEY = "kaleidoscopecookery:tea_banner";
    private static final String ITEM_DATA_KEY = DATA_KEY + ":item";
    private static final Key BANNER = Key.of("kaleidoscopecookery:tea_banner");
    private static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final Set<String> PATTERNS = Set.of(
            "minecraft:creeper_banner_pattern", "minecraft:skull_banner_pattern",
            "minecraft:flower_banner_pattern", "minecraft:mojang_banner_pattern",
            "minecraft:globe_banner_pattern", "minecraft:piglin_banner_pattern");

    private String color = "red";
    private Item pattern = Item.empty();

    public TeaBannerController(BlockEntity blockEntity, TeaBannerBehavior behavior) {
        super(blockEntity);
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
            changed();
            play("minecraft:item.dye.use");
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (PATTERNS.containsKey(id)) {
            if (held.isSimilar(pattern)) {
                return InteractionResult.PASS;
            }
            Item previous = pattern;
            pattern = held.copyWithCount(1);
            InventoryUtils.shrinkHeld(player, held, 1);
            if (!previous.isEmpty()) {
                DropUtils.dropAtCenter(blockEntity, previous);
            }
            changed();
            play("minecraft:block.wool.place");
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
        if (id.equals("minecraft:shears") && !pattern.isEmpty()) {
            Item previous = pattern;
            pattern = Item.empty();
            InventoryUtils.shrinkHeld(player, held, 1);
            InventoryUtils.giveOrHold(player, hand, previous);
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
        blockEntity.world.playSound(Vec3d.atCenterOf(blockEntity.pos), Key.of(sound), 1, 1,
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
        }
    }

    @Override
    public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(CEWorld world,
                                                                                           ImmutableBlockState state) {
        return null;
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
    }

    private void validate() {
        if (!COLORS.contains(color)) {
            color = "red";
        }
        if (!pattern.isEmpty() && !PATTERNS.containsKey(pattern.id().asString())) {
            pattern = Item.empty();
        }
    }
}
