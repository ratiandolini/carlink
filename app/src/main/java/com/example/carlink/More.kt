package com.example.carlink

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MoreScreen(vm: CarViewModel, withBluetooth: (() -> Unit) -> Unit) {
    val ui by vm.state.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("მეტი", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        LiveChartCard(ui)
        ExtraCard(ui)
        TestsCard(ui, vm)
        TripsCard(ui)
        WatchCard(ui, vm, withBluetooth)
        ProfileCard(ui, vm)
        TiresCard(ui, vm)
        MaintCard(ui, vm)
        ExportCard(vm)
        BackupCard(ui, vm)
    }
}

// ---------- ცოცხალი გრაფიკი ----------

@Composable
private fun LineChart(values: List<Float?>, color: Color) {
    val nums = values.filterNotNull()
    if (nums.size < 2) {
        Text("გრაფიკი გამოჩნდება, როცა მონაცემი დაგროვდება (მანქანასთან დაკავშირებისას).", color = Muted, fontSize = 13.sp)
        return
    }
    val lo = minOf(nums.min(), 0f)
    val hi = maxOf(nums.max(), lo + 1f)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        val n = values.size
        // ნულოვანი ხაზი
        val zeroY = h * (1f - (0f - lo) / (hi - lo))
        drawLine(Track, Offset(0f, zeroY), Offset(w, zeroY), strokeWidth = 1.dp.toPx())
        var prev: Offset? = null
        for (i in 0 until n) {
            val v = values[i]
            if (v == null) {
                prev = null
                continue
            }
            val p = Offset(w * i / (n - 1).coerceAtLeast(1), h * (1f - (v - lo) / (hi - lo)))
            prev?.let { drawLine(color, it, p, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round) }
            prev = p
        }
    }
    Text(
        "მინ. %.1f · მაქს. %.1f · ახლა %.1f".format(nums.min(), nums.max(), nums.last()),
        color = Muted, fontSize = 12.sp,
    )
}

@Composable
private fun LiveChartCard(ui: UiState) {
    var mode by remember { mutableStateOf(0) }
    val names = listOf("ბუსტი kPa", "კორექცია %", "ბრუნი", "სიჩქარე", "ხარჯი ლ/სთ")
    Card {
        Text("ცოცხალი გრაფიკი", color = Muted, fontSize = 13.sp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            names.forEachIndexed { i, n ->
                if (i == mode) Button(onClick = { mode = i }, contentPadding = BtnPad) { BtnText(n) }
                else OutlinedButton(onClick = { mode = i }, contentPadding = BtnPad) { BtnText(n) }
            }
        }
        val vals: List<Float?> = when (mode) {
            0 -> ui.live.map { it.boost }
            1 -> ui.live.map { it.trim }
            2 -> ui.live.map { it.rpm?.toFloat() }
            3 -> ui.live.map { it.speed?.toFloat() }
            else -> ui.live.map { it.lph }
        }
        LineChart(vals, Accent)
        if (mode == 1) {
            Text("კორექცია = მოკლე + გრძელი. +15%-ზე მაღალი = ძალიან ღარიბი ნარევი.", color = Muted, fontSize = 12.sp)
        }
    }
}

// ---------- დამატებითი მონაცემები ----------

@Composable
private fun ExtraCard(ui: UiState) {
    val snap = ui.snapshot
    val x = if (ui.connection == Conn.Connected && snap != null && snap.ecuAlive) snap.extra else null
    Card {
        Text("დამატებითი მონაცემები", color = Muted, fontSize = 13.sp)
        Spacer4()
        if (x == null) {
            Text("ჩანს, როცა მანქანასთან ხარ დაკავშირებული და ანთება ჩართულია. განახლდება დაახლოებით წუთში ერთხელ.", color = Muted, fontSize = 13.sp)
            return@Card
        }
        x.milOn?.let {
            Text(
                if (it) "Check Engine ნათურა: ანთია" else "Check Engine ნათურა: ჩამქრალია",
                color = if (it) Warn else Good, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer4()
        }
        val items = buildList {
            x.timingAdv?.let { add("ანთების წინსწრება" to "%.1f°".format(it)) }
            x.fuelPressKpa?.let { add("საწვავის წნევა" to "$it kPa") }
            x.o2B1S1V?.let { add("O2 წინა (B1S1)" to "%.2f V".format(it)) }
            x.o2B1S2V?.let { add("O2 უკანა (B1S2)" to "%.2f V".format(it)) }
            x.lambda?.let { add("ლამბდა (მოთხოვნილი)" to "%.2f".format(it)) }
            x.catTempC?.let { add("კატალიზატორის ტემპ." to "%.0f °C".format(it)) }
            x.absLoadPct?.let { add("აბსოლუტური დატვირთვა" to "%.0f%%".format(it)) }
            x.ambientC?.let { add("გარე ჰაერი" to "$it °C") }
            x.oilC?.let { add("ზეთის ტემპ." to "$it °C") }
            x.ecuVoltage?.let { add("კომპიუტერის ძაბვა" to "%.1f V".format(it)) }
            x.runTimeSec?.let { add("ძრავის მუშაობა" to "${it / 60} წთ") }
            x.warmups?.let { add("გახურებები წაშლიდან" to "$it") }
            x.distClearedKm?.let { add("კმ კოდების წაშლიდან" to "$it") }
            x.distMilKm?.let { add("კმ Check Engine-ის ანთებიდან" to "$it") }
            x.fuelRateLph?.let { add("ხარჯი (PID)" to "%.1f ლ/სთ".format(it)) }
            snap?.let { fuelLph(it) }?.let { add("ხარჯი ახლა (გამოთვლილი)" to "%.1f ლ/სთ".format(it)) }
        }
        if (items.isEmpty()) {
            Text("ეს მანქანა დამატებით მონაცემს ჯერ არ აჩვენებს.", color = Muted, fontSize = 13.sp)
        }
        items.chunked(2).forEachIndexed { idx, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = if (idx == 0) 0.dp else 10.dp),
            ) {
                row.forEach { (l, v) -> MiniStat(l, v, Modifier.weight(1f)) }
                if (row.size == 1) Spacer4WeightFill(Modifier.weight(1f))
            }
        }
        if (x.readiness.isNotEmpty()) {
            Spacer4()
            Text("გამონაბოლქვის ტესტები (ტექდათვალიერებისთვის)", color = Muted, fontSize = 13.sp)
            Spacer4()
            x.readiness.forEach { (name, ready) ->
                Text(
                    (if (ready) "✓ " else "○ ") + name + if (ready) "" else " (ჯერ არ დასრულებულა)",
                    color = if (ready) Good else Warn, fontSize = 13.sp,
                )
            }
            Spacer4()
            Text(
                "კოდების წაშლის შემდეგ ტესტებს რამდენიმე სრული ტარება სჭირდება, სანამ ✓ გახდებიან.",
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Spacer4WeightFill(modifier: Modifier) {
    Column(modifier) {}
}

// ---------- ფონური მონიტორინგი ----------

@Composable
private fun WatchCard(ui: UiState, vm: CarViewModel, withBluetooth: (() -> Unit) -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ფონური მონიტორინგი", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (ui.watching) when (ui.connection) {
                        Conn.Connected -> "ჩართულია · ადაპტერთან დაკავშირებულია"
                        Conn.Connecting -> "ჩართულია · ვუკავშირდები…"
                        else -> "ჩართულია · ველოდები ადაპტერს"
                    } else "გამორთულია",
                    color = if (ui.watching) Good else Muted, fontSize = 13.sp,
                )
            }
            Switch(
                checked = ui.watching,
                onCheckedChange = { on ->
                    if (on) withBluetooth { vm.setWatching(true) } else vm.setWatching(false)
                },
            )
        }
        Spacer4()
        Text(
            "ტელეფონი ეკრანის გარეშეც ეძებს ადაპტერს და გაფრთხილებას გიგზავნის (დაბალი ბენზინი, აკუმულატორი, ახალი კოდი, გადახურება). მუშაობს მხოლოდ Bluetooth-ის რადიუსში (დაახლ. 10 მ), ინტერნეტი არ სჭირდება. გაჩერებულ მანქანაზე ანთების გარეშე ჩანს მხოლოდ აკუმულატორის ძაბვა.",
            color = Muted, fontSize = 12.sp,
        )
        Spacer4()
        Text(
            "გაითვალისწინე: ადაპტერი მუდმივად ჩართული შეიძლება ცოტა დენს ხარჯავდეს. თუ მანქანას დიდხანს არ იყენებ, ამოიღე ან გამორთე ეს ფუნქცია. Samsung-ზე შეიძლება დაგჭირდეს აპისთვის «Battery → Unrestricted».",
            color = Muted, fontSize = 12.sp,
        )
    }
}

// ---------- პროფილი ----------

@Composable
private fun ProfileCard(ui: UiState, vm: CarViewModel) {
    val p = ui.profile
    var name by remember(p) { mutableStateOf(p.name) }
    var tank by remember(p) { mutableStateOf(p.tankL.toString()) }
    var fuel by remember(p) { mutableStateOf(p.fuelAlertPct.toString()) }
    var batt by remember(p) { mutableStateOf(p.batteryAlertV.toString()) }
    var cool by remember(p) { mutableStateOf(p.coolantAlertC.toString()) }
    var saved by remember { mutableStateOf(false) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Card {
        Text("მანქანის პროფილი", color = Muted, fontSize = 13.sp)
        Spacer4()
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40); saved = false },
            label = { Text("მანქანის სახელი") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = tank, onValueChange = { tank = it.take(5); saved = false },
                label = { Text("ბაკი (ლ)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = fuel, onValueChange = { fuel = it.take(3); saved = false },
                label = { Text("ბენზინი <%") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = batt, onValueChange = { batt = it.take(5); saved = false },
                label = { Text("ძაბვა <V") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = cool, onValueChange = { cool = it.take(3); saved = false },
                label = { Text("ტემპ. >°C") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = {
                val d = VehicleProfile()
                vm.saveProfile(
                    VehicleProfile(
                        name = name.trim().ifEmpty { d.name },
                        tankL = tank.replace(',', '.').toFloatOrNull()?.coerceIn(5f, 200f) ?: d.tankL,
                        fuelAlertPct = fuel.toIntOrNull()?.coerceIn(3, 50) ?: d.fuelAlertPct,
                        batteryAlertV = batt.replace(',', '.').toFloatOrNull()?.coerceIn(10f, 13f) ?: d.batteryAlertV,
                        coolantAlertC = cool.toIntOrNull()?.coerceIn(90, 130) ?: d.coolantAlertC,
                    )
                )
                saved = true
            },
            modifier = Modifier.padding(top = 8.dp), contentPadding = BtnPad,
        ) { BtnText("შენახვა") }
        if (saved) Text("✓ შენახულია", color = Good, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer4()
        Text(
            "ბაკის მოცულობა გადაამოწმე შენი მანქანის მონაცემით. ის გამოიყენება დარჩენილი ლიტრებისა და კილომეტრების შესაფასებლად. სხვა მანქანაზე გადასვლისას აქ შეცვალე სახელი და ზღვრები.",
            color = Muted, fontSize = 12.sp,
        )
    }
}

// ---------- მომსახურების შეხსენებები ----------

@Composable
private fun MaintCard(ui: UiState, vm: CarViewModel) {
    var name by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    val presets = listOf(
        "ძრავის ზეთი" to 180, "საჰაერო ფილტრი" to 365, "ანტიფრიზი" to 730,
        "მუხრუჭის სითხე" to 730, "ბორბლების შემოწმება" to 30,
    )
    Card {
        Text("მომსახურების შეხსენებები", color = Muted, fontSize = 13.sp)
        Spacer4()
        if (ui.maint.isEmpty()) {
            Text("ჯერ არაფერია დამატებული. აირჩიე ქვემოდან ან ჩაწერე საკუთარი.", color = Muted, fontSize = 13.sp)
        }
        ui.maint.forEachIndexed { i, m ->
            val left = m.daysLeft()
            Text(m.name, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (left < 0) "ვადა ${-left} დღით გადაცდა" else "დარჩა $left დღე (ყოველ ${m.intervalDays} დღეში)",
                color = if (left < 0) Bad else if (left < 14) Warn else Muted, fontSize = 13.sp,
            )
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.doneMaint(i) }, contentPadding = BtnPad) { BtnText("შესრულდა დღეს") }
                OutlinedButton(onClick = { vm.removeMaint(i) }, contentPadding = BtnPad) { BtnText("წაშლა") }
            }
        }
        Spacer4()
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { (n, d) ->
                OutlinedButton(onClick = { vm.addMaint(n, d) }, contentPadding = BtnPad) { BtnText("+ $n") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(30) },
                label = { Text("დასახელება") }, singleLine = true, modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = days, onValueChange = { days = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("დღე") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = {
                vm.addMaint(name, days.toIntOrNull() ?: 0)
                name = ""; days = ""
            },
            modifier = Modifier.padding(top = 8.dp), contentPadding = BtnPad,
        ) { BtnText("დამატება") }
        Spacer4()
        Text(
            "დროით ითვლება (დღეებით), რადგან მანქანის კილომეტრაჟს ეს კავშირი არ გვაწვდის. ვადის გადაცდენისას აპის გახსნისას შეტყობინება მოვა.",
            color = Muted, fontSize = 12.sp,
        )
    }
}

// ---------- ექსპორტი ----------

@Composable
private fun ExportCard(vm: CarViewModel) {
    val ctx = LocalContext.current
    Card {
        Text("მონაცემების გაზიარება", color = Muted, fontSize = 13.sp)
        Spacer4()
        Text(
            "მგზავრობების, კოდების ჟურნალისა და ისტორიის ტექსტი. გაუგზავნე მექანიკოსს ან შეინახე.",
            color = TextMain, fontSize = 13.sp,
        )
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, vm.exportText())
                }
                ctx.startActivity(Intent.createChooser(send, "გაგზავნა").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            modifier = Modifier.padding(top = 8.dp), contentPadding = BtnPad,
        ) { BtnText("გაზიარება") }
    }
}

// ---------- Mode 06 თვითტესტები ----------

@Composable
private fun TestsCard(ui: UiState, vm: CarViewModel) {
    val can = ui.connection == Conn.Connected && ui.snapshot?.ecuAlive == true && !ui.testsBusy
    Card {
        Text("ძრავის თვითტესტები (Mode 06)", color = Muted, fontSize = 13.sp)
        Spacer4()
        Text(
            "მანქანის საკუთარი ტესტების შედეგები: ცილინდრების გამოტოვებები, კატალიზატორი, ჟანგბადის სენსორები, EVAP. ✓ = ნორმის ფარგლებშია, ✗ = გასცდა, ? = ზღვარი არ არის. რიცხვები ნედლია (ერთეულის გარეშე) და დიაგნოზი არ არის, ის მექანიკოსს აჩვენე. მხოლოდ გაჩერებულ მანქანაზე.",
            color = TextMain, fontSize = 13.sp,
        )
        Button(
            onClick = { vm.readTests() }, enabled = can,
            modifier = Modifier.padding(top = 8.dp), contentPadding = BtnPad,
        ) { BtnText(if (ui.testsBusy) "ვკითხულობ…" else "წაკითხვა") }
        ui.tests?.let { lines ->
            Spacer4()
            lines.forEach { l ->
                Text(
                    l,
                    color = when {
                        l.endsWith("✓") -> Good
                        l.endsWith("✗") -> Bad
                        else -> TextMain
                    },
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = { vm.dismissTests() }) { Text("დახურვა") }
        }
    }
}

// ---------- მგზავრობების ისტორია ----------

@Composable
private fun TripsCard(ui: UiState) {
    var all by remember { mutableStateOf(false) }
    val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
    Card {
        Text("მგზავრობების ისტორია", color = Muted, fontSize = 13.sp)
        Spacer4()
        if (ui.trips.isEmpty()) {
            Text("ჯერ ჩანაწერი არ არის.", color = Muted, fontSize = 13.sp)
        }
        ui.trips.reversed().take(if (all) 100 else 5).forEach { t ->
            Text(
                "${fmt.format(Date(t.start))} · ${t.moveSec / 60} წთ · %.1f კმ".format(t.distKm),
                color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "ბუსტი " + (if (t.maxMap > 0) "+${t.maxBoost} kPa" else "—") +
                    " · კორექცია %+.1f%%".format(t.avgTrim) +
                    " · ხარჯი " + (t.per100?.let { "%.1f ლ/100".format(it) } ?: "—"),
                color = Muted, fontSize = 12.sp,
            )
            Spacer4()
        }
        if (ui.trips.size > 5) {
            TextButton(onClick = { all = !all }) { Text(if (all) "ნაკლების ჩვენება" else "ყველას ჩვენება (${ui.trips.size})") }
        }
    }
}

// ---------- ბორბლების წნევა ხელით ----------

@Composable
private fun TiresCard(ui: UiState, vm: CarViewModel) {
    val t = ui.tires
    var fl by remember(t) { mutableStateOf(t?.fl?.toString().orEmpty()) }
    var fr by remember(t) { mutableStateOf(t?.fr?.toString().orEmpty()) }
    var rl by remember(t) { mutableStateOf(t?.rl?.toString().orEmpty()) }
    var rr by remember(t) { mutableStateOf(t?.rr?.toString().orEmpty()) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    fun p(s: String): Float? = s.replace(',', '.').toFloatOrNull()?.takeIf { it in 0.5f..6.5f }
    Card {
        Text("ბორბლების წნევა (ხელით)", color = Muted, fontSize = 13.sp)
        Spacer4()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = fl, onValueChange = { fl = it.take(4) }, label = { Text("წინა მარცხ.") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
            OutlinedTextField(value = fr, onValueChange = { fr = it.take(4) }, label = { Text("წინა მარჯ.") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = rl, onValueChange = { rl = it.take(4) }, label = { Text("უკან მარცხ.") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
            OutlinedTextField(value = rr, onValueChange = { rr = it.take(4) }, label = { Text("უკან მარჯ.") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
        }
        Button(
            onClick = { vm.saveTires(p(fl), p(fr), p(rl), p(rr)) },
            modifier = Modifier.padding(top = 8.dp), contentPadding = BtnPad,
        ) { BtnText("შენახვა") }
        Spacer4()
        Text(
            "ბარი (მაგ. 2.3). რეკომენდებული წნევა წერია ეტიკეტზე მძღოლის კარის ჩარჩოში. წნევას ცივ ბორბლებზე ზომავენ. შეხსენებისთვის დაამატე «ბორბლების შემოწმება» ზემოთ შეხსენებებში.",
            color = Muted, fontSize = 12.sp,
        )
    }
}

// ---------- ბექაპი ----------

@Composable
private fun BackupCard(ui: UiState, vm: CarViewModel) {
    val ctx = LocalContext.current
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.backupText().toByteArray(Charsets.UTF_8)) }
                vm.setBackupMsg("✓ ბექაპი შენახულია")
            } catch (e: Exception) {
                vm.setBackupMsg("⚠ შენახვა ვერ მოხერხდა: ${e.message}")
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                vm.setBackupMsg(vm.restoreBackup(text))
            } catch (e: Exception) {
                vm.setBackupMsg("⚠ აღდგენა ვერ მოხერხდა: ${e.message}")
            }
        }
    }
    Card {
        Text("ბექაპი და აღდგენა", color = Muted, fontSize = 13.sp)
        Spacer4()
        Text(
            "შეინახე მგზავრობები, კოდების ჟურნალი, ისტორია, შეხსენებები, პროფილი და ბორბლების ჩანაწერი ფაილად (მაგ. Google Drive ან Downloads). აპის წაშლამდე გააკეთე ბექაპი და ახალ ინსტალაციაზე აღადგინე. აღდგენა არსებულ ჩანაწერებს ცვლის.",
            color = TextMain, fontSize = 13.sp,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { create.launch("carlink-backup.txt") }, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("შენახვა") }
            OutlinedButton(onClick = { open.launch(arrayOf("text/plain", "*/*")) }, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("აღდგენა") }
        }
        ui.backupMsg?.let {
            Text(it, color = if (it.startsWith("✓")) Good else Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
