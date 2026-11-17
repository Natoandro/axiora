//! Self-contained, path-based SVG output for Texlet layouts.

use std::{error::Error, fmt, fmt::Write as _};
use texlet::{DrawCommand, Layout};
use ttf_parser::{Face, FaceParsingError, GlyphId, OutlineBuilder};

#[derive(Debug)]
pub enum SvgError {
    Font(FaceParsingError),
    MissingOutline(u16),
    InvalidViewport,
}

impl fmt::Display for SvgError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Font(error) => write!(f, "could not open the layout font: {error}"),
            Self::MissingOutline(id) => write!(f, "glyph {id} has no vector outline"),
            Self::InvalidViewport => write!(f, "layout exceeds the SVG coordinate range"),
        }
    }
}

impl Error for SvgError {}

/// Emits an SVG using the exact face that produced the layout. The consumer
/// supplies accessible text alongside the SVG when embedding it.
pub fn render(layout: &Layout<'_, '_>) -> Result<String, SvgError> {
    let font = layout.font();
    let face = Face::parse(font.data(), font.index()).map_err(SvgError::Font)?;
    let metrics = layout.metrics();
    let left = metrics.ink_left;
    let top = -metrics.ascent;
    let width = (metrics.width.max(metrics.ink_right) - left).max(1.0);
    let height = (metrics.ascent + metrics.descent).max(1.0);
    if !width.is_finite() || !height.is_finite() {
        return Err(SvgError::InvalidViewport);
    }
    let mut svg = format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{width}\" height=\"{height}\" viewBox=\"{left} {top} {width} {height}\">\n"
    );

    for command in layout.commands() {
        match *command {
            DrawCommand::Glyph { id, x, y, size } => {
                let mut path = Path::default();
                face.outline_glyph(GlyphId(id), &mut path)
                    .ok_or(SvgError::MissingOutline(id))?;
                let scale = size / f64::from(font.units_per_em());
                writeln!(
                    svg,
                    "  <path transform=\"translate({x} {y}) scale({scale} -{scale})\" d=\"{}\"/>",
                    path.0
                )
                .unwrap();
            }
            DrawCommand::Rule {
                x,
                y,
                width,
                height,
            } => {
                writeln!(
                    svg,
                    "  <rect x=\"{x}\" y=\"{y}\" width=\"{width}\" height=\"{height}\"/>"
                )
                .unwrap();
            }
        }
    }
    svg.push_str("</svg>\n");
    Ok(svg)
}

#[derive(Default)]
struct Path(String);

impl OutlineBuilder for Path {
    fn move_to(&mut self, x: f32, y: f32) {
        write!(self.0, "M {x} {y} ").unwrap();
    }

    fn line_to(&mut self, x: f32, y: f32) {
        write!(self.0, "L {x} {y} ").unwrap();
    }

    fn quad_to(&mut self, x1: f32, y1: f32, x: f32, y: f32) {
        write!(self.0, "Q {x1} {y1} {x} {y} ").unwrap();
    }

    fn curve_to(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, x: f32, y: f32) {
        write!(self.0, "C {x1} {y1} {x2} {y2} {x} {y} ").unwrap();
    }

    fn close(&mut self) {
        self.0.push_str("Z ");
    }
}
