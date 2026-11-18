package mg.abela.axiora.mathspike;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MathCanvas extends View {
    static {
        System.loadLibrary("texlet_canvas");
    }

    private static native float[] nativeLayout(byte[] font, byte[] source, float textSize, boolean display);

    public static MathCanvas create(Context context, byte[] font, String source, boolean display) {
        float size = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 22, context.getResources().getDisplayMetrics());
        Formula formula = layout(font, source, size, display);
        return formula == null ? null : new MathCanvas(context, formula);
    }

    public static Formula layout(byte[] font, String source, float size, boolean display) {
        float[] commands = nativeLayout(font, source.getBytes(StandardCharsets.UTF_8), size, display);
        return commands == null ? null : new Formula(commands);
    }

    private final Formula formula;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private MathCanvas(Context context, Formula formula) {
        super(context);
        this.formula = formula;
        TypedValue textColor = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, textColor, true)) {
            int color = textColor.resourceId == 0 ? textColor.data
                : context.getColorStateList(textColor.resourceId).getDefaultColor();
            paint.setColor(color);
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(formula.width, widthSpec),
            resolveSize(formula.ascent + formula.descent, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        formula.draw(canvas, 0, formula.ascent, paint);
    }

    public static final class Formula {
        private final List<Path> paths = new ArrayList<>();
        private final float left;
        public final int width;
        public final int ascent;
        public final int descent;

        private Formula(float[] commands) {
            left = commands[0];
            width = (int) Math.ceil(commands[2]);
            ascent = (int) Math.ceil(-commands[1]);
            descent = (int) Math.ceil(commands[3] + commands[1]);

            Path path = null;
            for (int i = 4; i < commands.length; ) {
                switch ((int) commands[i++]) {
                    case 0:
                        path = new Path();
                        paths.add(path);
                        break;
                    case 1:
                        path.moveTo(commands[i++], commands[i++]);
                        break;
                    case 2:
                        path.lineTo(commands[i++], commands[i++]);
                        break;
                    case 3:
                        path.quadTo(commands[i++], commands[i++], commands[i++], commands[i++]);
                        break;
                    case 4:
                        path.cubicTo(commands[i++], commands[i++], commands[i++], commands[i++],
                            commands[i++], commands[i++]);
                        break;
                    case 5:
                        path.close();
                        break;
                    case 6:
                        float x = commands[i++];
                        float y = commands[i++];
                        float ruleWidth = commands[i++];
                        float ruleHeight = commands[i++];
                        Path rule = new Path();
                        rule.addRect(x, y, x + ruleWidth, y + ruleHeight, Path.Direction.CW);
                        paths.add(rule);
                        break;
                    default:
                        throw new IllegalStateException("Unknown Texlet Canvas command");
                }
            }
        }

        public void draw(Canvas canvas, float x, float baseline, Paint paint) {
            int save = canvas.save();
            canvas.translate(x - left, baseline);
            for (Path path : paths) {
                canvas.drawPath(path, paint);
            }
            canvas.restoreToCount(save);
        }
    }
}
