package com.example.carlink

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File

data class HistPoint(
    val t: Long,
    val fuel: Float?,
    val coolant: Int?,
    val battery: Float?,
)

/** მარტივი ისტორია CSV ფაილში: დრო, ბენზინი, ტემპერატურა, ძაბვა. */
class HistoryStore(private val file: File) {
    fun append(s: CarSnapshot) {
        file.appendText("${s.timeMillis},${s.fuelPercent ?: ""},${s.coolantC ?: ""},${s.batteryV ?: ""}\n")
        if (file.length() > 300_000) {
            val keep = file.readLines().takeLast(2000)
            file.writeText(keep.joinToString("\n") + "\n")
        }
    }

    fun load(max: Int = 300): List<HistPoint> {
        if (!file.exists()) return emptyList()
        return file.readLines().takeLast(max).mapNotNull { line ->
            val p = line.split(",")
            val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            HistPoint(
                t = t,
                fuel = p.getOrNull(1)?.toFloatOrNull(),
                coolant = p.getOrNull(2)?.toIntOrNull(),
                battery = p.getOrNull(3)?.toFloatOrNull(),
            )
        }
    }
}

/** კოდების ჟურნალი: დროის ნიშნული | ტექსტი. წაშლამდე აქ ინახება, რა კოდები იყო. */
class DtcLog(private val file: File) {
    fun append(text: String) {
        file.appendText("${System.currentTimeMillis()}|${text.replace("\n", " ")}\n")
    }

    fun load(max: Int = 40): List<Pair<Long, String>> {
        if (!file.exists()) return emptyList()
        return file.readLines().takeLast(max).mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) return@mapNotNull null
            val t = line.substring(0, i).toLongOrNull() ?: return@mapNotNull null
            t to line.substring(i + 1)
        }
    }
}

/** ტელეფონის ლოკალური შეტყობინებები (ინტერნეტი და სერვერი არ სჭირდება). */
object Notifier {
    private const val CHANNEL = "carlink_alerts"

    fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "CarLink გაფრთხილებები", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}

/** მანქანის პროფილი: სახელი, ბაკის მოცულობა და გაფრთხილების ზღვრები (სხვა მანქანისთვისაც იცვლება). */
data class VehicleProfile(
    val name: String = "Chevrolet Cruze 2017",
    val tankL: Float = 53f,
    val fuelAlertPct: Int = 15,
    val batteryAlertV: Float = 11.8f,
    val coolantAlertC: Int = 105,
)

/** ცოცხალი გრაფიკის ერთი წერტილი. */
data class LivePoint(
    val boost: Float?,
    val trim: Float?,
    val rpm: Int?,
    val speed: Int?,
    val lph: Float?,
)

/** მომსახურების შეხსენება: ბოლოს როდის (და რამდენ კმ-ზე) გაკეთდა და რა ინტერვალით (დღე და/ან კმ). */
data class MaintItem(
    val name: String,
    val lastDone: Long,
    val intervalDays: Int,
    val lastKm: Int = -1,
    val intervalKm: Int = 0,
) {
    fun daysLeft(now: Long = System.currentTimeMillis()): Int? =
        if (intervalDays <= 0) null
        else Math.floorDiv(lastDone + intervalDays * 86_400_000L - now, 86_400_000L).toInt()

    fun kmLeft(odo: Int?): Int? =
        if (intervalKm <= 0 || lastKm < 0 || odo == null) null else lastKm + intervalKm - odo
}

/** საწვავის მიახლოებითი ხარჯი ლ/სთ: პირდაპირი PID 015E ან MAF-დან (ბენზინი, AFR 14.7, სიმკვრივე 740 გ/ლ). */
fun fuelLph(s: CarSnapshot): Float? {
    s.extra?.fuelRateLph?.let { return it }
    val maf = s.mafGs ?: return null
    val st = s.stftPct
    val lt = s.ltftPct
    val trim = if (st != null && lt != null) 1f + (st + lt) / 100f else 1f
    return maf / 14.7f * trim * 3600f / 740f
}

/** ლიტრი 100 კმ-ზე (მხოლოდ მოძრაობისას, ≥ 5 კმ/სთ). */
fun fuelPer100(s: CarSnapshot): Float? {
    val lph = fuelLph(s) ?: return null
    val v = s.speedKmh ?: return null
    return if (v >= 5) lph / v * 100f else null
}

/** ბორბლების წნევა, ხელით შეყვანილი (ბარი). */
data class TireLog(val fl: Float?, val fr: Float?, val rl: Float?, val rr: Float?, val time: Long)

/** ბენზინის შევსების ჩანაწერი: ლიტრი და დონე (%) ჩასხმამდე და მერე. */
data class RefuelItem(val time: Long, val liters: Float, val before: Float?, val after: Float?) {
    /** ბაკის მოცულობის შეფასება, თუ დონის ცვლილება ≥ 20 პუნქტია. */
    fun tankEstimate(): Float? {
        val b = before ?: return null
        val a = after ?: return null
        return if (a - b >= 20f) liters / ((a - b) / 100f) else null
    }
}

/** ცივი დაქოქვა: ძრავის გათბობისას საწვავის კორექციის შეჯამება. */
data class ColdStart(val time: Long, val startC: Int, val minutes: Int, val avgTrim: Float, val maxTrim: Float, val avgLtft: Float)
