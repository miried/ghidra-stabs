package stabs.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal ELF section reader, for reading {@code .stab}/{@code .stabstr} outside of Ghidra
 * (tests and the dump tool). Supports 32 and 64 bit, either endianness.
 */
public final class ElfSections {
	private final byte[] data;
	private final boolean bigEndian;
	private final Map<String, long[]> sections = new LinkedHashMap<>(); // name -> {offset, size}

	private ElfSections(byte[] data) {
		this.data = data;
		if (data.length < 0x34 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' ||
			data[3] != 'F') {
			throw new IllegalArgumentException("not an ELF file");
		}
		boolean is64 = data[4] == 2;
		bigEndian = data[5] == 2;
		ByteBuffer b = ByteBuffer.wrap(data)
				.order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
		long shoff = is64 ? b.getLong(0x28) : Integer.toUnsignedLong(b.getInt(0x20));
		int shentsize = Short.toUnsignedInt(b.getShort(is64 ? 0x3a : 0x2e));
		int shnum = Short.toUnsignedInt(b.getShort(is64 ? 0x3c : 0x30));
		int shstrndx = Short.toUnsignedInt(b.getShort(is64 ? 0x3e : 0x32));

		long[][] hdrs = new long[shnum][];
		for (int i = 0; i < shnum; i++) {
			int h = (int) (shoff + (long) i * shentsize);
			long nameOff = Integer.toUnsignedLong(b.getInt(h));
			long off = is64 ? b.getLong(h + 0x18) : Integer.toUnsignedLong(b.getInt(h + 0x10));
			long size = is64 ? b.getLong(h + 0x20) : Integer.toUnsignedLong(b.getInt(h + 0x14));
			hdrs[i] = new long[] { nameOff, off, size };
		}
		long strtab = hdrs[shstrndx][1];
		for (long[] h : hdrs) {
			int start = (int) (strtab + h[0]);
			int end = start;
			while (data[end] != 0) {
				end++;
			}
			sections.putIfAbsent(new String(data, start, end - start, StandardCharsets.US_ASCII),
				new long[] { h[1], h[2] });
		}
	}

	public static ElfSections read(Path path) throws IOException {
		return new ElfSections(Files.readAllBytes(path));
	}

	public boolean isBigEndian() {
		return bigEndian;
	}

	/** @return the section's contents, or null if there is no such section */
	public byte[] section(String name) {
		long[] s = sections.get(name);
		if (s == null) {
			return null;
		}
		byte[] out = new byte[(int) s[1]];
		System.arraycopy(data, (int) s[0], out, 0, out.length);
		return out;
	}
}
