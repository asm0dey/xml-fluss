package xmlfluss.sample.poly

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlPolymorphic
import xmlfluss.XmlRecord
import xmlfluss.XmlSubtype
import xmlfluss.XmlText

@XmlPolymorphic
sealed interface Shape {
    @XmlSubtype("circle")
    data class Circle(@XmlAttr val r: Double) : Shape

    @XmlSubtype("square")
    data class Square(@XmlAttr val side: Double) : Shape

    @XmlSubtype("triangle")
    data class Triangle(
        @XmlAttr val base: Double,
        @XmlAttr val height: Double,
        @XmlChild val label: String?,
    ) : Shape
}

@XmlPolymorphic(discriminator = "@type")
sealed interface Event {
    @XmlSubtype("login")
    data class Login(
        @XmlAttr val user: String,
        @XmlText val msg: String,
    ) : Event

    @XmlSubtype("logout")
    data class Logout(@XmlAttr val user: String) : Event
}

@XmlRecord("//drawing")
data class Drawing(
    @XmlAttr val id: Int,
    @XmlChild val shapes: List<Shape>,
    @XmlChild("event") val events: List<Event>,
    @XmlChild("highlight") val highlight: Event?,
    @XmlChild("primary") val primary: Event,
)
