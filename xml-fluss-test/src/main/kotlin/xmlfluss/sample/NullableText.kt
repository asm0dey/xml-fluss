package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlRecord
import xmlfluss.XmlText

/**
 * Locks the documented behavior of nullable `@XmlText`: when the source element is
 * empty, the generated parser binds an empty string, NOT null. Null is reserved
 * for genuinely-absent values (e.g. attributes that were never written).
 */
@XmlRecord("//memo")
data class Memo(
    @XmlAttr("id") val id: String,
    @XmlText val body: String?,
)
