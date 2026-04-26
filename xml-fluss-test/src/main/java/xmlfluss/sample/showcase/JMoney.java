package xmlfluss.sample.showcase;

import xmlfluss.XmlAttr;
import xmlfluss.XmlText;

import java.math.BigDecimal;

/**
 * Both fields required by package-default ({@code @NullMarked}); no explicit annotation needed.
 * Used as a {@code @XmlMap} key — record auto-equality powers map dedup.
 */
public record JMoney(
        @XmlAttr String currency,
        @XmlText BigDecimal amount
) {}
