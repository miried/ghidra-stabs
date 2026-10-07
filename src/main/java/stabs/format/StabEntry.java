package stabs.format;

/**
 * One decoded entry of a {@code .stab} section, with its string already resolved.
 *
 * @param index position of the entry in the section (of the first entry, for joined continuations)
 * @param type n_type, see {@link StabType}
 * @param other n_other
 * @param desc n_desc (line number for N_SLINE, unsigned 16 bit)
 * @param value n_value, as stored in the file (link-time address for symbols)
 * @param string the entry's string, or "" if it has none
 */
public record StabEntry(int index, int type, int other, int desc, long value, String string) {

	@Override
	public String toString() {
		return String.format("#%d %s other=%d desc=%d value=0x%x %s", index, StabType.name(type),
			other, desc, value, string);
	}
}
