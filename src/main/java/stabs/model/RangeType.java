package stabs.model;

/** A subrange of another type that is not one of the recognized builtin idioms. */
public record RangeType(SType base, long lower, long upper) implements SType {
}
