package com.daviddokakis.karoointerval

/**
 * Power zone colours: the Karoo's (mint, green, yellow, salmon, red, purple) or Garmin / Zwift
 * style (grey, blue, green, yellow, orange, red). One choice for the free-ride chart and arc,
 * the zone-coloured watts, the workout profile and the workout details.
 */
enum class ZonePalette(val label: Int, val fills: IntArray, val texts: IntArray, val backgrounds: IntArray) {
    KAROO(
        R.string.palette_karoo,
        intArrayOf(R.color.pz1, R.color.pz2, R.color.pz3, R.color.pz4, R.color.pz5, R.color.pz6),
        intArrayOf(R.color.zone_text_1, R.color.zone_text_2, R.color.zone_text_3, R.color.zone_text_4, R.color.zone_text_5, R.color.zone_text_6),
        intArrayOf(R.drawable.bg_zone_1, R.drawable.bg_zone_2, R.drawable.bg_zone_3, R.drawable.bg_zone_4, R.drawable.bg_zone_5, R.drawable.bg_zone_6),
    ),
    ZWIFT(
        R.string.palette_zwift,
        intArrayOf(R.color.zw1, R.color.zw2, R.color.zw3, R.color.zw4, R.color.zw5, R.color.zw6),
        intArrayOf(R.color.zw_text_1, R.color.zw_text_2, R.color.zw_text_3, R.color.zw_text_4, R.color.zw_text_5, R.color.zw_text_6),
        intArrayOf(R.drawable.bg_zw_1, R.drawable.bg_zw_2, R.drawable.bg_zw_3, R.drawable.bg_zw_4, R.drawable.bg_zw_5, R.drawable.bg_zw_6),
    ),
    ;

    companion object {
        /** The palette in use (set from Settings; read by every renderer). */
        @Volatile
        var current: ZonePalette = KAROO
    }
}
