package com.javafxlogin.core.audit;

/**
 * What one export of the record came to.
 *
 * <p>Two numbers and no events: this is the whole of what the application ever learns about what it
 * has recorded.
 *
 * @param events how many entries were copied
 * @param chainIntact whether every entry after the first still followed from the one before it. An
 *     export that says {@code false} is saying the record was edited or had entries removed since
 *     it was written — the first entry kept is exempt, because whatever it followed has been
 *     rotated away
 */
public record AuthenticationEventExport(long events, boolean chainIntact) {}
