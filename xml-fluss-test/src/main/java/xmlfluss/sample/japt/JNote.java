package xmlfluss.sample.japt;

import org.jspecify.annotations.NonNull;
import xmlfluss.XmlAttr;
import xmlfluss.XmlChild;
import xmlfluss.XmlNs;
import xmlfluss.XmlRecord;

/**
 * Java-record equivalent of {@code xmlfluss.sample.Note}, used to verify that the APT
 * processor running inside xml-fluss-test produces a working {@code JNoteParser} the same
 * way the KSP processor does for the Kotlin variant.
 */
@XmlRecord(path = "//note")
@XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")
public record JNote(
        @XmlAttr(name = "id") @NonNull String id,
        @XmlAttr(name = "xml:lang") @NonNull String lang,
        @XmlChild String body
) {}
