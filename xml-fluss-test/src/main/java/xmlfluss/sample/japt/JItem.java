package xmlfluss.sample.japt;

import org.jspecify.annotations.NonNull;
import xmlfluss.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase-5 showcase Java record: namespaces, lists, custom format pattern, custom converter,
 * raw text capture. Used by {@code JItemParserTest}.
 */
@XmlRecord(path = "//item")
@XmlNs(prefix = "dc", uri = "http://purl.org/dc/elements/1.1/")
public record JItem(
        @XmlAttr(name = "id") int id,
        @XmlChild(path = "name") @NonNull String name,
        @XmlChild(path = "tag") List<String> tags,
        @XmlChild(path = "published") @XmlFormat(pattern = "dd/MM/yyyy") @NonNull LocalDate published,
        @XmlChild(path = "price") @XmlConverter(cls = MoneyConverter.class) @NonNull Money price,
        @XmlText String raw
) {}
