package mg.abela.axiora.mathspike;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MathActivity extends Activity {
    private LinearLayout page;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(page);
        setContentView(scroll);

        heading("Mathematical notation");
        example("Ideal membership", "a \\in I", "a belongs to the ideal I.");
        example("Quotient ring", "\\mathbb{Z}/(0)", "The integers modulo the zero ideal.");
        example(
            "Long expression",
            "a \\in I and b \\in I and c \\in I and d \\in I and e \\in I and f \\in I",
            "Each of a, b, c, d, e, and f belongs to the ideal I."
        );
        example("Unsupported notation", "\\frac{a}{b}", "a divided by b.");
    }

    private void example(String title, String source, String explanation) {
        heading(title);
        TextView preview = text("", 22);
        String display = source.replaceAll("\\\\in(?![A-Za-z])", "∈")
            .replace("\\mathbb{Z}", "ℤ");
        boolean supported = display.matches("[a-zA-Z0-9 ∈ℤ/()=+,-]+")
            && (!display.contains("ℤ") || preview.getPaint().hasGlyph("ℤ"))
            && (!display.contains("∈") || preview.getPaint().hasGlyph("∈"));
        if (supported) {
            preview.setText(display);
            preview.setTextIsSelectable(true);
        } else {
            page.removeView(preview);
            text("Preview unavailable. Read the LaTeX source below.", 16);
        }
        text(explanation, 16);
        TextView raw = text("LaTeX source\n" + source, 16);
        raw.setTextIsSelectable(true);
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
