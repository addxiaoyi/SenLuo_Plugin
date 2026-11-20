package net.kaleidoscope.music;

import java.util.ArrayList;
import java.util.List;

/** Bounds polyphony and approximates held notes with periodic vanilla note sounds. */
final class MusicTransport {
    private static final int MAX_SOUNDS = 64;
    private static final int SUSTAIN_PERIOD = 4;
    private final MidiSong song;
    private final List<MidiSong.Note> held = new ArrayList<>();
    private int cursor;
    private long previousTick = -1;

    MusicTransport(MidiSong song) { this.song = song; }

    List<MidiSong.Note> tick(long tick) {
        if (tick != previousTick + 1) throw new IllegalArgumentException("Playback ticks must be sequential");
        previousTick = tick;
        List<MidiSong.Note> sounds = new ArrayList<>();
        held.removeIf(note -> tick >= note.tick() + note.durationTicks());
        for (var note : held) if ((tick - note.tick()) % SUSTAIN_PERIOD == 0) sounds.add(note);
        while (cursor < song.notes().size() && song.notes().get(cursor).tick() <= tick) {
            var note = song.notes().get(cursor++);
            sounds.add(note);
            if (!note.percussion() && note.durationTicks() > 1) held.add(note);
            if (sounds.size() > MAX_SOUNDS || held.size() > MAX_SOUNDS)
                throw new IllegalArgumentException("该 MIDI 同时发出的音符过多，播放已停止。");
        }
        return sounds;
    }

    boolean finished(long tick) {
        return cursor == song.notes().size() && held.isEmpty() && tick >= song.durationTicks();
    }
}
