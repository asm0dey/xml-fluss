# xml-fluss

Streaming XML parser for the JVM. Annotate a data class, get a typed `Flow<T>` parser generated at compile time. Built on Aalto StAX + KSP + KotlinPoet.

## Why the name?

**xml-fluss** combines the technical focus with a catchy German-English pun:
- **Fluss** is the German word for **Flow**, highlighting that this library is built around Kotlin Coroutines `Flow`.
- It reflects the streaming nature of the parser—data flows through it like a river (*Fluss*), never buffering more than one record at a time.

## Premise

Hand-rolling StAX/SAX code is tedious and error-prone. JAXB-style binders force you to mirror the entire document tree. We want neither. The goal: declare the shape of the data you care about — at any depth, anywhere in the document — and let the compiler emit a fast, streaming parser.

Use cases: ETL pipelines reading multi-GB XML feeds, scraping a few records out of a deeply nested document, log processing, dataset import.

## Path syntax

Mini-XPath subset, parsed by `xmlfluss.path.PathParser`:

| Syntax | Meaning | Where allowed |
|---|---|---|
| `//author` | descendant axis — match anywhere | `@XmlRecord`, `@XmlChild` |
| `/library/section/author` | absolute path from document root | `@XmlRecord` |
| `authors/author` | relative descendant (auto-prepended `//`) | `@XmlRecord` |
| `//author[@role='main']` | predicate filter | `@XmlRecord` |
| `{uri}local` | namespaced via Clark notation | `@XmlRecord` |
| `atom:entry`, `atom:title` | namespaced via `@XmlNs` prefix | `@XmlRecord`, `@XmlChild`, `@XmlMap` |
| `//{*}author` | any namespace | `@XmlRecord` |
| `wrapper/leaf` | multi-segment direct sub-path | `@XmlChild`, `@XmlMap` key/value |
| `leaf/@attr` | attribute on a nested child | `@XmlChild`, `@XmlMap` key/value |
| `leaf/@xml:lang` | namespaced attr leaf | same as above |
| `@id`, `@xml:lang` | attribute on the enclosing element | `@XmlAttr`, `@XmlMap` key/value |
| `//head/.../@attr` | descendant axis followed by direct sub-path / attr leaf | `@XmlChild`, `@XmlMap` key/value |

Path grammar:

```
path       := ('//' | '/')? step ( ('/' | '//') step )* ('/@' qname)?
step       := qname ('[' predicate ']')?
qname      := (ncname ':')? ncname
predicate  := term (('and'|'or') term)*
term       := '@' qname ('=' | '!=') quoted-string | integer
```

## Annotation surface

```kotlin
@XmlRecord("//atom:entry")
@XmlNs("atom", "http://www.w3.org/2005/Atom")     // repeatable
@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")
data class Entry(
    @XmlAttr("id")              val id: Int,
    @XmlAttr("xml:lang")        val lang: String?,        // namespaced attr
    @XmlChild("atom:title")     val title: String,        // missing → throw
    @XmlChild("atom:summary")   val summary: String?,     // missing → null
    @XmlChild("bio/@lang")      val bioLang: String?,     // attr on nested child
    @XmlChild("//book")         val books: List<Book>,    // descendant + nested
    @XmlMap(entry = "score", key = "@author", value = "v")
                                val scores: Map<String, Double>,
    @XmlText                    val raw: String,
    @XmlFormat("yyyy-MM-dd")    val published: java.time.LocalDate,
    @XmlConverter(MoneyConv::class) val price: Money,
)
```

Generated artifact: `${Record}Parser` object with:

```kotlin
public fun parse(input: InputStream, ignoreNamespace: Boolean = false): Flow<Entry>
```

Pass `ignoreNamespace = true` to drop every element/attribute namespace at the cursor — useful when a producer omits the declared namespace (or uses a different one) and you don't want to fork the data class. The flag flows through the `PathMatcher` (record-path element + attribute-predicate matching), the `recordAttr` / `childAttr` lookups, and the codegen-emitted child `when` arms.

## Usage patterns

Each pattern below is a working snippet. The matching XML the snippet expects is shown alongside.

### 1. Bare record at any depth

```kotlin
@XmlRecord("//author")
data class Author(
    @XmlAttr           val id: Int,       // attr name = field name "id"
    @XmlChild          val name: String,  // child element <name>
    @XmlChild          val bio: String?,
)
```

`@XmlAttr` and `@XmlChild` default their name/path to the field's Kotlin identifier when the
argument is omitted. Use the explicit form when the XML name differs (`@XmlAttr("xml:lang") val lang`)
or the path is non-trivial (multi-segment, descendant, attribute leaf, namespaced).

```xml
<library><section><author id="1"><name>Ada</name></author></section></library>
```

### 2. Anchored record paths

`@XmlRecord("/library/section/author")` matches only at the absolute path. `authors/author` is auto-anchored as `//authors/author` (relative descendant).

### 3. Predicate filters

Predicates allow filtering elements based on their attributes or their position among siblings. They are evaluated at `START_ELEMENT` time, making them extremely efficient for streaming as no element content needs to be buffered to decide whether to enter a record or sub-path.

#### Attribute predicates
Filter by attribute presence and value:
```kotlin
@XmlRecord("//author[@role='main']")               // Matches <author role="main">
@XmlRecord("//author[@active!='false']")           // Matches if active is present and not 'false'
@XmlRecord("//author[@xml:lang='en']")             // Namespaced attribute
```

#### Positional predicates
Filter by the one-based index of the element among its same-named siblings:
```kotlin
@XmlRecord("//entry[1]")    // First <entry> in any container
@XmlRecord("//entry[2]")    // Second <entry>
```

#### Boolean logic
Combine terms using `and` or `or`. Parentheses are not currently supported, and `and` has higher precedence than `or`:
```kotlin
@XmlRecord("//book[@featured='true' and @lang='en']")
@XmlRecord("//item[@id='100' or @id='200']")
```

#### Syntax summary
| Feature | Syntax | Example |
|---|---|---|
| Equality | `@attr='val'` | `[@role='admin']` |
| Inequality | `@attr!='val'` | `[@status!='deprecated']` |
| Position | `integer` | `[1]` |
| Logical AND | `and` | `[@a='1' and @b='2']` |
| Logical OR | `or` | `[@a='1' or @a='2']` |
| Namespaces | `prefix:attr` | `[@xml:lang='en']` |

#### Streaming constraints
Since predicates are evaluated at the moment the parser encounters the opening tag (`START_ELEMENT`):
- Only attributes of the current element can be used in predicates.
- Text content or nested child elements cannot be used as filters (e.g., `author[name='Ada']` is NOT supported).
- Position `[N]` refers to the count of siblings with the *same name* encountered so far under the current parent.

### 4. Scalars, temporals, BigDecimal

```kotlin
@XmlChild("count")                         val count: Int,
@XmlChild("active")                        val active: Boolean,
@XmlChild("ratio")                         val ratio: Double,
@XmlFormat("yyyy-MM-dd")
@XmlChild("published")                     val published: LocalDate,
@XmlFormat("#,##0.00")
@XmlChild("price")                         val price: BigDecimal,
```

`String`, `Int`, `Long`, `Double`, `Boolean`, `LocalDate`, `LocalDateTime`, `Instant`, `BigDecimal`, all with `T?` variants.

### 5. Nullable vs missing

`T?` field → missing element/attr stores `null`. Non-null `T` → missing throws `XmlParseException.Missing` with `Location`.

### 6. Custom converters

```kotlin
class MoneyConv : Converter<Money> {
    override fun convert(raw: String, loc: Location): Money = Money.parse(raw)
}

@XmlConverter(MoneyConv::class) @XmlChild("price") val price: Money
```

Instantiated once per parser object. Receives raw string + `Location`.

### 7. List of scalars

```kotlin
@XmlChild("tag") val tags: List<String>  // every <tag> under the record
```

Repeated direct children collected in document order. Empty list = no matches; the list itself is non-null.

### 8. Multi-segment paths and attribute leaves

```kotlin
@XmlChild("meta/issn")     val issn: String?,    // <meta><issn>...</issn></meta>
@XmlChild("bio/@lang")     val bioLang: String?, // <bio lang="en">...</bio>
@XmlChild("link/@xml:lang") val linkLang: String?,
```

### 9. Descendant `@XmlChild` (`//`)

```kotlin
@XmlChild("//book") val books: List<Book>
```

Every `<book>` at any depth inside the record — undeclared wrappers (`<works>`, `<archive>`) skipped automatically. Single segment after `//` only (or one trailing `@attr`).

### 10. `@XmlText`

```kotlin
@XmlText val raw: String                          // trimmed
@XmlText(preserveWhitespace = true) val verbatim: String
```

One per class. Reads chardata of the enclosing element.

### 11. Namespaces

```kotlin
@XmlRecord("//atom:entry")
@XmlNs("atom", "http://www.w3.org/2005/Atom")
data class Entry(
    @XmlAttr("id")             val id: String,
    @XmlChild("atom:title")    val title: String,
)
```

Default namespace via `@XmlNs("", uri)` — bare element segments resolve to `uri`. Attributes always null-NS unless `prefix:local`. Clark notation `{uri}local` works in `@XmlRecord` paths.

### 12. Namespaced attributes

```kotlin
@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")
@XmlAttr("xml:lang")        val lang: String,
@XmlChild("link/@xml:lang") val linkLang: String?,
```

Bare `@XmlAttr("name")` always null-NS — the class default namespace does *not* apply to attributes (XML spec).

### 13. Nested data classes

```kotlin
data class Book(
    @XmlAttr("isbn")  val isbn: String,
    @XmlChild("title") val title: String,
)

@XmlRecord("//author")
data class Author(
    @XmlChild("books/book")   val books: List<Book>,
    @XmlChild("favorite")     val favorite: Book?,
)
```

Nested classes don't need `@XmlRecord`. They inherit the enclosing record's `@XmlNs` map; redeclaring a prefix is allowed only with the same URI (different URI → build error).

### 14. Map fields

```kotlin
@XmlMap(entry = "score", key = "@author", value = "v")
val scores: Map<String, Double>

@XmlMap(entry = "trans", key = "@xml:lang", value = "@x:val")
val translations: Map<String, String>

@XmlMap(entry = "row", key = "@id", value = "cell")
val rows: Map<Int, List<String>>     // multimap: append per-entry

@XmlMap(entry = "rec", key = "tag", value = "data")
val grouped: Map<List<String>, NestedData>  // composite key (list eq.)
```

`K`, `V` independently scalar / temporal / `BigDecimal` / nested data class / `T?` / `List<T>`. `Map<K, V>?` allowed — stays `null` until the first entry. See `XmlMap` KDoc for the full aggregation table.

### 15. Sealed-class polymorphism

Annotate the sealed parent with `@XmlPolymorphic` and each `data class` variant with `@XmlSubtype("name")`. Two dispatch modes — chosen by the `discriminator` argument.

**Tag-name mode** (default — `discriminator = ""`): each subtype matches a distinct child element directly under the enclosing record/subrecord. The field's `@XmlChild` carries no path; the union of `@XmlSubtype` tags drives the match. At most one tag-mode field per scope; subtype tags must not clash with sibling `@XmlChild` / `@XmlMap` keys.

```kotlin
@XmlPolymorphic
sealed interface Shape {
    @XmlSubtype("circle")   data class Circle(@XmlAttr val r: Double) : Shape
    @XmlSubtype("square")   data class Square(@XmlAttr val side: Double) : Shape
    @XmlSubtype("triangle") data class Triangle(
        @XmlAttr val base: Double,
        @XmlAttr val height: Double,
        @XmlChild val label: String?,
    ) : Shape
}

@XmlRecord("//drawing")
data class Drawing(
    @XmlChild val shapes: List<Shape>,
)
```

```xml
<drawing>
  <circle r="2.5"/>
  <square side="3.0"/>
  <triangle base="4.0" height="5.0"><label>tri</label></triangle>
</drawing>
```

**Attribute mode** (`discriminator = "@local"` or `"@prefix:local"`): all subtypes share one wrapping element. `@XmlChild` names that wrap tag (single direct-child segment); the discriminator attribute on each match selects the variant. Unknown attribute values skip the element.

```kotlin
@XmlPolymorphic(discriminator = "@type")
sealed interface Event {
    @XmlSubtype("login")  data class Login(@XmlAttr val user: String, @XmlText val msg: String) : Event
    @XmlSubtype("logout") data class Logout(@XmlAttr val user: String) : Event
}

@XmlRecord("//log")
data class Log(
    @XmlChild("event") val events: List<Event>,
    @XmlChild("highlight") val highlight: Event?,
)
```

```xml
<log>
  <event type="login" user="alice">welcome</event>
  <event type="logout" user="alice"/>
  <highlight type="login" user="bob">featured</highlight>
</log>
```

Subtypes are nested data classes — they don't need `@XmlRecord` and inherit the enclosing record's `@XmlNs` map (same rules as `@XmlChild` nested data classes). Cardinality covers `T`, `T?`, and `List<T>`. `@XmlFormat` / `@XmlConverter` not honoured on polymorphic fields.

## Architecture

```
xml-fluss-runtime   annotations, exceptions, Converter SPI, path AST + matcher, Aalto cursor, Coercions
xml-fluss-ksp       KSP processor: scans @XmlRecord data classes, emits parsers via KotlinPoet
xml-fluss-test      sample data class + JUnit5 tests
```

Generated parser drives `XmlReadCursor`:
- `findNextRecord()` — advances Aalto reader until path matches at a `START_ELEMENT`
- `recordAttr(ns, name)` / `recordLocation()` — read record attributes / position
- `forEachRecordChild { ln, ns -> ... }` — iterate direct children; body must call exactly one of `childText()` / `skipChild()` to consume the child subtree
- `childText(preserveWhitespace)` / `childAttr(ns, name)` — read child content
- Closing the cursor releases the Aalto reader

`PathMatcher` is a small NFA over compiled steps. State stack tracks active path positions; descendant axis (`//`) keeps states alive across deeper elements; predicates evaluate against captured attributes at `START_ELEMENT`.

## Error model

```kotlin
sealed class XmlParseException(message: String, cause: Throwable? = null) : RuntimeException(...)
    class Missing(val field: String, val loc: Location)
    class Coercion(val field: String, val raw: String, val type: String, val loc: Location, cause: Throwable)
    class Malformed(message: String, val loc: Location, cause: Throwable? = null)
```

`Location(line, col, path)` carried on every throw. `path` is the live element-stack breadcrumb (e.g. `/library/section/shelf/author`).

Per-error precision:

| Error site | Reported `Location` |
|---|---|
| Missing required field (attr / child / text) | record (or nested) element — no offending node exists |
| Coercion on a `@XmlAttr` (record-level attribute) | record element (the attribute lives on it) |
| Coercion on a `@XmlChild` element value | the child element itself (line, column, full path including the child) |
| Coercion via a custom `@XmlConverter` | the child element the converter ran on |
| `@XmlText` coercion | the enclosing element |

Aalto well-formedness errors (truncated input, mismatched tags, illegal XML) currently propagate as Aalto `WFCException` rather than `Malformed`; the underlying message still carries `[row,col]`.

## Build

```
./gradlew build       # compile, run KSP, run tests
./gradlew test        # tests only
```

Stack: Kotlin 2.3.20, KSP 2.3.6, KotlinPoet 2.3.0, Aalto-XML 1.3.3, kotlinx-coroutines 1.10.1, JUnit Jupiter 5.11.4. JDK toolchain 17.
