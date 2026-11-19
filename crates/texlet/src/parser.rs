use std::ops::Range;

use crate::{
    LayoutError,
    symbols::{self, Alphabet},
};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Class {
    Ord,
    Op,
    Bin,
    Rel,
    Open,
    Close,
    Punct,
    Inner,
}

pub struct Atom {
    pub kind: Kind,
    pub class: Class,
    pub span: Range<usize>,
}

pub enum Kind {
    Glyph(char),
    Sized(char, f64),
    Operator(char),
    Text(String),
    Row(Vec<Atom>),
    Alphabet(Alphabet, Box<Atom>),
    Fraction(Box<Atom>, Box<Atom>),
    Radical(Option<Vec<Atom>>, Box<Atom>),
    Delimited(Option<AtomDelimiter>, Vec<Atom>, Option<AtomDelimiter>),
    Scripts {
        base: Box<Atom>,
        upper: Vec<Atom>,
        lower: Option<Box<Atom>>,
        limits: Option<bool>,
    },
    Space(f64),
}

pub type AtomDelimiter = (char, Range<usize>);

pub const MAX_SOURCE_BYTES: usize = 4096;
const MAX_DEPTH: usize = 32;

pub fn parse(source: &str) -> Result<Vec<Atom>, LayoutError> {
    if source.len() > MAX_SOURCE_BYTES {
        return Err(LayoutError::LimitExceeded {
            span: 0..source.len(),
        });
    }
    Parser { source, offset: 0 }.row(None, 0)
}

struct Parser<'a> {
    source: &'a str,
    offset: usize,
}

impl<'a> Parser<'a> {
    fn peek(&mut self) -> &'a str {
        let rest =
            self.source[self.offset..].trim_start_matches(|c| matches!(c, ' ' | '\t'..='\r'));
        self.offset = self.source.len() - rest.len();
        let mut chars = rest.chars();
        match chars.next() {
            None => "",
            Some('\\') => {
                let word = rest[1..]
                    .bytes()
                    .take_while(u8::is_ascii_alphabetic)
                    .count();
                &rest[..1 + if word == 0 {
                    chars.next().map_or(0, char::len_utf8)
                } else {
                    word
                }]
            }
            Some(c) => &rest[..c.len_utf8()],
        }
    }

    fn take(&mut self) -> (&'a str, Range<usize>) {
        let token = self.peek();
        let start = self.offset;
        self.offset += token.len();
        (token, start..self.offset)
    }

    fn expected(&mut self, expected: &'static str) -> LayoutError {
        let len = self.peek().len();
        LayoutError::Syntax {
            expected,
            span: self.offset..self.offset + len,
        }
    }

    fn check_depth(&mut self, depth: usize) -> Result<(), LayoutError> {
        if depth > MAX_DEPTH {
            let len = self.peek().len();
            return Err(LayoutError::LimitExceeded {
                span: self.offset..self.offset + len,
            });
        }
        Ok(())
    }

    fn row(&mut self, end: Option<&str>, depth: usize) -> Result<Vec<Atom>, LayoutError> {
        self.check_depth(depth)?;
        let mut atoms = Vec::new();
        loop {
            let token = self.peek();
            if Some(token) == end {
                self.take();
                return Ok(atoms);
            }
            if token.is_empty() {
                return if end.is_none() {
                    Ok(atoms)
                } else {
                    Err(self.expected("a closing group, root index, or \\right delimiter"))
                };
            }
            atoms.push(self.decorated(depth)?);
        }
    }

    fn decorated(&mut self, depth: usize) -> Result<Atom, LayoutError> {
        let base = self.atom(depth)?;
        let mut upper = Vec::new();
        let mut lower = None;
        let mut limits = None;
        let mut explicit_upper = false;
        loop {
            match self.peek() {
                "^" if !explicit_upper => {
                    self.take();
                    upper.push(self.atom(depth + 1)?);
                    explicit_upper = true;
                }
                "_" if lower.is_none() => {
                    self.take();
                    lower = Some(Box::new(self.atom(depth + 1)?));
                }
                "'" if !explicit_upper => {
                    let (_, span) = self.take();
                    upper.push(Atom {
                        kind: Kind::Glyph('′'),
                        class: Class::Ord,
                        span,
                    });
                }
                "^" | "_" | "'" => {
                    return Err(self.expected("at most one subscript and one superscript"));
                }
                "\\limits" | "\\nolimits" if base.class == Class::Op => {
                    limits = Some(self.take().0 == "\\limits");
                }
                "\\limits" | "\\nolimits" => {
                    return Err(self.expected("an operator before a limits modifier"));
                }
                _ => break,
            }
        }
        if upper.is_empty() && lower.is_none() {
            return Ok(base);
        }
        Ok(Atom {
            class: base.class,
            span: base.span.start..self.offset,
            kind: Kind::Scripts {
                base: Box::new(base),
                upper,
                lower,
                limits,
            },
        })
    }

    fn atom(&mut self, depth: usize) -> Result<Atom, LayoutError> {
        use Class::*;
        self.check_depth(depth)?;
        let (token, span) = self.take();
        let (kind, class) = match token {
            "{" => (Kind::Row(self.row(Some("}"), depth + 1)?), Ord),
            "\\frac" => (
                Kind::Fraction(
                    Box::new(self.atom(depth + 1)?),
                    Box::new(self.atom(depth + 1)?),
                ),
                Inner,
            ),
            "\\sqrt" => {
                let index = if self.peek() == "[" {
                    self.take();
                    Some(self.row(Some("]"), depth + 1)?)
                } else {
                    None
                };
                (Kind::Radical(index, Box::new(self.atom(depth + 1)?)), Ord)
            }
            "\\left" => {
                let left = self.delimiter(true)?.0;
                let body = self.row(Some("\\right"), depth + 1)?;
                let right = self.delimiter(true)?.0;
                (Kind::Delimited(left, body, right), Inner)
            }
            "\\text" | "\\operatorname" => {
                let text = self.text()?;
                (Kind::Text(text), if token == "\\text" { Ord } else { Op })
            }
            "\\mathrm" | "\\mathbf" | "\\mathit" | "\\mathsf" | "\\mathtt" | "\\mathcal"
            | "\\mathbb" => {
                let alphabet = match token {
                    "\\mathrm" => Alphabet::Roman,
                    "\\mathbf" => Alphabet::Bold,
                    "\\mathit" => Alphabet::Italic,
                    "\\mathsf" => Alphabet::Sans,
                    "\\mathtt" => Alphabet::Mono,
                    "\\mathcal" => Alphabet::Script,
                    _ => Alphabet::Blackboard,
                };
                if self.peek() != "{" {
                    return Err(self.expected("a braced alphabet argument"));
                }
                (
                    Kind::Alphabet(alphabet, Box::new(self.atom(depth + 1)?)),
                    Ord,
                )
            }
            "\\sin" | "\\cos" | "\\tan" | "\\arcsin" | "\\log" | "\\ln" | "\\exp" | "\\max"
            | "\\min" | "\\lim" | "\\gcd" | "\\det" => (Kind::Text(token[1..].into()), Op),
            "\\sum" | "\\prod" | "\\int" | "\\iint" | "\\oint" => {
                let glyph = match token {
                    "\\sum" => '∑',
                    "\\prod" => '∏',
                    "\\int" => '∫',
                    "\\iint" => '∬',
                    _ => '∮',
                };
                (Kind::Operator(glyph), Op)
            }
            "\\," | "\\:" | "\\;" | "\\!" | "\\quad" | "\\qquad" => {
                let mu = match token {
                    "\\," => 3,
                    "\\:" => 4,
                    "\\;" => 5,
                    "\\!" => -3,
                    "\\quad" => 18,
                    _ => 36,
                };
                (Kind::Space(f64::from(mu) / 18.0), Ord)
            }
            _ if token.starts_with("\\big") || token.starts_with("\\Big") => {
                let command = &token[1..];
                let name = command.trim_end_matches(['l', 'r', 'm']);
                let scale = match name {
                    "big" => 1.2,
                    "Big" => 1.55,
                    "bigg" => 1.95,
                    "Bigg" => 2.4,
                    _ => return Err(LayoutError::Unsupported { span }),
                };
                let (delimiter, _) = self.delimiter(false)?;
                let (glyph, _) = delimiter.expect("visible delimiter required");
                let class = match &command[name.len()..] {
                    "" => Ord,
                    "l" => Open,
                    "r" => Close,
                    "m" => Rel,
                    _ => return Err(LayoutError::Unsupported { span }),
                };
                (Kind::Sized(glyph, scale), class)
            }
            "" | "}" | "^" | "_" | "'" | "\\right" => {
                return Err(LayoutError::Syntax {
                    expected: "a math atom",
                    span,
                });
            }
            _ => {
                let Some((glyph, class)) = symbols::delimiter(token)
                    .or_else(|| token.strip_prefix('\\').and_then(symbols::symbol))
                    .or_else(|| literal(token))
                else {
                    return Err(LayoutError::Unsupported { span });
                };
                (Kind::Glyph(glyph), class)
            }
        };
        Ok(Atom {
            kind,
            class,
            span: span.start..self.offset,
        })
    }

    fn delimiter(
        &mut self,
        invisible: bool,
    ) -> Result<(Option<AtomDelimiter>, Class), LayoutError> {
        let (token, span) = self.take();
        if invisible && token == "." {
            return Ok((None, Class::Ord));
        }
        symbols::delimiter(token)
            .map(|(glyph, class)| (Some((glyph, span.clone())), class))
            .ok_or(LayoutError::Syntax {
                expected: "a delimiter",
                span,
            })
    }

    fn text(&mut self) -> Result<String, LayoutError> {
        if self.peek() != "{" {
            return Err(self.expected("braced text"));
        }
        self.take();
        let mut text = String::new();
        let mut depth = 1;
        while let Some(c) = self.source[self.offset..].chars().next() {
            let start = self.offset;
            self.offset += c.len_utf8();
            match c {
                '{' => depth += 1,
                '}' => {
                    depth -= 1;
                    if depth == 0 {
                        return Ok(text);
                    }
                }
                '\\' => {
                    let (token, span) = self.take();
                    match token {
                        "{" | "}" | "%" | "$" | "#" | "&" | "_" | "\\" => text.push_str(token),
                        _ => {
                            return Err(LayoutError::Unsupported {
                                span: start..span.end,
                            });
                        }
                    }
                }
                c if c.is_ascii_whitespace() => text.push(' '),
                c => text.push(c),
            }
        }
        Err(self.expected("a closing text brace"))
    }
}

fn literal(token: &str) -> Option<(char, Class)> {
    use Class::*;
    let c = token.chars().next()?;
    Some(match c {
        'a'..='z' | 'A'..='Z' | '0'..='9' | '.' | '/' => (c, Ord),
        '+' | '*' => (c, Bin),
        '-' => ('−', Bin),
        '=' | '<' | '>' | ':' => (c, Rel),
        ',' | ';' => (c, Punct),
        _ => return None,
    })
}

#[cfg(test)]
mod tests;
