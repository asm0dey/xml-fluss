package xmlfluss.sample.japt;

import java.math.BigDecimal;

/** Value object used by {@link JItem} via {@link MoneyConverter}. */
public record Money(String currency, BigDecimal amount) {}
