package stabs.model;

/** A pointer-to-member offset type ({@code @domain,member}). */
public record MemberPointerType(SType domain, SType memberType) implements SType {
}
