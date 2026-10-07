package stabs.format;

/**
 * Stab entry type codes (n_type) as defined in binutils include/aout/stab.def.
 */
public final class StabType {
	public static final int N_UNDF = 0x00; // ELF: per-unit header (string table chunk size)
	public static final int N_GSYM = 0x20;
	public static final int N_FNAME = 0x22;
	public static final int N_FUN = 0x24;
	public static final int N_STSYM = 0x26;
	public static final int N_LCSYM = 0x28;
	public static final int N_MAIN = 0x2a;
	public static final int N_ROSYM = 0x2c;
	public static final int N_PC = 0x30;
	public static final int N_NSYMS = 0x32;
	public static final int N_NOMAP = 0x34;
	public static final int N_OBJ = 0x38;
	public static final int N_OPT = 0x3c;
	public static final int N_RSYM = 0x40;
	public static final int N_M2C = 0x42;
	public static final int N_SLINE = 0x44;
	public static final int N_DSLINE = 0x46;
	public static final int N_BSLINE = 0x48;
	public static final int N_FLINE = 0x4c;
	public static final int N_EHDECL = 0x50;
	public static final int N_CATCH = 0x54;
	public static final int N_SSYM = 0x60;
	public static final int N_ENDM = 0x62;
	public static final int N_SO = 0x64;
	public static final int N_LSYM = 0x80;
	public static final int N_BINCL = 0x82;
	public static final int N_SOL = 0x84;
	public static final int N_PSYM = 0xa0;
	public static final int N_EINCL = 0xa2;
	public static final int N_ENTRY = 0xa4;
	public static final int N_LBRAC = 0xc0;
	public static final int N_EXCL = 0xc2;
	public static final int N_SCOPE = 0xc4;
	public static final int N_RBRAC = 0xe0;
	public static final int N_BCOMM = 0xe2;
	public static final int N_ECOMM = 0xe4;
	public static final int N_ECOML = 0xe8;
	public static final int N_LENG = 0xfe;

	private StabType() {
	}

	public static String name(int type) {
		return switch (type) {
			case N_UNDF -> "UNDF";
			case N_GSYM -> "GSYM";
			case N_FNAME -> "FNAME";
			case N_FUN -> "FUN";
			case N_STSYM -> "STSYM";
			case N_LCSYM -> "LCSYM";
			case N_MAIN -> "MAIN";
			case N_ROSYM -> "ROSYM";
			case N_OPT -> "OPT";
			case N_RSYM -> "RSYM";
			case N_SLINE -> "SLINE";
			case N_SO -> "SO";
			case N_LSYM -> "LSYM";
			case N_BINCL -> "BINCL";
			case N_SOL -> "SOL";
			case N_PSYM -> "PSYM";
			case N_EINCL -> "EINCL";
			case N_LBRAC -> "LBRAC";
			case N_EXCL -> "EXCL";
			case N_RBRAC -> "RBRAC";
			case N_BCOMM -> "BCOMM";
			case N_ECOMM -> "ECOMM";
			default -> String.format("0x%02x", type);
		};
	}
}
