package xmlfluss.sample.showcase;

import xmlfluss.Converter;
import xmlfluss.Location;

/** Counts {@code '*'} characters in the rating text. Mirrors {@code StarRatingConverter}. */
public final class JStarRatingConverter implements Converter<Integer> {
    public JStarRatingConverter() {}

    @Override
    public Integer convert(String raw, Location loc) {
        int n = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '*') n++;
        }
        return n;
    }
}
