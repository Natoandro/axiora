use crate::parser::Class::{self, *};

pub fn symbol(command: &str) -> Option<(char, Class)> {
    let greek = match command {
        "alpha" => 'α',
        "beta" => 'β',
        "gamma" => 'γ',
        "delta" => 'δ',
        "epsilon" => 'ϵ',
        "varepsilon" => 'ε',
        "zeta" => 'ζ',
        "eta" => 'η',
        "theta" => 'θ',
        "vartheta" => 'ϑ',
        "iota" => 'ι',
        "kappa" => 'κ',
        "varkappa" => 'ϰ',
        "lambda" => 'λ',
        "mu" => 'μ',
        "nu" => 'ν',
        "xi" => 'ξ',
        "omicron" => 'ο',
        "pi" => 'π',
        "varpi" => 'ϖ',
        "rho" => 'ρ',
        "varrho" => 'ϱ',
        "sigma" => 'σ',
        "varsigma" => 'ς',
        "tau" => 'τ',
        "upsilon" => 'υ',
        "phi" => 'ϕ',
        "varphi" => 'φ',
        "chi" => 'χ',
        "psi" => 'ψ',
        "omega" => 'ω',
        "Gamma" => 'Γ',
        "Delta" => 'Δ',
        "Theta" => 'Θ',
        "Lambda" => 'Λ',
        "Xi" => 'Ξ',
        "Pi" => 'Π',
        "Sigma" => 'Σ',
        "Upsilon" => 'Υ',
        "Phi" => 'Φ',
        "Psi" => 'Ψ',
        "Omega" => 'Ω',
        _ => {
            return Some(match command {
                "times" => ('×', Bin),
                "cdot" => ('⋅', Bin),
                "div" => ('÷', Bin),
                "pm" => ('±', Bin),
                "mp" => ('∓', Bin),
                "ne" | "neq" => ('≠', Rel),
                "le" | "leq" => ('≤', Rel),
                "ge" | "geq" => ('≥', Rel),
                "approx" => ('≈', Rel),
                "sim" => ('∼', Rel),
                "equiv" => ('≡', Rel),
                "infty" => ('∞', Ord),
                "partial" => ('∂', Ord),
                "nabla" => ('∇', Ord),
                "ldots" => ('…', Ord),
                "cdots" => ('⋯', Ord),
                "in" => ('∈', Rel),
                "notin" => ('∉', Rel),
                "ni" | "owns" => ('∋', Rel),
                "subset" => ('⊂', Rel),
                "subseteq" => ('⊆', Rel),
                "supset" => ('⊃', Rel),
                "supseteq" => ('⊇', Rel),
                "cup" => ('∪', Bin),
                "cap" => ('∩', Bin),
                "setminus" => ('∖', Bin),
                "emptyset" => ('∅', Ord),
                "varnothing" => ('⌀', Ord),
                "forall" => ('∀', Ord),
                "exists" => ('∃', Ord),
                "neg" | "lnot" => ('¬', Ord),
                "land" | "wedge" => ('∧', Bin),
                "lor" | "vee" => ('∨', Bin),
                "to" | "rightarrow" => ('→', Rel),
                "mapsto" => ('↦', Rel),
                "gets" | "leftarrow" => ('←', Rel),
                "leftrightarrow" => ('↔', Rel),
                "Rightarrow" => ('⇒', Rel),
                "Leftarrow" => ('⇐', Rel),
                "Leftrightarrow" => ('⇔', Rel),
                "longrightarrow" => ('⟶', Rel),
                "longleftarrow" => ('⟵', Rel),
                "longleftrightarrow" => ('⟷', Rel),
                "longmapsto" => ('⟼', Rel),
                "Longrightarrow" | "implies" => ('⟹', Rel),
                "Longleftarrow" | "impliedby" => ('⟸', Rel),
                "Longleftrightarrow" | "iff" => ('⟺', Rel),
                _ => return None,
            });
        }
    };
    Some((greek, Ord))
}

pub fn delimiter(token: &str) -> Option<(char, Class)> {
    Some(match token {
        "(" => ('(', Open),
        ")" => (')', Close),
        "[" | "\\lbrack" => ('[', Open),
        "]" | "\\rbrack" => (']', Close),
        "\\{" | "\\lbrace" => ('{', Open),
        "\\}" | "\\rbrace" => ('}', Close),
        "\\langle" => ('⟨', Open),
        "\\rangle" => ('⟩', Close),
        "\\lfloor" => ('⌊', Open),
        "\\rfloor" => ('⌋', Close),
        "\\lceil" => ('⌈', Open),
        "\\rceil" => ('⌉', Close),
        "\\lvert" => ('|', Open),
        "\\rvert" => ('|', Close),
        "\\lVert" => ('‖', Open),
        "\\rVert" => ('‖', Close),
        "|" | "\\vert" => ('|', Ord),
        "\\|" | "\\Vert" => ('‖', Ord),
        _ => return None,
    })
}

#[derive(Clone, Copy)]
pub enum Alphabet {
    Default,
    Roman,
    Italic,
    Bold,
    Sans,
    Mono,
    Script,
    Blackboard,
}

impl Alphabet {
    pub fn map(self, c: char) -> char {
        use Alphabet::*;
        let base = match (self, c) {
            (Default | Italic, 'α'..='ω') => {
                return char::from_u32(0x1d6fc + u32::from(c) - u32::from('α')).unwrap();
            }
            (Default | Italic, 'ϵ') => return '𝜖',
            (Default | Italic, 'ϑ') => return '𝜗',
            (Default | Italic, 'ϰ') => return '𝜘',
            (Default | Italic, 'ϕ') => return '𝜙',
            (Default | Italic, 'ϱ') => return '𝜚',
            (Default | Italic, 'ϖ') => return '𝜛',
            (Italic, 'Α'..='Ω') => {
                return char::from_u32(0x1d6e2 + u32::from(c) - u32::from('Α')).unwrap();
            }
            (Default | Italic, 'h') => return 'ℎ',
            (Default | Italic, 'A'..='Z') => 0x1d434,
            (Default | Italic, 'a'..='z') => 0x1d44e,
            (Bold, 'A'..='Z') => 0x1d400,
            (Bold, 'a'..='z') => 0x1d41a,
            (Bold, '0'..='9') => 0x1d7ce,
            (Sans, 'A'..='Z') => 0x1d5a0,
            (Sans, 'a'..='z') => 0x1d5ba,
            (Sans, '0'..='9') => 0x1d7e2,
            (Mono, 'A'..='Z') => 0x1d670,
            (Mono, 'a'..='z') => 0x1d68a,
            (Mono, '0'..='9') => 0x1d7f6,
            (Script, 'A'..='Z') => {
                if let Some(special) = "BEFHILMR".find(c) {
                    return "ℬℰℱℋℐℒℳℛ".chars().nth(special).unwrap();
                }
                0x1d49c
            }
            (Blackboard, 'A'..='Z') => {
                if let Some(special) = "CHNPQRZ".find(c) {
                    return "ℂℍℕℙℚℝℤ".chars().nth(special).unwrap();
                }
                0x1d538
            }
            (Blackboard, 'a'..='z') => 0x1d552,
            (Blackboard, '0'..='9') => 0x1d7d8,
            _ => return c,
        };
        let first = if c.is_ascii_uppercase() {
            'A'
        } else if c.is_ascii_lowercase() {
            'a'
        } else {
            '0'
        };
        char::from_u32(base + u32::from(c) - u32::from(first)).unwrap()
    }
}
