package com.example.carlink

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
    val profile: VehicleProfile = VehicleProfile(),
    val watching: Boolean = false,
    val live: List<LivePoint> = emptyList(),
    val freeze: List<String>? = null,
    val maint: List<MaintItem> = emptyList(),
    val tests: List<String>? = null,
    val testsBusy: Boolean = false,
    val tires: TireLog? = null,
    val backupMsg: String? = null,
    val odoKm: Int? = null,
    val curTripKm: Float = 0f,
    val curTripL: Float = 0f,
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
        UiState(
            lastSaved = loadSaved(),
            deviceName = prefs.getString("dev_name", null),
            profile = loadProfile(),
            watching = prefs.getBoolean("watching", false),
            maint = loadMaint(),
            tires = loadTires(),
            odoKm = odoNow(),
        )
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
            val t = withContext(Dispatchers.IO) { tripStore.load(100) }
            _state.update { it.copy(history = h, dtcLog = l, trips = t) }
            checkMaint()
        }
    }

    // ---------- პროფილი და შეხსენებები ----------

    private fun loadProfile(): VehicleProfile {
        val d = VehicleProfile()
        return VehicleProfile(
            name = prefs.getString("p_name", null) ?: d.name,
            tankL = prefs.getFloat("p_tank", d.tankL),
            fuelAlertPct = prefs.getInt("p_fuel", d.fuelAlertPct),
            batteryAlertV = prefs.getFloat("p_batt", d.batteryAlertV),
            coolantAlertC = prefs.getInt("p_cool", d.coolantAlertC),
        )
    }

    fun saveProfile(p: VehicleProfile) {
        prefs.edit()
            .putString("p_name", p.name)
            .putFloat("p_tank", p.tankL)
            .putInt("p_fuel", p.fuelAlertPct)
            .putFloat("p_batt", p.batteryAlertV)
            .putInt("p_cool", p.coolantAlertC)
            .apply()
        _state.update { it.copy(profile = p) }
    }

    private fun loadTires(): TireLog? {
        val t = prefs.getLong("tire_t", 0L)
        if (t == 0L) return null
        fun f(k: String) = prefs.getString(k, null)?.toFloatOrNull()
        return TireLog(f("tire_fl"), f("tire_fr"), f("tire_rl"), f("tire_rr"), t)
    }

    fun saveTires(fl: Float?, fr: Float?, rl: Float?, rr: Float?) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("tire_fl", fl?.toString()).putString("tire_fr", fr?.toString())
            .putString("tire_rl", rl?.toString()).putString("tire_rr", rr?.toString())
            .putLong("tire_t", now).apply()
        _state.update { it.copy(tires = TireLog(fl, fr, rl, rr, now)) }
    }

    /** Mode 06 თვითტესტები. მხოლოდ გაჩერებულ მანქანაზე (ადაპტერს რამდენიმე წამით იკავებს). */
    fun readTests() {
        val src = source ?: return
        if ((_state.value.snapshot?.speedKmh ?: 0) > 0) {
            _state.update { it.copy(tests = listOf("⛔ მანქანა მოძრაობს. ტესტები მხოლოდ გაჩერებულზე იკითხება")) }
            return
        }
        if (_state.value.testsBusy) return
        viewModelScope.launch {
            _state.update { it.copy(testsBusy = true, tests = null) }
            try {
                val r = ioMutex.withLock { src.onboardTests() }
                _state.update { it.copy(tests = r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(tests = listOf("⚠ ${e.message ?: "ვერ წავიკითხე"}")) }
            } finally {
                _state.update { it.copy(testsBusy = false) }
            }
        }
    }

    fun dismissTests() {
        _state.update { it.copy(tests = null) }
    }

    // ---------- ბექაპი ----------

    fun backupText(): String {
        val dir = getApplication<Application>().filesDir
        fun file(n: String) = File(dir, n).takeIf { it.exists() }?.readText().orEmpty().trimEnd()
        val p = _state.value.profile
        return buildString {
            append("CARLINK-BACKUP v1\n")
            append("@@trips.csv\n").append(file("trips.csv")).append("\n")
            append("@@dtc_log.txt\n").append(file("dtc_log.txt")).append("\n")
            append("@@history.csv\n").append(file("history.csv")).append("\n")
            append("@@maint\n").append(prefs.getString("maint", "").orEmpty()).append("\n")
            append("@@profile\n")
            append("name=${p.name}\ntank=${p.tankL}\nfuel=${p.fuelAlertPct}\nbatt=${p.batteryAlertV}\ncool=${p.coolantAlertC}\n")
            append("@@odo\n")
            append("odo_base=${prefs.getFloat("odo_base", -1f)}\nodo_trk=${prefs.getFloat("odo_trk", 0f)}\ntrk_km=${prefs.getFloat("trk_km", 0f)}\n")
            append("@@tires\n")
            listOf("tire_fl", "tire_fr", "tire_rl", "tire_rr").forEach { append(it).append("=").append(prefs.getString(it, "").orEmpty()).append("\n") }
            append("tire_t=").append(prefs.getLong("tire_t", 0L)).append("\n")
        }
    }

    /** აბრუნებს შედეგის ტექსტს. არსებულ ჩანაწერებს სრულად ცვლის ბექაპის შიგთავსით. */
    fun restoreBackup(text: String): String {
        if (!text.startsWith("CARLINK-BACKUP")) return "⚠ ეს CarLink-ის ბექაპი არ არის"
        val sections = mutableMapOf<String, String>()
        var cur: String? = null
        val sb = StringBuilder()
        for (line in text.lines().drop(1)) {
            if (line.startsWith("@@")) {
                cur?.let { sections[it] = sb.toString().trimEnd() }
                cur = line.substring(2).trim()
                sb.clear()
            } else {
                sb.append(line).append("\n")
            }
        }
        cur?.let { sections[it] = sb.toString().trimEnd() }
        return try {
            val dir = getApplication<Application>().filesDir
            for (n in listOf("trips.csv", "dtc_log.txt", "history.csv")) {
                val body = sections[n].orEmpty()
                File(dir, n).writeText(if (body.isEmpty()) "" else body + "\n")
            }
            val e = prefs.edit()
            e.putString("maint", sections["maint"].orEmpty())
            sections["profile"]?.lines()?.forEach { l ->
                val i = l.indexOf('=')
                if (i > 0) {
                    val k = l.substring(0, i)
                    val v = l.substring(i + 1)
                    when (k) {
                        "name" -> e.putString("p_name", v)
                        "tank" -> v.toFloatOrNull()?.let { e.putFloat("p_tank", it) }
                        "fuel" -> v.toIntOrNull()?.let { e.putInt("p_fuel", it) }
                        "batt" -> v.toFloatOrNull()?.let { e.putFloat("p_batt", it) }
                        "cool" -> v.toIntOrNull()?.let { e.putInt("p_cool", it) }
                    }
                }
            }
            sections["odo"]?.lines()?.forEach { l ->
                val i = l.indexOf('=')
                if (i > 0) l.substring(i + 1).toFloatOrNull()?.let { v ->
                    val k = l.substring(0, i)
                    if (k == "odo_base" || k == "odo_trk" || k == "trk_km") e.putFloat(k, v)
                }
            }
            sections["tires"]?.lines()?.forEach { l ->
                val i = l.indexOf('=')
                if (i > 0) {
                    val k = l.substring(0, i)
                    val v = l.substring(i + 1)
                    if (k == "tire_t") e.putLong(k, v.toLongOrNull() ?: 0L)
                    else if (k.startsWith("tire_")) e.putString(k, v.ifEmpty { null })
                }
            }
            e.apply()
            val h = history.load()
            _state.update {
                it.copy(
                    history = h, dtcLog = dtcLog.load(), trips = tripStore.load(100),
                    maint = loadMaint(), profile = loadProfile(), tires = loadTires(), odoKm = odoNow(),
                )
            }
            "✓ აღდგენილია: ${tripStore.load(100).size} მგზავრობა, ${loadMaint().size} შეხსენება"
        } catch (ex: Exception) {
            "⚠ აღდგენა ვერ მოხერხდა: ${ex.message}"
        }
    }

    fun setBackupMsg(msg: String?) {
        _state.update { it.copy(backupMsg = msg) }
    }

    private fun loadMaint(): List<MaintItem> =
        prefs.getString("maint", "").orEmpty().lines().mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 3) return@mapNotNull null
            val t = p[1].toLongOrNull() ?: return@mapNotNull null
            val d = p[2].toIntOrNull() ?: return@mapNotNull null
            MaintItem(p[0], t, d, p.getOrNull(3)?.toIntOrNull() ?: -1, p.getOrNull(4)?.toIntOrNull() ?: 0)
        }

    private fun storeMaint(list: List<MaintItem>) {
        prefs.edit().putString("maint", list.joinToString("\n") { "${it.name.replace("|", " ")}|${it.lastDone}|${it.intervalDays}|${it.lastKm}|${it.intervalKm}" }).apply()
        _state.update { it.copy(maint = list) }
    }

    fun addMaint(name: String, days: Int, km: Int) {
        val n = name.trim()
        if (n.isEmpty() || (days <= 0 && km <= 0)) return
        storeMaint(_state.value.maint + MaintItem(n, System.currentTimeMillis(), days, odoNow() ?: -1, km))
    }

    // გარბენი: ხელით შეყვანილი საწყისი + მგზავრობებით დათვლილი მანძილი (ზუსტი ოდომეტრი OBD-ით არ იკითხება)
    private fun odoNow(): Int? {
        val base = prefs.getFloat("odo_base", -1f)
        if (base < 0f) return null
        val driven = prefs.getFloat("trk_km", 0f) - prefs.getFloat("odo_trk", 0f) + recorder.liveDistKm
        return (base + driven).roundToInt()
    }

    fun setOdometer(km: Int) {
        if (km < 0) return
        prefs.edit().putFloat("odo_base", km.toFloat()).putFloat("odo_trk", prefs.getFloat("trk_km", 0f)).apply()
        _state.update { it.copy(odoKm = odoNow()) }
    }

    fun doneMaint(index: Int) {
        val l = _state.value.maint.toMutableList()
        if (index !in l.indices) return
        l[index] = l[index].copy(lastDone = System.currentTimeMillis(), lastKm = odoNow() ?: -1)
        storeMaint(l)
    }

    fun removeMaint(index: Int) {
        val l = _state.value.maint.toMutableList()
        if (index !in l.indices) return
        l.removeAt(index)
        storeMaint(l)
    }

    private fun checkMaint() {
        val ctx = getApplication<Application>()
        val odo = odoNow()
        _state.value.maint.forEachIndexed { i, m ->
            val dl = m.daysLeft()
            val kl = m.kmLeft(odo)
            if ((dl != null && dl < 0) || (kl != null && kl < 0)) {
                val txt = listOfNotNull(
                    dl?.takeIf { it < 0 }?.let { "ვადა ${-it} დღით გადაცდა" },
                    kl?.takeIf { it < 0 }?.let { "გარბენი ${-it} კმ-ით გადაცდა" },
                ).joinToString(" · ")
                Notifier.notify(ctx, 20 + i, "დროა: ${m.name}", txt)
            }
        }
    }

    // ---------- ფონური მონიტორინგი ----------

    /** ღილაკიდან: ჩართვა/გამორთვა. ჩართვისას ფონური სერვისი იწყება და ადაპტერს თავისით ეძებს. */
    fun setWatching(on: Boolean) {
        prefs.edit().putBoolean("watching", on).apply()
        _state.update { it.copy(watching = on) }
        val ctx = getApplication<Application>()
        val i = Intent(ctx, MonitorService::class.java)
        if (on) {
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                _state.update { it.copy(error = "ფონური სერვისი ვერ ჩაირთო: ${e.message}") }
            }
            startWatchConnection()
        } else {
            ctx.stopService(i)
        }
    }

    /** სერვისიდან (სისტემამ თავიდან გაუშვა): სერვისს ხელახლა არ ვიწყებთ. */
    fun resumeWatching() {
        _state.update { it.copy(watching = true) }
        startWatchConnection()
    }

    private fun startWatchConnection() {
        if (job?.isActive != true) connect(false)
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
            var fails = 0
            do {
                val ok = connectOnce(demo)
                if (!demo && _state.value.watching) {
                    fails = if (ok) 0 else fails + 1
                    delay(minOf(30_000L + fails * 15_000L, 180_000L))
                }
            } while (!demo && _state.value.watching)
        }
    }

    /** ერთი კავშირის სესია. true = კავშირი დამყარდა (და მერე გაწყდა), false = ვერ დამყარდა. */
    @SuppressLint("MissingPermission")
    private suspend fun connectOnce(demo: Boolean): Boolean {
        var src: ObdSource? = null
        var wasConnected = false
        fuelWin.clear()
        lowSince = 0L
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
            wasConnected = true
            _state.update { it.copy(connection = Conn.Connected) }
            while (true) {
                (active as? Elm327Source)?.probeIntervalMs = if (_state.value.watching) 150_000L else 15_000L
                val snap = smoothFuel(ioMutex.withLock { active.read() })
                _state.update { it.copy(snapshot = snap) }
                if (!demo) {
                    save(snap)
                    checkAlerts(snap)
                    if (snap.ecuAlive) {
                        maybeLogHistory(snap)
                        recorder.update(snap)?.let { saveTrip(it) }
                    } else {
                        recorder.finish()?.let { saveTrip(it) }
                    }
                    _state.update {
                        it.copy(
                            lastSaved = loadSaved(), tripActive = recorder.active, odoKm = odoNow(),
                            curTripKm = recorder.liveDistKm, curTripL = recorder.liveFuelL,
                        )
                    }
                }
                pushLive(snap)
                delay(if (snap.ecuAlive) 1000L else if (_state.value.watching) 20_000L else 4_000L)
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
            _state.update { it.copy(tripActive = false, curTripKm = 0f, curTripL = 0f) }
            source = null
            src?.close()
        }
        return wasConnected
    }

    /** ბენზინის სენსორი ტარებისას "ცმუკავს" (ბაკში ბენზინი ტალღავს), ამიტომ ვიღებთ ბოლო 9 ჩვენების მედიანას. */
    private val fuelWin = ArrayDeque<Float>()
    private var lowSince = 0L

    private fun smoothFuel(s: CarSnapshot): CarSnapshot {
        val f = s.fuelPercent ?: return s
        fuelWin.addLast(f)
        while (fuelWin.size > 9) fuelWin.removeFirst()
        val sorted = fuelWin.sorted()
        return s.copy(fuelPercent = sorted[sorted.size / 2])
    }

    private fun pushLive(s: CarSnapshot) {
        if (!s.ecuAlive) return
        val m = s.mapKpa
        val b = s.baroKpa
        val st = s.stftPct
        val lt = s.ltftPct
        val p = LivePoint(
            boost = if (m != null && b != null) (m - b).toFloat() else null,
            trim = if (st != null && lt != null) st + lt else null,
            rpm = s.rpm,
            speed = s.speedKmh,
            lph = fuelLph(s),
        )
        _state.update { it.copy(live = (it.live + p).takeLast(120)) }
    }

    /** Mode 02 გაყინული კადრი (მხოლოდ წაკითხვა). */
    fun readFreezeFrame() {
        val src = source ?: return
        viewModelScope.launch {
            try {
                val r = ioMutex.withLock { src.freezeFrame() }
                _state.update { it.copy(freeze = r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(freeze = listOf("⚠ ${e.message ?: "ვერ წავიკითხე"}")) }
            }
        }
    }

    fun dismissFreeze() {
        _state.update { it.copy(freeze = null) }
    }

    /** მონაცემების ტექსტი გასაზიარებლად (ისტორია, მგზავრობები, კოდების ჟურნალი). */
    fun exportText(): String {
        val dir = getApplication<Application>().filesDir
        fun tail(name: String, n: Int): String =
            File(dir, name).takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n").orEmpty()
        return buildString {
            append("CarLink მონაცემები · ${_state.value.profile.name}\n\n")
            append("## მგზავრობები (დასაწყისი,დასასრული,მოძრ.წმ,მაქს.სიჩქარე,მაქს.ბრუნი,მაქს.MAP,ბარო,მაქს.დატვირთვა,საშ.კორექცია,მაქს.კორექცია,ღარიბი%,კმ,ლიტრი)\n")
            append(tail("trips.csv", 100)).append("\n\n")
            append("## კოდების ჟურნალი (დრო|ტექსტი)\n")
            append(tail("dtc_log.txt", 100)).append("\n\n")
            append("## ისტორია (დრო,ბენზინი%,ტემპ,ძაბვა)\n")
            append(tail("history.csv", 500))
        }.takeLast(100_000)
    }

    fun disconnect() {
        if (_state.value.watching) setWatching(false)
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

    private fun ninesText(resp: String, sid: String, hex: Boolean): String? {
        val m = isoMessages(resp.uppercase()).firstOrNull { it.startsWith(sid) } ?: return null
        val bytes = m.substring(sid.length).chunked(2).filter { it.length == 2 }.map { it.toInt(16) }.drop(1)
        if (bytes.isEmpty()) return null
        return if (hex) bytes.joinToString(" ") { "%02X".format(it) }
        else bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim().ifEmpty { null }
    }

    /** Mode 09: კალიბრაციის ID, CVN, ECU-ს სახელი (მხოლოდ წაკითხვა). */
    private suspend fun identityLines(src: ObdSource) {
        log("კალიბრაციის ID (0904): " + (ninesText(src.raw(null, "0904", 4000), "4904", false) ?: "არ გვპასუხობს"))
        log("CVN (0906): " + (ninesText(src.raw(null, "0906", 4000), "4906", true) ?: "არ გვპასუხობს"))
        log("ECU სახელი (090A): " + (ninesText(src.raw(null, "090A", 4000), "490A", false) ?: "არ გვპასუხობს"))
    }

    fun readIdentity() = explorer("მანქანის ID-ები (Mode 09)") { src -> identityLines(src) }

    /** GM Mode 22 ძებნა ძრავის კომპიუტერზე (7E0): ვეძებთ DID-ებს, რასაც მანქანა პასუხობს. */
    fun gmSearch() {
        if (movingNow()) {
            log("⛔ მანქანა მოძრაობს. ძებნა მხოლოდ გაჩერებულზე")
            return
        }
        explorer("GM ძებნა (Mode 22, 7E0)") { src ->
            val ranges = listOf(
                0x0000..0x00FF, 0x1100..0x11FF, 0x1200..0x12FF,
                0x2000..0x20FF, 0x2200..0x22FF, 0xF100..0xF1FF,
            )
            for (r in ranges) {
                log("დიაპაზონი %04X–%04X".format(r.first, r.last))
                sweepRange(src, "7E0", r.first, r.last)
                if (movingNow()) break
            }
        }
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
            identityLines(src)
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
        val p = _state.value.profile
        s.fuelPercent?.let {
            if (it < p.fuelAlertPct) {
                // ერთი ცუდი ჩვენება არ კმარა: დონე უნდა დარჩეს დაბალი მთელი წუთის განმავლობაში
                if (lowSince == 0L) lowSince = s.timeMillis
                if (!fuelAlert && s.timeMillis - lowSince >= 60_000L) {
                    fuelAlert = true
                    Notifier.notify(ctx, 1, "ბენზინი დაბალია", "დარჩენილია დაახლოებით ${it.roundToInt()}%")
                }
            } else {
                lowSince = 0L
                if (it > p.fuelAlertPct + 5) fuelAlert = false
            }
        }
        s.batteryV?.let {
            if (it < p.batteryAlertV && !batteryAlert) {
                batteryAlert = true
                Notifier.notify(ctx, 2, "აკუმულატორის ძაბვა დაბალია", "%.1f V".format(it))
            } else if (it > p.batteryAlertV + 0.4f) batteryAlert = false
        }
        s.coolantC?.let {
            if (it > p.coolantAlertC && !coolantAlert) {
                coolantAlert = true
                Notifier.notify(ctx, 3, "ძრავი გადახურდა", "გამაგრილებელი: $it °C")
            } else if (it < p.coolantAlertC - 5) coolantAlert = false
        }
        if (!s.ecuAlive) return
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
            prefs.edit().putFloat("trk_km", prefs.getFloat("trk_km", 0f) + t.distKm).apply()
            val all = tripStore.load(100)
            _state.update { it.copy(trips = all, odoKm = odoNow()) }
            checkMaint()
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
        s.batteryV?.let { e.putString("battery", it.toString()) }
        if (s.ecuAlive) {
            e.putString("fuel", s.fuelPercent?.toString())
            e.putString("coolant", s.coolantC?.toString())
            e.putString("dtcs", s.dtcs.joinToString(","))
        }
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
