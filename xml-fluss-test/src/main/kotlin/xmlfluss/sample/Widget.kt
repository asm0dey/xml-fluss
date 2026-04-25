package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

data class Knob(
    @XmlAttr val id: String,
    @XmlChild val label: String,
)

@XmlRecord("//widget")
data class Widget(
    @XmlAttr val id: String,
    @XmlAttr val color: String?,
    @XmlChild val name: String,
    @XmlChild val tags: List<String>,
    @XmlChild val note: String?,
    @XmlChild val knob: Knob?,
    @XmlChild val knobs: List<Knob>,
)
