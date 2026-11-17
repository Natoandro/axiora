package mg.abela.axiora.mathspike;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class MathActivity extends Activity {
    private LinearLayout page;
    private byte[] font;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(page);
        setContentView(scroll);

        font = loadFont();
        heading("Texlet · Tier 1");
        example("Ideal membership", "a \\in I", "a belongs to the ideal I.");
        example("Quotient ring", "\\mathbb{Z}/(0)", "The integers modulo the zero ideal.");
        example("Number sets", "2 \\in \\mathbb{N}, -2 \\in \\mathbb{Z}, 1/2 \\in \\mathbb{Q}",
            "Two is a natural number, minus two is an integer, and one half is rational.");
        example("Arithmetic and comparison", "(3 - 1)/2 + 0.5 = 1.5, 1 < 1.5, 2 > 1.5",
            "Three minus one, divided by two, plus one half is one and a half. "
                + "One is less than one and a half, and two is greater.");
        example("Interval", "0.5 \\in [0, 1]", "One half belongs to the closed interval from zero to one.");
        example(
            "Long expression",
            "a \\in I, b \\in I, c \\in I, d \\in I, e \\in I, f \\in I",
            "Each of a, b, c, d, e, and f belongs to the ideal I."
        );
        example("Nested fraction and indexed root", "\\frac{1+x_i^2}{\\sqrt[3]{1+\\frac{x}{2}}}",
            "One plus x sub i squared, divided by the cube root of one plus x over two.", true);
        example("Greek letters and primes", "{\\varphi}''(x) + \\alpha\\cdot\\beta \\approx \\pi",
            "Phi double prime of x plus alpha times beta is approximately pi.");
        example("Math alphabets and text",
            "\\mathbb{RQ}\\quad\\mathbf{v}\\;\\mathit{dx}\\;\\mathrm{d}x\\;\\mathsf{A}\\;\\mathtt{id}\\;\\mathcal{F}",
            "Blackboard bold R Q, bold v, italic d x, upright d followed by x, sans serif A, monospace i d, and calligraphic F.");
        example("Delimiters and named operator",
            "\\left\\lVert\\frac{x}{2}\\right\\rVert \\leq \\operatorname{norm}\\bigl(x\\bigr)",
            "The norm of x over two is less than or equal to the norm of x.", true);
        String sum = "\\sum_{k=1}^{n} k = \\frac{n(n+1)}{2}";
        example("Sum with inline limits", sum, "The sum from k equals one to n of k equals n times n plus one over two.");
        example("Sum with display limits", sum, "The same identity with limits above and below the sum.", true);
        example("Side limits and spacing", "\\int_0^1 x^2\\,\\mathrm{d}x = \\frac{1}{3}\\quad\\text{for real }x",
            "The integral from zero to one of x squared is one third, for real x.", true);
        example("Sets, logic, and arrows",
            "A\\subseteq B \\implies (x\\in A \\Rightarrow x\\in B)",
            "If A is a subset of B, then membership in A implies membership in B.");
        example("Unsupported notation", "\\binom{n}{k}", "n choose k.");
    }

    private void example(String title, String source, String explanation) {
        example(title, source, explanation, false);
    }

    private void example(String title, String source, String explanation, boolean display) {
        heading(title);
        MathCanvas preview = null;
        if (font != null) {
            try {
                preview = MathCanvas.create(this, font, source, display);
            } catch (LinkageError error) {
                android.util.Log.w("Texlet", "Native renderer unavailable", error);
                font = null;
            }
        }
        if (preview == null) {
            text("Preview unavailable. Read the LaTeX source below.", 16);
        } else {
            HorizontalScrollView viewport = new HorizontalScrollView(this);
            viewport.addView(preview);
            viewport.setPadding(0, dp(4), 0, dp(8));
            page.addView(viewport);
        }
        text(explanation, 16);
        TextView raw = text("LaTeX source\n" + source, 16);
        raw.setTextIsSelectable(true);
    }

    private byte[] loadFont() {
        try (InputStream input = getAssets().open("math.otf");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException error) {
            android.util.Log.w("Texlet", "Math font unavailable", error);
            return null;
        }
    }

    private void heading(String value) {
        TextView view = text(value, 20);
        if (Build.VERSION.SDK_INT >= 28) {
            view.setAccessibilityHeading(true);
        }
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 0, 0, dp(12));
        page.addView(view);
        return view;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }
}
