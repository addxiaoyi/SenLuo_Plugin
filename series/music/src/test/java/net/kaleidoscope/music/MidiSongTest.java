package net.kaleidoscope.music;

import org.junit.jupiter.api.Test;
import javax.sound.midi.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MidiSongTest {
    @Test void noteOffAndTempoChangesDetermineHeldDuration() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 480);
        Track track = sequence.createTrack();
        note(track, 0, 60, 100);
        track.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x0f, 0x42, 0x40}, 3), 480));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 960));
        assertEquals(30, MidiSong.read(sequence).notes().getFirst().durationTicks());
    }

    @Test void pedalReleaseEndsOnlyItsChannelAndRepeatedKeysUseFifo() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 100);
        Track track = sequence.createTrack();
        note(track, 0, 60, 100);
        note(track, 50, 60, 80);
        track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 64, 127), 60));
        note(track, 100, 60, 0);
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 1, 60, 90), 100));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 1, 60, 0), 150));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 64, 0), 200));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 300));
        var notes = MidiSong.read(sequence).notes();
        assertEquals(20, notes.get(0).durationTicks());
        assertEquals(25, notes.get(1).durationTicks());
        assertEquals(5, notes.get(2).durationTicks());
    }

    @Test void allSoundOffStopsHeldPedalVoices() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 100);
        Track track = sequence.createTrack();
        note(track, 0, 60, 100);
        track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 64, 127), 1));
        note(track, 50, 60, 0);
        track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 120, 0), 100));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 64, 0), 200));
        assertEquals(10, MidiSong.read(sequence).notes().getFirst().durationTicks());
    }

    @Test void mergesTempoTrackAndNoteTracksInTickOrder() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 480);
        Track tempo = sequence.createTrack();
        tempo.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x0f, 0x42, 0x40}, 3), 480));
        Track notes = sequence.createTrack();
        note(notes, 0, 60, 127);
        note(notes, 480, 62, 100);
        note(notes, 960, 64, 90);
        MidiSong song = MidiSong.read(sequence);
        assertArrayEquals(new long[]{0, 10, 30}, song.notes().stream().mapToLong(MidiSong.Note::tick).toArray());
    }

    @Test void velocityZeroNoteOnIsNotAnotherNote() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 96);
        Track track = sequence.createTrack();
        note(track, 0, 60, 127);
        note(track, 96, 60, 0);
        assertEquals(1, MidiSong.read(sequence).notes().size());
    }

    @Test void unmatchedNoteOffDoesNotCreateOrShortenAnotherVoice() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 100);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 0));
        note(track, 10, 60, 100);
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 61, 0), 20));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 30));
        var parsed = MidiSong.read(sequence).notes();
        assertEquals(1, parsed.size());
        assertEquals(2, parsed.getFirst().durationTicks());
    }

    @Test void programChangesStayOnTheirOwnChannels() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 96);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 24, 0), 0));
        note(track, 1, 60, 127);
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 1, 60, 127), 2));
        var notes = MidiSong.read(sequence).notes();
        assertEquals(24, notes.get(0).program());
        assertEquals(0, notes.get(1).program());
    }

    @Test void supportsSmpteTiming() throws Exception {
        Sequence sequence = new Sequence(Sequence.SMPTE_24, 10);
        note(sequence.createTrack(), 240, 60, 127);
        assertEquals(20, MidiSong.read(sequence).notes().getFirst().tick());
    }

    @Test void rejectsEmptyAndInvalidTempoSongs() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> MidiSong.read(new Sequence(Sequence.PPQ, 96)));
        Sequence sequence = new Sequence(Sequence.PPQ, 96);
        sequence.createTrack().add(new MidiEvent(new MetaMessage(0x51, new byte[3], 3), 0));
        assertThrows(IllegalArgumentException.class, () -> MidiSong.read(sequence));
    }

    @Test void rejectsExcessiveDurationBeforePlaying() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 96);
        note(sequence.createTrack(), Long.MAX_VALUE, 60, 127);
        assertThrows(IllegalArgumentException.class, () -> MidiSong.read(sequence));
    }

    @Test void foldsOctavesIntoTheVanillaPitchRange() {
        for (int key = 0; key < 128; key++) assertTrue(MidiSong.pitch(key) >= 0.5f && MidiSong.pitch(key) <= 2f);
        assertEquals(1f, MidiSong.pitch(66));
        assertThrows(IllegalArgumentException.class, () -> MidiSong.pitch(128));
    }

    @Test void irrelevantEventsCannotMoveAnExactlyTimedNoteIntoThePreviousServerTick() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 480);
        Track track = sequence.createTrack();
        for (int tick = 0; tick < 48; tick++)
            track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 1, 0), tick));
        note(track, 48, 60, 100);
        MidiSong song = MidiSong.read(sequence);
        assertEquals(1, song.notes().getFirst().tick());
        assertEquals(1, song.durationTicks());
    }

    @Test void preservesFractionalTimeAcrossTempoChanges() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 3);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x0f, 0x42, 0x40}, 3), 1));
        note(track, 2, 60, 100);
        MidiSong song = MidiSong.read(sequence);
        assertEquals(10, song.notes().getFirst().tick());
        assertEquals(10, song.durationTicks());
    }

    @Test void dropFrameUsesItsExactRationalRate() throws Exception {
        Sequence sequence = new Sequence(Sequence.SMPTE_30DROP, 1);
        note(sequence.createTrack(), 1500, 60, 100);
        MidiSong song = MidiSong.read(sequence);
        assertEquals(1001, song.notes().getFirst().tick());
        assertEquals(1001, song.durationTicks());
    }

    @Test void largeSmpteResolutionsDoNotOverflowFractionalTime() throws Exception {
        Sequence sequence = new Sequence(Sequence.SMPTE_30DROP, Integer.MAX_VALUE);
        note(sequence.createTrack(), 30L * Integer.MAX_VALUE, 60, 100);
        MidiSong song = MidiSong.read(sequence);
        assertEquals(20, song.notes().getFirst().tick());
        assertEquals(21, song.durationTicks());
    }

    @Test void excessiveQuantizedDensityIsRejectedBeforeAnyNoteCanPlay() throws Exception {
        Sequence permitted = new Sequence(Sequence.PPQ, 960);
        for (int i = 0; i < 64; i++) note(permitted.createTrack(), i, 60, 100);
        assertEquals(64, MidiSong.read(permitted).notes().size());
        Sequence rejected = new Sequence(Sequence.PPQ, 960);
        for (int i = 0; i < 65; i++) note(rejected.createTrack(), i, 60, 100);
        assertThrows(IllegalArgumentException.class, () -> MidiSong.read(rejected));
    }

    @Test void thirtyMinuteBoundaryIsInclusiveAndDoesNotOverflow() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 1);
        Track track = sequence.createTrack();
        note(track, 3600, 60, 100);
        assertEquals(36000, MidiSong.read(sequence).durationTicks());
        note(track, 3601, 62, 100);
        assertThrows(IllegalArgumentException.class, () -> MidiSong.read(sequence));
    }

    @Test void publicSongValuesCannotBypassTimingAndNoteValidation() {
        var first = new MidiSong.Note(0, 60, 100, 0, false);
        var later = new MidiSong.Note(1, 62, 100, 0, false);
        assertThrows(IllegalArgumentException.class, () -> new MidiSong(List.of(later, first), 1));
        assertThrows(IllegalArgumentException.class, () -> new MidiSong(List.of(later), 0));
        assertThrows(IllegalArgumentException.class, () -> new MidiSong(List.of(first), 36001));
        assertThrows(IllegalArgumentException.class, () -> new MidiSong.Note(0, 60, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new MidiSong.Note(0, 60, 100, 128, false));
        assertThrows(IllegalArgumentException.class, () -> new MidiSong.Note(0, 60, 100, 0, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new MidiSong.Note(0, 60, 100, 0, false, 36_001));
    }

    private static void note(Track track, long tick, int key, int velocity) throws Exception {
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, key, velocity), tick));
    }
}
