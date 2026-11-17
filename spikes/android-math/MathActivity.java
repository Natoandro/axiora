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
        heading("Mathematical notation");
        example("Ideal membership", "a \\in I", "a belongs to the ideal I.");
        example("Quotient ring", "\\mathbb{Z}/(0)", "The integers modulo the zero ideal.");
        example(
            "Long expression",
            "a \\in I, b \\in I, c \\in I, d \\in I, e \\in I, f \\in I",
            "Each of a, b, c, d, e, and f belongs to the ideal I."
        );
        example("Unsupported notation", "\\frac{a}{b}", "a divided by b.");
    }

    private void example(String title, String source, String explanation) {
        heading(title);
        MathCanvas preview = null;
        if (font != null) {
            try {
                preview = MathCanvas.create(this, font, source);
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
