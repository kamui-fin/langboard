package app.langboard.ui

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date

private val KnownApps = mapOf(
  "com.tencent.mm" to "WeChat",
  "com.whatsapp" to "WhatsApp",
  "com.google.android.apps.messaging" to "Messages",
  "com.android.chrome" to "Chrome",
  "org.telegram.messenger" to "Telegram",
  "com.discord" to "Discord",
  "com.instagram.android" to "Instagram",
  "jp.naver.line.android" to "LINE",
  "com.xingin.xhs" to "Xiaohongshu",
  "com.ss.android.ugc.aweme" to "Douyin",
  "com.sina.weibo" to "Weibo",
  "com.tencent.mobileqq" to "QQ",
  "app.langboard" to "Expression Lab",
)

/** A friendly name for the apps people chat in; null for the rest (we don't guess from the package). */
internal fun appName(pkg: String?): String? = pkg?.let { KnownApps[it] }

internal fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun dayLabel(day: LocalDate): String {
  val today = LocalDate.now()
  return when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"))
  }
}

/** "Today", "Yesterday", "3 days ago", then "12 Sep". */
internal fun shortDay(ms: Long): String {
  val day = dayOf(ms)
  val today = LocalDate.now()
  val days = ChronoUnit.DAYS.between(day, today)
  return when {
    days <= 0L -> "Today"
    days == 1L -> "Yesterday"
    days < 7 -> "$days days ago"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM yyyy"))
  }
}

internal fun timeOf(context: Context, ms: Long): String =
  android.text.format.DateFormat.getTimeFormat(context).format(Date(ms))

internal fun fullDate(context: Context, ms: Long): String = "${dayLabel(dayOf(ms))}, ${timeOf(context, ms)}"

/** "Good morning" and so on, by the phone's clock. */
internal fun greeting(hour: Int = java.time.LocalTime.now().hour): String = when (hour) {
  in 5..11 -> "Good morning"
  in 12..17 -> "Good afternoon"
  else -> "Good evening"
}

internal fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
