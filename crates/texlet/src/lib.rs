//! Layout for a small subset of LaTeX math expressions.

use std::{error::Error, fmt, ops::Range};
use ttf_parser::{Face, FaceParsingError};

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
    Unsupported { span: Range<usize> },
    ExpectedBlackboardArgument { span: Range<usize> },
    MissingGlyph { character: char, span: Range<usize> },
}

impl fmt::Display for LayoutError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidFontSize => write!(f, "font size must be finite and positive"),
            Self::Unsupported { span } => write!(f, "unsupported notation at bytes {span:?}"),
            Self::ExpectedBlackboardArgument { span } => {
                write!(
                    f,
                    "expected a single braced uppercase ASCII letter at bytes {span:?}"
                )
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

/// Lays out an expression body without math-mode delimiters.
pub fn layout<'font, 'data>(
    latex: &str,
    font: &'font MathFont<'data>,
    options: LayoutOptions,
) -> Result<Layout<'font, 'data>, LayoutError> {
    let size = options.font_size;
    if !size.is_finite() || size <= 0.0 {
        return Err(LayoutError::InvalidFontSize);
    }
    let scale = size / f64::from(font.units_per_em());
    let mut metrics = Metrics {
        width: 0.0,
        ascent: 0.0,
        descent: 0.0,
        ink_left: 0.0,
        ink_right: 0.0,
    };
    let mut commands = Vec::new();
    let mut previous_relation = false;
    for atom in (Atoms {
        remaining: latex,
        offset: 0,
    }) {
        let (character, span) = atom?;
        let relation = matches!(character, '∈' | '=' | '<' | '>');
        if !commands.is_empty() && (relation || previous_relation) {
            metrics.width += size * (5.0 / 18.0);
        }
        let missing = || LayoutError::MissingGlyph {
            character,
            span: span.clone(),
        };
        let id = font.face.glyph_index(character).ok_or_else(missing)?;
        let advance = font.face.glyph_hor_advance(id).ok_or_else(missing)?;
        let bounds = font.face.glyph_bounding_box(id).ok_or_else(missing)?;
        let x = metrics.width;
        metrics.ascent = metrics.ascent.max(f64::from(bounds.y_max) * scale);
        metrics.descent = metrics.descent.max(-f64::from(bounds.y_min) * scale);
        metrics.ink_left = metrics.ink_left.min(x + f64::from(bounds.x_min) * scale);
        metrics.ink_right = metrics.ink_right.max(x + f64::from(bounds.x_max) * scale);
        metrics.width += f64::from(advance) * scale;
        if !metrics.width.is_finite()
            || !metrics.ink_right.is_finite()
            || !metrics.ink_left.is_finite()
            || !metrics.ascent.is_finite()
            || !metrics.descent.is_finite()
        {
            return Err(LayoutError::InvalidFontSize);
        }
        commands.push(DrawCommand::Glyph {
            id: id.0,
            x,
            y: 0.0,
            size,
        });
        previous_relation = relation;
    }
    Ok(Layout {
        font,
        metrics,
        commands,
    })
}

struct Atoms<'a> {
    remaining: &'a str,
    offset: usize,
}

impl Atoms<'_> {
    fn take(&mut self, bytes: usize) -> Range<usize> {
        let start = self.offset;
        self.offset += bytes;
        self.remaining = &self.remaining[bytes..];
        start..self.offset
    }

    fn skip_space(&mut self) {
        self.take(
            self.remaining.len()
                - self
                    .remaining
                    .trim_start_matches(|c| matches!(c, ' ' | '\t'..='\r'))
                    .len(),
        );
    }
}

impl Iterator for Atoms<'_> {
    type Item = Result<(char, Range<usize>), LayoutError>;

    fn next(&mut self) -> Option<Self::Item> {
        self.skip_space();
        let character = self.remaining.chars().next()?;
        if character != '\\' {
            let span = self.take(character.len_utf8());
            return Some(
                if character.is_ascii_alphanumeric()
                    || matches!(
                        character,
                        '+' | '-' | '=' | '<' | '>' | '(' | ')' | '[' | ']' | '/' | '.' | ','
                    )
                {
                    Ok((if character == '-' { '−' } else { character }, span))
                } else {
                    Err(LayoutError::Unsupported { span })
                },
            );
        }

        let word_len = self.remaining[1..]
            .bytes()
            .take_while(u8::is_ascii_alphabetic)
            .count();
        if word_len == 0 {
            let symbol_len = self.remaining[1..].chars().next().map_or(0, char::len_utf8);
            return Some(Err(LayoutError::Unsupported {
                span: self.take(1 + symbol_len),
            }));
        }
        let command = &self.remaining[1..1 + word_len];
        let span = self.take(1 + word_len);
        match command {
            "in" => Some(Ok(('∈', span))),
            "mathbb" => Some(self.blackboard(span)),
            _ => Some(Err(LayoutError::Unsupported { span })),
        }
    }
}

impl Atoms<'_> {
    fn blackboard(&mut self, mut span: Range<usize>) -> Result<(char, Range<usize>), LayoutError> {
        self.skip_space();
        if !self.remaining.starts_with('{') {
            return Err(LayoutError::ExpectedBlackboardArgument { span });
        }
        self.take(1);
        self.skip_space();
        let Some(argument) = self.remaining.chars().next() else {
            return Err(LayoutError::ExpectedBlackboardArgument { span });
        };
        if !argument.is_ascii_uppercase() {
            return Err(LayoutError::ExpectedBlackboardArgument { span });
        }
        self.take(1);
        self.skip_space();
        if !self.remaining.starts_with('}') {
            return Err(LayoutError::ExpectedBlackboardArgument { span });
        }
        span.end = self.take(1).end;
        let symbol = match argument {
            'C' => 'ℂ',
            'H' => 'ℍ',
            'N' => 'ℕ',
            'P' => 'ℙ',
            'Q' => 'ℚ',
            'R' => 'ℝ',
            'Z' => 'ℤ',
            _ => char::from_u32(0x1D538 + u32::from(argument) - u32::from('A'))
                .expect("uppercase ASCII maps inside the mathematical alphabet"),
        };
        Ok((symbol, span))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn atoms(expression: &str) -> Atoms<'_> {
        Atoms {
            remaining: expression,
            offset: 0,
        }
    }

    #[test]
    fn parses_membership_and_both_blackboard_unicode_ranges() {
        let parsed = atoms(r"x \in \mathbb{Z}\mathbb{A}")
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        assert_eq!(
            parsed,
            vec![('x', 0..1), ('∈', 2..5), ('ℤ', 6..16), ('𝔸', 16..26)]
        );
    }

    #[test]
    fn parses_all_tier_zero_literals_and_ignores_ascii_whitespace() {
        let literals = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-=<>()[]/.,";
        let source = format!(" \t\n\r\x0b\x0c{literals}");
        let characters: String = atoms(&source).map(|atom| atom.unwrap().0).collect();
        assert_eq!(characters, literals.replace('-', "−"));
    }

    #[test]
    fn parses_the_full_blackboard_alphabet_with_whitespace() {
        let source: String = ('A'..='Z')
            .map(|letter| format!("\\mathbb {{ {letter} }}"))
            .collect();
        let characters: String = atoms(&source).map(|atom| atom.unwrap().0).collect();
        assert_eq!(characters, "𝔸𝔹ℂ𝔻𝔼𝔽𝔾ℍ𝕀𝕁𝕂𝕃𝕄ℕ𝕆ℙℚℝ𝕊𝕋𝕌𝕍𝕎𝕏𝕐ℤ");
        assert_eq!(atoms(r"\mathbb { Z }").next(), Some(Ok(('ℤ', 0..13))));
    }

    #[test]
    fn rejects_unsupported_commands_without_matching_prefixes() {
        for (source, span) in [
            (r"x \infty", 2..8),
            (r"x \%", 2..4),
            (r"x \α", 2..5),
            (r"x \", 2..3),
        ] {
            assert_eq!(
                atoms(source).nth(1),
                Some(Err(LayoutError::Unsupported { span }))
            );
        }
    }

    #[test]
    fn reports_byte_offsets_and_handles_malformed_arguments() {
        assert_eq!(
            atoms("x α").nth(1),
            Some(Err(LayoutError::Unsupported { span: 2..4 }))
        );
        for source in [
            r"\mathbb",
            r"\mathbb{",
            r"\mathbb{Z",
            r"\mathbb{α}",
            r"\mathbb{ZZ}",
        ] {
            assert!(matches!(
                atoms(source).next(),
                Some(Err(LayoutError::ExpectedBlackboardArgument { .. }))
            ));
        }
    }
}
