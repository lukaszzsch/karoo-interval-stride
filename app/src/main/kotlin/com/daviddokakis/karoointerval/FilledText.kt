package com.daviddokakis.karoointerval

/** Text on filled (coloured) tiles: white, black, or whichever contrasts more with the colour. */
enum class FilledText(val label: Int) {
    WHITE(R.string.filled_text_white),
    BLACK(R.string.filled_text_black),
    AUTO(R.string.filled_text_auto),
}
