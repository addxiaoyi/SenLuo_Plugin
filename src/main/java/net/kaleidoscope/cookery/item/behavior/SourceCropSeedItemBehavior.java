package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.UseOnContext;

public final class SourceCropSeedItemBehavior extends BlockItemBehavior {
    public static final ItemBehaviorFactory<SourceCropSeedItemBehavior> FACTORY = (pack, path, key, section) ->
            new SourceCropSeedItemBehavior(section.getNonNullValue("block", ConfigConstants.ARGUMENT_SECTION).getAsIdentifier());

    SourceCropSeedItemBehavior(Key blockId) {
        super(blockId);
    }

    @Override public InteractionResult useOnBlock(UseOnContext context) {
        // CE reads replaceability while constructing BlockPlaceContext, before place().
        try (var guard = KaleidoscopeCookeryPlugin.tryEnterSourceCropResources()) {
            return guard == null ? InteractionResult.FAIL : super.useOnBlock(context);
        }
    }
}
