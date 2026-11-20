package net.kaleidoscope.cookery.util;

import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.momirealms.craftengine.core.entity.furniture.FurniturePersistentData;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;

/** Durable, non-automatic recovery when an already removed furniture cannot return its inventory. */
public final class FurnitureRecovery {
    private FurnitureRecovery() {}
    public static void save(FurnitureController controller,String name) {
        if(!name.matches("[a-z_-]+"))throw new IllegalArgumentException("Invalid recovery name");
        CompoundTag root=new CompoundTag();controller.saveCustomData(root);
        var pos=controller.furniture().position();
        root.putString("recovery_world",((org.bukkit.World)pos.world().platformWorld()).getUID().toString());
        root.putString("recovery_position",pos.x+","+pos.y+","+pos.z);
        root.putString("recovery_furniture",controller.furniture().id().asString());
        var plugin=KaleidoscopeCookeryPlugin.instance();
        Path file=plugin.getDataFolder().toPath().resolve("recovery").resolve(name+"-"+UUID.randomUUID()+".nbt");
        try {
            byte[] bytes=FurniturePersistentData.of(root).toBytes();Files.createDirectories(file.getParent());
            try(var channel=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
        } catch(java.io.IOException error){throw new IllegalStateException("Cannot preserve furniture contents "+file,error);}
        plugin.getLogger().warning("家具移除后的未发放内容已保存至 "+file+"，请核对后人工恢复。");
    }
}
