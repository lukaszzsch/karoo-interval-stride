package com.daviddokakis.karoointerval

/** 168 -> "2:48", 4068 -> "1:07:48". */
fun formatClock(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** 300 -> "5m", 90 -> "1m30s", 45 -> "45s", 4500 -> "1h15m". */
fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return buildString {
        if (h > 0) append("${h}h")
        if (m > 0) append("${m}m")
        if (s > 0 && h == 0) append("${s}s")
        if (isEmpty()) append("0s")
    }
}
