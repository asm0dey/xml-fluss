/**
 * Service-provider interfaces that abstract over each processor's native symbol model.
 * APT implements over {@code javax.lang.model}; KSP implements over {@code KSDeclaration}.
 * The classifier (PR 3) consumes only these interfaces — no native types leak in.
 */
@NullMarked
package xmlfluss.codegen.spi;

import org.jspecify.annotations.NullMarked;
