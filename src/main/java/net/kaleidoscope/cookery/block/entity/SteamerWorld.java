package net.kaleidoscope.cookery.block.entity;

import net.kaleidoscope.cookery.nms.NmsBridgeProvider;
import net.kaleidoscope.cookery.util.HeatSourceUtils;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.proxy.minecraft.core.DirectionProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.state.BlockBehaviourProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.FallingBlockProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.SupportTypeProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.block.state.StateHolderProxy;
import org.bukkit.Bukkit;

/** Source face/support rules, shared by placement, interaction and falling. */
public final class SteamerWorld {
    private SteamerWorld() {}
    public static boolean accessible(World level, BlockPos pos) {
        var world=(org.bukkit.World)level.platformWorld();
        return pos.y()>=world.getMinHeight() && pos.y()<world.getMaxHeight()
                && Bukkit.isOwnedByCurrentRegion(world,pos.x()>>4,pos.z()>>4) && world.isChunkLoaded(pos.x()>>4,pos.z()>>4);
    }
    public static boolean faceSturdy(World level, BlockPos pos, Direction face) {
        Object nativePos=LocationUtils.toBlockPos(pos), nativeLevel=level.minecraftWorld();
        return BlockBehaviourProxy.BlockStateBaseProxy.INSTANCE.isFaceSturdy(BlockGetterProxy.INSTANCE.getBlockState(nativeLevel,nativePos),
                nativeLevel,nativePos,face==Direction.DOWN?DirectionProxy.DOWN:DirectionProxy.UP,SupportTypeProxy.FULL);
    }
    public static boolean sameBlock(World level, BlockPos pos, BlockDefinition definition) {
        var state=level.getBlock(pos).customBlockState();return state!=null && state.owner().value()==definition;
    }
    public static boolean hasBase(Object level, Object below, BlockDefinition definition) {
        Object state=BlockGetterProxy.INSTANCE.getBlockState(level,below);
        if(FallingBlockProxy.INSTANCE.isFree(state))return false;
        var custom=BlockStateUtils.getOptionalCustomBlockState(state).orElse(null);
        if(custom!=null && custom.owner().value()==definition)return false;
        boolean hasLit=StateHolderProxy.INSTANCE.hasProperty(state,NmsBridgeProvider.bridge().litBlockStateProperty())
                || custom!=null && custom.getProperty("lit")!=null;
        return hasLit || HeatSourceUtils.isHeatSource(level,below)
                || !BlockBehaviourProxy.BlockStateBaseProxy.INSTANCE.isFaceSturdy(state,level,below,DirectionProxy.UP,SupportTypeProxy.FULL);
    }
    public static boolean landingSupported(Object level, Object below) {
        return !FallingBlockProxy.INSTANCE.isFree(BlockGetterProxy.INSTANCE.getBlockState(level,below));
    }
}
