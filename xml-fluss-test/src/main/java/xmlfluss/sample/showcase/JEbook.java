package xmlfluss.sample.showcase;

import xmlfluss.XmlAttr;
import xmlfluss.XmlSubtype;

@XmlSubtype(name = "ebook")
public record JEbook(@XmlAttr double sizeMb) implements JFormat {}
