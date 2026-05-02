# Contributing to xml-fluss

Thanks for considering a contribution. This document describes how to propose changes.

## Before you start

- For non-trivial features or breaking changes, open an issue first to discuss the design. Small fixes and isolated improvements can go straight to a PR.
- Adopting a new framework, test runner, or major dependency must be agreed on in an issue before implementation.

## Developer Certificate of Origin (DCO)

All commits must be signed off, certifying that you wrote the change or otherwise have the right to submit it under the project's Apache 2.0 license. See <https://developercertificate.org/> for the full text.

Add the sign-off trailer to every commit:

```bash
git commit -s -m "Your message"
```

This appends a `Signed-off-by: Your Name <your.email@example.com>` line. The name and email must match your `git config`.

## AI-assisted contributions

Using AI tools is fine. Two rules:

- AI may be credited as a co-author via a `Co-authored-by:` trailer on the commit.
- Do not commit any AI workflow artifacts. Examples: `CLAUDE.md`, `AGENTS.md`, `.claude/`, `.cursor/`, `.tessl/`, `.mcp.json`, custom skill or rule files. These are personal tooling and stay out of the repo. Add them to your local `.gitignore` or global excludes.

You remain responsible for every line you submit, regardless of how it was produced.

## Commit messages

- Follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, etc.).
- Subject line in the imperative mood, ~50 characters.
- Commits must be atomic — one logical change per commit. Don't mix refactors with features or formatting with fixes.
- The body explains **why**; the diff shows what.

## Pull requests

- Target the `main` branch. Branch names are not prescribed.
- A maintainer review and approval (currently @asm0dey) is required before merge.
- Keep PRs focused. Large, mixed-scope PRs will be asked to split.
- User-visible changes must be reflected in `README.md` in the same PR.

## Module layout

xml-fluss is composed of four Gradle modules:

- **`xml-fluss-runtime`** — language-neutral runtime: path AST (`xmlfluss.path.*`), coercions, cursor primitives the generated parsers call into. Kotlin. Published.
- **`xml-fluss-codegen-core`** — shared code-generation core. Holds the neutral data model (`xmlfluss.codegen.model.*`), the `SymbolProvider` SPI (`xmlfluss.codegen.spi.*`), the classifier (`xmlfluss.codegen.classify.*`), and the dispatch-plan builder (`xmlfluss.codegen.plan.*`). Java 17. Internal — not published. Depends only on `xml-fluss-runtime`; no JavaPoet, KotlinPoet, KSP, or `javax.lang.model`.
- **`xml-fluss-apt`** — Java APT processor. Implements the SymbolProvider SPI over `javax.lang.model.*`, runs core's classifier and dispatch-plan builder, emits parsers via JavaPoet. The single TypeRef↔JavaPoet bridge is `xmlfluss.apt.TypeRefs` (TypeRef→TypeName, called at emit) plus `xmlfluss.apt.spi.AptModelToCore` (TypeName→TypeRef, called by the SPI views).
- **`xml-fluss-ksp`** — KSP processor. Same shape as APT, over `com.google.devtools.ksp.symbol.*` and KotlinPoet. Bridges live at `xmlfluss.ksp.TypeRefs` and `xmlfluss.ksp.spi.KspModelToCore`.

Adding an annotation: edit the runtime annotation, extend `AnnotationView` in `xml-fluss-codegen-core/src/main/java/xmlfluss/codegen/spi/AnnotationView.java`, implement the new accessor in both `AptAnnotationView` and `KspAnnotationView`, and teach the classifier to consume it. The compiler enforces parity in both SPI implementations.

Adding an emit feature: if the routing is annotation-driven, extend the core classifier and `DispatchPlan`; both emitters walk the same plan. If the change is purely lexical (formatting, comments, attribute order), edit each emitter locally — those stay separate by design.

## Tests

- New functionality requires new tests.
- Existing tests must pass.
- Match the testing approach already used in the module you are touching (JUnit + the project's existing fixtures). Introducing a new test framework requires prior discussion per the rule above.

## Build and verification

Before opening a PR, run:

```bash
./gradlew check build
```

Both must succeed locally. CI will run the same gates.

- JDK 17 is the current baseline. Build with JDK 17.
- Adhere to the existing code style of the file and module you are modifying. Do not reformat unrelated code.

## Reporting security issues

Do not open public issues for security vulnerabilities. Email **me+security@asm0dey.site** with details and reproduction steps. You will get an acknowledgement and a coordinated disclosure timeline.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE), as confirmed by your DCO sign-off.
