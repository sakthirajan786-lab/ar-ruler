package com.arruler.app

import java.util.Locale

enum class MeasureUnit(val label: String) {
    CM("cm"),
    M("m"),
    IN("in"),
    FT("ft");

    fun next(): MeasureUnit = entries[(ordinal + 1) % entries.size]

    fun format(meters: Float): String {
        val locale = Locale.getDefault()
        return when (this) {
            CM -> String.format(locale, "%.1f cm", meters * 100f)
            M -> String.format(locale, "%.2f m", meters)
            IN -> String.format(locale, "%.1f in", meters * INCHES_PER_METER)
            FT -> {
                val totalInches = meters * INCHES_PER_METER
                var feet = (totalInches / 12f).toInt()
                var inches = totalInches - feet * 12f
                if (inches >= 11.95f) {
                    feet += 1
                    inches = 0f
                }
                String.format(locale, "%d′ %.1f″", feet, inches)
            }
        }
    }

    companion object {
        private const val INCHES_PER_METER = 39.37008f

        fun fromOrdinal(index: Int): MeasureUnit = entries.getOrElse(index) { CM }
    }
}
