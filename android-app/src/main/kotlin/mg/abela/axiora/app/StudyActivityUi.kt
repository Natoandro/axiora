package mg.abela.axiora.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared native view shell, typography and controls for the app's screens. */
internal abstract class StudyActivityUi : Activity() {
    protected val ink = Color.rgb(38, 35, 33)
    protected val quietInk = Color.rgb(92, 85, 77)
    protected val paper = Color.rgb(244, 242, 235)
    protected val whitePaper = Color.rgb(254, 252, 246)
    protected val line = Color.rgb(212, 205, 193)
    protected val accent = Color.rgb(158, 42, 26)
    private lateinit var footer: LinearLayout
    protected lateinit var column: LinearLayout
    protected var editor: EditText? = null
    protected var status: TextView? = null
    protected abstract val font: ByteArray?
    protected abstract val uiBusy: Boolean

    protected abstract fun navigateHome()

    protected abstract fun navigateLibrary()

    protected abstract fun navigateReview()

    protected fun screen(
        title: String,
        selectedTab: String? = null,
        backLabel: String? = null,
        showTitle: Boolean = true,
        onBack: () -> Unit = {},
    ) {
        editor = null
        status = null
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(paper)
            }
        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(paper)
            }
        column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(26), dp(24), dp(32))
                setBackgroundColor(paper)
            }
        scroll.addView(
            column,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )
        if (resources.displayMetrics.widthPixels > dp(760)) column.layoutParams.width = dp(760)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (selectedTab != null) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ImageView(this@StudyActivityUi).apply {
                        setImageResource(resources.getIdentifier("ic_axiora", "drawable", packageName))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(38), dp(38)),
                )
                addView(
                    TextView(this@StudyActivityUi).apply {
                        text = "Axiora"
                        textSize = 21f
                        letterSpacing = -.03f
                        typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
                        setTextColor(ink)
                        setPadding(dp(12), 0, 0, 0)
                    },
                )
                setPadding(0, 0, 0, dp(22))
                column.addView(this)
            }
        } else if (backLabel != null) {
            Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                isAllCaps = false
                textSize = 16f
                setTextColor(accent)
                setPadding(dp(12), 0, dp(12), 0)
                minHeight = dp(48)
                text = "‹  $backLabel"
                contentDescription = backLabel
                setOnClickListener { if (!uiBusy) onBack() }
                column.addView(this, LinearLayout.LayoutParams(-2, -2))
            }
        }
        if (showTitle) heading(title, 30f)
        footer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(whitePaper)
                setPadding(dp(24), dp(6), dp(24), 0)
                visibility = View.GONE
            }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        if (selectedTab != null) {
            val navigation =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(whitePaper)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    elevation = dp(5).toFloat()
                }
            listOf("Home", "Library", "Review").forEach { tab ->
                val selected = selectedTab == tab
                val color = if (selected) accent else quietInk
                val button =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        isSelected = selected
                        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = if (selected) "Selected" else null
                        background =
                            android.graphics.drawable.GradientDrawable().apply {
                                setColor(if (selected) android.graphics.Color.rgb(242, 219, 211) else whitePaper)
                                cornerRadius = dp(12).toFloat()
                            }
                        minimumHeight = dp(56)
                        contentDescription = tab
                        isFocusable = true
                        isClickable = true
                        addView(NavigationIcon(this@StudyActivityUi, tab, color), LinearLayout.LayoutParams(dp(20), dp(20)))
                        addView(
                            TextView(this@StudyActivityUi).apply {
                                text = tab
                                textSize = 12f
                                gravity = Gravity.CENTER
                                setTextColor(color)
                                if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                            },
                        )
                        setOnClickListener {
                            if (tab == "Home") {
                                navigateHome()
                            } else if (tab == "Library") {
                                navigateLibrary()
                            } else {
                                navigateReview()
                            }
                        }
                    }
                navigation.addView(
                    button,
                    LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    },
                )
            }
            root.addView(navigation, LinearLayout.LayoutParams(-1, dp(68)))
        }
        this.title = title
    }

    protected fun card(content: LinearLayout.() -> Unit) {
        val panel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(whitePaper)
                        setStroke(dp(1), line)
                        cornerRadius = dp(15).toFloat()
                    }
            }
        val params =
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(14)
            }
        column.addView(panel, params)
        val previous = column
        column = panel
        try {
            panel.content()
        } finally {
            column = previous
        }
    }

    protected fun entry(
        value: String,
        detail: String? = null,
        bottomRule: Boolean = false,
    ) {
        column.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1)))
        column.addView(
            TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(ink)
                setPadding(0, dp(12), 0, dp(12))
            },
        )
        detail?.let { detailText ->
            column.addView(
                TextView(this).apply {
                    text = detailText
                    textSize = 13f
                    setTextColor(quietInk)
                    setPadding(0, 0, 0, dp(12))
                },
            )
        }
        if (bottomRule) column.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    protected fun catalogRow(
        title: String,
        detail: String,
        click: () -> Unit,
    ) = catalogRow(title, detail, column, click)

    protected fun catalogRow(
        title: String,
        detail: String,
        parent: LinearLayout,
        click: () -> Unit,
    ) {
        parent.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1)))
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(12), dp(4), dp(12))
                minimumHeight = dp(56)
                isClickable = true
                isFocusable = true
                val ripple =
                    android.content.res.ColorStateList
                        .valueOf(Color.rgb(242, 219, 211))
                background = android.graphics.drawable.RippleDrawable(ripple, android.graphics.drawable.ColorDrawable(paper), null)
                setOnClickListener { click() }
            }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(
            TextView(this).apply {
                text = title
                textSize = 16f
                setTextColor(ink)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
        )
        if (detail.isNotBlank()) {
            copy.addView(
                TextView(this).apply {
                    text = detail
                    textSize = 13f
                    setTextColor(quietInk)
                },
            )
        }
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            ImageView(this).apply {
                val iconId = resources.getIdentifier("ic_chevron_right", "drawable", packageName)
                if (iconId != 0) setImageResource(iconId)
                imageTintList =
                    android.content.res.ColorStateList
                        .valueOf(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(24), dp(24)),
        )
        parent.addView(row)
    }

    protected fun textAction(
        title: String,
        click: () -> Unit,
    ) {
        val view =
            TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(accent)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setOnClickListener { click() }
            }
        column.addView(view)
    }

    protected fun footerAction(
        title: String,
        click: () -> Unit,
    ): Button {
        footer.visibility = View.VISIBLE
        return primaryAction(title, footer, click)
    }

    protected fun primaryAction(
        title: String,
        click: () -> Unit,
    ): Button = stylePrimary(action(title, click))

    protected fun primaryAction(
        title: String,
        parent: LinearLayout,
        click: () -> Unit,
    ): Button = stylePrimary(action(title, parent, click))

    private fun stylePrimary(button: Button): Button =
        button.apply {
            setTextColor(whitePaper)
            cornerRadiusCompat()
            backgroundTintList =
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled)),
                    intArrayOf(accent, quietInk),
                )
        }

    protected fun Button.cornerRadiusCompat() {
        background =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(28).toFloat()
            }
    }

    protected fun heading(
        value: String,
        size: Float = 22f,
        parent: LinearLayout = column,
        renderMath: Boolean = false,
    ): TextView =
        label(value, size, parent = parent).apply {
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
            setTextColor(ink)
            setPadding(0, dp(12), 0, dp(12))
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            if (renderMath) setMathText(value)
        }

    protected fun label(
        value: String,
        size: Float = 17f,
        selectable: Boolean = false,
        parent: LinearLayout = column,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(if (size <= 14f) quietInk else ink)
            setTextIsSelectable(selectable)
            setPadding(0, dp(8), 0, dp(8))
            parent.addView(this)
        }

    protected fun content(
        source: String,
        parent: LinearLayout = column,
    ) {
        if (source.isBlank()) return
        label("", selectable = true, parent = parent).setMathText(source)
    }

    protected fun TextView.setMathText(source: String) {
        val viewWidth =
            width.takeIf { it > 0 } ?: (
                minOf(resources.displayMetrics.widthPixels, dp(760)) - column.paddingLeft - column.paddingRight
            )
        text = renderMathText(source, font, textSize, viewWidth - compoundPaddingLeft - compoundPaddingRight)
    }

    protected fun action(
        title: String,
        click: () -> Unit,
    ): Button = createAction(title, column, click)

    protected fun action(
        title: String,
        parent: LinearLayout,
        click: () -> Unit,
    ): Button = createAction(title, parent, click)

    private fun createAction(
        title: String,
        parent: LinearLayout,
        click: () -> Unit,
    ): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 16f
            setTextColor(
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled)),
                    intArrayOf(accent, quietInk),
                ),
            )
            backgroundTintList =
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled)),
                    intArrayOf(whitePaper, Color.rgb(232, 227, 218)),
                )
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minHeight = dp(48)
            setOnClickListener { if (!uiBusy) click() }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(8)
            parent.addView(this, params)
        }

    protected fun writingField(
        title: String,
        value: String,
    ): EditText =
        EditText(this).apply {
            hint = title
            contentDescription = title
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 12
            // Imported checkpoints can contain longer text than this editor allows for new input.
            setText(value)
            filters = arrayOf(android.text.InputFilter.LengthFilter(12_000))
            column.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

private class NavigationIcon(
    context: Context,
    private val destination: String,
    color: Int,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val left = width / 2f - 8 * density
        val top = height / 2f - 7 * density
        val right = width / 2f + 8 * density
        val bottom = height / 2f + 7 * density
        when (destination) {
            "Home" -> {
                val roof =
                    Path().apply {
                        moveTo(left - density, top + 5 * density)
                        lineTo(width / 2f, top - density)
                        lineTo(right + density, top + 5 * density)
                    }
                canvas.drawPath(roof, paint)
                canvas.drawRect(left + density, top + 5 * density, right - density, bottom, paint)
            }

            "Library" -> {
                canvas.drawRect(left, top, right, bottom, paint)
                canvas.drawLine(width / 2f, top, width / 2f, bottom, paint)
            }

            "Review" -> {
                canvas.drawCircle(width / 2f, height / 2f, 7 * density, paint)
                canvas.drawLine(width / 2f, height / 2f, width / 2f, top + 3 * density, paint)
                canvas.drawLine(width / 2f, height / 2f, right - 3 * density, height / 2f, paint)
            }
        }
    }
}
