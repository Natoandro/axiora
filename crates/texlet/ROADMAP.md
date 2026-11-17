# Texlet notation roadmap

Texlet takes one LaTeX math expression body at a time. An app or Markdown parser extracts it from its surrounding content. Texlet lays it out using a caller-provided font face and returns baseline metrics and ordered glyph and rule commands. Adapters draw those commands. On error, the consumer can present the original expression with the diagnostic.

The tiers prioritize notation likely to recur across school mathematics, university courses, and technical writing, with Axiora's first lesson breaking ties. They are editorial estimates. The [AMS math user guide](https://www.ams.org/arc/tex/amsmath/amsldoc.pdf) informs command families and syntax, not frequency rankings. Within each tier, work generally proceeds in table order, subject to layout dependencies.

## Tier 0 — Preview slice

| Family | Implemented |
| --- | --- |
| Basic atoms | ASCII letters and digits, `+ - = < > ( ) [ ] / . ,`. ASCII whitespace is ignored and minus uses the mathematical minus glyph. |
| Sets | `\in` and `\mathbb{A}` through `\mathbb{Z}`. Blackboard bold takes one braced uppercase ASCII letter and allows ASCII whitespace around it. |
| Layout | One row, horizontal font advances, spacing around relations, ink bounds, baseline metrics. Tier 1 makes the default variables italic. |
| Output | The SVG and Android Canvas adapters draw outlines from the layout font. The drawing contract also includes rectangles for rules. |

## Tier 1 — Everyday formulas

| Priority | Family | Supported syntax and behavior |
| --- | --- | --- |
| 1 | Structural layout | Braced groups, `^`, `_`, primes, `\frac`, `\sqrt`, `\sqrt[n]{x}`. Support nested arguments, script sizing, and inline versus display styles. |
| 2 | Common symbols | Greek letters and variants, `\times \cdot \div \pm \mp \neq \leq \geq \approx \sim \equiv \infty \partial \nabla \ldots \cdots`. Include common aliases such as `\ne`, `\le`, and `\ge`. |
| 3 | Math typography | Italic variables, upright digits and operators, math atom spacing, punctuation, `\mathrm \mathbf \mathit \mathsf \mathtt \mathcal`, and `\text{…}`. `\mathbb` accepts strings of ASCII letters and digits. |
| 4 | Delimiters and spacing | Escaped braces, absolute values and norms, `\langle \rangle \lfloor \rfloor \lceil \rceil \lvert \rvert \lVert \rVert`, `\left \right` with invisible delimiters, `\big` sizing family, and `\, \: \; \! \quad \qquad`. |
| 5 | Named functions and large operators | `\sin \cos \tan \arcsin \log \ln \exp \max \min \lim \gcd \det \operatorname \sum \prod \int \iint \oint`, plus `\limits \nolimits`. Cover ordinary function spacing and placement of limits. |
| 6 | Sets, logic, and arrows | `\notin \ni \subset \subseteq \supset \supseteq \cup \cap \setminus \emptyset \varnothing \forall \exists \neg \land \lor \to \mapsto \leftarrow \leftrightarrow \implies \iff`, including long and double arrows. |

The Rust `layout` function defaults to inline style. Use `layout_with_style` with `MathStyle::Display` for display fractions and large operators. The adapters consume the same positioned glyphs and rules in either style.

The engine accepts up to 4,096 UTF-8 bytes and 32 nested levels. Each drawing command consumes at least one source byte, so a successful layout emits at most 4,096 commands. Layout uses one font. Missing glyphs in the selected face are errors with source byte ranges. Math alphabets map supported characters to Unicode math glyphs, with calligraphic mapping for ASCII capitals. Text runs preserve spaces and use upright glyphs from the same font with per-glyph advances.

For tall delimiters, operators, and radicals, Texlet uses available OpenType MATH variants and scales the largest available shape if needed. Font-part assemblies are scheduled for Tier 2.

Run the notation and layout check with a local Latin Modern Math font.

```sh
TEXLET_TEST_FONT="$(fc-match -f '%{file}' 'Latin Modern Math')" \
  cargo test -p texlet --test tier_one -- --ignored
```

## Tier 2 — Common multi-line and specialized notation

| Priority | Family | Candidate syntax and behavior |
| --- | --- | --- |
| 1 | Matrices and cases | `matrix pmatrix bmatrix Bmatrix vmatrix Vmatrix smallmatrix cases`, row and column separators, and matrix ellipses `\vdots \ddots`. |
| 2 | Aligned equations | `aligned alignedat gathered split`, aligned relation columns, line breaks, and row spacing. |
| 3 | Accents and annotations | `\hat \bar \vec \dot \ddot \tilde \widehat \widetilde \overline \underline \overbrace \underbrace \overset \underset \stackrel`. |
| 4 | Binomials and style controls | `\binom \dfrac \tfrac \dbinom \tbinom \cfrac \substack \displaystyle \textstyle \scriptstyle \scriptscriptstyle`. |
| 5 | Extended operators and alphabets | `\bigcup \bigcap \bigoplus \bigotimes \coprod \biguplus \oplus \otimes \circ \bullet \star \mathfrak \boldsymbol`, plus common AMS relations and negated forms. |
| 6 | Annotated arrows and delimiters | `\xrightarrow \xleftarrow \middle`, extensible arrows and delimiters with font-provided variants and assemblies. |

## Tier 3 — Lower-frequency and package-specific notation

| Priority | Family | Candidate syntax and behavior |
| --- | --- | --- |
| 1 | Arrays and advanced alignment | `array` with basic column specifications, `multlined`, optional row spacing and simple rules. Document-level numbering, labels and references can be handled by the consumer. |
| 2 | Low-level typesetting controls | `\genfrac \smash \phantom \hphantom \vphantom \mathord \mathbin \mathrel \mathop \mathopen \mathclose \mathpunct`. |
| 3 | Long-tail symbol inventory | Additional AMS arrows, geometric and proof symbols, `\hbar \ell \Re \Im \aleph \beth`, uncommon integrals and accents. Prioritize examples from real formulas and font availability. |
| 4 | Domain extensions | Chemistry, units, commutative diagrams, tensor indices, cancellation and boxed expressions. Consider independently scoped subsets based on demand. |

Document preambles, TeX macro programming, package loading, file I/O, and shell execution are outside the expression contract. Unsupported input should return an error with a UTF-8 byte range. Revisit input, nesting, and output limits when adding multi-line notation. Test new families with representative expressions, invalid input, font coverage, and visual comparisons in both inline and display styles where applicable.
