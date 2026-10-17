package com.example.carlink

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** ბენზინის ისტორიის მარტივი ხაზოვანი გრაფიკი. */
@Composable
internal fun HistoryChart(points: List<HistPoint>) {
    val vals = points.mapNotNull { it.fuel }
    if (vals.size < 2) {
        Text("გრაფიკი გამოჩნდება, როცა რამდენიმე ჩანაწერი დაგროვდება (ჩაწერა ხდება ყოველ 30 წამში, მანქანასთან დაკავშირებისას).", color = Muted, fontSize = 13.sp)
        return
    }
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val w = size.width
        val h = size.height
        for (i in 1 until vals.size) {
            val x0 = w * (i - 1) / (vals.size - 1)
            val x1 = w * i / (vals.size - 1)
            val y0 = h * (1f - vals[i - 1].coerceIn(0f, 100f) / 100f)
            val y1 = h * (1f - vals[i].coerceIn(0f, 100f) / 100f)
            drawLine(Accent, Offset(x0, y0), Offset(x1, y1), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * ექსპლორერი: მხოლოდ წაკითხვა. გამოიყენება მანქანაზე იმის გასარკვევად, რას პასუხობს Cruze
 * (მათ შორის ბორბლების წნევის მოთხოვნის საპოვნელად). ბრძანებები გადის whitelist-ზე.
 */
@Composable
fun ExplorerScreen(vm: CarViewModel) {
    val ex by vm.explorerState.collectAsState()
    val ui by vm.state.collectAsState()
    val ctx = LocalContext.current
    var header by remember { mutableStateOf("7DF") }
    var cmd by remember { mutableStateOf("0100") }
    var didHeader by remember { mutableStateOf("7E0") }
    var didFrom by remember { mutableStateOf("F180") }
    var didTo by remember { mutableStateOf("F19F") }
    var rngFrom by remember { mutableStateOf("700") }
    var rngTo by remember { mutableStateOf("7FF") }
    var lsSec by remember { mutableStateOf("10") }
    var lsId by remember { mutableStateOf("") }
    val canRun = ui.connection == Conn.Connected && !ex.busy

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("ექსპლორერი", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card {
            Text(
                "მხოლოდ წაკითხვა. ჩამწერი/წამშლელი ბრძანებები დაბლოკილია. მუშაობს მხოლოდ გაჩერებულ მანქანაზე, ჩართული ანთებით. ჯერ დააკავშირე ადაპტერი დაფაზე.",
                color = Muted, fontSize = 13.sp,
            )
        }

        Card {
            Text("ერთი ღილაკით (რეკომენდებულია)", color = Muted, fontSize = 13.sp)
            Text(
                "ადაპტერის ინფო, PID-ები, VIN, მოდულები და საიდენტიფიკაციო DID-ები ერთად (დაახლ. 1–2 წუთი). ბოლოს დააჭირე «გაზიარება».",
                color = TextMain, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            Button(
                onClick = { vm.fullTest() }, enabled = canRun,
                modifier = Modifier.fillMaxWidth(), contentPadding = BtnPad,
            ) { BtnText("სრული ტესტი") }
        }

        Card {
            Text("სწრაფი ტესტები", color = Muted, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { vm.scanSupported() }, enabled = canRun, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("PID-ები") }
                Button(onClick = { vm.readVin() }, enabled = canRun, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("VIN") }
                Button(onClick = { vm.scanEcus() }, enabled = canRun, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("მოდულები") }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { vm.readIdentity() }, enabled = canRun, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("მანქანის ID-ები") }
            }
        }

        Card {
            Text("GM ძებნა (Mode 22, ძრავის კომპიუტერი)", color = Muted, fontSize = 13.sp)
            Text(
                "ავტომატურად ეძებს, რა დამატებით მონაცემს აძლევს Cruze ძრავის კომპიუტერი (ზეთის ხანგრძლივობა, ბუსტის მოთხოვნა, გამოტოვებები და სხვა). მხოლოდ კითხულობს. გრძელდება რამდენიმე წუთი, შეგიძლია შეაჩერო. ბოლოს დააჭირე «გაზიარება» ლოგში და გამომიგზავნე.",
                color = TextMain, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            Button(
                onClick = { vm.gmSearch() }, enabled = canRun,
                modifier = Modifier.fillMaxWidth(), contentPadding = BtnPad,
            ) { BtnText("GM ძებნის დაწყება") }
        }

        Card {
            Text("ხელით ბრძანება (წაკითხვა)", color = Muted, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = header, onValueChange = { header = it.take(3) },
                    label = { Text("Header") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it.take(24) },
                    label = { Text("ბრძანება") }, singleLine = true, modifier = Modifier.weight(2f),
                )
            }
            Button(
                onClick = { vm.sendCustom(header, cmd) }, enabled = canRun,
                modifier = Modifier.padding(top = 8.dp),
            ) { BtnText("გაგზავნა") }
        }

        Card {
            Text("DID წაკითხვა (UDS 22, მაქს. 256)", color = Muted, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = didHeader, onValueChange = { didHeader = it.take(3) },
                    label = { Text("Header") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = didFrom, onValueChange = { didFrom = it.take(4) },
                    label = { Text("დან") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = didTo, onValueChange = { didTo = it.take(4) },
                    label = { Text("მდე") }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = { vm.sweepDids(didHeader, didFrom, didTo) }, enabled = canRun,
                modifier = Modifier.padding(top = 8.dp),
            ) { BtnText("წაკითხვა") }
        }

        Card {
            Text("მოდულების ძებნა (700–7FF, 3E00)", color = Muted, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = rngFrom, onValueChange = { rngFrom = it.take(3) },
                    label = { Text("დან") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = rngTo, onValueChange = { rngTo = it.take(3) },
                    label = { Text("მდე") }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = { vm.scanAddressRange(rngFrom, rngTo) }, enabled = canRun,
                modifier = Modifier.padding(top = 8.dp), contentPadding = BtnPad,
            ) { BtnText("ძებნა") }
            Text("გრძელდება რამდენიმე წუთი; შეგიძლია შეაჩერო.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Card {
            Text("ბუსის მოსმენა (პასიური)", color = Muted, fontSize = 13.sp)
            Text(
                "არაფერს აგზავნის, მხოლოდ ისმენს. ID-ის გარეშე ნახავ, რომელი ID-ები დადიან ბუსზე; კონკრეტული ID-ით უფრო სრულ სურათს მიიღებ.",
                color = TextMain, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = lsSec, onValueChange = { lsSec = it.take(2) },
                    label = { Text("წამი") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = lsId, onValueChange = { lsId = it.take(3) },
                    label = { Text("ID (ან ცარიელი)") }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = { vm.listenBus(lsSec, lsId) }, enabled = canRun,
                modifier = Modifier.padding(top = 8.dp), contentPadding = BtnPad,
            ) { BtnText("მოსმენა") }
        }

        Card {
            Text("ლოგი", color = Muted, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(modifier = Modifier.weight(1f), contentPadding = BtnPad, onClick = {
                    val text = buildString {
                        append("CarLink log\n")
                        ex.vin?.let { append("VIN: $it\n") }
                        if (ex.supported.isNotEmpty()) append("PIDs: ${ex.supported.joinToString(" ")}\n")
                        append(ex.log.joinToString("\n"))
                    }.takeLast(60000)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    ctx.startActivity(Intent.createChooser(send, "გაგზავნა").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { BtnText("გაზიარება") }
                OutlinedButton(onClick = { vm.stopExplorer() }, enabled = ex.busy, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("შეჩერება") }
                OutlinedButton(onClick = { vm.clearLog() }, modifier = Modifier.weight(1f), contentPadding = BtnPad) { BtnText("გასუფთავება") }
            }
            if (ex.log.isEmpty()) {
                Text("ცარიელია", color = Muted, fontSize = 13.sp)
            } else {
                ex.log.takeLast(200).forEach {
                    Text(it, color = TextMain, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
