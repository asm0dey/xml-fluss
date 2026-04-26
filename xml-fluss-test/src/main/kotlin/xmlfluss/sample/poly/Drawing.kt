package xmlfluss.sample.poly

import xmlfluss.*

@XmlPolymorphic
sealed interface Shape {
    @XmlSubtype(name = "circle")
    data class Circle(@XmlAttr val r: Double) : Shape

    @XmlSubtype(name = "square")
    data class Square(@XmlAttr val side: Double) : Shape

    @XmlSubtype(name = "triangle")
    data class Triangle(
        @XmlAttr val base: Double,
        @XmlAttr val height: Double,
        @XmlChild val label: String?,
    ) : Shape
}

@XmlPolymorphic(discriminator = "@type")
sealed interface Event {
    @XmlSubtype(name = "login")
    data class Login(
        @XmlAttr val user: String,
        @XmlText val msg: String,
    ) : Event

    @XmlSubtype(name = "logout")
    data class Logout(@XmlAttr val user: String) : Event
}

@XmlRecord(path = "//drawing")
data class Drawing(
    @XmlAttr val id: Int,
    @XmlChild val shapes: List<Shape>,
    @XmlChild(path = "event") val events: List<Event>,
    @XmlChild(path = "highlight") val highlight: Event?,
    @XmlChild(path = "primary") val primary: Event,
)
