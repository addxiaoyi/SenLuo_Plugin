package net.kaleidoscope.music;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class MusicTransportTest {
    @Test void sustainsUntilNoteOffAndDoesNotRepeatPercussion() {
        var melodic = new MidiSong.Note(0, 60, 100, 24, false, 9);
        var drum = new MidiSong.Note(0, 38, 100, 0, true, 9);
        var transport = new MusicTransport(new MidiSong(List.of(melodic, drum), 9));
        assertEquals(List.of(melodic, drum), transport.tick(0));
        for (int tick = 1; tick <= 9; tick++) {
            assertEquals(tick == 4 || tick == 8 ? List.of(melodic) : List.of(), transport.tick(tick));
        }
        assertTrue(transport.finished(9));
    }

    @Test void overlappingLongNotesCannotGrowWithoutAPolyphonyLimit() {
        var notes = IntStream.range(0, 65).mapToObj(tick -> new MidiSong.Note(tick, 60, 100, 0, false, 100)).toList();
        var transport = new MusicTransport(new MidiSong(notes, 165));
        for (int tick = 0; tick < 64; tick++) transport.tick(tick);
        assertThrows(IllegalArgumentException.class, () -> transport.tick(64));
    }

    @Test void duplicateTicksCannotRepeatNotes() {
        var transport = new MusicTransport(new MidiSong(List.of(new MidiSong.Note(0, 60, 100, 0, false)), 1));
        transport.tick(0);
        assertThrows(IllegalArgumentException.class, () -> transport.tick(0));
    }
}
