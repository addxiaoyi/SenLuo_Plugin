package net.kaleidoscope.cookery.block.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kaleidoscope.cookery.catalog.CookeryCatalog;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Source keyframe timestamps and destination-key interpolation, sampled in seconds. */
public final class TrashCanAnimation {
    public enum Clip { PUT, WITHDRAW, PLAYER1, PLAYER2, ENTER }
    public record Pose(float bodyY, float lidX, float lidZ, float lidY,
                       float eyeY, float pupilX, float pupilY) {
        public static final Pose REST = new Pose(0,0,0,0,0,0,0);
        Pose plus(Pose other) {
            return new Pose(bodyY+other.bodyY,lidX+other.lidX,lidZ+other.lidZ,lidY+other.lidY,
                    eyeY+other.eyeY,pupilX+other.pupilX,pupilY+other.pupilY);
        }
    }
    private record Frame(float time, float[] vector, boolean cubic) {}
    private record Channel(String part, String target, List<Frame> frames) {}
    private record Definition(float length, List<Channel> channels) {}
    private static final Map<Clip,Definition> DEFINITIONS = load();
    private final EnumMap<Clip,Long> starts = new EnumMap<>(Clip.class);

    public void start(Clip clip,long tick) {
        if(clip==Clip.PLAYER1) starts.remove(Clip.PLAYER2);
        if(clip==Clip.PLAYER2) starts.remove(Clip.PLAYER1);
        starts.put(clip,tick);
    }
    public void stopIdle() { starts.remove(Clip.PLAYER1); starts.remove(Clip.PLAYER2); }
    public void clear() { starts.clear(); }
    public boolean moving(long tick) {
        return starts.entrySet().stream().anyMatch(e -> e.getKey()!=Clip.PLAYER1 && e.getKey()!=Clip.PLAYER2
                && (tick-e.getValue())/20f < DEFINITIONS.get(e.getKey()).length);
    }
    public Pose pose(long tick) {
        Pose result=Pose.REST;
        for(var entry:starts.entrySet())result=result.plus(sample(entry.getKey(),Math.max(0,tick-entry.getValue())/20f));
        return result;
    }
    public static float length(Clip clip) { return DEFINITIONS.get(clip).length; }

    public static Pose sample(Clip clip,float seconds) {
        if(!Float.isFinite(seconds) || seconds<0)throw new IllegalArgumentException("Invalid animation time");
        float bodyY=0,lidX=0,lidZ=0,lidY=0,eyeY=0,pupilX=0,pupilY=0;
        for(Channel channel:DEFINITIONS.get(clip).channels) {
            float[] value=interpolate(channel.frames,seconds);
            switch(channel.part+":"+channel.target) {
                case "bone:ROTATION" -> bodyY+=value[1];
                case "bone2:ROTATION" -> {lidX+=value[0];lidZ+=value[2];}
                case "bone2:POSITION" -> lidY+=value[1];
                case "eye:POSITION" -> eyeY+=value[1];
                case "bone3:POSITION" -> {pupilX+=value[0];pupilY+=value[1];}
                default -> throw new IllegalStateException("Unmapped trash can animation channel");
            }
        }
        return new Pose(bodyY,lidX,lidZ,lidY,eyeY,pupilX,pupilY);
    }

    private static float[] interpolate(List<Frame> frames,float seconds) {
        int right=0;
        while(right<frames.size() && frames.get(right).time<seconds)right++;
        int left=Math.max(0,right-1); right=Math.min(left+1,frames.size()-1);
        Frame from=frames.get(left),to=frames.get(right);
        float alpha=left==right?0:Math.max(0,Math.min(1,(seconds-from.time)/(to.time-from.time)));
        float[] result=new float[3];
        for(int axis=0;axis<3;axis++) {
            float a=from.vector[axis],b=to.vector[axis];
            if(!to.cubic)result[axis]=a+alpha*(b-a);
            else {
                float p=frames.get(Math.max(0,left-1)).vector[axis],q=frames.get(Math.min(frames.size()-1,right+1)).vector[axis];
                // Minecraft Mth.catmullrom uses this four-point polynomial.
                result[axis]=.5f*(2*a+(b-p)*alpha+(2*p-5*a+4*b-q)*alpha*alpha
                        +(3*a-p-3*b+q)*alpha*alpha*alpha);
            }
        }
        return result;
    }

    private static Map<Clip,Definition> load() {
        try(var stream=TrashCanAnimation.class.getResourceAsStream("/trash-can-animations.json")) {
            if(stream==null)throw new IllegalStateException("Missing trash can source animations");
            JsonObject root=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!CookeryCatalog.SOURCE_COMMIT.equals(root.get("sourceCommit").getAsString()))
                throw new IllegalArgumentException("Foreign trash can animations");
            EnumMap<Clip,Definition> definitions=new EnumMap<>(Clip.class);
            for(var entry:root.getAsJsonObject("animations").entrySet()) {
                JsonObject raw=entry.getValue().getAsJsonObject(); List<Channel> channels=new ArrayList<>();
                for(var value:raw.getAsJsonArray("channels")) {
                    JsonObject channel=value.getAsJsonObject();List<Frame> frames=new ArrayList<>();
                    for(var frameValue:channel.getAsJsonArray("frames")) {
                        JsonObject frame=frameValue.getAsJsonObject();var vector=frame.getAsJsonArray("value");
                        String interpolation=frame.get("interpolation").getAsString();
                        if(!Set.of("LINEAR","CATMULLROM").contains(interpolation)||vector.size()!=3)
                            throw new IllegalArgumentException("Invalid animation frame");
                        frames.add(new Frame(frame.get("time").getAsFloat(),new float[]{vector.get(0).getAsFloat(),
                                vector.get(1).getAsFloat(),vector.get(2).getAsFloat()},interpolation.equals("CATMULLROM")));
                    }
                    channels.add(new Channel(channel.get("part").getAsString(),channel.get("target").getAsString(),List.copyOf(frames)));
                }
                definitions.put(Clip.valueOf(entry.getKey()),new Definition(raw.get("length").getAsFloat(),List.copyOf(channels)));
            }
            if(definitions.size()!=Clip.values().length)throw new IllegalArgumentException("Missing source animation");
            return Collections.unmodifiableMap(definitions);
        } catch(java.io.IOException error){throw new IllegalStateException("Cannot load trash can animations",error);}
    }
}
