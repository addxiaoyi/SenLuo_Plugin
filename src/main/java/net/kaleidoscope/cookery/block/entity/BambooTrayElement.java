package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.entity.render.ItemDisplayPackets;
import net.kaleidoscope.cookery.block.entity.render.ItemDisplaySet;
import net.kaleidoscope.cookery.block.entity.render.TrackedPlayers;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.render.element.BlockEntityElement;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.joml.Quaternionf;

import java.util.List;

public final class BambooTrayElement implements BlockEntityElement {
    private static final float DEGREES_TO_RADIANS = (float) Math.PI / 180;
    private final BambooTrayController controller;
    private final ItemDisplaySet displays = new ItemDisplaySet(BambooTrayLayout.SLOTS * BambooTrayLayout.MAX_COPIES);
    private final Item[] previousItems = new Item[BambooTrayLayout.SLOTS];
    private final int[] shownCopies = new int[BambooTrayLayout.SLOTS];
    private final List<List<BambooTrayLayout.Copy>> positions;

    BambooTrayElement(BambooTrayController controller) {
        this.controller = controller;
        var pos = controller.blockEntity().pos;
        positions = java.util.stream.IntStream.range(0, BambooTrayLayout.SLOTS)
                .mapToObj(slot -> BambooTrayLayout.positions(pos.x(), pos.y(), pos.z(), slot)).toList();
    }

    @Override
    public void activate() {
        refresh();
    }

    void refresh() {
        BlockEntity entity = controller.blockEntity();
        for (int slot = 0; slot < BambooTrayLayout.SLOTS; slot++) {
            Item item = controller.item(slot);
            Item previous = previousItems[slot];
            if (item.isEmpty() && previous == null) continue;
            if (!item.isEmpty() && previous != null && previous.count() == item.count() && previous.isSimilar(item)) continue;

            int copies = item.isEmpty() ? 0 : BambooTrayLayout.copies(item.count());
            int start = slot * BambooTrayLayout.MAX_COPIES;
            Item snapshot = copies == 0 ? null : item.copy();
            for (int copy = 0; copy < copies; copy++) {
                var position = positions.get(slot).get(copy);
                WorldPosition pos = new WorldPosition(null,
                        entity.pos.x() + position.x(), entity.pos.y() + position.y(), entity.pos.z() + position.z());
                var rotation = new Quaternionf().rotateX(90 * DEGREES_TO_RADIANS)
                        .rotateZ(position.angleDegrees() * DEGREES_TO_RADIANS);
                ItemDisplayPackets packets = ItemDisplayPackets.at(pos).item(snapshot).scale(BambooTrayLayout.SCALE)
                        .itemTransform(BambooTrayLayout.FIXED_TRANSFORM).leftRotation(rotation);
                int index = start + copy;
                displays.setPackets(index, packets.spawn(displays.id(index), displays.uuid(index)), packets.meta(displays.id(index)));
            }
            for (int copy = copies; copy < shownCopies[slot]; copy++) displays.clear(start + copy);
            shownCopies[slot] = copies;
            previousItems[slot] = snapshot;
        }
    }

    @Override
    public void show(Player player) {
        displays.show(player);
    }

    @Override
    public void update(Player player) {
        displays.update(player);
    }

    @Override
    public void hide(Player player) {
        displays.hide(player);
    }

    @Override
    public void deactivate() {
        if (controller.blockEntity().world != null) {
            TrackedPlayers.forEach(controller.blockEntity(), displays::hide);
        }
    }
}
