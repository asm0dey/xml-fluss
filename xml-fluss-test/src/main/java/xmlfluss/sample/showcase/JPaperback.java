package xmlfluss.sample.showcase;

import xmlfluss.XmlAttr;
import xmlfluss.XmlSubtype;

@XmlSubtype(name = "paperback")
public record JPaperback(@XmlAttr int pages) implements JFormat {}
