package stabs.model;

/** A function type ({@code f}); STABS records only the return type. */
public record FunctionType(SType returnType) implements SType {
}
