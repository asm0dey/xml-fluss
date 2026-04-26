package xmlfluss.sample.japt;

import org.jspecify.annotations.NonNull;
import xmlfluss.Converter;
import xmlfluss.Location;
import xmlfluss.XmlParseException;

import java.math.BigDecimal;

/**
 * Splits "{@code <CCY> <amount>}" into a {@link Money} value. Mirrors
 * {@code xmlfluss.sample.MoneyConverter} so the Java records can compose the same
 * Phase 5 surface.
 */
public final class MoneyConverter implements Converter<Money> {
    public MoneyConverter() {}

    @Override
    public Money convert(String raw, @NonNull Location loc) {
        String trimmed = raw.trim();
        int sp = trimmed.indexOf(' ');
        if (sp < 0) {
            throw new XmlParseException.Coercion(
                    "price", raw, "Money", loc,
                    new IllegalArgumentException("expected '<CCY> <amount>'"));
        }
        String ccy = trimmed.substring(0, sp);
        String amt = trimmed.substring(sp + 1).trim();
        try {
            return new Money(ccy, new BigDecimal(amt));
        } catch (NumberFormatException e) {
            throw new XmlParseException.Coercion("price", raw, "Money", loc, e);
        }
    }
}
