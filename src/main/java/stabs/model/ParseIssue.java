package stabs.model;

/** A stab that could not be parsed (or was only partially understood). */
public record ParseIssue(int entryIndex, String message, String stab) {

	@Override
	public String toString() {
		return "#" + entryIndex + ": " + message + ": " + stab;
	}
}
