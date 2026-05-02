/**
 * Core classifier. Walks a {@link xmlfluss.codegen.spi.RecordSymbol} and produces a
 * neutral {@link xmlfluss.codegen.model.RecordSpec}. Operates only against the SPI;
 * no host-language symbol APIs leak in.
 */
@NullMarked
package xmlfluss.codegen.classify;

import org.jspecify.annotations.NullMarked;
