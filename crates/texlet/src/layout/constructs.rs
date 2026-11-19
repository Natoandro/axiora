use super::*;

impl Context<'_, '_> {
    pub(super) fn fraction(self, top: &Atom, bottom: &Atom) -> Result<MathBox, LayoutError> {
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

    pub(super) fn radical(
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

    pub(super) fn scripts(
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
