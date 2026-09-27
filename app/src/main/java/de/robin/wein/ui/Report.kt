package de.robin.wein.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.robin.wein.AppViewModel
import de.robin.wein.core.Cellar
import de.robin.wein.core.MoveKind
import de.robin.wein.core.Movement
import de.robin.wein.core.Period
import de.robin.wein.core.Sale
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Gain = Color(0xFF2E7D4F)
private val Loss = Color(0xFFC0392B)

fun signedEuro(v: Double): String = (if (v > 0.005) "+" else "") + String.format(Locale.GERMANY, "%.2f €", v)
private fun shortEuro(v: Double): String = (if (v > 0.5) "+" else "") + "${v.roundToInt()}"

@Composable
fun gainColor(v: Double): Color = when {
    v > 0.005 -> Gain
    v < -0.005 -> Loss
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Zeile unter einer Verkaufsbuchung: Gewinn/Verlust nach FIFO. */
@Composable
fun SaleLine(s: Sale) {
    val text = when {
        s.ownUse -> "Eigenverbrauch · Warenwert ${euro(s.cost)}"
        s.profit == null -> "Gewinn unbekannt (Einkaufspreis fehlt)"
        else -> "${if (s.profit >= 0) "Gewinn" else "Verlust"} ${signedEuro(s.profit)} (Einkauf ${euro(s.cost)})"
    }
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (s.profit != null && !s.ownUse) gainColor(s.profit) else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Kasse = Verkauf − Einkauf (Geldfluss). Gewinn = Verkauf − Einkaufspreis der verkauften Flaschen (FIFO). */
private enum class Mode(val label: String) { CASH("Kasse"), PROFIT("Gewinn") }
private enum class View(val label: String) { MONTHS("Monate"), YEARS("Jahre"), CALENDAR("Kalender") }
private enum class Range(val label: String) { ALL("Alles"), THIS_YEAR("Dieses Jahr"), LAST_YEAR("Letztes Jahr"), LAST_12("12 Monate"), CUSTOM("Eigener …") }

private fun Period.value(m: Mode) = if (m == Mode.CASH) cash else profit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportTab(vm: AppViewModel, onOpen: (String) -> Unit) {
    val cellar = vm.cellar
    var mode by rememberSaveable { mutableStateOf(Mode.CASH) }
    var view by rememberSaveable { mutableStateOf(View.MONTHS) }
    var range by rememberSaveable { mutableStateOf(Range.ALL) }
    var customFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var customTo by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()
    val span: Pair<LocalDate?, LocalDate?> = when (range) {
        Range.ALL -> null to null
        Range.THIS_YEAR -> LocalDate.of(today.year, 1, 1) to LocalDate.of(today.year, 12, 31)
        Range.LAST_YEAR -> LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31)
        Range.LAST_12 -> today.minusMonths(12).plusDays(1) to today
        Range.CUSTOM -> customFrom?.let(LocalDate::parse) to customTo?.let(LocalDate::parse)
    }
    val from = span.first
    val to = span.second
    val total = remember(cellar, from, to) { cellar.total(from, to) }
    val periods = remember(cellar, view, from, to) {
        if (view == View.CALENDAR) emptyList() else cellar.periods(view == View.YEARS, from, to)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
                Spacer(Modifier.width(8.dp))
                View.entries.forEach { v -> FilterChip(selected = view == v, onClick = { view = v }, label = { Text(v.label) }) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Range.entries.forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { if (r == Range.CUSTOM) picking = true else range = r },
                        label = {
                            Text(if (r == Range.CUSTOM && range == Range.CUSTOM && from != null && to != null)
                                "${from.format(DATE_FMT)} – ${to.format(DATE_FMT)}" else r.label)
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val v = total.value(mode)
            Text(if (mode == Mode.CASH) "Kasse (Verkauf − Einkauf)" else "Gewinn/Verlust (FIFO)",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(signedEuro(v), style = MaterialTheme.typography.displaySmall, color = gainColor(v))
            Text(
                "Einkauf −${euro(total.spent)} (${total.bottlesIn} Fl.) · Verkauf +${euro(total.revenue)} (${total.bottlesOut} Fl.)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (mode == Mode.CASH) "Gewinn nach FIFO im selben Zeitraum: ${signedEuro(total.profit)} · Lagerwert jetzt ${euro(cellar.stockValue)}"
                else "Kasse im selben Zeitraum: ${signedEuro(total.cash)} · Einkäufe zählen erst beim Verkauf",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (total.unknown > 0) Text("${total.unknown}× Verkauf ohne bekannten Einkaufspreis – nicht im Gewinn.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            when {
                view == View.CALENDAR -> CalendarView(cellar, mode, from, to, onOpen)
                periods.isEmpty() -> Text("Keine Buchungen im Zeitraum.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> { Bars(periods.take(if (view == View.YEARS) 10 else 12).reversed(), mode); Spacer(Modifier.height(16.dp)) }
            }
        }
        if (view != View.CALENDAR) items(periods, key = { it.label }) { p ->
            val expanded = open == p.label
            Column(Modifier.fillMaxWidth().clickable { open = if (expanded) null else p.label }.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(periodLabel(p.label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(signedEuro(p.value(mode)), style = MaterialTheme.typography.titleMedium,
                        color = gainColor(p.value(mode)), fontWeight = FontWeight.SemiBold)
                }
                Text(summaryLine(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded) MovementList(cellar, p.movements, onOpen)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = from?.let { it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
            initialSelectedEndDateMillis = to?.let { it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        val s = day(state.selectedStartDateMillis!!)
                        val e = state.selectedEndDateMillis?.let(::day) ?: s
                        customFrom = s.toString(); customTo = e.toString()
                        range = Range.CUSTOM
                        picking = false
                    },
                ) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Abbrechen") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f), title = {
                Text("Zeitraum wählen", Modifier.padding(start = 24.dp, top = 16.dp))
            })
        }
    }
}

private fun summaryLine(p: Period) = listOfNotNull(
    "Einkauf −${euro(p.spent)} (${p.bottlesIn} Fl.)".takeIf { p.bottlesIn > 0 },
    "Verkauf +${euro(p.revenue)} (${p.bottlesOut} Fl.)".takeIf { p.bottlesOut > 0 },
    "Eigenverbrauch ${euro(p.ownUseValue)}".takeIf { p.ownUseValue > 0 },
    "${p.unknown}× Einkauf unbekannt".takeIf { p.unknown > 0 },
).joinToString(" · ")

/** Einzelbuchungen eines Zeitraums/Tags: Einkäufe mit Betrag, Verkäufe mit FIFO-Gewinn. */
@Composable
private fun MovementList(cellar: Cellar, moves: List<Movement>, onOpen: (String) -> Unit) {
    if (moves.isEmpty()) {
        Text("Keine Buchungen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp))
        return
    }
    moves.sortedBy { it.date }.forEach { m ->
        val w = cellar.wine(m.key)
        Row(Modifier.fillMaxWidth().clickable { w?.let { onOpen(it.key) } }.padding(start = 8.dp, top = 8.dp)) {
            Text(m.date.format(DATE_FMT), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
            Column(Modifier.weight(1f)) {
                Text("${if (m.kind == MoveKind.BUY) "+" else "−"}${m.quantity} Fl. ${w?.name ?: "?"} ${w?.vintageLabel.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium)
                if (m.kind == MoveKind.BUY) {
                    Text(m.price?.let { "Einkauf −${euro(it * m.quantity)}" } ?: "Einkauf ohne Preis",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    cellar.sale(m)?.let { SaleLine(it) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Kalender

@Composable
private fun CalendarView(cellar: Cellar, mode: Mode, from: LocalDate?, to: LocalDate?, onOpen: (String) -> Unit) {
    val start = remember(cellar, from, to) {
        YearMonth.from(to ?: cellar.movements.lastOrNull()?.date ?: LocalDate.now())
    }
    var month by remember(start) { mutableStateOf(start) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val first = month.atDay(1)
    val last = month.atEndOfMonth()
    val days = remember(cellar, month) { cellar.days(first, last) }
    val monthTotal = remember(cellar, month) { cellar.total(first, last) }
    val maxAbs = days.values.maxOfOrNull { abs(it.value(mode)) }?.takeIf { it > 0.005 } ?: 1.0

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { month = month.minusMonths(1); selected = null }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vormonat") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${MONTHS_LONG[month.monthValue - 1]} ${month.year}", style = MaterialTheme.typography.titleMedium)
            Text(signedEuro(monthTotal.value(mode)), color = gainColor(monthTotal.value(mode)), style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = { month = month.plusMonths(1); selected = null }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nächster Monat") }
    }
    Row(Modifier.fillMaxWidth()) {
        listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach {
            Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val lead = first.dayOfWeek.value - 1                       // Montag = 0
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { d ->
                Box(Modifier.weight(1f).aspectRatio(0.9f).padding(2.dp)) {
                    if (d != null) DayCell(d, days[d], mode, maxAbs, d == selected,
                        dimmed = (from != null && d.isBefore(from)) || (to != null && d.isAfter(to))) {
                        selected = if (selected == d) null else d
                    }
                }
            }
            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    selected?.let { d ->
        Spacer(Modifier.height(8.dp))
        Text(d.format(DATE_FMT), style = MaterialTheme.typography.titleSmall)
        MovementList(cellar, days[d]?.movements.orEmpty(), onOpen)
    }
}

@Composable
private fun DayCell(d: LocalDate, p: Period?, mode: Mode, maxAbs: Double, selected: Boolean, dimmed: Boolean, onClick: () -> Unit) {
    val v = p?.value(mode) ?: 0.0
    val hasMoves = p != null && p.movements.isNotEmpty()
    val tint = when {
        !hasMoves -> Color.Transparent
        v > 0.005 -> Gain.copy(alpha = (0.12f + 0.45f * (abs(v) / maxAbs).toFloat()))
        v < -0.005 -> Loss.copy(alpha = (0.12f + 0.45f * (abs(v) / maxAbs).toFloat()))
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(border).padding(if (selected) 2.dp else 0.dp)
            .clip(RoundedCornerShape(7.dp)).background(tint).clickable(enabled = hasMoves, onClick = onClick).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelMedium,
            color = if (dimmed) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
        if (hasMoves) Text(shortEuro(v), style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------------------------------------------------------- Balken

private val MONTHS = listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")
private val MONTHS_LONG = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

private fun periodLabel(l: String): String =
    if (l.length == 7) "${MONTHS_LONG.getOrElse(l.substring(5).toInt() - 1) { l }} ${l.substring(0, 4)}" else l

/** Balken je Zeitraum: grün nach oben = Plus, rot nach unten = Minus. */
@Composable
private fun Bars(periods: List<Period>, mode: Mode) {
    val max = periods.maxOfOrNull { abs(it.value(mode)) }?.takeIf { it > 0.005 } ?: 1.0
    val axis = MaterialTheme.colorScheme.outlineVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val slot = size.width / periods.size
            val bar = slot * 0.6f
            val mid = size.height / 2
            drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
            periods.forEachIndexed { i, p ->
                val v = p.value(mode)
                val h = (abs(v) / max * (mid - 4.dp.toPx())).toFloat()
                if (h < 0.5f) return@forEachIndexed
                val x = i * slot + (slot - bar) / 2
                drawRect(if (v >= 0) Gain else Loss, Offset(x, if (v >= 0) mid - h else mid), Size(bar, h))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            periods.forEach { p ->
                Text(
                    if (p.label.length == 7) MONTHS.getOrElse(p.label.substring(5).toInt() - 1) { "" } else p.label.takeLast(2),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
