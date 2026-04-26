package xmlfluss.sample.showcase;

import xmlfluss.XmlPolymorphic;

/**
 * Sealed interface for tag-mode polymorphic dispatch. The three subtypes live in their own
 * files so each can carry its own {@code @XmlSubtype}.
 */
@XmlPolymorphic
public sealed interface JFormat permits JPaperback, JEbook, JAudio {}
