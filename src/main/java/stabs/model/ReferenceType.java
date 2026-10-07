package stabs.model;

/** A C++ reference ({@code &}). */
public record ReferenceType(SType target) implements SType {
}
