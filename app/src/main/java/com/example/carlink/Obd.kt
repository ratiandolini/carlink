package com.example.carlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** მანქანის მონაცემების ერთი "ფოტო". null = მანქანამ ეს მონაცემი არ მოგვცა. */
data class CarSnapshot(
    val fuelPercent: Float? = null,
    val coolantC: Int? = null,
    val batteryV: Float? = null,
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val mapKpa: Int? = null,
    val baroKpa: Int? = null,
    val iatC: Int? = null,
    val mafGs: Float? = null,
    val loadPct: Float? = null,
    val throttlePct: Float? = null,
    val stftPct: Float? = null,
    val ltftPct: Float? = null,
    val dtcs: List<String> = emptyList(),
    val pendingDtcs: List<String> = emptyList(),
    val timeMillis: Long = System.currentTimeMillis(),
)

interface ObdSource {
    suspend fun connect()
    suspend fun read(): CarSnapshot

    /** მხოლოდ წაკითხვის ბრძანება (ვალიდაცია ზემოთ, ViewModel-ში). header = null -> ზოგადი 7DF. */
    suspend fun raw(header: String?, cmd: String, timeoutMs: Long = 3000): String
    suspend fun supportedPids(): List<String>
    suspend fun vin(): String?
    suspend fun adapterInfo(): String

    /** Mode 04: ძრავის (OBD-II) კოდების და Check Engine ნათურის წაშლა. */
    suspend fun clearDtcs(): String

    /** პასიური მოსმენა (ATMA): არაფერს აგზავნის. აბრუნებს ნედლ ხაზებს: "ID B0 B1 ...". */
    suspend fun monitor(filterId: String?, seconds: Int): List<String>
    fun close()
}

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * უსაფრთხოების ფილტრი: ექსპლორერიდან მხოლოდ ეს წაკითხვის სერვისები გადის.
 * დაბლოკილია: 04 (კოდების წაშლა), 10/11 (სესია/გადატვირთვა), 14, 27, 2E, 2F, 31, 34-37 და ა.შ.
 */
private val READ_ONLY = listOf(
    Regex("^(01|02|03|07|0A)([0-9A-F]{2}){0,6}$"), // OBD-II წაკითხვა
    Regex("^09([0-9A-F]{2})$"),                    // მანქანის ინფო (VIN...)
    Regex("^22([0-9A-F]{2}){2,}$"),                // UDS ReadDataByIdentifier
    Regex("^19([0-9A-F]{2}){1,6}$"),               // UDS ReadDTCInformation
    Regex("^3E00$"),                               // TesterPresent
)

fun isReadOnly(cmd: String): Boolean {
    val c = cmd.uppercase().replace(" ", "")
    return READ_ONLY.any { it.matches(c) }
}

/**
 * ELM327 Bluetooth კლიენტი. მხოლოდ კითხვა: ბრძანებები (ფარები, საკეტი და ა.შ.)
 * ამ ეტაპზე ჩაშენებული არ არის.
 */
class Elm327Source(private val device: BluetoothDevice) : ObdSource {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var tick = 0
    private var lastDtcs: List<String> = emptyList()
    private var lastPending: List<String> = emptyList()

    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val s = openSocket()
            socket = s
            input = s.inputStream
            output = s.outputStream
            for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
                send(cmd, if (cmd == "ATZ") 3000 else 1500)
            }
            // პროტოკოლის ავტოამოცნობას რამდენიმე წამი სჭირდება; ვათბობთ, რომ პირველი კითხვა არ გაფუჭდეს
            try { send("0100", 12000) } catch (_: Exception) {}
        }
    }

    /** ზოგ ადაპტერზე ჩვეულებრივი კავშირი ვერ გადის; ვცდით სამ ხერხს თანმიმდევრობით. */
    @SuppressLint("MissingPermission")
    private fun openSocket(): BluetoothSocket {
        val makers = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (make in makers) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                return s
            } catch (e: Exception) {
                last = e
                try { s?.close() } catch (_: Exception) {}
            }
        }
        throw last ?: IllegalStateException("ადაპტერთან კავშირი ვერ დამყარდა")
    }

    /** PID, რომელიც 3-ჯერ ზედიზედ არ გვიპასუხა, ხშირად აღარ ვკითხულობთ (დრო არ დაიკარგოს). */
    private val misses = mutableMapOf<String, Int>()

    private fun pidSmart(p: String): List<Int>? {
        if ((misses[p] ?: 0) >= 3 && tick % 30 != 0) return null
        val r = pid(p)
        misses[p] = if (r == null) (misses[p] ?: 0) + 1 else 0
        return r
    }

    override suspend fun monitor(filterId: String?, seconds: Int): List<String> = withContext(Dispatchers.IO) {
        val out = output ?: throw IllegalStateException("კავშირი არ არის")
        val inp = input ?: throw IllegalStateException("კავშირი არ არის")
        val sb = StringBuilder()
        var started = false
        try {
            send("ATH1")
            send("ATS1")
            send("ATCAF0")
            if (filterId != null) send("ATCRA$filterId")
            while (inp.available() > 0) inp.read()
            out.write("ATMA\r".toByteArray())
            out.flush()
            started = true
            val end = System.currentTimeMillis() + seconds * 1000L
            while (System.currentTimeMillis() < end) {
                ensureActive()
                if (inp.available() > 0) sb.append(inp.read().toChar()) else Thread.sleep(5)
            }
        } finally {
            if (started) {
                // ნებისმიერი სიმბოლო აჩერებს მონიტორინგს
                try {
                    out.write("\r".toByteArray())
                    out.flush()
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 2500) {
                        if (inp.available() > 0) {
                            if (inp.read().toChar() == '>') break
                        } else {
                            Thread.sleep(5)
                        }
                    }
                } catch (_: Exception) {}
            }
            try {
                send("ATAR")
                send("ATCAF1")
                send("ATS0")
                send("ATH0")
            } catch (_: Exception) {}
        }
        sb.toString().replace("\r", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    override suspend fun clearDtcs(): String = withContext(Dispatchers.IO) {
        val r = send("04", 6000)
        lastDtcs = emptyList()
        lastPending = emptyList()
        tick = 0 // შემდეგ ციკლზე კოდები თავიდან წაიკითხება
        r
    }

    override suspend fun adapterInfo(): String = withContext(Dispatchers.IO) {
        listOf("ATI", "ATDP", "ATRV").joinToString("\n") { c ->
            "$c → ${send(c, 2500).replace("\n", " | ")}"
        }
    }

    override suspend fun read(): CarSnapshot = withContext(Dispatchers.IO) {
        val fuel = pidSmart("012F")?.firstOrNull()?.let { it * 100f / 255f }
        val coolant = pidSmart("0105")?.firstOrNull()?.let { it - 40 }
        val rpm = pidSmart("010C")?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 4 }
        val speed = pidSmart("010D")?.firstOrNull()
        val map = pidSmart("010B")?.firstOrNull()
        val baro = pidSmart("0133")?.firstOrNull()
        val iat = pidSmart("010F")?.firstOrNull()?.let { it - 40 }
        val maf = pidSmart("0110")?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 100f }
        val load = pidSmart("0104")?.firstOrNull()?.let { it * 100f / 255f }
        val thr = pidSmart("0111")?.firstOrNull()?.let { it * 100f / 255f }
        val stft = pidSmart("0106")?.firstOrNull()?.let { (it - 128) * 100f / 128f }
        val ltft = pidSmart("0107")?.firstOrNull()?.let { (it - 128) * 100f / 128f }
        val volt = readVoltage()
        // შეცდომის კოდებს ყოველ ~30 წამში ვკითხულობთ, ყოველ ციკლზე საჭირო არაა
        if (tick++ % 15 == 0) {
            lastDtcs = readDtcs("03")
            lastPending = readDtcs("07")
        }
        CarSnapshot(
            fuelPercent = fuel,
            coolantC = coolant,
            batteryV = volt,
            rpm = rpm,
            speedKmh = speed,
            mapKpa = map,
            baroKpa = baro,
            iatC = iat,
            mafGs = maf,
            loadPct = load,
            throttlePct = thr,
            stftPct = stft,
            ltftPct = ltft,
            dtcs = lastDtcs,
            pendingDtcs = lastPending,
        )
    }

    override suspend fun raw(header: String?, cmd: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            val custom = header != null && header != "7DF"
            try {
                if (custom) send("ATSH$header")
                send(cmd, timeoutMs)
            } finally {
                if (custom) {
                    try { send("ATSH7DF") } catch (_: Exception) {}
                }
            }
        }

    /** Mode 01 PID 00/20/40... ბიტმასკებით ვარკვევთ, რას უჭერს მხარს მანქანა. */
    override suspend fun supportedPids(): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        var base = 0
        while (base <= 0xC0) {
            val data = pid("01%02X".format(base)) ?: break
            if (data.size < 4) break
            val mask = data.take(4).fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
            for (i in 0 until 32) {
                if (((mask shr (31 - i)) and 1L) == 1L) out += "%02X".format(base + i + 1)
            }
            if ((mask and 1L) == 0L) break
            base += 0x20
        }
        out
    }

    /** VIN (Mode 09 PID 02). საუკეთესო მცდელობა; თუ მანქანა არ პასუხობს, null. */
    override suspend fun vin(): String? = withContext(Dispatchers.IO) {
        val resp = send("0902", 5000).uppercase()
        if (resp.contains("NO DATA")) return@withContext null
        val cleaned = resp.lines().mapNotNull { line ->
            val l = line.replace(" ", "")
            when {
                Regex("^[0-9A-F]{3}$").matches(l) -> null
                Regex("^[0-9A-F]:.*").matches(l) -> l.substring(2)
                else -> l
            }
        }.joinToString("").filter { it in "0123456789ABCDEF" }
        val idx = cleaned.indexOf("490201")
        if (idx < 0) return@withContext null
        val text = cleaned.substring(idx + 6)
            .chunked(2)
            .filter { it.length == 2 }
            .map { it.toInt(16) }
            .filter { it in 0x30..0x39 || it in 0x41..0x5A }
            .map { it.toChar() }
            .joinToString("")
        text.take(17).takeIf { it.length == 17 }
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    private fun send(cmd: String, timeoutMs: Long = 2500): String {
        val out = output ?: throw IllegalStateException("კავშირი არ არის")
        val inp = input ?: throw IllegalStateException("კავშირი არ არის")
        while (inp.available() > 0) inp.read()
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (inp.available() > 0) {
                val c = inp.read().toChar()
                if (c == '>') break
                sb.append(c)
            } else {
                Thread.sleep(10)
            }
        }
        return sb.toString()
            .replace("\r", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != cmd }
            .joinToString("\n")
    }

    /** სტანდარტული Mode 01 PID. აბრუნებს მონაცემის ბაიტებს ან null-ს. */
    private fun pid(pid: String): List<Int>? {
        val resp = send(pid).uppercase()
        if (resp.contains("NO DATA") || resp.contains("UNABLE") ||
            resp.contains("ERROR") || resp.contains("?")
        ) return null
        val hex = resp.replace(Regex("[^0-9A-F]"), "")
        val header = "41" + pid.substring(2)
        val idx = hex.indexOf(header)
        if (idx < 0) return null
        return hex.substring(idx + header.length)
            .chunked(2)
            .filter { it.length == 2 }
            .map { it.toInt(16) }
            .takeIf { it.isNotEmpty() }
    }

    private fun readVoltage(): Float? {
        val resp = send("ATRV")
        return Regex("([0-9]+\\.?[0-9]*)").find(resp)?.value?.toFloatOrNull()
    }

    /**
     * Mode 03 (დადასტურებული), 07 (მოლოდინში), 0A (მუდმივი). CAN-ზე პასუხი: SID, კოდების რაოდენობა, წყვილები.
     * მაგ. 43 05 0171 0236 ... -> 5 კოდი.
     */
    private fun readDtcs(mode: String = "03"): List<String> {
        val resp = send(mode, 5000).uppercase()
        if (resp.contains("NO DATA")) return emptyList()
        val sid = "%02X".format(mode.toInt(16) + 0x40)
        val result = mutableListOf<String>()
        for (msg in isoMessages(resp)) {
            if (!msg.startsWith(sid)) continue
            val bytes = msg.substring(2).chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
            if (bytes.isEmpty()) continue
            val n = bytes[0]
            val body = bytes.drop(1)
            val count = minOf(n, body.size / 2)
            for (i in 0 until count) {
                val code = "%02X%02X".format(body[2 * i], body[2 * i + 1])
                if (code != "0000") result += decodeDtc(code)
            }
        }
        return result.distinct()
    }
}

/** მანქანის გარეშე სატესტო მონაცემები. */
class DemoSource : ObdSource {
    private var t = 0
    override suspend fun connect() { delay(600) }
    override suspend fun read(): CarSnapshot {
        delay(300)
        t++
        return CarSnapshot(
            fuelPercent = 62f - (t % 40) * 0.1f,
            coolantC = 88 + (t % 5),
            batteryV = 14.1f - (t % 3) * 0.05f,
            rpm = 850 + (t % 7) * 15,
            speedKmh = 0,
            mapKpa = 98,
            baroKpa = 97,
            iatC = 24,
            mafGs = 2.6f,
            loadPct = 21f,
            throttlePct = 14f,
            stftPct = 1.5f,
            ltftPct = 9.4f,
            dtcs = listOf("P0420", "P0171"),
            pendingDtcs = listOf("P0446"),
        )
    }

    override suspend fun raw(header: String?, cmd: String, timeoutMs: Long): String {
        delay(150)
        return if (cmd == "0100") "41 00 BE 3F A8 13" else "NO DATA"
    }

    override suspend fun supportedPids(): List<String> {
        delay(400)
        return listOf("04", "05", "0C", "0D", "0F", "2F")
    }

    override suspend fun vin(): String? {
        delay(300)
        return "DEMO-VIN-NOT-REAL"
    }

    override suspend fun adapterInfo(): String = "ATI → DEMO\nATDP → DEMO\nATRV → 12.6V"

    override suspend fun clearDtcs(): String {
        delay(500)
        return "44"
    }

    override suspend fun monitor(filterId: String?, seconds: Int): List<String> {
        delay(seconds * 100L)
        return listOf(
            "1A0 11 22 33 44 55 66 77 88",
            "1A0 11 22 33 44 55 66 77 89",
            "3C9 00 11 22 33 44 55 66 77",
        )
    }

    override fun close() {}
}

/** ELM-ის ტექსტური პასუხი -> ცალკეული ISO-TP შეტყობინებები (hex ტექსტად). მრავალკადრიანი: "00C" + "0:..." + "1:...". */
internal fun isoMessages(resp: String): List<String> {
    val out = mutableListOf<StringBuilder>()
    var cur: StringBuilder? = null
    for (raw in resp.uppercase().lines()) {
        val l = raw.replace(" ", "").trim()
        if (l.isEmpty()) continue
        when {
            Regex("^[0-9A-F]{3}$").matches(l) -> {
                val sb = StringBuilder()
                out.add(sb)
                cur = sb
            }
            Regex("^[0-9A-F]:[0-9A-F]*$").matches(l) -> {
                if (cur == null) {
                    val sb = StringBuilder()
                    out.add(sb)
                    cur = sb
                }
                cur.append(l.substring(2))
            }
            Regex("^[0-9A-F]+$").matches(l) -> {
                out.add(StringBuilder(l))
                cur = null
            }
        }
    }
    return out.map { it.toString() }
}

fun decodeDtc(code: String): String {
    val a = code.substring(0, 2).toInt(16)
    val letter = "PCBU"[(a shr 6) and 3]
    val d1 = (a shr 4) and 3
    val d2 = (a and 0xF).toString(16).uppercase()
    return "$letter$d1$d2${code.substring(2).uppercase()}"
}

/** ხშირი კოდების მოკლე ახსნა (სრული სია არაა; უცნობი კოდი უბრალოდ ნაჩვენები იქნება). */
val DTC_INFO: Map<String, String> = mapOf(
    "P0420" to "კატალიზატორის ეფექტურობა დაბალია",
    "P0236" to "ბუსტის (MAP) სენსორი: დიაპაზონი/მუშაობა",
    "P0299" to "ტურბოს წნევა დაბალია (underboost)",
    "P2227" to "ბარომეტრული წნევის სენსორი: დიაპაზონი/მუშაობა",
    "P0446" to "საწვავის ორთქლის სისტემა: ვენტილაციის წრედი",
    "P0501" to "სიჩქარის სენსორი: დიაპაზონი/მუშაობა",
    "P0171" to "ნარევი ძალიან ღარიბია (ბანკი 1)",
    "P0172" to "ნარევი ძალიან მდიდარია (ბანკი 1)",
    "P0128" to "ძრავი ნელა თბება (თერმოსტატი?)",
    "P0300" to "ცილინდრების შემთხვევითი გამოტოვება",
    "P0301" to "პირველი ცილინდრი გამოტოვებს",
    "P0302" to "მეორე ცილინდრი გამოტოვებს",
    "P0303" to "მესამე ცილინდრი გამოტოვებს",
    "P0304" to "მეოთხე ცილინდრი გამოტოვებს",
    "P0442" to "საწვავის ორთქლის სისტემა: მცირე გაჟონვა",
    "P0455" to "საწვავის ორთქლის სისტემა: დიდი გაჟონვა (ხშირად ბაკის თავსახური)",
)
