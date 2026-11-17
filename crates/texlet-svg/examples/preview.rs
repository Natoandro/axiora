use std::{
    env,
    error::Error,
    fs,
    io::{self, Write as _},
};
use texlet::{LayoutOptions, MathFont, layout};

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<_> = env::args().skip(1).collect();
    let [font_path, expression] = args.as_slice() else {
        return Err("usage: preview <font-file> <latex-expression>".into());
    };
    let bytes = fs::read(font_path)?;
    let font = MathFont::from_bytes(&bytes, 0)?;
    let expression = layout(expression, &font, LayoutOptions { font_size: 48.0 })?;
    io::stdout()
        .lock()
        .write_all(texlet_svg::render(&expression)?.as_bytes())?;
    Ok(())
}
