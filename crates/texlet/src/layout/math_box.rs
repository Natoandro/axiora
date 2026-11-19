use crate::{DrawCommand, Metrics};

pub(super) struct MathBox {
    pub(super) metrics: Metrics,
    pub(super) commands: Vec<DrawCommand>,
    pub(super) bounds: Option<[f64; 4]>,
}

impl MathBox {
    pub(super) fn new() -> Self {
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

    pub(super) fn ink(&mut self, left: f64, top: f64, right: f64, bottom: f64) {
        self.bounds = Some(match self.bounds {
            Some([l, t, r, b]) => [l.min(left), t.min(top), r.max(right), b.max(bottom)],
            None => [left, top, right, bottom],
        });
        self.metrics.ascent = self.metrics.ascent.max(-top);
        self.metrics.descent = self.metrics.descent.max(bottom);
        self.metrics.ink_left = self.metrics.ink_left.min(left);
        self.metrics.ink_right = self.metrics.ink_right.max(right);
    }

    pub(super) fn place(&mut self, child: Self, x: f64, y: f64) {
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

    pub(super) fn append(&mut self, child: Self) {
        let width = child.metrics.width;
        self.place(child, self.metrics.width, 0.0);
        self.metrics.width += width;
    }

    pub(super) fn rule(&mut self, x: f64, y: f64, width: f64, height: f64) {
        self.ink(x, y, x + width, y + height);
        self.commands.push(DrawCommand::Rule {
            x,
            y,
            width,
            height,
        });
    }
}
