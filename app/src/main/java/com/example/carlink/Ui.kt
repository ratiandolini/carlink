package com.example.carlink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal val Bg = Color(0xFF0E1116)
internal val CardBg = Color(0xFF171C24)
internal val Track = Color(0xFF262D38)
internal val TextMain = Color(0xFFEAF0F7)
internal val Muted = Color(0xFF8A96A6)
internal val Accent = Color(0xFF4FC3F7)
internal val Good = Color(0xFF66BB6A)
internal val Warn = Color(0xFFFFB74D)
internal val Bad = Color(0xFFEF5350)

@Composable
fun CarLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Bg,
            surface = CardBg,
            onSurface = TextMain,
            onBackground = TextMain,
        ),
        content = content,
    )
}

@Composable
fun CarLinkApp(vm: CarViewModel, withBluetooth: (() -> Unit) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabButton("დაფა", tab == 0) { tab = 0 }
            TabButton("მეტი", tab == 1) { tab = 1 }
            TabButton("ექსპლორერი", tab == 2) { tab = 2 }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> Dashboard(vm, withBluetooth)
                1 -> MoreScreen(vm, withBluetooth)
                else -> ExplorerScreen(vm)
            }
        }
    }
}

internal val BtnPad = PaddingValues(horizontal = 8.dp, vertical = 10.dp)

@Composable
internal fun BtnText(t: String) = Text(t, maxLines = 1, softWrap = false, fontSize = 13.sp)

@Composable
private fun RowScope.TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText(text) }
    else OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText(text) }
}

@Composable
private fun Dashboard(vm: CarViewModel, withBluetooth: (() -> Unit) -> Unit) {
    val ui by vm.state.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val connected = ui.connection == Conn.Connected
    val snap = ui.snapshot
    // ECU არ პასუხობს (ანთება გამორთულია) ან კავშირი არ არის: ვაჩვენებთ ბოლო შენახულ მნიშვნელობებს
    val useSaved = !connected || snap?.ecuAlive == false

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // სათაური
        Column {
            Text(ui.profile.name, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer4()
            StatusRow(ui)
        }

        // ღილაკები
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (connected || ui.connection == Conn.Connecting) {
                Button(
                    onClick = { vm.disconnect() },
                    modifier = Modifier.weight(1f),
                    contentPadding = BtnPad,
                    colors = ButtonDefaults.buttonColors(containerColor = Track, contentColor = TextMain),
                ) { BtnText("გათიშვა") }
            } else {
                Button(onClick = { withBluetooth { vm.connect(demo = false) } }, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("დაკავშირება") }
            }
            OutlinedButton(modifier = Modifier.weight(1f), contentPadding = BtnPad, onClick = {
                withBluetooth {
                    devices = vm.pairedDevices()
                    showPicker = true
                }
            }) { BtnText("ადაპტერი") }
            OutlinedButton(onClick = { vm.connect(demo = true) }, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("დემო") }
        }

        if (ui.error != null) {
            Card { Text(ui.error ?: "", color = Bad, fontSize = 14.sp) }
        }

        HealthCard(ui, useSaved)

        // ბენზინი
        Card {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FuelGauge(if (useSaved) ui.lastSaved?.fuelPercent else snap?.fuelPercent)
            }
            val fp = (if (useSaved) ui.lastSaved?.fuelPercent else snap?.fuelPercent)
            if (fp != null) {
                val liters = fp * ui.profile.tankL / 100f
                val avg = avgConsumption(ui.trips, ui.curTripKm, ui.curTripL)
                Spacer4()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MiniStat("ბაკში (დაახლ.)", "%.1f ლ".format(liters), Modifier.weight(1f))
                    MiniStat(
                        "დარჩენილი კმ (დაახლ.)",
                        if (avg != null) "~%.0f კმ".format(liters / avg * 100f) else "—",
                        Modifier.weight(1f),
                    )
                }
                // ახლანდელი ხარჯი: ბოლო ჩვენებების საშუალო, მხოლოდ ≥ 20 კმ/სთ სიჩქარეზე (დაბალ სიჩქარეზე ციფრი უაზროა)
                val recent = if (!useSaved) ui.live.takeLast(10).mapNotNull { p ->
                    val l = p.lph
                    val v = p.speed
                    if (l != null && v != null && v >= 20) l / v * 100f else null
                } else emptyList()
                val now = if (recent.size >= 3) recent.average().toFloat() else null
                val idle = if (!useSaved && snap != null && (snap.speedKmh ?: 0) < 5) fuelLph(snap) else null
                Spacer4()
                Text(
                    when {
                        idle != null -> "უქმ სვლაზე: %.1f ლ/სთ".format(idle) + (avg?.let { " · საშუალო %.1f ლ/100 კმ".format(it) } ?: "")
                        now != null -> "ახლანდელი ხარჯი: %.1f ლ/100 კმ".format(now)
                        avg != null -> "საშუალო ხარჯი ბოლო მგზავრობებით: %.1f ლ/100 კმ".format(avg)
                        else -> "კმ-ის შეფასებისთვის ჯერ რამდენიმე კმ გაიარე ჩართული აპით"
                    },
                    color = Muted, fontSize = 12.sp,
                )
            }
        }

        if (connected && snap?.ecuAlive == false) {
            Card {
                Text("ანთება გამორთულია", color = Warn, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer4()
                Text(
                    "ადაპტერი ჩანს, მაგრამ ძრავის კომპიუტერი გათიშულია, ამიტომ ახლა მხოლოდ აკუმულატორის ძაბვა იკითხება. ბენზინი და დანარჩენი ბოლოს ნანახია. ანთების ჩართვისას მონაცემები თავისით განახლდება.",
                    color = TextMain, fontSize = 13.sp,
                )
            }
        }

        // სენსორები
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(
                "აკუმულატორი",
                (snap?.batteryV ?: if (useSaved) ui.lastSaved?.batteryV else null)?.let { "%.1f V".format(it) } ?: "—",
                batteryColor(snap?.batteryV),
                Modifier.weight(1f),
            )
            StatCard(
                "ძრავის ტემპ.",
                (if (useSaved) ui.lastSaved?.coolantC else snap?.coolantC)?.let { "$it °C" } ?: "—",
                coolantColor(if (useSaved) null else snap?.coolantC),
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("ბრუნი", snap?.rpm?.let { "$it" } ?: "—", TextMain, Modifier.weight(1f))
            StatCard("სიჩქარე", snap?.speedKmh?.let { "$it კმ/სთ" } ?: "—", TextMain, Modifier.weight(1f))
        }

        // ძრავის დიაგნოსტიკა
        Card {
            val m = snap?.mapKpa
            val b = snap?.baroKpa
            val boost = if (m != null && b != null) m - b else null
            val st = snap?.stftPct
            val lt = snap?.ltftPct
            val trims = if (st != null && lt != null) "%+.1f%% / %+.1f%%".format(st, lt) else "—"
            Text("ძრავის დიაგნოსტიკა", color = Muted, fontSize = 13.sp)
            Spacer4()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MiniStat("ბუსტი (MAP − ბარო)", boost?.let { "%+d kPa".format(it) } ?: "—", Modifier.weight(1f))
                MiniStat("MAP", m?.let { "$it kPa" } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                MiniStat("ბარომეტრი", b?.let { "$it kPa" } ?: "—", Modifier.weight(1f))
                MiniStat("ჰაერის ტემპ.", snap?.iatC?.let { "$it °C" } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                MiniStat("MAF", snap?.mafGs?.let { "%.1f გ/წმ".format(it) } ?: "—", Modifier.weight(1f))
                MiniStat("დატვირთვა", snap?.loadPct?.let { "%.0f%%".format(it) } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                MiniStat("დროსელი", snap?.throttlePct?.let { "%.0f%%".format(it) } ?: "—", Modifier.weight(1f))
                MiniStat("საწვავის კორექცია (მოკლე / გრძელი)", trims, Modifier.weight(1f))
            }
            Spacer4()
            Text(
                "ინფორმაციისთვის. ანთება ჩართული და ძრავი გამორთული: MAP ≈ ბარომეტრს. დიაგნოზს მექანიკოსი სვამს.",
                color = Muted, fontSize = 12.sp,
            )
        }

        // ისტორია
        Card {
            Text("ბენზინის ისტორია", color = Muted, fontSize = 13.sp)
            Spacer4()
            HistoryChart(ui.history)
        }

        // შეცდომები
        val liveOk = connected && snap != null && snap.ecuAlive
        val dtcs = if (liveOk) snap!!.dtcs else if (useSaved) ui.lastSaved?.dtcs.orEmpty() else emptyList()
        Card {
            Text("შეცდომის კოდები", color = Muted, fontSize = 13.sp)
            Spacer4()
            if (dtcs.isEmpty()) {
                Text(
                    if (snap == null && ui.lastSaved == null) "მონაცემი ჯერ არ არის" else "პრობლემა არ ჩანს ✓",
                    color = if (snap == null && ui.lastSaved == null) Muted else Good,
                    fontSize = 16.sp,
                )
            } else {
                dtcs.forEach { code ->
                    Text(code, color = Warn, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(dtcText(code), color = Muted, fontSize = 13.sp)
                    Spacer4()
                }
            }
            val pending = if (liveOk) snap!!.pendingDtcs.filter { it !in dtcs } else emptyList()
            if (pending.isNotEmpty()) {
                Spacer4()
                Text("მოლოდინში (ჯერ არ დადასტურებულა)", color = Muted, fontSize = 13.sp)
                Spacer4()
                pending.forEach { code ->
                    Text(code, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(dtcText(code), color = Muted, fontSize = 13.sp)
                    Spacer4()
                }
            }
            val perm = if (liveOk) snap!!.permanentDtcs.filter { it !in dtcs && it !in pending } else emptyList()
            if (perm.isNotEmpty()) {
                Spacer4()
                Text("მუდმივი (წაიშლება მხოლოდ გამოსწორების მერე)", color = Muted, fontSize = 13.sp)
                Spacer4()
                perm.forEach { code ->
                    Text(code, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(dtcText(code), color = Muted, fontSize = 13.sp)
                    Spacer4()
                }
            }
            if (liveOk) {
                Spacer4()
                OutlinedButton(
                    onClick = { vm.readFreezeFrame() },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = BtnPad,
                ) { BtnText("გაყინული კადრი (პირობები, როცა შეცდომა გაჩნდა)") }
            }
            ui.freeze?.let { lines ->
                Spacer4()
                lines.forEach { Text(it, color = TextMain, fontSize = 13.sp) }
                TextButton(onClick = { vm.dismissFreeze() }) { Text("დახურვა") }
            }
            if (connected && (dtcs.isNotEmpty() || (liveOk && snap!!.pendingDtcs.isNotEmpty()))) {
                Spacer4()
                OutlinedButton(
                    onClick = { showClear = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = BtnPad,
                ) { BtnText("კოდების წაშლა…") }
            }
            ui.clearResult?.let { msg ->
                Spacer4()
                Text(msg, color = if (msg.startsWith("✓")) Good else Warn, fontSize = 13.sp)
                TextButton(onClick = { vm.dismissClearResult() }) { Text("კარგი") }
            }
        }

        // კოდების ჟურნალი
        if (ui.dtcLog.isNotEmpty()) {
            Card {
                Text("კოდების ჟურნალი", color = Muted, fontSize = 13.sp)
                Spacer4()
                val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                ui.dtcLog.takeLast(8).reversed().forEach { (t, line) ->
                    Text("${fmt.format(Date(t))} · $line", color = TextMain, fontSize = 13.sp)
                    Spacer4()
                }
            }
        }

        // მგზავრობები
        Card {
            Text("გზაზე ჩაწერა", color = Muted, fontSize = 13.sp)
            Spacer4()
            if (ui.tripActive) {
                Text("● იწერება მგზავრობა…", color = Good, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer4()
            }
            val last = ui.trips.lastOrNull()
            if (last == null) {
                Text(
                    "დააკავშირე ადაპტერი და იარე. სიჩქარის დაწყებისას ჩაწერა თავისით დაიწყება, გაჩერებისას შეჯამებას აქ ნახავ. ეკრანს ტარებისას არ უყურო.",
                    color = Muted, fontSize = 13.sp,
                )
            } else {
                val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                Text("ბოლო მგზავრობა · ${fmt.format(Date(last.start))} · ${last.moveSec / 60} წთ", color = TextMain, fontSize = 14.sp)
                Spacer4()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MiniStat("მაქს. ბუსტი", if (last.maxMap > 0) "%+d kPa".format(last.maxBoost) else "—", Modifier.weight(1f))
                    MiniStat("მაქს. MAP", if (last.maxMap > 0) "${last.maxMap} kPa" else "—", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    MiniStat("კორექცია (საშ.)", "%+.1f%%".format(last.avgTrim), Modifier.weight(1f))
                    MiniStat("მაქს. დატვირთვა", "${last.maxLoad}%", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    MiniStat("მაქს. სიჩქარე", "${last.maxSpeed} კმ/სთ", Modifier.weight(1f))
                    MiniStat("მაქს. ბრუნი", "${last.maxRpm}", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    MiniStat("მანძილი", "%.1f კმ".format(last.distKm), Modifier.weight(1f))
                    MiniStat("ხარჯი", last.per100?.let { "%.1f ლ/100".format(it) } ?: "—", Modifier.weight(1f))
                }
                Spacer4()
                last.hints().forEach {
                    Text(it, color = TextMain, fontSize = 13.sp)
                    Spacer4()
                }
                Text("ინფორმაციისთვისაა და დიაგნოზი არ არის. მექანიკოსს აჩვენე.", color = Muted, fontSize = 12.sp)
                if (ui.trips.size > 1) {
                    Spacer4()
                    Text("წინა მგზავრობები", color = Muted, fontSize = 12.sp)
                    ui.trips.dropLast(1).takeLast(5).reversed().forEach { t ->
                        Text(
                            "${fmt.format(Date(t.start))} · ბუსტი ${if (t.maxMap > 0) "%+d".format(t.maxBoost) else "—"} kPa · კორექცია %+.1f%%".format(t.avgTrim),
                            color = TextMain, fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // ბორბლები (ჯერ არ არის რეალიზებული)
        Card {
            Text("ბორბლების წნევა", color = Muted, fontSize = 13.sp)
            Spacer4()
            val tl = ui.tires
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Tire("წინა მარცხ. (ბარი)", tl?.fl, Modifier.weight(1f))
                Tire("წინა მარჯ. (ბარი)", tl?.fr, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Tire("უკან მარცხ. (ბარი)", tl?.rl, Modifier.weight(1f))
                Tire("უკან მარჯ. (ბარი)", tl?.rr, Modifier.weight(1f))
            }
            Spacer4()
            Text(
                if (tl != null) "ხელით შეყვანილი: " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(tl.time)) +
                    ". ავტომატურად ამ ადაპტერით წნევა არ იკითხება (სჭირდება ახალი სენსორები). შეყვანა: «მეტი» ჩანართი."
                else "ავტომატურად ამ ადაპტერით წნევა არ იკითხება (სჭირდება ახალი სენსორები). ხელით შეყვანა: «მეტი» ჩანართი.",
                color = Muted, fontSize = 12.sp,
            )
        }

        // ბოლოს ნანახი
        val saved = ui.lastSaved
        if (!connected && saved != null) {
            val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
            Card {
                Text("ბოლოს ნანახი მდგომარეობა", color = Muted, fontSize = 13.sp)
                Spacer4()
                Text(fmt.format(Date(saved.timeMillis)), color = TextMain, fontSize = 16.sp)
            }
        }
    }

    if (showClear) {
        val codes = (snap?.dtcs.orEmpty() + snap?.pendingDtcs.orEmpty()).distinct()
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("კოდების წაშლა") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("წაიშლება ძრავის (OBD-II) კოდები და Check Engine ნათურა:\n${codes.joinToString(", ").ifEmpty { "—" }}")
                    Text("• კოდები ჯერ ინახება «კოდების ჟურნალში».", fontSize = 13.sp)
                    Text("• თუ პრობლემა რჩება, კოდები დაბრუნდება.", fontSize = 13.sp)
                    Text("• მზადყოფნის მონიტორები გადაიტვირთება; ტექდათვალიერებამდე გარკვეული გარბენი დასჭირდება.", fontSize = 13.sp)
                    Text("• საჰაერო ბალიშის (SRS) ნათურა ამით არ იწმინდება.", fontSize = 13.sp)
                    Text("მხოლოდ გაჩერებულ მანქანაზე, ძრავი ჩაქრობილი, ანთება ჩართული.", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    vm.clearDtcs()
                }) { Text("წაშლა") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("გაუქმება") } },
        )
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("დახურვა") } },
            title = { Text("აირჩიე ადაპტერი") },
            text = {
                Column {
                    if (devices.isEmpty()) {
                        Text("დაწყვილებული მოწყობილობა არ არის. ჯერ ტელეფონის Bluetooth-ის პარამეტრებში დააწყვილე ELM327 (კოდი ხშირად 1234 ან 0000).")
                    }
                    devices.forEach { (name, addr) ->
                        TextButton(onClick = {
                            vm.selectDevice(name, addr)
                            showPicker = false
                        }) { Text("$name\n$addr", fontSize = 14.sp) }
                    }
                }
            },
        )
    }
}

@Composable
internal fun Spacer4() {
    Box(Modifier.size(4.dp))
}

/** ერთი შეხედვით მდგომარეობა: მწვანე = კარგი, ყვითელი = ყურადღება, წითელი = საჭიროა მოქმედება. */
@Composable
private fun HealthCard(ui: UiState, useSaved: Boolean) {
    val snap = ui.snapshot
    val live = !useSaved && snap != null
    val p = ui.profile
    val saved = ui.lastSaved
    val rows = mutableListOf<Triple<String, String, Color>>()

    val dtcs = if (live) snap!!.dtcs else saved?.dtcs.orEmpty()
    rows += if (!live && saved == null) Triple("შეცდომის კოდები", "მონაცემი არ არის", Muted)
    else if (dtcs.isEmpty()) Triple("შეცდომის კოდები", "არ ჩანს", Good)
    else Triple("შეცდომის კოდები", "${dtcs.size} კოდი", Warn)

    val mil = if (live) snap!!.extra?.milOn else null
    if (mil != null) rows += Triple("Check Engine", if (mil) "ანთია" else "ჩამქრალია", if (mil) Warn else Good)

    val v = if (live) snap!!.batteryV else saved?.batteryV
    if (v != null) {
        val running = live && (snap!!.rpm ?: 0) > 400
        val col = if (running) (if (v < 13.0f) Warn else Good) else if (v < p.batteryAlertV) Bad else if (v < 12.2f) Warn else Good
        rows += Triple("აკუმულატორი", "%.1f V".format(v), col)
    }

    val c = if (live) snap!!.coolantC else null
    if (c != null) {
        rows += Triple(
            "ძრავის ტემპერატურა", "$c °C",
            if (c > p.coolantAlertC) Bad else if (c > 100) Warn else if (c < 60) Muted else Good,
        )
    }

    val f = if (live) snap!!.fuelPercent else saved?.fuelPercent
    if (f != null) {
        rows += Triple(
            "ბენზინი", "%.0f%%".format(f),
            if (f < p.fuelAlertPct) Bad else if (f < p.fuelAlertPct + 10) Warn else Good,
        )
    }

    val over = ui.maint.count { m -> (m.daysLeft()?.let { it < 0 } ?: false) || (m.kmLeft(ui.odoKm)?.let { it < 0 } ?: false) }
    val soon = ui.maint.count { m -> (m.daysLeft()?.let { it in 0..13 } ?: false) || (m.kmLeft(ui.odoKm)?.let { it in 0..499 } ?: false) }
    rows += if (ui.maint.isEmpty()) Triple("შეხსენებები", "არ არის დამატებული", Muted)
    else if (over > 0) Triple("შეხსენებები", "$over ვადაგადაცილებული", Bad)
    else if (soon > 0) Triple("შეხსენებები", "$soon მალე", Warn)
    else Triple("შეხსენებები", "ყველაფერი ვადაშია", Good)

    Card {
        Text("მდგომარეობა", color = Muted, fontSize = 13.sp)
        Spacer4()
        rows.forEach { (label, text, color) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Text(label, color = TextMain, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatusRow(ui: UiState) {
    val (text, color) = when (ui.connection) {
        Conn.Connected -> (if (ui.demo) "დემო რეჟიმი" else "დაკავშირებულია") to Good
        Conn.Connecting -> "ვუკავშირდები…" to Warn
        Conn.Failed -> "კავშირი ვერ მოხერხდა" to Bad
        Conn.Idle -> "არ არის დაკავშირებული" to Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text("  $text", color = color, fontSize = 14.sp)
        if (ui.deviceName != null) Text("  ·  ${ui.deviceName}", color = Muted, fontSize = 14.sp)
    }
}

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .padding(16.dp),
    ) {
        Text(label, color = Muted, fontSize = 13.sp)
        Spacer4()
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Track)
            .padding(12.dp),
    ) {
        Text(label, color = Muted, fontSize = 12.sp)
        Spacer4()
        Text(value, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Tire(label: String, value: Float?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Track)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value?.let { "%.1f".format(it) } ?: "—",
            color = if (value != null) TextMain else Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        )
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun FuelGauge(percent: Float?) {
    val p = (percent ?: 0f).coerceIn(0f, 100f)
    val color = when {
        percent == null -> Muted
        p < 15f -> Bad
        p < 30f -> Warn
        else -> Accent
    }
    Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Track, startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color, startAngle = 135f, sweepAngle = 270f * p / 100f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (percent == null) "—" else "${p.roundToInt()}%",
                color = TextMain, fontSize = 36.sp, fontWeight = FontWeight.Bold,
            )
            Text("ბენზინი", color = Muted, fontSize = 13.sp)
        }
    }
}

private fun batteryColor(v: Float?): Color = when {
    v == null -> TextMain
    v < 11.8f -> Bad
    v < 12.3f -> Warn
    else -> Good
}

private fun coolantColor(c: Int?): Color = when {
    c == null -> TextMain
    c > 110 -> Bad
    c > 100 -> Warn
    else -> TextMain
}
