package com.example.carlink

import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** ერთი მგზავრობის შეჯამება (ბუსტი, საწვავის კორექცია, სიჩქარე). */
data class TripSummary(
    val start: Long,
    val end: Long,
    val moveSec: Int,
    val maxSpeed: Int,
    val maxRpm: Int,
    val maxMap: Int,
    val baro: Int,
    val maxLoad: Int,
    val avgTrim: Float,
    val maxTrim: Float,
    val leanPct: Int,
) {
    val maxBoost: Int get() = if (maxMap > 0 && baro > 0) maxMap - baro else 0

    /** მოკლე დასკვნები. მხოლოდ ინფორმაციისთვის, დიაგნოზი არ არის. */
    fun hints(): List<String> {
        val out = mutableListOf<String>()
        if (maxMap <= 0) {
            out += "MAP მონაცემი არ ჩაწერილა."
        } else if (maxLoad >= 70) {
            if (maxBoost < 30) {
                out += "დატვირთვა მაღალი იყო (${maxLoad}%), მაგრამ ბუსტი დაბალი: მაქს. +$maxBoost kPa. ეს დაბალი ბუსტის ნიშანია (P0299-თან შესაბამისი)."
            } else {
                out += "ძლიერ დატვირთვაზე ბუსტი გაიზარდა: მაქს. +$maxBoost kPa."
            }
        } else {
            out += "ძლიერი დატვირთვა არ ყოფილა (მაქს. ${maxLoad}%), ამიტომ ბუსტის შეფასება ჯერ ზუსტი არ არის."
        }
        if (avgTrim >= 12f) {
            out += "საწვავის კორექცია საშუალოდ +${"%.1f".format(avgTrim)}%: ნარევი ღარიბია (P0171-თან შესაბამისი)."
        } else if (avgTrim <= -12f) {
            out += "საწვავის კორექცია საშუალოდ ${"%.1f".format(avgTrim)}%: ნარევი მდიდარია."
        } else {
            out += "საწვავის კორექცია ნორმის ფარგლებშია (საშუალოდ ${"%.1f".format(avgTrim)}%)."
        }
        if (leanPct >= 30) out += "დროის ${leanPct}%-ში ნარევი ძალიან ღარიბი იყო (კორექცია > +15%)."
        return out
    }

    fun toLine(): String =
        "$start,$end,$moveSec,$maxSpeed,$maxRpm,$maxMap,$baro,$maxLoad,$avgTrim,$maxTrim,$leanPct"

    companion object {
        fun fromLine(line: String): TripSummary? {
            val p = line.split(",")
            if (p.size < 11) return null
            return try {
                TripSummary(
                    p[0].toLong(), p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toInt(),
                    p[5].toInt(), p[6].toInt(), p[7].toInt(), p[8].toFloat(), p[9].toFloat(), p[10].toInt(),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

class TripStore(private val file: File) {
    fun append(t: TripSummary) {
        file.appendText(t.toLine() + "\n")
        if (file.length() > 100_000) {
            file.writeText(file.readLines().takeLast(100).joinToString("\n") + "\n")
        }
    }

    fun load(max: Int = 10): List<TripSummary> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { TripSummary.fromLine(it) }.takeLast(max)
    }
}

/**
 * მგზავრობის ჩამწერი: იწყება, როცა სიჩქარე > 3 კმ/სთ, მთავრდება 3 წუთიანი გაჩერებისას
 * ან კავშირის გაწყვეტისას. მხოლოდ კითხულობს, მანქანას არაფერს უგზავნის.
 */
class TripRecorder {
    var active = false
        private set
    private var start = 0L
    private var lastT = 0L
    private var lastMovingT = 0L
    private var moveMs = 0L
    private var maxSpeed = 0
    private var maxRpm = 0
    private var maxMap = 0
    private var baro = 0
    private var maxLoad = 0f
    private var trimSum = 0.0
    private var trimN = 0
    private var maxTrim = -100f
    private var leanN = 0

    private fun reset() {
        active = false; start = 0; lastT = 0; lastMovingT = 0; moveMs = 0
        maxSpeed = 0; maxRpm = 0; maxMap = 0; baro = 0; maxLoad = 0f
        trimSum = 0.0; trimN = 0; maxTrim = -100f; leanN = 0
    }

    /** აბრუნებს დასრულებულ მგზავრობას, თუ ის ახლა დასრულდა. */
    fun update(s: CarSnapshot): TripSummary? {
        val moving = (s.speedKmh ?: 0) > 3
        if (!active) {
            if (!moving) return null
            reset()
            active = true
            start = s.timeMillis
            lastT = s.timeMillis
            lastMovingT = s.timeMillis
        }
        val dt = (s.timeMillis - lastT).coerceIn(0, 10_000)
        lastT = s.timeMillis
        s.baroKpa?.let { baro = it }
        if (moving) {
            lastMovingT = s.timeMillis
            moveMs += dt
            maxSpeed = max(maxSpeed, s.speedKmh ?: 0)
            maxRpm = max(maxRpm, s.rpm ?: 0)
            s.mapKpa?.let { maxMap = max(maxMap, it) }
            s.loadPct?.let { maxLoad = max(maxLoad, it) }
            val st = s.stftPct
            val lt = s.ltftPct
            if (st != null && lt != null) {
                val tot = st + lt
                trimSum += tot
                trimN++
                maxTrim = max(maxTrim, tot)
                if (tot > 15f) leanN++
            }
        } else if (s.timeMillis - lastMovingT > 180_000) {
            return finish()
        }
        return null
    }

    /** ძალით დასრულება (კავშირი გაწყდა). მოკლე მგზავრობებს (< 60 წმ) არ ინახავს. */
    fun finish(): TripSummary? {
        if (!active) return null
        val sum = TripSummary(
            start = start,
            end = lastT,
            moveSec = (moveMs / 1000).toInt(),
            maxSpeed = maxSpeed,
            maxRpm = maxRpm,
            maxMap = maxMap,
            baro = baro,
            maxLoad = maxLoad.roundToInt(),
            avgTrim = if (trimN > 0) (trimSum / trimN).toFloat() else 0f,
            maxTrim = if (trimN > 0) maxTrim else 0f,
            leanPct = if (trimN > 0) leanN * 100 / trimN else 0,
        )
        reset()
        return if (sum.moveSec >= 60) sum else null
    }
}
