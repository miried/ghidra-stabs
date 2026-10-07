package stabs.model;

/**
 * An array with inclusive index bounds. Arrays of unknown size have {@code upper == lower - 1}
 * (gcc emits {@code 0;-1;}).
 */
public record ArrayType(SType indexType, long lower, long upper, SType element) implements SType {

	public long count() {
		return Math.max(0, upper - lower + 1);
	}
}
