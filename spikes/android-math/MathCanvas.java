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

final class MathCanvas extends View {
    static {
        System.loadLibrary("texlet_canvas");
    }

    private static native float[] nativeLayout(byte[] font, byte[] source, float textSize);

    static MathCanvas create(Context context, byte[] font, String source) {
        float size = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 22, context.getResources().getDisplayMetrics());
        float[] commands = nativeLayout(font, source.getBytes(StandardCharsets.UTF_8), size);
        return commands == null ? null : new MathCanvas(context, commands);
    }

    private final List<Path> paths = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float left;
    private final float top;
    private final int width;
    private final int height;

    private MathCanvas(Context context, float[] commands) {
        super(context);
        left = commands[0];
        top = commands[1];
        width = (int) Math.ceil(commands[2]);
        height = (int) Math.ceil(commands[3]);
        TypedValue textColor = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, textColor, true)) {
            int color = textColor.resourceId == 0 ? textColor.data
                : context.getColorStateList(textColor.resourceId).getDefaultColor();
            paint.setColor(color);
        }

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
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.translate(-left, -top);
        for (Path path : paths) {
            canvas.drawPath(path, paint);
        }
        canvas.restoreToCount(save);
    }
}
