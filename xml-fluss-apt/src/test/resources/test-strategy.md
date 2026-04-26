# xml-fluss-apt — MVP Test Strategy

## 1. Scope

**In scope (MVP — what these tests cover):**

- `@XmlRecord(path = "...")` on Java records.
- `@XmlAttr` (explicit `name` and defaulted from component name).
- `@XmlChild` (single-segment explicit path and defaulted from component name).
- `@XmlText` (with and without `preserveWhitespace`).
- Field types: `String`, `int`/`Integer`, `long`/`Long`, `double`/`Double`, `boolean`/`Boolean`,
  `BigDecimal`, `LocalDate`, `LocalDateTime`, `Instant`, nested records, `List<T>` of those.
- Nullability via JSpecify `@org.jspecify.annotations.NonNull`. Primitives, lists, and
  `@NonNull` annotated fields are required; everything else is nullable.
- `parse(InputStream, true)` namespace-stripping overload.
- `Stream` close semantics (cursor must release the StAX reader).

**Out of scope (deferred to later phases):**

- Namespaces (`@XmlNs` / `@XmlNamespaces`), `@XmlMap`, `@XmlFormat`, `@XmlConverter`,
  `@XmlPolymorphic` / `@XmlSubtype`, multi-segment paths, descendant axis (`//` in child
  paths), attribute-leaf segments (`name/@attr`).
- These are exercised as **rejection** cases in `ErrorTest` — every annotation listed in the
  processor's `UNSUPPORTED_ANNOTATIONS` set must produce an ERROR diagnostic.

**Security touch-points:**

- The processor runs `javac` on synthetic in-memory sources only; the runtime parser receives
  byte-array streams built from string literals. No file-system writes outside the JUnit-managed
  temp dirs, no network I/O, no external XML entities (Aalto-XML rejects DTD declarations by
  default, which is the secure posture and is left undisturbed by the MVP).

## 2. Test types

| Type                          | Where                                        | What it does                                                                       |
| ----------------------------- | -------------------------------------------- | ---------------------------------------------------------------------------------- |
| Compile-only processor checks | `SmokeTest`, `ErrorTest`                     | Run javac with `-proc:only` and assert `success` / `ERROR diagnostic substring`.   |
| End-to-end parse-and-assert   | `PositiveTest`, `IgnoreNamespaceTest`        | Compile record + processor, load generated `${Name}Parser`, parse XML, assert each field.   |
| Missing-field semantics       | `MissingFieldTest`                           | Drive runtime-level outcomes: required missing → `XmlParseException.Missing`; nullable → `null`; list → empty. |
| Edge cases                    | `EdgeCasesTest`                              | Empty document, single vs. multi-record streams, moderate-volume input (10k records, deterministic), `Stream.close()` releases the underlying input stream. |

The harness (`AptTestHarness`) is the shared utility:

1. Receives a Java source string.
2. Invokes the system `JavaCompiler` once with `XmlDslProcessor` registered (no
   `-proc:only` — we want the generated source compiled too).
3. Drops both the user record's `.class` and the generated `${Name}Parser.class` into a
   per-test temp directory.
4. Hands back a `ParserFacade` that resolves `parse(InputStream)` and
   `parse(InputStream, boolean)` reflectively and returns a typed `Stream<Record>`.
5. Tears down the temp dir on `close()`.

## 3. Coverage targets

| Category       | Target | Rationale                                                                         |
| -------------- | ------ | --------------------------------------------------------------------------------- |
| Positive       | 100% of MVP feature combinations | One test per feature axis × one combined test; each feature must show up at least once with non-default and default annotation forms. |
| Error          | 100% of `UNSUPPORTED_ANNOTATIONS` + every distinct `error(...)` site reachable for MVP-shape input | Validation surfaces are the contract with the user — a missed surface is a foot-gun. |
| Missing-field  | All three branches: required throws, nullable returns null, list returns empty. | Branch coverage of `coerceField`. |
| Edge / volume  | Empty doc, single record, multi-record, 10k records (deterministic fixture), close-after-drain, close-mid-stream. | Asserts the spliterator + onClose contract.      |
| Namespace      | `ignoreNamespace=true` matches namespaced elements; `ignoreNamespace=false` (MVP-default null-NS) misses them. | Verifies the only knob the MVP exposes. |

JaCoCo is not yet wired for `xml-fluss-apt`; once it is, the same suite should keep
processor-line coverage above 80% — the gap will be in defensive `IllegalStateException`
branches that aren't reachable from any MVP-legal input.

## 4. Risk-based prioritization

1. **Highest** — Generated-code correctness on the four binding kinds (attr / child /
   text / nested) and the temporal/numeric coercions: every downstream user fails closed
   if these are wrong. Covered by `PositiveTest` with exact-value assertions.
2. **High** — Required-vs-nullable semantics. The contract is "primitive | list | @NonNull
   ⇒ required". Misclassifying any of these silently changes behaviour. Covered by
   `MissingFieldTest`.
3. **High** — Rejection of every out-of-scope annotation. A silent accept would emit a
   parser that ignores the user's intent. Covered by `ErrorTest`.
4. **Medium** — `ignoreNamespace` overload. Only one boolean knob, but the only way
   users with namespaced documents get past the MVP. Covered by `IgnoreNamespaceTest`.
5. **Medium** — `Stream.close()` releases the cursor. Resource leaks compound over many
   files; covered by `EdgeCasesTest` with an `InputStream` whose `close()` is observed
   indirectly via the cursor's own close path (`onClose` handler runs).
6. **Low** — Volume / stability under 10 000 records. Not a perf benchmark; just a
   guard against accidental `O(n²)` bugs and stack growth.

## 5. Constraints honoured

- All inputs are synthetic. No production data.
- No `Random`, no `Instant.now()`, no environment lookups in tests or fixtures.
- Each test allocates its own JUnit `@TempDir`; the harness wires it into the compiler's
  output location, so tests are independent and order-insensitive.
- Assertions check exact values (string, int, list contents in order); negative tests
  check exact diagnostic substrings.
- The runtime is **not** mocked. The processor's only contract is the runtime API surface,
  so any mock would drift. Tests run the real `XmlReadCursor`, `Coercions`, and `Paths`.
