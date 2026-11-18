package mg.abela.axiora.app

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import mg.abela.axiora.mathspike.MathCanvas

private val math = Regex("""(?<!\\)\$\$([^$]+)\$\$|(?<![\\$])\$([^$\n]+)\$(?!\$)""")

internal fun renderMathText(
    source: String,
    font: ByteArray?,
    textSize: Float,
    maxWidth: Int,
): CharSequence {
    val result = SpannableStringBuilder()
    val layouts = mutableMapOf<Pair<String, Boolean>, MathCanvas.Formula?>()
    var offset = 0
    for (match in math.findAll(source)) {
        result.append(source, offset, match.range.first)
        val display = match.groups[1] != null
        val expression = (match.groups[1] ?: match.groups[2]!!).value.trim()
        val formula =
            if (font != null && expression.length in 1..512) {
                val key = expression to display
                // Bound native layout work per text field while allowing repeated formulas to reuse paths.
                if (key !in layouts && layouts.size < 64) {
                    layouts[key] = runCatching { MathCanvas.layout(font, expression, textSize, display) }.getOrNull()
                }
                layouts[key]?.takeIf { it.width in 1..maxWidth }
            } else {
                null
            }

        if (display && result.isNotEmpty() && result.last() != '\n') result.append('\n')
        val start = result.length
        offset = match.range.last + 1
        if (formula == null) {
            result.append(match.value)
        } else {
            // Keep LaTeX in the text buffer for selection and copying.
            result.append(if (display) match.value.replace('\n', ' ').replace('\r', ' ') else match.value)
            val span = FormulaSpan(formula)
            if (Build.VERSION.SDK_INT >= 30) span.contentDescription = expression
            result.setSpan(span, start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (display && offset < source.length && source[offset] != '\n') result.append('\n')
    }
    if (offset == 0) return source
    return result.append(source, offset, source.length)
}

private class FormulaSpan(
    private val formula: MathCanvas.Formula,
) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        fm?.apply {
            paint.getFontMetricsInt(this)
            ascent = minOf(ascent, -formula.ascent)
            descent = maxOf(descent, formula.descent)
            top = minOf(top, ascent)
            bottom = maxOf(bottom, descent)
        }
        return formula.width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        formula.draw(canvas, x, y.toFloat(), paint)
    }
}
