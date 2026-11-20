package net.kaleidoscope.cookery.item.behavior;

import net.kaleidoscope.cookery.item.LunchBagContents;
import net.kaleidoscope.cookery.item.LunchBagEating;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

// 嬗变饭袋：背包右键装填，世界中右键使用。
public class TransmutationLunchBagBehavior extends ItemBehavior {
    public static final ItemBehaviorFactory<TransmutationLunchBagBehavior> FACTORY = new Factory();


    @Override
    public InteractionResult use(World world, @Nullable Player player, InteractionHand hand) {
        if (player == null) {
            return InteractionResult.PASS;
        }
        Item bag = player.getItemInHand(hand);
        if (!LunchBagContents.isLunchBag(bag)) {
            return InteractionResult.PASS;
        }
        if (LunchBagContents.isEmpty(bag)) {
            return InteractionResult.PASS;
        }
        LunchBagEating.begin(player, hand, bag);
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    // 指向方块时 CE 不会调 use 只调 useOnBlock 行为一致
    // 方块本身可交互(如箱子)且玩家没潜行时 CE 在这之前就 return 了 不会误吃
    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        return use(context.getLevel(), context.getPlayer(), context.getHand());
    }

    private static class Factory implements ItemBehaviorFactory<TransmutationLunchBagBehavior> {
        @Override
        public TransmutationLunchBagBehavior create(Pack pack, Path path, Key key, ConfigSection section) {
            return new TransmutationLunchBagBehavior();
        }
    }
}
