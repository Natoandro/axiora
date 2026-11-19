use std::ops::Range;

use ttf_parser::GlyphId;

use crate::{
    DrawCommand, Layout, LayoutError, LayoutOptions, MathFont, Metrics,
    parser::{self, Atom, AtomDelimiter, Class, Kind},
    symbols::Alphabet,
};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum MathStyle {
    #[default]
    Inline,
    Display,
}

/// Lays out one expression body in inline style.
pub fn layout<'font, 'data>(
    latex: &str,
    font: &'font MathFont<'data>,
    options: LayoutOptions,
) -> Result<Layout<'font, 'data>, LayoutError> {
    layout_with_style(latex, font, options, MathStyle::Inline)
}

/// Lays out one expression body with explicit inline or display style.
pub fn layout_with_style<'font, 'data>(
    latex: &str,
    font: &'font MathFont<'data>,
    options: LayoutOptions,
    style: MathStyle,
) -> Result<Layout<'font, 'data>, LayoutError> {
    let size = options.font_size;
    if !size.is_finite() || size <= 0.0 || size / f64::from(font.units_per_em()) == 0.0 {
        return Err(LayoutError::InvalidFontSize);
    }
    let atoms = parser::parse(latex)?;
    let context = Context {
        font,
        size,
        level: 0,
        style,
        alphabet: Alphabet::Default,
    };
    let result = context.row(&atoms)?;
    let metrics = result.metrics;
    if [
        metrics.width,
        metrics.ascent,
        metrics.descent,
        metrics.ink_left,
        metrics.ink_right,
    ]
    .into_iter()
    .any(|value| !value.is_finite())
        || result.commands.iter().any(|command| match *command {
            DrawCommand::Glyph { x, y, size, .. } => {
                !x.is_finite() || !y.is_finite() || !size.is_finite()
            }
            DrawCommand::Rule {
                x,
                y,
                width,
                height,
            } => [x, y, width, height]
                .into_iter()
                .any(|value| !value.is_finite()),
        })
    {
        return Err(LayoutError::InvalidFontSize);
    }
    Ok(Layout {
        font,
        metrics,
        commands: result.commands,
    })
}

mod math_box;
use math_box::MathBox;

#[derive(Clone, Copy)]
struct Context<'font, 'data> {
    font: &'font MathFont<'data>,
    size: f64,
    level: usize,
    style: MathStyle,
    alphabet: Alphabet,
}

impl Context<'_, '_> {
    fn constants(&self) -> Option<ttf_parser::math::Constants<'_>> {
        self.font.face.tables().math?.constants
    }

    fn unit(self) -> f64 {
        self.size / f64::from(self.font.units_per_em())
    }

    fn axis(self) -> f64 {
        self.constants().map_or(self.size * 0.25, |c| {
            f64::from(c.axis_height().value) * self.unit()
        })
    }

    fn script(self) -> Self {
        let (first, second) = self.constants().map_or((0.7, 0.5), |c| {
            let first = f64::from(c.script_percent_scale_down()) / 100.0;
            let second = f64::from(c.script_script_percent_scale_down()) / 100.0;
            if first > 0.0 && second > 0.0 {
                (first, second)
            } else {
                (0.7, 0.5)
            }
        });
        let factor = match self.level {
            0 => first,
            1 => second / first,
            _ => 1.0,
        };
        Self {
            size: self.size * factor,
            level: (self.level + 1).min(2),
            style: MathStyle::Inline,
            ..self
        }
    }

    fn row(self, atoms: &[Atom]) -> Result<MathBox, LayoutError> {
        use Class::*;
        let mut result = MathBox::new();
        let mut previous = None;
        let mut classes = atoms
            .iter()
            .filter(|atom| !matches!(atom.kind, Kind::Space(_)))
            .map(|atom| atom.class)
            .peekable();
        for atom in atoms {
            if let Kind::Space(em) = atom.kind {
                result.metrics.width += self.size * em;
                continue;
            }
            let mut class = classes.next().expect("one class per atom");
            if class == Bin
                && (matches!(previous, None | Some(Bin | Op | Rel | Open | Punct))
                    || matches!(classes.peek(), None | Some(Rel | Close | Punct)))
            {
                class = Ord;
            }
            if let Some(left) = previous {
                result.metrics.width +=
                    self.size * f64::from(spacing(left, class, self.level)) / 18.0;
            }
            result.append(self.atom(atom)?);
            previous = Some(class);
        }
        Ok(result)
    }

    fn atom(self, atom: &Atom) -> Result<MathBox, LayoutError> {
        let span = &atom.span;
        match &atom.kind {
            Kind::Glyph(c) => self.glyph(self.alphabet.map(*c), span, 0.0),
            Kind::Sized(c, factor) => self.delimiter(*c, span, self.size * factor),
            Kind::Operator(c) => {
                if self.style == MathStyle::Display {
                    let height = self.constants().map_or(self.size * 1.35, |c| {
                        f64::from(c.display_operator_min_height()) * self.unit()
                    });
                    self.delimiter(*c, span, height)
                } else {
                    self.glyph(*c, span, 0.0)
                }
            }
            Kind::Text(text) => {
                let mut result = MathBox::new();
                for c in text.chars() {
                    result.append(self.glyph(c, span, 0.0)?);
                }
                Ok(result)
            }
            Kind::Row(atoms) => self.row(atoms),
            Kind::Alphabet(alphabet, body) => Self {
                alphabet: *alphabet,
                ..self
            }
            .atom(body),
            Kind::Fraction(top, bottom) => self.fraction(top, bottom),
            Kind::Radical(degree, body) => self.radical(degree.as_deref(), body, span),
            Kind::Delimited(left, body, right) => {
                self.delimited(left.as_ref(), body, right.as_ref())
            }
            Kind::Scripts {
                base,
                upper,
                lower,
                limits,
            } => self.scripts(base, upper, lower.as_deref(), *limits),
            Kind::Space(em) => {
                let mut result = MathBox::new();
                result.metrics.width = self.size * em;
                Ok(result)
            }
        }
    }

    fn glyph(self, c: char, span: &Range<usize>, min_height: f64) -> Result<MathBox, LayoutError> {
        let missing = || LayoutError::MissingGlyph {
            character: c,
            span: span.clone(),
        };
        let face = &self.font.face;
        let mut id = face.glyph_index(c).ok_or_else(missing)?;
        let mut size = self.size;
        let height = |id: GlyphId| {
            face.glyph_bounding_box(id).map_or(0.0, |b| {
                (f64::from(b.y_max) - f64::from(b.y_min)) * self.unit()
            })
        };
        if min_height > height(id) {
            if let Some(construction) = face
                .tables()
                .math
                .and_then(|table| table.variants)
                .and_then(|variants| variants.vertical_constructions.get(id))
            {
                for variant in construction.variants {
                    if height(variant.variant_glyph) > height(id) {
                        id = variant.variant_glyph;
                    }
                    if height(id) >= min_height {
                        break;
                    }
                }
            }
            let height = height(id);
            if height > 0.0 && height < min_height {
                size *= min_height / height;
            }
        }
        let scale = size / f64::from(face.units_per_em());
        let mut result = MathBox::new();
        result.metrics.width = f64::from(face.glyph_hor_advance(id).ok_or_else(missing)?) * scale;
        if c != ' ' {
            let bounds = face.glyph_bounding_box(id).ok_or_else(missing)?;
            result.ink(
                f64::from(bounds.x_min) * scale,
                -f64::from(bounds.y_max) * scale,
                f64::from(bounds.x_max) * scale,
                -f64::from(bounds.y_min) * scale,
            );
            result.commands.push(DrawCommand::Glyph {
                id: id.0,
                x: 0.0,
                y: 0.0,
                size,
            });
        }
        Ok(result)
    }

    fn delimiter(self, c: char, span: &Range<usize>, height: f64) -> Result<MathBox, LayoutError> {
        let glyph = self.glyph(c, span, height)?;
        let mut result = MathBox::new();
        result.metrics.width = glyph.metrics.width;
        let center = glyph
            .bounds
            .map_or(0.0, |[_, top, _, bottom]| (top + bottom) / 2.0);
        result.place(glyph, 0.0, -center - self.axis());
        Ok(result)
    }

    fn delimited(
        self,
        left: Option<&AtomDelimiter>,
        body: &[Atom],
        right: Option<&AtomDelimiter>,
    ) -> Result<MathBox, LayoutError> {
        let body = self.row(body)?;
        let height = 2.0
            * (body.metrics.ascent - self.axis()).max(body.metrics.descent + self.axis())
            + self.size * 0.1;
        let mut result = MathBox::new();
        if let Some((c, span)) = left {
            result.append(self.delimiter(*c, span, height)?);
        }
        result.append(body);
        if let Some((c, span)) = right {
            result.append(self.delimiter(*c, span, height)?);
        }
        Ok(result)
    }
}

mod constructs;

fn spacing(left: Class, right: Class, level: usize) -> u8 {
    use Class::*;
    match (left, right) {
        (Ord | Op | Close | Inner, Op) | (Op, Ord) => 3,
        _ if level > 0 => 0,
        (Open, _) | (_, Close | Punct) => 0,
        (Rel, _) | (_, Rel) => 5,
        (Bin, _) | (_, Bin) => 4,
        (Punct | Inner, _) | (_, Inner) => 3,
        _ => 0,
    }
}
