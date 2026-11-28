package com.saurabhtiwari.voicetranscriber

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.IOException

/**
 * Dependency-free, paginated PDF export for a meeting.
 *
 * The document is rendered from the same canonical plain-text representation used by sharing,
 * so notes, speaker labels and transcript timestamps stay consistent across export formats.
 */
internal object TranscriptPdfExport {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 42f
    private const val FOOTER_Y = PAGE_HEIGHT - 28f
    private const val CONTENT_BOTTOM = PAGE_HEIGHT - 58f

    fun write(context: Context, uri: Uri, document: ExportDocument) {
        val pdf = PdfDocument()
        try {
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(35, 39, 47)
                textSize = 10.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(20, 31, 48)
                textSize = 19f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.GRAY
                textSize = 8f
            }
            val content = TranscriptExport.plainText(document).replace("\r\n", "\n").replace('\r', '\n')
            val paragraphs = content.split('\n')
            var pageNumber = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            var canvas: Canvas = page.canvas
            var y = 54f

            fun nextPage() {
                canvas.drawText("Meeting Intelligence · $pageNumber", MARGIN, FOOTER_Y, footerPaint)
                pdf.finishPage(page)
                pageNumber += 1
                page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = page.canvas
                y = 54f
            }

            paragraphs.forEachIndexed { index, paragraph ->
                val isTitle = index == 0
                val paint = if (isTitle) titlePaint else bodyPaint
                val lineHeight = if (isTitle) 27f else 15f
                val gapAfter = if (isTitle) 8f else if (paragraph.isBlank()) 5f else 3f
                val lines = wrap(paragraph, paint, PAGE_WIDTH - 2f * MARGIN)
                if (lines.isEmpty()) {
                    y += gapAfter
                } else {
                    lines.forEach { line ->
                        if (y + lineHeight > CONTENT_BOTTOM) nextPage()
                        canvas.drawText(line, MARGIN, y, paint)
                        y += lineHeight
                    }
                    y += gapAfter
                }
            }
            canvas.drawText("Meeting Intelligence · $pageNumber", MARGIN, FOOTER_Y, footerPaint)
            pdf.finishPage(page)
            context.contentResolver.openOutputStream(uri, "w")?.use(pdf::writeTo)
                ?: throw IOException("Could not open the selected PDF destination.")
        } finally {
            pdf.close()
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder()
                // Avoid clipping a single unusually long token or URL.
                var fragment = StringBuilder()
                word.forEach { character ->
                    val next = fragment.toString() + character
                    if (fragment.isNotEmpty() && paint.measureText(next) > maxWidth) {
                        lines += fragment.toString()
                        fragment = StringBuilder(character.toString())
                    } else fragment.append(character)
                }
                current = fragment
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}
