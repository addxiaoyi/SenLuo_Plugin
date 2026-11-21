package net.kaleidoscope.music;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record MidiSong(List<Note> notes, long durationTicks) {
    private static final int MAX_EVENTS = 200_000;
    private static final int MAX_NOTES = 30_000;
    private static final long MAX_MICROS = 1_800_000_000L;
    private static final long MICROS_PER_SERVER_TICK = 50_000;
    private static final int MAX_NOTES_PER_TICK = 64;

    public record Note(long tick, int key, int velocity, int program, boolean percussion) {
        public Note {
            if (tick < 0 || key < 0 || key > 127 || velocity < 1 || velocity > 127 || program < 0 || program > 127)
                throw new IllegalArgumentException("MIDI 音符无效。");
        }
    }

    public MidiSong {
        notes = List.copyOf(notes);
        if (notes.isEmpty()) throw new IllegalArgumentException("MIDI 没有可播放的音符。");
        if (notes.size() > MAX_NOTES) throw new IllegalArgumentException("MIDI 音符过多。");
        if (durationTicks < 0 || durationTicks > MAX_MICROS / MICROS_PER_SERVER_TICK)
            throw new IllegalArgumentException("MIDI 时长无效。");
        long previousTick = -1;
        int simultaneous = 0;
        for (Note note : notes) {
            if (note.tick() < previousTick || note.tick() > durationTicks)
                throw new IllegalArgumentException("MIDI 音符顺序或时长无效。");
            simultaneous = note.tick() == previousTick ? simultaneous + 1 : 1;
            if (simultaneous > MAX_NOTES_PER_TICK)
                throw new IllegalArgumentException("该 MIDI 同时发出的音符过多。");
            previousTick = note.tick();
        }
    }

    public static MidiSong read(Sequence sequence) {
        if (sequence.getResolution() <= 0) throw new IllegalArgumentException("MIDI 分辨率无效。");
        List<MidiEvent> events = new ArrayList<>();
        for (Track track : sequence.getTracks()) {
            if (events.size() + (long) track.size() > MAX_EVENTS) throw new IllegalArgumentException("MIDI 事件过多。");
            for (int i = 0; i < track.size(); i++) events.add(track.get(i));
        }
        events.sort(Comparator.comparingLong(MidiEvent::getTick));
        int[] programs = new int[16];
        int tempo = 500_000;
        long previousTick = 0;
        boolean ppq = sequence.getDivisionType() == Sequence.PPQ;
        long denominator = sequence.getResolution();
        long numerator = 1_000_000;
        if (!ppq) {
            if (sequence.getDivisionType() == Sequence.SMPTE_30DROP) {
                denominator *= 30_000;
                numerator *= 1_001;
            } else denominator *= (long) sequence.getDivisionType();
        }
        Clock clock = new Clock(denominator);
        List<Note> notes = new ArrayList<>();
        for (MidiEvent event : events) {
            long delta = event.getTick() - previousTick;
            if (delta < 0) throw new IllegalArgumentException("MIDI 时间戳无效。");
            clock.advance(delta, ppq ? tempo : numerator);
            previousTick = event.getTick();
            if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                byte[] bytes = meta.getData();
                if (bytes.length != 3) throw new IllegalArgumentException("MIDI 速度事件无效。");
                tempo = ((bytes[0] & 255) << 16) | ((bytes[1] & 255) << 8) | (bytes[2] & 255);
                if (tempo == 0) throw new IllegalArgumentException("MIDI 速度不能为零。");
            }
            if (!(event.getMessage() instanceof ShortMessage message)) continue;
            int channel = message.getChannel();
            if (message.getCommand() == ShortMessage.PROGRAM_CHANGE) programs[channel] = message.getData1();
            if (message.getCommand() != ShortMessage.NOTE_ON || message.getData2() == 0) continue;
            if (notes.size() == MAX_NOTES) throw new IllegalArgumentException("MIDI 音符过多。");
            notes.add(new Note(clock.tick(), message.getData1(),
                    message.getData2(), programs[channel], channel == 9));
        }
        return new MidiSong(notes, clock.durationTicks());
    }

    /** Preserve fractions across tempo changes and unrelated events at MIDI tick boundaries. */
    private static final class Clock {
        private final long denominator;
        private long micros, remainder;

        private Clock(long denominator) { this.denominator = denominator; }

        private void advance(long delta, long numerator) {
            long whole = delta / denominator;
            if (whole > (MAX_MICROS - micros) / numerator) tooLong();
            long extra, rest;
            try {
                long fraction = Math.addExact(Math.multiplyExact(delta % denominator, numerator), remainder);
                extra = fraction / denominator;
                rest = fraction % denominator;
            } catch (ArithmeticException overflow) {
                // Extremely large SMPTE resolutions can overflow the fractional product.
                var fraction = BigInteger.valueOf(delta % denominator).multiply(BigInteger.valueOf(numerator))
                        .add(BigInteger.valueOf(remainder)).divideAndRemainder(BigInteger.valueOf(denominator));
                extra = fraction[0].longValueExact();
                rest = fraction[1].longValueExact();
            }
            micros += whole * numerator + extra;
            remainder = rest;
            if (micros > MAX_MICROS || micros == MAX_MICROS && remainder != 0) tooLong();
        }

        private long tick() { return micros / MICROS_PER_SERVER_TICK; }
        private long durationTicks() {
            return (micros + MICROS_PER_SERVER_TICK - 1 + (remainder == 0 ? 0 : 1)) / MICROS_PER_SERVER_TICK;
        }
        private static void tooLong() { throw new IllegalArgumentException("MIDI 超过 30 分钟。"); }
    }

    public static float pitch(int key) {
        if (key < 0 || key > 127) throw new IllegalArgumentException("Invalid MIDI key: " + key);
        while (key < 54) key += 12;
        while (key > 78) key -= 12;
        return (float) Math.pow(2, (key - 66) / 12d);
    }

    public static String sound(Note note) {
        if (note.percussion()) return note.key() < 40 ? "block.note_block.basedrum" : "block.note_block.snare";
        int family = note.program() / 8;
        return "block.note_block." + switch (family) {
            case 1 -> "bell";
            case 2, 5 -> "bit";
            case 3 -> "guitar";
            case 4 -> "bass";
            case 6, 7, 8 -> "flute";
            case 9, 10 -> "chime";
            default -> "harp";
        };
    }
}
