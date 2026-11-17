//! Layout for a small subset of LaTeX math expressions.

use std::{error::Error, fmt, ops::Range};
use ttf_parser::{Face, FaceParsingError};

mod layout;
mod parser;
mod symbols;

pub use layout::{MathStyle, layout, layout_with_style};

/// The font face shared by the layout engine and drawing adapter.
pub struct MathFont<'data> {
    data: &'data [u8],
    index: u32,
    face: Face<'data>,
}

impl<'data> MathFont<'data> {
    pub fn from_bytes(data: &'data [u8], index: u32) -> Result<Self, FaceParsingError> {
        Ok(Self {
            data,
            index,
            face: Face::parse(data, index)?,
        })
    }

    pub fn data(&self) -> &'data [u8] {
        self.data
    }

    pub fn index(&self) -> u32 {
        self.index
    }

    pub fn units_per_em(&self) -> u16 {
        self.face.units_per_em()
    }
}

#[derive(Debug, Clone, Copy)]
pub struct LayoutOptions {
    /// Font size in the consumer's logical units.
    pub font_size: f64,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum DrawCommand {
    /// A font-specific glyph positioned at its baseline origin.
    Glyph { id: u16, x: f64, y: f64, size: f64 },
    /// A filled rectangle positioned at its top-left corner.
    Rule {
        x: f64,
        y: f64,
        width: f64,
        height: f64,
    },
}

/// Coordinates start at the left edge of the baseline. Positive y points down.
pub struct Layout<'font, 'data> {
    font: &'font MathFont<'data>,
    metrics: Metrics,
    commands: Vec<DrawCommand>,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Metrics {
    /// Horizontal advance for surrounding content.
    pub width: f64,
    /// Distance above the baseline to the highest ink.
    pub ascent: f64,
    /// Distance below the baseline to the lowest ink.
    pub descent: f64,
    /// Left and right ink edges, which can extend beyond the advance.
    pub ink_left: f64,
    pub ink_right: f64,
}

impl<'font, 'data> Layout<'font, 'data> {
    pub fn font(&self) -> &'font MathFont<'data> {
        self.font
    }

    pub fn metrics(&self) -> Metrics {
        self.metrics
    }

    pub fn commands(&self) -> &[DrawCommand] {
        &self.commands
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LayoutError {
    InvalidFontSize,
    Unsupported {
        span: Range<usize>,
    },
    Syntax {
        expected: &'static str,
        span: Range<usize>,
    },
    LimitExceeded {
        span: Range<usize>,
    },
    MissingGlyph {
        character: char,
        span: Range<usize>,
    },
}

impl fmt::Display for LayoutError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidFontSize => write!(f, "font size must be finite and positive"),
            Self::Unsupported { span } => write!(f, "unsupported notation at bytes {span:?}"),
            Self::Syntax { expected, span } => write!(f, "expected {expected} at bytes {span:?}"),
            Self::LimitExceeded { span } => {
                write!(f, "expression limit exceeded at bytes {span:?}")
            }
            Self::MissingGlyph { character, span } => {
                write!(
                    f,
                    "font lacks a usable glyph for {character:?} at bytes {span:?}"
                )
            }
        }
    }
}

impl Error for LayoutError {}
