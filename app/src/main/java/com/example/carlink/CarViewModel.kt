package com.example.carlink

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class Conn { Idle, Connecting, Connected, Failed }

data class UiState(
    val connection: Conn = Conn.Idle,
    val deviceName: String? = null,
    val snapshot: CarSnapshot? = null,
    val lastSaved: CarSnapshot? = null,
    val history: List<HistPoint> = emptyList(),
    val dtcLog: List<Pair<Long, String>> = emptyList(),
    val clearResult: String? = null,
    val error: String? = null,
    val demo: Boolean = false,
    val trips: List<TripSummary> = emptyList(),
    val tripActive: Boolean = false,
)

data class ExplorerState(
    val log: List<String> = emptyList(),
    val busy: Boolean = false,
    val vin: String? = null,
    val supported: List<String> = emptyList(),
)

class CarViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("carlink", Context.MODE_PRIVATE)
    private val history = HistoryStore(File(app.filesDir, "history.csv"))
    private val dtcLog = DtcLog(File(app.filesDir, "dtc_log.txt"))
    private val tripStore = TripStore(File(app.filesDir, "trips.csv"))
    private val recorder = TripRecorder()
    private val _state = MutableStateFlow(
        UiState(lastSaved = loadSaved(), deviceName = prefs.getString("dev_name", null))
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _ex = MutableStateFlow(ExplorerState())
    val explorerState: StateFlow<ExplorerState> = _ex.asStateFlow()

    private var job: Job? = null
    private var exJob: Job? = null
    private var source: ObdSource? = null

    /** ადაპტერზე ერთდროულად მხოლოდ ერთი ოპერაცია (პერიოდული კითხვა ან ექსპლორერი). */
    private val ioMutex = Mutex()

    private var lastHistoryAt = 0L
    private var fuelAlert = false
    private var batteryAlert = false
    private var coolantAlert = false
    private var knownDtcs: Set<String> = loadSaved()?.dtcs?.toSet() ?: emptySet()

    init {
        viewModelScope.launch {
            val h = withContext(Dispatchers.IO) { history.load() }
            val l = withContext(Dispatchers.IO) { dtcLog.load() }
            val t = withContext(Dispatchers.IO) { tripStore.load() }
            _state.update { it.copy(history = h, dtcLog = l, trips = t) }
        }
    }

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<Pair<String, String>> = try {
        val bm = getApplication<Application>().getSystemService(BluetoothManager::class.java)
        bm?.adapter?.bondedDevices
            ?.map { (it.name ?: "უცნობი") to it.address }
            ?.sortedBy { it.first }
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun selectDevice(name: String, address: String) {
        prefs.edit().putString("dev_name", name).putString("dev_addr", address).apply()
        _state.update { it.copy(deviceName = name, error = null) }
    }

    @SuppressLint("MissingPermission")
    fun connect(demo: Boolean = false) {
        job?.cancel()
        exJob?.cancel()
        job = viewModelScope.launch {
            var src: ObdSource? = null
            try {
                _state.update { it.copy(connection = Conn.Connecting, error = null, demo = demo) }
                src = if (demo) {
                    DemoSource()
                } else {
                    val addr = prefs.getString("dev_addr", null)
                        ?: throw IllegalStateException("ჯერ აირჩიე ადაპტერი")
                    val bm = getApplication<Application>().getSystemService(BluetoothManager::class.java)
                    val adapter = bm?.adapter ?: throw IllegalStateException("Bluetooth არ არის ხელმისაწვდომი")
                    Elm327Source(adapter.getRemoteDevice(addr))
                }
                src.connect()
                val active: ObdSource = src
                source = active
                _state.update { it.copy(connection = Conn.Connected) }
                while (isActive) {
                    val snap = ioMutex.withLock { active.read() }
                    _state.update { it.copy(snapshot = snap) }
                    if (!demo) {
                        save(snap)
                        checkAlerts(snap)
                        maybeLogHistory(snap)
                        recorder.update(snap)?.let { saveTrip(it) }
                        _state.update { it.copy(tripActive = recorder.active) }
                    }
                    delay(1000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val friendly = if (e is IllegalStateException && msg.isNotBlank()) {
                    msg
                } else {
                    "ადაპტერთან კავშირი ვერ დამყარდა.\nშეამოწმე: ანთება ჩართულია, ELM327-ის ინდიკატორი ანთია, ტელეფონში დაწყვილებულია და სხვა აპი მასთან არ არის დაკავშირებული."
                }
                _state.update {
                    it.copy(
                        connection = Conn.Failed,
                        error = if (msg.isBlank() || friendly == msg) friendly else "$friendly\n\n[$msg]",
                    )
                }
            } finally {
                recorder.finish()?.let { saveTrip(it) }
                _state.update { it.copy(tripActive = false) }
                source = null
                src?.close()
            }
        }
    }

    fun disconnect() {
        exJob?.cancel()
        job?.cancel()
        _state.update { it.copy(connection = Conn.Idle, snapshot = null, lastSaved = loadSaved()) }
    }

    // ---------- ექსპლორერი (მხოლოდ წაკითხვა) ----------

    private fun log(line: String) {
        _ex.update { it.copy(log = (it.log + line).takeLast(500)) }
    }

    private fun logBlock(text: String) {
        text.lines().filter { it.isNotBlank() }.forEach { log("  $it") }
    }

    fun clearLog() {
        _ex.update { it.copy(log = emptyList()) }
    }

    fun stopExplorer() {
        exJob?.cancel()
    }

    private fun movingNow(): Boolean = (_state.value.snapshot?.speedKmh ?: 0) > 0

    private fun explorer(title: String, block: suspend (ObdSource) -> Unit) {
        val src = source
        if (src == null) {
            log("⚠ ჯერ დაუკავშირდი ადაპტერს (ან დემოს)")
            return
        }
        if (_ex.value.busy) return
        exJob = viewModelScope.launch {
            _ex.update { it.copy(busy = true) }
            log("▶ $title")
            try {
                ioMutex.withLock { block(src) }
                log("✓ დასრულდა")
            } catch (e: CancellationException) {
                log("■ შეჩერებულია")
                throw e
            } catch (e: Exception) {
                log("⚠ ${e.message}")
            } finally {
                _ex.update { it.copy(busy = false) }
            }
        }
    }

    fun scanSupported() = explorer("მხარდაჭერილი PID-ების სკანირება") { src ->
        val list = src.supportedPids()
        _ex.update { it.copy(supported = list) }
        log(if (list.isEmpty()) "PID ვერ ვიპოვე" else "ნაპოვნია ${list.size}: ${list.joinToString(" ")}")
    }

    fun readVin() = explorer("VIN-ის წაკითხვა") { src ->
        val v = src.vin()
        _ex.update { it.copy(vin = v) }
        log("VIN: ${v ?: "ვერ წავიკითხე"}")
    }

    /** 7E0..7E7 ფიზიკურ მისამართებზე ვცდით 0100-ს და ვნახავთ, რომელი მოდული პასუხობს. */
    fun scanEcus() = explorer("მოდულების (ECU) ძებნა 7E0–7E7") { src ->
        for (n in 0..7) {
            val h = "7E$n"
            val r = src.raw(h, "0100", 1500)
            log("$h → ${r.replace("\n", " | ").ifBlank { "(ცარიელი)" }}")
        }
    }

    fun sendCustom(header: String, cmd: String) {
        val h = header.trim().uppercase().ifEmpty { "7DF" }
        val c = cmd.trim().uppercase().replace(" ", "")
        if (!Regex("^[0-9A-F]{3}$").matches(h)) {
            log("⚠ header უნდა იყოს 3 სიმბოლო (მაგ. 7E0)")
            return
        }
        if (!isReadOnly(c)) {
            log("⛔ დაბლოკილია: ნებადართულია მხოლოდ წაკითხვა (01, 02, 03, 07, 09, 0A, 19, 22, 3E00)")
            return
        }
        if (movingNow()) {
            log("⛔ მანქანა მოძრაობს. ექსპლორერი მხოლოდ გაჩერებულ მანქანაზე მუშაობს")
            return
        }
        explorer("$h ← $c") { src ->
            val r = src.raw(h, c)
            if (r.isBlank()) log("  (ცარიელი პასუხი)") else logBlock(r)
        }
    }

    private suspend fun sweepRange(src: ObdSource, h: String, a: Int, b: Int) {
        var hits = 0
        var negatives = 0
        for (d in a..b) {
            if (movingNow()) {
                log("⛔ მანქანა დაიძრა, ვჩერდები")
                return
            }
            val did = "%04X".format(d)
            val r = src.raw(h, "22$did", 1200).uppercase().replace(" ", "")
            when {
                r.contains("62$did") -> {
                    hits++
                    log("✔ $h $did → ${r.replace("\n", "|")}")
                }
                r.contains("7F22") -> negatives++
            }
        }
        log("$h: ნაპოვნია $hits · უარყოფითი პასუხი: $negatives")
    }

    /** ერთი ღილაკი: ადაპტერის ინფო, PID-ები, VIN, ECU ძებნა 7E0–7E7, საიდენტიფიკაციო DID-ები. */
    fun fullTest() {
        if (movingNow()) {
            log("⛔ მანქანა მოძრაობს. ტესტი მხოლოდ გაჩერებულ მანქანაზე")
            return
        }
        explorer("სრული ტესტი") { src ->
            logBlock(src.adapterInfo())
            val pids = src.supportedPids()
            _ex.update { it.copy(supported = pids) }
            log(if (pids.isEmpty()) "PID ვერ ვიპოვე" else "PID (${pids.size}): ${pids.joinToString(" ")}")
            val v = src.vin()
            _ex.update { it.copy(vin = v) }
            log("VIN: ${v ?: "ვერ წავიკითხე"}")
            val responders = mutableListOf<String>()
            for (n in 0..7) {
                val h = "7E$n"
                val r = src.raw(h, "0100", 1500)
                log("$h → ${r.replace("\n", " | ").ifBlank { "(ცარიელი)" }}")
                if (r.uppercase().replace(" ", "").contains("4100")) responders += h
            }
            for (h in responders) {
                log("DID F180–F19F · $h")
                sweepRange(src, h, 0xF180, 0xF19F)
            }
        }
    }

    /** პასიური მოსმენა (ATMA): არაფერს აგზავნის, მხოლოდ ითვლის ბუსზე გამავალ კადრებს. */
    fun listenBus(secondsText: String, idText: String) {
        val sec = secondsText.trim().toIntOrNull()
        val id = idText.trim().uppercase()
        if (sec == null || sec !in 1..30) {
            log("⚠ წამები: 1–30")
            return
        }
        if (id.isNotEmpty() && !Regex("^[0-9A-F]{3}$").matches(id)) {
            log("⚠ ID = 3 სიმბოლო (მაგ. 3C9) ან ცარიელი")
            return
        }
        val title = if (id.isEmpty()) "ბუსის მოსმენა $sec წმ (ყველა ID)" else "ბუსის მოსმენა $sec წმ (ID $id)"
        explorer(title) { src ->
            val lines = src.monitor(id.ifEmpty { null }, sec)
            val frames = mutableMapOf<String, MutableList<String>>()
            var overflow = 0
            val hex3 = Regex("^[0-9A-F]{3}$")
            val hex2 = Regex("^[0-9A-F]{2}$")
            for (ln in lines) {
                val up = ln.uppercase()
                if (up.contains("BUFFER FULL")) {
                    overflow++
                    continue
                }
                val t = up.split(" ").filter { it.isNotEmpty() }
                if (t.size < 2 || !hex3.matches(t[0])) continue
                if (!t.drop(1).all { hex2.matches(it) }) continue
                frames.getOrPut(t[0]) { mutableListOf() }.add(t.drop(1).joinToString(" "))
            }
            val total = frames.values.sumOf { it.size }
            log("კადრები: $total · განსხვავებული ID: ${frames.size}" + if (overflow > 0) " · ბუფერი ივსებოდა ×$overflow" else "")
            if (id.isNotEmpty()) {
                val list = frames[id].orEmpty()
                list.groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }
                    .take(40)
                    .forEach { e -> log("  ${e.key}  ×${e.value}") }
            } else {
                frames.entries.sortedBy { it.key }.take(120).forEach { e ->
                    log("${e.key} ×${e.value.size} · უნიკ.${e.value.toSet().size} · ${e.value.last()}")
                }
            }
        }
    }

    /** UDS TesterPresent (3E00) მხოლოდ სტანდარტულ დიაგნოსტიკურ მისამართებზე 700–7FF. */
    fun scanAddressRange(from: String, to: String) {
        val f = from.trim().uppercase()
        val t = to.trim().uppercase()
        val hex3 = Regex("^[0-9A-F]{3}$")
        if (!hex3.matches(f) || !hex3.matches(t)) {
            log("⚠ მისამართები 3 სიმბოლოა (მაგ. 700)")
            return
        }
        val a = f.toInt(16)
        val b = t.toInt(16)
        if (a < 0x700 || b > 0x7FF || b < a || b - a > 255) {
            log("⚠ დაშვებულია მხოლოდ 700–7FF (მაქს. 256 მისამართი)")
            return
        }
        if (movingNow()) {
            log("⛔ მანქანა მოძრაობს")
            return
        }
        explorer("ECU ძებნა $f–$t (3E00)") { src ->
            var found = 0
            for (x in a..b) {
                if (movingNow()) {
                    log("⛔ მანქანა დაიძრა, ვჩერდები")
                    break
                }
                val h = "%03X".format(x)
                if ((x - a) % 16 == 0) log("… $h")
                val r = src.raw(h, "3E00", 1500).uppercase().replace(" ", "")
                if (r.contains("7E00") || r.contains("7F3E")) {
                    found++
                    log("✔ $h → ${r.replace("\n", "|")}")
                }
            }
            log("ნაპოვნია მოდული: $found")
        }
    }

    /** UDS 22 წაკითხვა DID დიაპაზონზე (მაქს. 256). ვინახავთ მხოლოდ დადებით პასუხებს. */
    fun sweepDids(header: String, from: String, to: String) {
        val h = header.trim().uppercase()
        val f = from.trim().uppercase()
        val t = to.trim().uppercase()
        val hex4 = Regex("^[0-9A-F]{4}$")
        if (!Regex("^[0-9A-F]{3}$").matches(h) || !hex4.matches(f) || !hex4.matches(t)) {
            log("⚠ header = 3 სიმბოლო (7E0), DID-ები = 4 სიმბოლო (F180)")
            return
        }
        val a = f.toInt(16)
        val b = t.toInt(16)
        if (b < a || b - a > 255) {
            log("⚠ დიაპაზონი: მაქსიმუმ 256 DID და 'დან' ≤ 'მდე'")
            return
        }
        if (movingNow()) {
            log("⛔ მანქანა მოძრაობს. სკანირება მხოლოდ გაჩერებულ მანქანაზე")
            return
        }
        explorer("DID წაკითხვა $h: $f–$t") { src ->
            var hits = 0
            var negatives = 0
            for (d in a..b) {
                if (movingNow()) {
                    log("⛔ მანქანა დაიძრა, ვჩერდები")
                    break
                }
                val did = "%04X".format(d)
                val r = src.raw(h, "22$did", 1200).uppercase().replace(" ", "")
                when {
                    r.contains("62$did") -> {
                        hits++
                        log("✔ $did → ${r.replace("\n", "|")}")
                    }
                    r.contains("7F22") -> negatives++
                }
            }
            log("სულ ნაპოვნია: $hits · უარყოფითი პასუხი: $negatives")
        }
    }

    // ---------- გაფრთხილებები და ისტორია ----------

    private fun checkAlerts(s: CarSnapshot) {
        val ctx = getApplication<Application>()
        s.fuelPercent?.let {
            if (it < 15f && !fuelAlert) {
                fuelAlert = true
                Notifier.notify(ctx, 1, "ბენზინი დაბალია", "დარჩენილია დაახლოებით ${it.roundToInt()}%")
            } else if (it > 20f) fuelAlert = false
        }
        s.batteryV?.let {
            if (it < 11.8f && !batteryAlert) {
                batteryAlert = true
                Notifier.notify(ctx, 2, "აკუმულატორის ძაბვა დაბალია", "%.1f V".format(it))
            } else if (it > 12.2f) batteryAlert = false
        }
        s.coolantC?.let {
            if (it > 105 && !coolantAlert) {
                coolantAlert = true
                Notifier.notify(ctx, 3, "ძრავი გადახურდა", "გამაგრილებელი: $it °C")
            } else if (it < 100) coolantAlert = false
        }
        val now = s.dtcs.toSet()
        val fresh = now - knownDtcs
        if (fresh.isNotEmpty()) {
            Notifier.notify(ctx, 4, "ახალი შეცდომა", fresh.joinToString(", "))
            logDtc("აღმოჩენილია: ${fresh.joinToString(", ")}")
        }
        knownDtcs = now
    }

    private fun logDtc(text: String) {
        viewModelScope.launch {
            val l = withContext(Dispatchers.IO) {
                dtcLog.append(text)
                dtcLog.load()
            }
            _state.update { it.copy(dtcLog = l) }
        }
    }

    fun dismissClearResult() {
        _state.update { it.copy(clearResult = null) }
    }

    /** კოდების წაშლა (Mode 04). მხოლოდ გაჩერებულ მანქანაზე და ჩაქრობილი ძრავით. კოდები ჯერ ჟურნალში ინახება. */
    fun clearDtcs() {
        val src = source
        val s = _state.value.snapshot
        if (src == null || s == null) {
            _state.update { it.copy(clearResult = "⚠ ჯერ დაუკავშირდი მანქანას") }
            return
        }
        if ((s.speedKmh ?: 0) > 0 || (s.rpm ?: 0) > 0) {
            _state.update { it.copy(clearResult = "⛔ წაშლა მხოლოდ გაჩერებულ მანქანაზე და ჩაქრობილი ძრავით (ანთება ჩართული)") }
            return
        }
        viewModelScope.launch {
            try {
                val codes = (s.dtcs + s.pendingDtcs).distinct()
                val r = ioMutex.withLock { src.clearDtcs() }
                val ok = r.uppercase().replace(" ", "").contains("44")
                knownDtcs = emptySet()
                logDtc("წაიშალა: ${codes.joinToString(", ").ifEmpty { "—" }} · პასუხი: ${r.replace("\n", " ").ifBlank { "ცარიელი" }}")
                _state.update {
                    it.copy(
                        clearResult = if (ok) "✓ კოდები წაიშალა. თუ პრობლემა რჩება, ისინი დაბრუნდება."
                        else "⚠ მანქანამ წაშლა არ დაადასტურა: ${r.ifBlank { "ცარიელი პასუხი" }}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(clearResult = "⚠ ${e.message ?: "წაშლა ვერ მოხერხდა"}") }
            }
        }
    }

    private fun saveTrip(t: TripSummary) {
        try {
            tripStore.append(t)
            val all = tripStore.load()
            _state.update { it.copy(trips = all) }
        } catch (e: Exception) {
        }
    }

    private suspend fun maybeLogHistory(s: CarSnapshot) {
        if (s.timeMillis - lastHistoryAt < 30_000) return
        lastHistoryAt = s.timeMillis
        val h = withContext(Dispatchers.IO) {
            history.append(s)
            history.load()
        }
        _state.update { it.copy(history = h) }
    }

    private fun save(s: CarSnapshot) {
        val e = prefs.edit()
        e.putLong("t", s.timeMillis)
        e.putString("fuel", s.fuelPercent?.toString())
        e.putString("coolant", s.coolantC?.toString())
        e.putString("battery", s.batteryV?.toString())
        e.putString("dtcs", s.dtcs.joinToString(","))
        e.apply()
    }

    private fun loadSaved(): CarSnapshot? {
        val t = prefs.getLong("t", 0L)
        if (t == 0L) return null
        return CarSnapshot(
            fuelPercent = prefs.getString("fuel", null)?.toFloatOrNull(),
            coolantC = prefs.getString("coolant", null)?.toIntOrNull(),
            batteryV = prefs.getString("battery", null)?.toFloatOrNull(),
            dtcs = prefs.getString("dtcs", "").orEmpty().split(",").filter { it.isNotBlank() },
            timeMillis = t,
        )
    }
}
