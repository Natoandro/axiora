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

struct MathBox {
    metrics: Metrics,
    commands: Vec<DrawCommand>,
    bounds: Option<[f64; 4]>,
}

impl MathBox {
    fn new() -> Self {
        Self {
            metrics: Metrics {
                width: 0.0,
                ascent: 0.0,
                descent: 0.0,
                ink_left: 0.0,
                ink_right: 0.0,
            },
            commands: Vec::new(),
            bounds: None,
        }
    }

    fn ink(&mut self, left: f64, top: f64, right: f64, bottom: f64) {
        self.bounds = Some(match self.bounds {
            Some([l, t, r, b]) => [l.min(left), t.min(top), r.max(right), b.max(bottom)],
            None => [left, top, right, bottom],
        });
        self.metrics.ascent = self.metrics.ascent.max(-top);
        self.metrics.descent = self.metrics.descent.max(bottom);
        self.metrics.ink_left = self.metrics.ink_left.min(left);
        self.metrics.ink_right = self.metrics.ink_right.max(right);
    }

    fn place(&mut self, child: Self, x: f64, y: f64) {
        if let Some([left, top, right, bottom]) = child.bounds {
            self.ink(x + left, y + top, x + right, y + bottom);
        }
        self.commands
            .extend(child.commands.into_iter().map(|command| match command {
                DrawCommand::Glyph {
                    id,
                    x: gx,
                    y: gy,
                    size,
                } => DrawCommand::Glyph {
                    id,
                    x: gx + x,
                    y: gy + y,
                    size,
                },
                DrawCommand::Rule {
                    x: rx,
                    y: ry,
                    width,
                    height,
                } => DrawCommand::Rule {
                    x: rx + x,
                    y: ry + y,
                    width,
                    height,
                },
            }));
    }

    fn append(&mut self, child: Self) {
        let width = child.metrics.width;
        self.place(child, self.metrics.width, 0.0);
        self.metrics.width += width;
    }

    fn rule(&mut self, x: f64, y: f64, width: f64, height: f64) {
        self.ink(x, y, x + width, y + height);
        self.commands.push(DrawCommand::Rule {
            x,
            y,
            width,
            height,
        });
    }
}

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

    fn fraction(self, top: &Atom, bottom: &Atom) -> Result<MathBox, LayoutError> {
        let display = self.style == MathStyle::Display;
        let child = if display {
            Self {
                style: MathStyle::Inline,
                ..self
            }
        } else {
            self.script()
        };
        let top = child.atom(top)?;
        let bottom = child.atom(bottom)?;
        let mut result = MathBox::new();
        let extent = |m: Metrics| m.width.max(m.ink_right) - m.ink_left;
        let top_width = extent(top.metrics);
        let bottom_width = extent(bottom.metrics);
        let width = top_width.max(bottom_width) + self.size * 0.2;
        let (thickness, top_gap, bottom_gap, rise, drop) = self.constants().map_or_else(
            || {
                (
                    0.04 * self.size,
                    0.12 * self.size,
                    0.12 * self.size,
                    0.0,
                    0.0,
                )
            },
            |c| {
                let (top_gap, bottom_gap, rise, drop) = if display {
                    (
                        c.fraction_num_display_style_gap_min(),
                        c.fraction_denom_display_style_gap_min(),
                        c.fraction_numerator_display_style_shift_up(),
                        c.fraction_denominator_display_style_shift_down(),
                    )
                } else {
                    (
                        c.fraction_numerator_gap_min(),
                        c.fraction_denominator_gap_min(),
                        c.fraction_numerator_shift_up(),
                        c.fraction_denominator_shift_down(),
                    )
                };
                let unit = self.unit();
                (
                    c.fraction_rule_thickness().value as f64 * unit,
                    top_gap.value as f64 * unit,
                    bottom_gap.value as f64 * unit,
                    rise.value as f64 * unit,
                    drop.value as f64 * unit,
                )
            },
        );
        let y = -self.axis() - thickness / 2.0;
        let top_y = (-rise).min(y - top_gap - top.metrics.descent);
        let bottom_y = drop.max(y + thickness + bottom_gap + bottom.metrics.ascent);
        let top_x = (width - top_width) / 2.0 - top.metrics.ink_left;
        result.place(top, top_x, top_y);
        let bottom_x = (width - bottom_width) / 2.0 - bottom.metrics.ink_left;
        result.place(bottom, bottom_x, bottom_y);
        result.rule(0.0, y, width, thickness);
        result.metrics.width = width;
        Ok(result)
    }

    fn radical(
        self,
        degree: Option<&[Atom]>,
        body: &Atom,
        span: &Range<usize>,
    ) -> Result<MathBox, LayoutError> {
        let body = self.atom(body)?;
        let (gap, thickness) = self
            .constants()
            .map_or((0.08 * self.size, 0.04 * self.size), |c| {
                let gap = if self.style == MathStyle::Display {
                    c.radical_display_style_vertical_gap()
                } else {
                    c.radical_vertical_gap()
                };
                (
                    f64::from(gap.value) * self.unit(),
                    f64::from(c.radical_rule_thickness().value) * self.unit(),
                )
            });
        let top = -body.metrics.ascent - gap - thickness;
        let radical = self.glyph('√', span, body.metrics.descent - top)?;
        let [_, radical_top, radical_right, radical_bottom] =
            radical.bounds.expect("radical glyph has ink");
        let radical_y = top - radical_top;
        let mut result = MathBox::new();
        let mut x = 0.0;
        if let Some(degree) = degree {
            let degree = self.script().script().row(degree)?;
            x = (degree.metrics.width - radical.metrics.width * 0.45).max(0.0);
            let y = top + (radical_bottom - radical_top) * 0.38 - degree.metrics.descent;
            result.place(degree, 0.0, y);
        }
        let body_x = x + radical.metrics.width;
        let bar_start = x + radical_right.min(radical.metrics.width) - thickness;
        let width = body_x + body.metrics.width.max(body.metrics.ink_right) + self.size * 0.08;
        result.place(radical, x, radical_y);
        result.place(body, body_x, 0.0);
        result.rule(bar_start, top, width - bar_start, thickness);
        result.metrics.width = width;
        Ok(result)
    }

    fn scripts(
        self,
        base: &Atom,
        upper: &[Atom],
        lower: Option<&Atom>,
        limits: Option<bool>,
    ) -> Result<MathBox, LayoutError> {
        let stacked = limits.unwrap_or(
            self.style == MathStyle::Display
                && match &base.kind {
                    Kind::Operator('∑' | '∏') => true,
                    Kind::Text(name) => matches!(name.as_str(), "lim" | "max" | "min"),
                    _ => false,
                },
        );
        let integral = matches!(base.kind, Kind::Operator('∫' | '∬' | '∮'));
        let base = self.atom(base)?;
        let script = self.script();
        let upper = script.row(upper)?;
        let lower = lower
            .map(|atom| script.atom(atom))
            .transpose()?
            .unwrap_or_else(MathBox::new);
        let mut result = MathBox::new();
        let b = base.metrics;
        let u = upper.metrics;
        let l = lower.metrics;
        if stacked {
            let width = b.width.max(u.width).max(l.width);
            let (upper_gap, lower_gap, rise, drop) =
                self.constants()
                    .map_or((0.14 * self.size, 0.14 * self.size, 0.0, 0.0), |c| {
                        let unit = self.unit();
                        (
                            f64::from(c.upper_limit_gap_min().value) * unit,
                            f64::from(c.lower_limit_gap_min().value) * unit,
                            f64::from(c.upper_limit_baseline_rise_min().value) * unit,
                            f64::from(c.lower_limit_baseline_drop_min().value) * unit,
                        )
                    });
            result.place(base, (width - b.width) / 2.0, 0.0);
            result.place(
                upper,
                (width - u.width) / 2.0,
                -rise.max(b.ascent + upper_gap + u.descent),
            );
            result.place(
                lower,
                (width - l.width) / 2.0,
                drop.max(b.descent + lower_gap + l.ascent),
            );
            result.metrics.width = width;
        } else {
            let x = b.width.max(b.ink_right);
            let mut up = (self.size * 0.4).max(b.ascent - self.size * 0.27 + u.descent);
            let mut down = (self.size * 0.2).max(b.descent + l.ascent - self.size * 0.25);
            if !upper.commands.is_empty() && !lower.commands.is_empty() {
                let correction =
                    (self.size * 0.15 - (up + down - u.descent - l.ascent)).max(0.0) / 2.0;
                up += correction;
                down += correction;
            }
            result.place(base, 0.0, 0.0);
            result.place(upper, x, -up);
            // Side subscripts tuck under the integral's slanted right edge.
            let lower_x = if integral {
                (x - self.size * 0.12).max(0.0)
            } else {
                x
            };
            result.place(lower, lower_x, down);
            result.metrics.width = (x + u.width).max(lower_x + l.width) + self.size * 0.05;
        }
        Ok(result)
    }
}

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
