package stabs.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the raw bytes of a {@code .stab}/{@code .stabstr} section pair into {@link StabEntry}s.
 * <p>
 * Handles the ELF ("Sun") layout, where each compilation unit's stabs start with an N_UNDF
 * header whose value is the size of that unit's chunk of the string table and whose string
 * offsets are relative to that chunk. The header's n_desc entry count is 16 bits and overflows
 * when the linker merges units, so it is not used. Entries whose string ends in a backslash are
 * continued by the next entry and are joined.
 */
public final class StabSectionReader {
	public static final int ENTRY_SIZE = 12;

	private StabSectionReader() {
	}

	public static List<StabEntry> read(byte[] stab, byte[] stabstr, boolean bigEndian) {
		ByteBuffer buf = ByteBuffer.wrap(stab)
				.order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		int count = stab.length / ENTRY_SIZE;
		List<StabEntry> entries = new ArrayList<>(count);

		long strBase = 0;
		long nextStrBase = 0;
		StringBuilder continued = null;
		int contIndex = 0;
		int contType = 0, contOther = 0, contDesc = 0;
		long contValue = 0;

		for (int i = 0; i < count; i++) {
			int off = i * ENTRY_SIZE;
			long strx = Integer.toUnsignedLong(buf.getInt(off));
			int type = Byte.toUnsignedInt(buf.get(off + 4));
			int other = Byte.toUnsignedInt(buf.get(off + 5));
			int desc = Short.toUnsignedInt(buf.getShort(off + 6));
			long value = Integer.toUnsignedLong(buf.getInt(off + 8));

			if (type == StabType.N_UNDF) {
				strBase = nextStrBase;
				nextStrBase = strBase + value;
				continue;
			}

			String s = strx == 0 ? "" : cString(stabstr, strBase + strx);

			if (continued != null) {
				continued.append(s);
				if (endsWithContinuation(continued)) {
					continued.setLength(continued.length() - 1);
					continue;
				}
				entries.add(new StabEntry(contIndex, contType, contOther, contDesc, contValue,
					continued.toString()));
				continued = null;
				continue;
			}

			if (endsWithContinuation(s)) {
				continued = new StringBuilder(s.substring(0, s.length() - 1));
				contIndex = i;
				contType = type;
				contOther = other;
				contDesc = desc;
				contValue = value;
				continue;
			}
			entries.add(new StabEntry(i, type, other, desc, value, s));
		}
		if (continued != null) {
			entries.add(new StabEntry(contIndex, contType, contOther, contDesc, contValue,
				continued.toString()));
		}
		return entries;
	}

	private static boolean endsWithContinuation(CharSequence s) {
		return s.length() > 0 && s.charAt(s.length() - 1) == '\\';
	}

	private static String cString(byte[] data, long offset) {
		if (offset < 0 || offset >= data.length) {
			return "";
		}
		int start = (int) offset;
		int end = start;
		while (end < data.length && data[end] != 0) {
			end++;
		}
		return new String(data, start, end - start, StandardCharsets.ISO_8859_1);
	}
}
