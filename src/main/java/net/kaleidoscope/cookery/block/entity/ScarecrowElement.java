package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.block.behavior.ScarecrowBehavior;
import net.kaleidoscope.cookery.block.entity.render.ItemDisplayPackets;
import net.kaleidoscope.cookery.block.entity.render.ItemDisplaySet;
import net.kaleidoscope.cookery.item.ItemKeys;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElement;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.IntConsumer;
import java.util.ArrayList;
import java.util.List;

// 稻草人本体与头颅 两手物品
// 戴上头颅时本体换成无头模型
public final class ScarecrowElement implements FurnitureElement {
    public static final int SLOT_HEAD = 0;
    public static final int SLOT_MAIN_HAND = 1;
    public static final int SLOT_OFF_HAND = 2;
    public static final int SLOTS = 3;

    private static final int DISPLAY_BODY = 3;
    private static final int DISPLAY_HAT = 4;
    private static final int DISPLAYS = 5;
    private static final byte ITEM_TRANSFORM_NONE = (byte) 0;

    private final ScarecrowController controller;
    private final ScarecrowBehavior behavior;
    private final ItemDisplaySet displays = new ItemDisplaySet(DISPLAYS);
    private record PartPose(Vector3f spawnOffset, Vector3f translation, Quaternionf rotation) {}
    private volatile PartPose[] poses = new PartPose[DISPLAYS];
    private volatile float wobble;

    public ScarecrowElement(ScarecrowController controller, ScarecrowBehavior behavior) {
        this.controller = controller;
        this.behavior = behavior;
    }

    public void rebuild() {
        wobble = controller.wobbleAngle();
        WorldPosition base = this.controller.furniture().position();
        double yaw = Math.toRadians(base.yRot());
        buildBody(base, yaw);
        buildHat(base, yaw);
        for (int slot = 0; slot < SLOTS; slot++) {
            Item item = this.controller.item(slot);
            if (ItemUtils.isEmpty(item)) {
                this.displays.clear(slot);
                continue;
            }
            buildSlot(base, yaw, slot, item);
        }
    }

    private void buildBody(WorldPosition base, double yaw) {
        Item body = InventoryUtils.createOrEmpty(
                ItemUtils.isEmpty(this.controller.item(SLOT_HEAD)) ? ItemKeys.SHOW_SCARECROW : ItemKeys.SHOW_SCARECROW_HEADLESS);
        if (ItemUtils.isEmpty(body)) {
            this.displays.clear(DISPLAY_BODY);
            return;
        }
        this.displays.setPackets(DISPLAY_BODY,
                ItemDisplayPackets.at(offsetOf(base, this.behavior.bodyOffset))
                        .spawn(this.displays.id(DISPLAY_BODY), this.displays.uuid(DISPLAY_BODY)),
                ItemDisplayPackets.builder()
                        .item(body)
                        .scale(this.behavior.bodyScale)
                        .leftRotation(new Quaternionf().rotateY((float) -yaw))
                        .itemTransform(ITEM_TRANSFORM_NONE)
                        .meta(this.displays.id(DISPLAY_BODY)));
        remember(DISPLAY_BODY, base, offsetOf(base, behavior.bodyOffset), new Vector3f(), new Quaternionf().rotateY((float)-yaw));
    }

    private void buildHat(WorldPosition base,double yaw) {
        if (behavior.hatItem == null || !ItemUtils.isEmpty(controller.item(SLOT_HEAD))) {
            displays.clear(DISPLAY_HAT);return;
        }
        Item hat=InventoryUtils.createOrEmpty(behavior.hatItem);
        if(ItemUtils.isEmpty(hat)){displays.clear(DISPLAY_HAT);return;}
        Quaternionf roll=new Quaternionf().rotateZ(behavior.hatRoll);
        Vector3f pivot=behavior.hatPivot;
        Vector3f translation=new Vector3f(pivot).sub(roll.transform(new Vector3f(pivot))).rotateY((float)-yaw);
        var packets=ItemDisplayPackets.at(offsetOf(base,behavior.bodyOffset)).item(hat).scale(behavior.bodyScale)
                .translation(translation.x,translation.y,translation.z)
                .leftRotation(new Quaternionf().rotateY((float)-yaw).mul(roll)).itemTransform(ITEM_TRANSFORM_NONE);
        displays.setPackets(DISPLAY_HAT,packets.spawn(displays.id(DISPLAY_HAT),displays.uuid(DISPLAY_HAT)),packets.meta(displays.id(DISPLAY_HAT)));
        remember(DISPLAY_HAT,base,offsetOf(base,behavior.bodyOffset),translation,new Quaternionf().rotateY((float)-yaw).mul(roll));
    }

    private void buildSlot(WorldPosition base, double yaw, int slot, Item item) {
        ScarecrowBehavior.SlotConfig config = this.behavior.slots[slot];
        Item display = slot == SLOT_OFF_HAND ? lanternDisplay(item) : item;
        if (ItemUtils.isEmpty(display)) {
            this.displays.clear(slot);
            return;
        }
        float rotation = (float) -yaw + (float) Math.toRadians(config.yawOffsetDegrees());
        // 灯笼那格已经放了真的 light 方块 展示实体吃所在格的光照就够亮 不用再覆盖亮度
        this.displays.setPackets(slot,
                ItemDisplayPackets.at(offsetOf(base, config.position()))
                        .spawn(this.displays.id(slot), this.displays.uuid(slot)),
                ItemDisplayPackets.builder()
                        .item(display)
                        .scale(config.scale())
                        .leftRotation(new Quaternionf().rotateY(rotation))
                        .itemTransform(config.itemTransform())
                        .meta(this.displays.id(slot)));
        remember(slot,base,offsetOf(base,config.position()),new Vector3f(),new Quaternionf().rotateY(rotation));
    }

    private void remember(int slot, WorldPosition base, WorldPosition spawn, Vector3f translation, Quaternionf rotation) {
        PartPose[] next=poses.clone();
        next[slot]=new PartPose(new Vector3f((float)(spawn.x-base.x),(float)(spawn.y-base.y),(float)(spawn.z-base.z)),
                new Vector3f(translation),new Quaternionf(rotation));
        poses=next;
    }

    private List<Object> wobblePackets(int duration) {
        List<Object> packets=new ArrayList<>();PartPose[] current=poses;
        for(int slot=0;slot<DISPLAYS;slot++) {
            PartPose pose=current[slot];if(pose==null||displays.meta(slot)==null)continue;
            var transformed=ScarecrowWobble.transform(pose.spawnOffset,pose.translation,pose.rotation,wobble);
            Vector3f t=transformed.translation();
            packets.add(ItemDisplayPackets.builder().translation(t.x,t.y,t.z).leftRotation(transformed.rotation())
                    .interpolation(duration,0).meta(displays.id(slot)));
        }
        return packets;
    }

    public void applyWobble(float degrees,Iterable<Player> recipients,int duration) {
        wobble=degrees;
        List<Object> packets=wobblePackets(duration);
        for(Player player:recipients)player.sendPackets(packets,false);
    }

    // 偏移要跟着家具朝向转 交给 CE 算 自己拼三角函数容易和碰撞箱对不齐
    private static WorldPosition offsetOf(WorldPosition base, org.joml.Vector3f relative) {
        Vec3d position = Furniture.getRelativePosition(base, relative);
        return new WorldPosition(base.world(), position.x, position.y, position.z);
    }

    private static Item lanternDisplay(Item item) {
        Key id = item.vanillaId();
        if (ItemKeys.SOUL_LANTERN.equals(id)) {
            return InventoryUtils.createOrEmpty(ItemKeys.SHOW_SCARECROW_SOUL_LANTERN);
        }
        if (ItemKeys.LANTERN.equals(id)) {
            return InventoryUtils.createOrEmpty(ItemKeys.SHOW_SCARECROW_LANTERN);
        }
        return item;
    }

    @Override
    public void gatherInteractableEntityId(IntConsumer collector) {
    }

    @Override
    public void show(Player player) {
        // 家具刚放下时 CE 可能先让玩家追踪再走 onLoad 这里兜底补建
        if (this.displays.spawn(DISPLAY_BODY) == null) {
            rebuild();
        }
        this.displays.show(player);
        player.sendPackets(wobblePackets(0),false);
    }

    @Override
    public void hide(Player player) {
        this.displays.hide(player);
    }

    @Override
    public void update(Player player) {
        this.displays.update(player);
        player.sendPackets(wobblePackets(0),false);
    }
}
