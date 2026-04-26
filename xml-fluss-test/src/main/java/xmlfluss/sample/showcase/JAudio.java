package xmlfluss.sample.showcase;

import xmlfluss.XmlAttr;
import xmlfluss.XmlSubtype;

@XmlSubtype(name = "audio")
public record JAudio(@XmlAttr(name = "durationMin") int duration) implements JFormat {}
