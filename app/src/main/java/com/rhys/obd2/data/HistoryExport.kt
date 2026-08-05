package com.rhys.obd2.data

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.rhys.obd2.obd.KnownIssues
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a car's history out to something you can keep, print or hand to a garage.
 *
 * Two formats, because they serve different ends. The PDF is for handing over — it prints,
 * it looks like a document, and a garage will accept it. The text file is for keeping and
 * searching, and it pastes into an email or a message without the recipient needing this
 * app or any app.
 *
 * PDF generation uses Android's own [PdfDocument], which has been in the platform since
 * API 19. No library, nothing to keep up to date, and nothing that can stop working when a
 * dependency is abandoned — which is the reason this app exists in the first place.
 */
object HistoryExport {

    private const val TAG = "HistoryExport"

    // A4 at 72dpi, the unit PdfDocument works in.
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 44f

    private val stamp = SimpleDateFormat("d MMMM yyyy 'at' HH:mm", Locale.UK)
    private val shortStamp = SimpleDateFormat("d MMM yyyy HH:mm", Locale.UK)
    private val fileStamp = SimpleDateFormat("yyyy-MM-dd", Locale.UK)

    private fun outputDir(context: Context): File =
        File(context.filesDir, "reports").apply { mkdirs() }

    private fun safeName(vehicle: Vehicle): String =
        vehicle.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "car" }

    // -----------------------------------------------------------------------------------
    // PDF
    // -----------------------------------------------------------------------------------

    fun toPdf(context: Context, vehicle: Vehicle, events: List<VehicleHistoryEvent>): File? {
        val document = PdfDocument()
        val file = File(outputDir(context), "${safeName(vehicle)}-history-${fileStamp.format(Date())}.pdf")

        val title = Paint().apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 20f; isAntiAlias = true
        }
        val heading = Paint().apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 12f; isAntiAlias = true
        }
        val body = Paint().apply {
            typeface = Typeface.SANS_SERIF; textSize = 10f; isAntiAlias = true
        }
        val dim = Paint().apply {
            typeface = Typeface.SANS_SERIF; textSize = 9f; isAntiAlias = true; color = 0xFF5A6672.toInt()
        }
        val rule = Paint().apply { strokeWidth = 0.6f; color = 0xFFC3CCD6.toInt() }

        try {
            var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
            var canvas = page.canvas
            var y = MARGIN + 16f
            var pageNumber = 1

            /** Starts a new page when the next block would run off this one. */
            fun ensureRoom(needed: Float) {
                if (y + needed <= PAGE_HEIGHT - MARGIN) return
                canvas.drawText("Page $pageNumber", PAGE_WIDTH - MARGIN - 40f, PAGE_HEIGHT - 24f, dim)
                document.finishPage(page)
                pageNumber++
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                )
                canvas = page.canvas
                y = MARGIN
            }

            fun line(text: String, paint: Paint, gap: Float = 14f) {
                ensureRoom(gap)
                canvas.drawText(text, MARGIN, y, paint)
                y += gap
            }

            /** Wraps to the page width rather than running off the edge. */
            fun paragraph(text: String, paint: Paint, indent: Float = 0f) {
                val maxWidth = PAGE_WIDTH - MARGIN * 2 - indent
                var remaining = text.replace('\n', ' ')
                while (remaining.isNotEmpty()) {
                    val count = paint.breakText(remaining, true, maxWidth, null)
                    // Break on a space where there is one, so words stay whole.
                    var take = count
                    if (take < remaining.length) {
                        val lastSpace = remaining.lastIndexOf(' ', take - 1)
                        if (lastSpace > 0) take = lastSpace
                    }
                    ensureRoom(13f)
                    canvas.drawText(remaining.substring(0, take).trim(), MARGIN + indent, y, paint)
                    y += 13f
                    remaining = remaining.substring(take).trimStart()
                }
            }

            line(vehicle.name, title, 26f)
            vehicle.vin?.let { line("VIN $it", dim) }
            vehicle.modelId?.let { id ->
                KnownIssues.byId(id)?.let { line(it.displayName, dim) }
            }
            listOfNotNull(vehicle.manufacturer, vehicle.modelYear).takeIf { it.isNotEmpty() }
                ?.let { line(it.joinToString(" · "), dim) }
            line("First seen ${stamp.format(Date(vehicle.firstSeen))}", dim)
            line("Exported ${stamp.format(Date())}", dim, 20f)

            ensureRoom(20f)
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rule)
            y += 18f

            line("History — ${events.size} ${if (events.size == 1) "entry" else "entries"}", heading, 20f)

            if (events.isEmpty()) {
                paragraph("Nothing recorded for this vehicle yet.", body)
            }

            events.forEach { event ->
                ensureRoom(34f)
                line("${event.type.label} · ${shortStamp.format(Date(event.timestamp))}", dim, 12f)
                paragraph(event.title, heading)
                if (event.detail.isNotBlank()) {
                    event.detail.lines().filter { it.isNotBlank() }.forEach { paragraph(it, body, indent = 8f) }
                }
                y += 8f
            }

            // Say plainly what this document is and is not, since it may be read by
            // someone who has never seen the app.
            ensureRoom(60f)
            y += 8f
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rule)
            y += 16f
            paragraph(
                "Recorded by OpenOBD from the vehicle's OBD-II port. Fault codes are as " +
                    "reported by the engine control unit and are a starting point for " +
                    "diagnosis, not a diagnosis. This record is kept outside the car, so it " +
                    "still shows codes that have since been cleared from the ECU.",
                dim,
            )

            canvas.drawText("Page $pageNumber", PAGE_WIDTH - MARGIN - 40f, PAGE_HEIGHT - 24f, dim)
            document.finishPage(page)

            file.outputStream().use { document.writeTo(it) }
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Could not write PDF", e)
            return null
        } finally {
            document.close()
        }
    }

    // -----------------------------------------------------------------------------------
    // Plain text
    // -----------------------------------------------------------------------------------

    fun toText(context: Context, vehicle: Vehicle, events: List<VehicleHistoryEvent>): File? {
        val file = File(outputDir(context), "${safeName(vehicle)}-history-${fileStamp.format(Date())}.txt")
        return runCatching {
            file.writeText(buildString {
                appendLine(vehicle.name)
                appendLine("=".repeat(vehicle.name.length))
                vehicle.vin?.let { appendLine("VIN: $it") }
                vehicle.modelId?.let { id -> KnownIssues.byId(id)?.let { appendLine("Model: ${it.displayName}") } }
                vehicle.manufacturer?.let { appendLine("Manufacturer: $it") }
                vehicle.modelYear?.let { appendLine("Model year: $it") }
                appendLine("First seen: ${stamp.format(Date(vehicle.firstSeen))}")
                appendLine("Exported: ${stamp.format(Date())}")
                appendLine()
                appendLine("HISTORY — ${events.size} ${if (events.size == 1) "entry" else "entries"}")
                appendLine()

                events.forEach { event ->
                    appendLine("[${shortStamp.format(Date(event.timestamp))}] ${event.type.label}")
                    appendLine("  ${event.title}")
                    event.detail.lines().filter { it.isNotBlank() }.forEach { appendLine("    $it") }
                    appendLine()
                }

                appendLine("---")
                appendLine(
                    "Recorded by OpenOBD from the vehicle's OBD-II port. Fault codes are as " +
                        "reported by the engine control unit and are a starting point for " +
                        "diagnosis, not a diagnosis. This record is kept outside the car, so " +
                        "it still shows codes that have since been cleared from the ECU."
                )
            })
            file
        }.onFailure { Log.e(TAG, "Could not write text export", it) }.getOrNull()
    }

    /**
     * Comma-separated, for anyone who wants to work with it in a spreadsheet.
     *
     * Quoted properly rather than hopefully: a fault description can contain a comma, and
     * an unquoted one shifts every column after it.
     */
    fun toCsv(context: Context, vehicle: Vehicle, events: List<VehicleHistoryEvent>): File? {
        val file = File(outputDir(context), "${safeName(vehicle)}-history-${fileStamp.format(Date())}.csv")
        fun quote(value: String) = "\"" + value.replace("\"", "\"\"").replace("\n", " ") + "\""
        return runCatching {
            file.writeText(buildString {
                appendLine("Date,Time,Type,Title,Detail")
                events.forEach { event ->
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date(event.timestamp))
                    val time = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date(event.timestamp))
                    appendLine("$date,$time,${quote(event.type.label)},${quote(event.title)},${quote(event.detail)}")
                }
            })
            file
        }.onFailure { Log.e(TAG, "Could not write CSV export", it) }.getOrNull()
    }
}
