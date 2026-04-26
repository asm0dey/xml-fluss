package xmlfluss.sample.showcase;

import org.jspecify.annotations.Nullable;
import xmlfluss.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Java/APT mirror of {@code xmlfluss.sample.showcase.Book}. Demonstrates JSpecify nullability
 * states in the same record:
 *
 * <ul>
 *   <li>{@code int rating} — primitive, always required.</li>
 *   <li>{@code String id, title, isbn} — non-null via package-level {@link
 *       org.jspecify.annotations.NullMarked}.</li>
 *   <li>{@code @Nullable String lang} — explicit {@code @Nullable} overrides the package default.</li>
 *   <li>{@code List<JAuthor> authors} — lists are always required (empty list = no matches).</li>
 *   <li>{@code Map<…, …> royalties / regionalPrice} — required maps; empty map = no entries.</li>
 *   <li>{@code JFormat format, JMoney price, LocalDate published} — required nested values.</li>
 * </ul>
 */
@XmlRecord(path = "//lib:catalog/lib:book")
@XmlNs(prefix = "lib", uri = "https://lib.example.com/v1")
@XmlNs(prefix = "m", uri = "https://lib.example.com/money")
public record JBook(
        @XmlAttr String id,
        @XmlChild String title,
        @XmlChild(path = "bio/@lang") @Nullable String lang,
        @XmlChild(path = "authors/author") List<JAuthor> authors,
        @XmlChild(path = "//isbn") String isbn,
        @XmlChild JFormat format,
        @XmlChild(path = "m:price") JMoney price,
        @XmlChild(path = "published") @XmlFormat(pattern = "yyyy-MM-dd") LocalDate published,
        @XmlChild(path = "rating") @XmlConverter(cls = JStarRatingConverter.class) int rating,
        @XmlMap(entry = "m:royalty", key = "price", value = "share/author")
        Map<JMoney, List<JAuthor>> royalties,
        @XmlMap(entry = "regional", key = "@region", value = "amount")
        Map<String, BigDecimal> regionalPrice,
        @XmlChild(path = "link[@type='application/epub+zip']/@href") @Nullable String epubHref,
        @XmlChild(path = "link[@type='application/epub+zip']/@rel") @Nullable String epubRel,
        @XmlChild(path = "link[@type='application/atom+xml']/@href") @Nullable String atomHref,
        // Chained-bracket predicate: implicit AND of two attribute checks.
        @XmlChild(path = "link[@type='application/epub+zip'][@rel='http://opds-spec.org/acquisition']/@href")
        @Nullable String acquisitionEpubHref
) {}
