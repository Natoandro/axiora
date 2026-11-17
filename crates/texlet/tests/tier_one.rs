use texlet::{
    DrawCommand, LayoutError, LayoutOptions, MathFont, MathStyle, layout, layout_with_style,
};

const FONT_SIZE: LayoutOptions = LayoutOptions { font_size: 48.0 };

#[test]
#[ignore = "requires TEXLET_TEST_FONT pointing to Latin Modern Math"]
fn renders_tier_one_with_latin_modern_math() {
    let bytes = std::fs::read(std::env::var_os("TEXLET_TEST_FONT").expect("set TEXLET_TEST_FONT"))
        .expect("read math font");
    let font = MathFont::from_bytes(&bytes, 0).unwrap();
    for expression in [
        r"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-=<>()[]/.,",
        r"\mathbb{ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789}",
        r"\mathit{xy}\mathrm{dx}\mathbf{v2}\mathsf{abc2}\mathtt{XYZ0}\mathcal{ABCDEFGHIJKLMNOPQRSTUVWXYZ}",
        r"\frac{{x_i'}^2}{\sqrt[3]{1+\frac{x}{2}}} \text{for real }x",
        r"\alpha\beta\gamma\delta\epsilon\varepsilon\zeta\eta\theta\vartheta\iota\kappa\varkappa\lambda\mu\nu\xi\omicron\pi\varpi\rho\varrho\sigma\varsigma\tau\upsilon\phi\varphi\chi\psi\omega",
        r"\Gamma\Delta\Theta\Lambda\Xi\Pi\Sigma\Upsilon\Phi\Psi\Omega",
        r"\times\cdot\div\pm\mp\neq\leq\geq\approx\sim\equiv\infty\partial\nabla\ldots\cdots",
        r"\left\{\left\langle\frac{a}{b}\right\rangle\right.\Bigg\}\lvert x\rvert\lVert x\rVert\lfloor x\rfloor\lceil x\rceil",
        r"\sin\cos\tan\arcsin\log\ln\exp\max\min\lim\gcd\det\operatorname{rank}",
        r"\sum_{i=1}^{n}\prod_j\int_0^1\iint\limits_A\oint\nolimits_C",
        r"\in\notin\ni\subset\subseteq\supset\supseteq\cup\cap\setminus\emptyset\varnothing\forall\exists\neg\land\lor",
        r"\to\mapsto\leftarrow\leftrightarrow\implies\iff\Rightarrow\Leftarrow\Leftrightarrow\longrightarrow\longleftarrow\longleftrightarrow\longmapsto\Longleftarrow",
    ] {
        for style in [MathStyle::Inline, MathStyle::Display] {
            let rendered = layout_with_style(expression, &font, FONT_SIZE, style)
                .unwrap_or_else(|error| panic!("{expression} ({style:?}): {error}"));
            assert!(rendered.metrics().width > 0.0);
        }
    }

    let fraction = layout(r"\frac{x}{y}", &font, FONT_SIZE).unwrap();
    assert!(matches!(fraction.commands(), [
        DrawCommand::Glyph { y: top, .. }, DrawCommand::Glyph { y: bottom, .. },
        DrawCommand::Rule { height, .. },
    ] if *top < 0.0 && *bottom > 0.0 && *height > 0.0));
    let display_fraction =
        layout_with_style(r"\frac{x}{y}", &font, FONT_SIZE, MathStyle::Display).unwrap();
    assert!(display_fraction.metrics().width > fraction.metrics().width);

    let baseline = layout("xy", &font, FONT_SIZE).unwrap().metrics().width;
    for (command, mu) in [
        (r"\,", 3),
        (r"\:", 4),
        (r"\;", 5),
        (r"\!", -3),
        (r"\quad ", 18),
        (r"\qquad ", 36),
    ] {
        let spaced = layout(&format!("x{command}y"), &font, FONT_SIZE).unwrap();
        let expected = FONT_SIZE.font_size * f64::from(mu) / 18.0;
        assert!((spaced.metrics().width - baseline - expected).abs() < 1e-9);
    }

    let lower_x = |expression, style| {
        let rendered = layout_with_style(expression, &font, FONT_SIZE, style).unwrap();
        match rendered.commands()[1] {
            DrawCommand::Glyph { x, .. } => x,
            _ => panic!("expected a lower-limit glyph"),
        }
    };
    assert!(
        lower_x(r"\sum_i", MathStyle::Display) < lower_x(r"\sum\nolimits_i", MathStyle::Display)
    );
    assert!(lower_x(r"\sum\limits_i", MathStyle::Inline) < lower_x(r"\sum_i", MathStyle::Inline));

    for style in [MathStyle::Inline, MathStyle::Display] {
        for source in [r"\int_0^1", r"\iint_0^1", r"\oint_0^1"] {
            let limits = layout_with_style(source, &font, FONT_SIZE, style).unwrap();
            assert!(matches!(limits.commands(), [
                DrawCommand::Glyph { .. }, DrawCommand::Glyph { x: upper, .. },
                DrawCommand::Glyph { x: lower, .. },
            ] if lower < upper));
        }
    }

    assert!(matches!(
        layout(r"\text{🦀}", &font, FONT_SIZE),
        Err(LayoutError::MissingGlyph {
            character: '🦀',
            ..
        })
    ));
}
