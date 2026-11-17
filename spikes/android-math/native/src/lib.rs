//! Converts Texlet's glyphs and rules to Canvas coordinates for the JNI shim.

use std::{ptr, slice, str};
use texlet::{DrawCommand, LayoutOptions, MathFont, MathStyle, layout_with_style};
use ttf_parser::{Face, GlyphId, OutlineBuilder};

#[repr(C)]
pub struct CanvasBuffer {
    data: *mut f32,
    len: usize,
}

/// # Safety
/// Both input pointers must be readable for their given lengths for the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn texlet_canvas_layout(
    font: *const u8,
    font_len: usize,
    source: *const u8,
    source_len: usize,
    size: f32,
    display: u8,
) -> CanvasBuffer {
    let render = || {
        if font.is_null() || source.is_null() {
            return None;
        }
        // JNI holds both arrays until layout returns.
        let font = unsafe { slice::from_raw_parts(font, font_len) };
        let source = str::from_utf8(unsafe { slice::from_raw_parts(source, source_len) }).ok()?;
        canvas_commands(font, source, size, display != 0)
    };
    match render() {
        Some(values) => {
            let values = values.into_boxed_slice();
            let len = values.len();
            CanvasBuffer {
                data: Box::into_raw(values).cast(),
                len,
            }
        }
        None => CanvasBuffer {
            data: ptr::null_mut(),
            len: 0,
        },
    }
}

/// # Safety
/// Buffer must be the unmodified result of texlet_canvas_layout, freed once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn texlet_canvas_free(buffer: CanvasBuffer) {
    if !buffer.data.is_null() {
        let data = ptr::slice_from_raw_parts_mut(buffer.data, buffer.len);
        drop(unsafe { Box::from_raw(data) });
    }
}

fn canvas_commands(bytes: &[u8], source: &str, size: f32, display: bool) -> Option<Vec<f32>> {
    let font = MathFont::from_bytes(bytes, 0).ok()?;
    let layout = layout_with_style(
        source,
        &font,
        LayoutOptions {
            font_size: f64::from(size),
        },
        if display {
            MathStyle::Display
        } else {
            MathStyle::Inline
        },
    )
    .ok()?;
    let face = Face::parse(bytes, 0).ok()?;
    let metrics = layout.metrics();
    // Header: left, top, width, height. Remaining values are drawing commands.
    let mut values = vec![
        metrics.ink_left as f32,
        -metrics.ascent as f32,
        (metrics.width.max(metrics.ink_right) - metrics.ink_left) as f32,
        (metrics.ascent + metrics.descent) as f32,
    ];
    for command in layout.commands() {
        match *command {
            DrawCommand::Glyph { id, x, y, size } => {
                // Begin a separate path for each glyph to preserve its fill.
                values.push(0.0);
                face.outline_glyph(
                    GlyphId(id),
                    &mut CanvasOutline {
                        values: &mut values,
                        x,
                        y,
                        scale: size / f64::from(font.units_per_em()),
                    },
                )?;
            }
            DrawCommand::Rule {
                x,
                y,
                width,
                height,
            } => {
                values.extend_from_slice(&[6.0, x as f32, y as f32, width as f32, height as f32]);
            }
        }
    }
    values
        .iter()
        .all(|value| value.is_finite())
        .then_some(values)
}

struct CanvasOutline<'a> {
    values: &'a mut Vec<f32>,
    x: f64,
    y: f64,
    scale: f64,
}

impl CanvasOutline<'_> {
    fn point(&mut self, x: f32, y: f32) {
        self.values
            .push((self.x + f64::from(x) * self.scale) as f32);
        self.values
            .push((self.y - f64::from(y) * self.scale) as f32);
    }
}

impl OutlineBuilder for CanvasOutline<'_> {
    fn move_to(&mut self, x: f32, y: f32) {
        self.values.push(1.0);
        self.point(x, y);
    }

    fn line_to(&mut self, x: f32, y: f32) {
        self.values.push(2.0);
        self.point(x, y);
    }

    fn quad_to(&mut self, x1: f32, y1: f32, x: f32, y: f32) {
        self.values.push(3.0);
        self.point(x1, y1);
        self.point(x, y);
    }

    fn curve_to(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, x: f32, y: f32) {
        self.values.push(4.0);
        self.point(x1, y1);
        self.point(x2, y2);
        self.point(x, y);
    }

    fn close(&mut self) {
        self.values.push(5.0);
    }
}
