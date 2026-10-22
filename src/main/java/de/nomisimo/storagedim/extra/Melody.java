package de.nomisimo.storagedim.extra;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

/**
 * Procedural note block tune, generated from a seed so every character has its own catchy loop.
 * Used when no resource pack with real audio is installed.
 */
public final class Melody {
	public record Note(int tick, int pitch, NoteBlockInstrument instrument) {
	}

	private static final int[] PENTATONIC = {0, 2, 4, 7, 9};
	private static final NoteBlockInstrument[] LEADS = {
			NoteBlockInstrument.HARP, NoteBlockInstrument.BELL, NoteBlockInstrument.FLUTE, NoteBlockInstrument.XYLOPHONE,
			NoteBlockInstrument.BIT, NoteBlockInstrument.BANJO, NoteBlockInstrument.PLING, NoteBlockInstrument.IRON_XYLOPHONE,
			NoteBlockInstrument.COW_BELL, NoteBlockInstrument.GUITAR, NoteBlockInstrument.CHIME, NoteBlockInstrument.DIDGERIDOO};

	public final List<Note> notes = new ArrayList<>();
	public final int length;

	public Melody(long seed) {
		Random random = new Random(seed);
		int step = 3 + random.nextInt(2); // ticks per eighth note
		NoteBlockInstrument lead = LEADS[random.nextInt(LEADS.length)];
		int root = random.nextInt(5);

		// Motif A and B, each 8 eighths; the song is A A B A, repeated with a bass line.
		int[] a = motif(random);
		int[] b = motif(random);
		int[][] form = {a, a, b, a};
		int t = 0;

		for (int[] bar : form) {
			for (int i = 0; i < bar.length; i++) {
				if (bar[i] >= 0) {
					notes.add(new Note(t, pitch(root, bar[i]), lead));
				}

				if (i % 4 == 0) {
					notes.add(new Note(t, pitch(root, 0) % 12, NoteBlockInstrument.BASS));
					notes.add(new Note(t, 0, NoteBlockInstrument.BASEDRUM));
				} else if (i % 4 == 2) {
					notes.add(new Note(t, 0, NoteBlockInstrument.SNARE));
				}

				t += step;
			}
		}

		length = t + step * 2;
	}

	private static int[] motif(Random random) {
		int[] bar = new int[8];
		int degree = random.nextInt(5);

		for (int i = 0; i < bar.length; i++) {
			if (i > 0 && random.nextInt(5) == 0) {
				bar[i] = -1; // rest
				continue;
			}

			degree = Math.max(0, Math.min(9, degree + random.nextInt(5) - 2));
			bar[i] = degree;
		}

		return bar;
	}

	/** Scale degree (0..9 over two octaves) to note block pitch 0..24. */
	private static int pitch(int root, int degree) {
		int semis = PENTATONIC[degree % 5] + 12 * (degree / 5) + root;
		return Math.min(24, semis);
	}

	public static float pitchToFloat(int note) {
		return (float) Math.pow(2.0, (note - 12) / 12.0);
	}
}
