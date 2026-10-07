package stabs.model;

/**
 * A compilation unit, started by the N_SO directory/file pair.
 *
 * @param address link-time start address of the unit's code
 */
public record CompileUnit(int index, String directory, String fileName, long address) {

	public String path() {
		if (directory == null || fileName.startsWith("/")) {
			return fileName;
		}
		return directory.endsWith("/") ? directory + fileName : directory + "/" + fileName;
	}

	@Override
	public String toString() {
		return fileName;
	}
}
