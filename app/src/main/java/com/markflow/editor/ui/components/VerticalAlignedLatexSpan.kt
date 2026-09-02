package com.markflow.editor.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan

/**
 * 行内 LaTeX 公式垂直对齐修正 Span
 *
 * markwon-latex 渲染行内公式时使用 JLatexInlineAsyncDrawableSpan（继承
 * AsyncDrawableSpan），以 ALIGN_CENTER 将公式 Drawable 的几何中心对齐到
 * 行盒中心，与中文文字的视觉中心存在偏差，导致公式看起来偏低。
 *
 * 此类继承 AsyncDrawableSpan 保留异步加载生命周期（结果就绪后仍会触发
 * TextView 重绘），仅重写 [draw] 在绘制前对画布施加向上的平移补偿，
 * 并重写 [getSize] 复刻行内公式原本的居中度量，避免布局行为差异。
 *
 * [upwardOffsetPx] 为向上平移的像素量，由 MarkwonConfig.fixFormulaSpanAlignment
 * 依据当前字体度量计算并传入（随字号缩放）。
 */
class VerticalAlignedLatexSpan(
    drawable: AsyncDrawable,
    private val upwardOffsetPx: Float
) : AsyncDrawableSpan(
    defaultTheme,
    drawable,
    AsyncDrawableSpan.ALIGN_CENTER,
    false
) {

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val offset = upwardOffsetPx
        if (offset == 0f) {
            super.draw(canvas, text, start, end, x, top, y, bottom, paint)
            return
        }
        canvas.save()
        canvas.translate(0f, -offset)
        try {
            super.draw(canvas, text, start, end, x, top, y, bottom, paint)
        } finally {
            canvas.restore()
        }
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (text == null) return 0
        val d = getDrawable()
        if (d.hasResult()) {
            val bounds = d.bounds
            if (fm != null) {
                // 复刻 JLatexInlineAsyncDrawableSpan：公式以自身高度一半的盒包围基线，
                // 保证行高扩展行为与替换前一致
                val half = bounds.bottom / 2
                fm.ascent = -half
                fm.descent = half
                fm.top = fm.ascent
                fm.bottom = 0
            }
            return bounds.right
        }
        return (paint.measureText(text, start, end) + 0.5f).toInt()
    }

    companion object {
        private val defaultTheme: MarkwonTheme by lazy {
            MarkwonTheme.emptyBuilder().build()
        }
    }
}
