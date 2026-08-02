package com.rhys.obd2.data

import java.io.File

/** One recorded parameter over the course of a trip. Missing samples stay null. */
data class TripSeries(
    val name: String,
    val values: List<Float?>,
) {
    private val present: List<Float> get() = values.filterNotNull()

    val min: Float? get() = present.minOrNull()
    val max: Float? get() = present.maxOrNull()
    val mean: Float? get() = present.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val hasData: Boolean get() = present.isNotEmpty()

    /** The unit is carried in the CSV header as "Name (unit)". */
    val unit: String
        get() = Regex("\\(([^)]*)\\)$").find(name.trim())?.groupValues?.get(1).orEmpty()

    val label: String get() = name.substringBeforeLast(" (").trim()
}

/**
 * A recorded trip, parsed back out of the CSV the logger wrote.
 *
 * Recording without being able to look at the result is only half a feature — the whole
 * point of logging is chasing a fault that never happens while you're watching the
 * gauges, and that means reviewing it afterwards. Exporting to a spreadsheet works but
 * asks a lot of someone sitting in a car park.
 */
data class TripLog(
    val file: File,
    val elapsedSeconds: List<Float>,
    val series: List<TripSeries>,
) {
    val sampleCount: Int get() = elapsedSeconds.size
    val durationSeconds: Float get() = elapsedSeconds.lastOrNull() ?: 0f

    /** Series that actually recorded something, which is what's worth offering to plot. */
    val populated: List<TripSeries> get() = series.filter { it.hasData }

    companion object {

        /**
         * Downsampling threshold. A long drive at two rows a second runs to tens of
         * thousands of points, far more than a phone-width chart can show, so the parser
         * keeps every Nth row rather than holding the lot in memory to draw 400 pixels.
         */
        private const val MAX_POINTS = 2_000

        fun parse(file: File): TripLog? {
            val lines = runCatching { file.readLines() }.getOrNull() ?: return null
            if (lines.size < 2) return null

            val header = splitCsv(lines.first())
            if (header.size < 3) return null
            // Columns 0 and 1 are the absolute timestamp and elapsed seconds.
            val names = header.drop(2)

            val dataLines = lines.drop(1).filter { it.isNotBlank() }
            val step = (dataLines.size / MAX_POINTS).coerceAtLeast(1)
            val sampled = dataLines.filterIndexed { index, _ -> index % step == 0 }

            val elapsed = ArrayList<Float>(sampled.size)
            val columns = List(names.size) { ArrayList<Float?>(sampled.size) }

            sampled.forEach { line ->
                val cells = splitCsv(line)
                if (cells.size < 2) return@forEach
                elapsed += cells[1].toFloatOrNull() ?: return@forEach
                names.indices.forEach { i ->
                    columns[i] += cells.getOrNull(i + 2)?.toFloatOrNull()
                }
            }

            if (elapsed.isEmpty()) return null

            return TripLog(
                file = file,
                elapsedSeconds = elapsed,
                series = names.mapIndexed { i, name -> TripSeries(name, columns[i]) },
            )
        }

        /**
         * The logger replaces commas in column names with semicolons before writing, so a
         * plain split is safe and there are no quoted fields to worry about.
         */
        private fun splitCsv(line: String): List<String> = line.split(',').map { it.trim() }
    }
}
