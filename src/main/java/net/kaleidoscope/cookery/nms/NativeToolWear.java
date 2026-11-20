package net.kaleidoscope.cookery.nms;

import java.util.Objects;
import net.momirealms.craftengine.core.item.Item;

/** A fully attributed native main-hand transition; the companion validates components and object identity. */
public record NativeToolWear(Object nativeIdentity, Item beforeUnit, int selectedSlot, int beforeCount, int afterCount,
                             int beforeDamage, int finalEventDamage, int afterDamage, int maxDamage, boolean brokeOne) {
    public NativeToolWear {
        Objects.requireNonNull(nativeIdentity, "nativeIdentity");
        Objects.requireNonNull(beforeUnit, "beforeUnit");
        if (selectedSlot < 0 || selectedSlot > 8 || beforeCount <= 0 || afterCount < 0
                || beforeDamage < 0 || afterDamage < 0 || maxDamage <= 0
                || (brokeOne ? afterCount != beforeCount - 1 || (long) beforeDamage + finalEventDamage < maxDamage || afterDamage != maxDamage
                        : afterCount != beforeCount || afterDamage != Math.max(0L, (long) beforeDamage + finalEventDamage)
                        || (long) beforeDamage + finalEventDamage >= maxDamage))
            throw new IllegalArgumentException("Invalid native main-hand damage transition");
    }
}
