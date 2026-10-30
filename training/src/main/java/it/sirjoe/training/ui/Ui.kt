package it.sirjoe.training.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import it.sirjoe.training.R

/** Piccoli aiuti per costruire le pagine: colori, testi grandi leggibili al sole, pulsanti. */
object Ui {
    const val BG = 0xFF0F1A14.toInt()
    const val CARD = 0xFF1B2B22.toInt()
    const val CARD2 = 0xFF25392D.toInt()
    const val TEXT = 0xFFF2F5F3.toInt()
    const val MUTED = 0xFFA9BFB2.toInt()
    const val ORANGE = 0xFFFF6D00.toInt()
    const val GREEN = 0xFF43A047.toInt()
    const val RED = 0xFFE53935.toInt()
    const val BLUE = 0xFF1E88E5.toInt()

    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun font(ctx: Context): Typeface? = try {
        ResourcesCompat.getFont(ctx, R.font.russo_one)
    } catch (e: Exception) {
        Typeface.DEFAULT_BOLD
    }

    fun rounded(color: Int, radius: Float, strokeColor: Int? = null, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null) setStroke(strokeW, strokeColor)
    }

    fun text(ctx: Context, s: CharSequence = "", size: Float = 16f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun big(ctx: Context, size: Float): TextView = TextView(ctx).apply {
        textSize = size
        setTextColor(TEXT)
        typeface = font(ctx)
        includeFontPadding = false
        maxLines = 1
        gravity = Gravity.CENTER
    }

    fun title(ctx: Context, s: String): TextView = text(ctx, s.uppercase(), 13f, MUTED, true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(ctx, 18), 0, dp(ctx, 6))
    }

    fun button(ctx: Context, s: String, color: Int = CARD2, onClick: () -> Unit): Button = Button(ctx).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = rounded(color, dp(ctx, 10).toFloat())
        minHeight = dp(ctx, 52)
        setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
        setOnClickListener { onClick() }
    }

    fun vbox(ctx: Context, pad: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, pad)
        setPadding(p, p, p, p)
    }

    fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(ctx: Context, color: Int = CARD): LinearLayout = vbox(ctx, 12).apply {
        background = rounded(color, dp(ctx, 12).toFloat())
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun gap(ctx: Context, v: View, top: Int = 8): View {
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(ctx, top)
        return v
    }

    fun add(parent: LinearLayout, v: View, top: Int = 8, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, weight: Float = 0f): View {
        val p = LinearLayout.LayoutParams(if (weight > 0f) 0 else w, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        if (parent.orientation == LinearLayout.VERTICAL) p.topMargin = dp(parent.context, top)
        else p.marginStart = dp(parent.context, top)
        parent.addView(v, p)
        return v
    }

    fun numberInput(ctx: Context, value: Int?, hint: String): EditText = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value?.takeIf { it > 0 }?.toString() ?: "")
        this.hint = hint
        setTextColor(TEXT)
        setHintTextColor(MUTED)
    }

    /** Colore di testo leggibile su uno sfondo (nero sul giallo, bianco sul resto). */
    fun onColor(bg: Int): Int {
        val r = Color.red(bg); val g = Color.green(bg); val b = Color.blue(bg)
        return if (r * 0.299 + g * 0.587 + b * 0.114 > 150) Color.BLACK else Color.WHITE
    }
}
