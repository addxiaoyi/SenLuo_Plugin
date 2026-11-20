package net.kaleidoscope.cookery.block.entity;

import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import net.kaleidoscope.cookery.block.entity.render.ItemDisplayPackets;
import net.kaleidoscope.cookery.block.entity.render.ItemDisplaySet;

public final class SteamerElement implements BlockEntityElement {
    private static final int SLOTS = 8;

    private final SteamerController controller;
    private final WorldPosition basePos;
    private final ItemDisplaySet display = new ItemDisplaySet(SLOTS);


    public SteamerElement(@NotNull SteamerController controller, @NotNull WorldPosition position) {
        this.controller = controller;
        this.basePos = position;
        refreshMetaPackets(false);
    }

    private void refreshMetaPackets(boolean covered) {
        Item[] items = controller.getItems();
        for (int i = 0; i < SLOTS; i++) {
            if (!covered && !items[i].isEmpty()) {
                float xOffset = (i % 2) * 0.3f - 0.15f;
                float yOffset = (i / 4) * 0.5f + 0.15f + (i % 4) * 0.01f;
                float zOffset = ((i / 2) % 2) * 0.3f - 0.15f;

                ItemDisplayPackets packets = ItemDisplayPackets.at(basePos)
                        .item(items[i])
                        .scale(0.5f)
                        .translation(xOffset, yOffset, zOffset)
                        .leftRotation(new Quaternionf().rotateX((float) Math.toRadians(-90)))
                        .itemTransform((byte) 8);

                display.setPackets(i, packets.spawn(display.id(i), display.uuid(i)), packets.meta(display.id(i)));
            } else {
                display.clear(i);
            }
        }
    }

    public void prepareUpdate() {
        refreshMetaPackets(controller.isCovered());
    }

    @Override public void update(@NotNull Player player) { display.update(player); }
    @Override public void show(@NotNull Player player) { display.show(player); }

    @Override
    public void hide(@NotNull Player player) {
        display.hide(player);
    }
}