package za.co.kmcapture

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.*
import java.io.File
import java.util.zip.*

data class Slip(
    val date: String, val station: String, val litres: String, val total: String,
    val odo: String, val fuel: String = "", val veh: Int = 0, val img: String = "",
    val loc: String = "", val place: String = ""
)

const val NV = 5

fun parse(t: String): Slip {
    var date = ""
    Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""").find(t)?.let {
        date = "%s-%02d-%02d".format(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt())
    }
    if (date.isEmpty()) Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})""").find(t)?.let {
        val y = it.groupValues[3].let { s -> if (s.length == 2) "20$s" else s }
        date = "%s-%02d-%02d".format(y, it.groupValues[2].toInt(), it.groupValues[1].toInt())
    }
    val num = """(\d+[.,]\d{2,3})"""
    // xx.xx or x.xx (not part of a longer number)
    val d2 = Regex("""(?<![\d.,])\d{1,2}[.,]\d{2}(?!\d)""")
    val fuelLine = t.lines().firstOrNull { Regex("(?i)diesel|petrol|unleaded|ulp|lrp").containsMatchIn(it) }
    val litres = (
        Regex("""$num\s*(?:L|l|ltr|litres?)\b""").find(t)?.groupValues?.get(1)
            ?: Regex("""(?i)(?:litres?|ltrs?|qty|volume)[^\d\n]{0,10}$num""").find(t)?.groupValues?.get(1)
            ?: fuelLine?.let { d2.find(it)?.value }
            ?: t.lines().filter { !Regex("""(?i)\bver|total|amount|amt|balance|price|\d:\d""").containsMatchIn(it) }
                .firstNotNullOfOrNull { d2.find(it)?.value }
            ?: ""
        ).replace(',', '.')
    val total = Regex("""(?i)(?:total|amount|amt)[^\d\n]{0,12}$num""").find(t)?.groupValues?.get(1)?.replace(',', '.') ?: ""
    val odo = Regex("""(?i)(?:odo\w*|km|mileage)[^\d\n]{0,8}(\d{3,7})""").find(t)?.groupValues?.get(1) ?: ""
    val station = t.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
    val fuel = fuelLine?.let { line ->
        val m = Regex("""\s\d+[.,]\d{2}""").find(line)
        if (m != null) line.substring(0, m.range.first).trim() else line.trim()
    } ?: ""
    return Slip(date, station, litres, total, odo, fuel)
}

fun load(c: Context): List<Slip> {
    val a = JSONArray(c.getSharedPreferences("km", 0).getString("d", "[]"))
    return List(a.length()) {
        val o = a.getJSONObject(it)
        Slip(o.getString("d"), o.getString("s"), o.getString("l"), o.getString("t"), o.getString("o"),
            o.optString("f"), o.optInt("v", 0).coerceIn(0, NV - 1), o.optString("i"), o.optString("g"), o.optString("p"))
    }
}

fun save(c: Context, l: List<Slip>) {
    val a = JSONArray()
    l.forEach {
        a.put(JSONObject().put("d", it.date).put("s", it.station).put("l", it.litres)
            .put("t", it.total).put("o", it.odo).put("f", it.fuel).put("v", it.veh).put("i", it.img).put("g", it.loc).put("p", it.place))
    }
    c.getSharedPreferences("km", 0).edit().putString("d", a.toString()).apply()
}

fun loadLabels(c: Context): List<String> {
    val a = JSONArray(c.getSharedPreferences("km", 0).getString("lb", "[]"))
    return List(NV) { if (it < a.length()) a.getString(it) else "Vehicle ${it + 1}" }
}

fun saveLabels(c: Context, l: List<String>) {
    c.getSharedPreferences("km", 0).edit().putString("lb", JSONArray(l).toString()).apply()
}

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

data class Pic(val row: Int, val media: Int, val w: Int, val h: Int)

fun imgFile(c: Context, name: String) = File(File(c.filesDir, "slips"), name)

fun dropImg(c: Context, name: String) { if (name.isNotBlank()) imgFile(c, name).delete() }

// Crops to the bounding box of the largest bright, low-colour region (the white slip).
fun cropToSlip(b: Bitmap): Bitmap {
    val sc = 200f / maxOf(b.width, b.height)
    val w = maxOf(1, (b.width * sc).toInt())
    val h = maxOf(1, (b.height * sc).toInt())
    val px = IntArray(w * h)
    Bitmap.createScaledBitmap(b, w, h, true).getPixels(px, 0, w, 0, 0, w, h)
    val lum = IntArray(w * h) {
        val p = px[it]
        (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
    }
    val hist = IntArray(256)
    lum.forEach { hist[it]++ }
    var sum = 0L
    for (i in 0..255) sum += i.toLong() * hist[i]
    var wB = 0L; var sB = 0L; var best = 0.0; var th = 128
    val total = w.toLong() * h
    for (i in 0..255) {
        wB += hist[i]
        if (wB == 0L) continue
        val wF = total - wB
        if (wF == 0L) break
        sB += i.toLong() * hist[i]
        val mB = sB.toDouble() / wB
        val mF = (sum - sB).toDouble() / wF
        val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
        if (v > best) { best = v; th = i }
    }
    val mask = BooleanArray(w * h) {
        val p = px[it]
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
        lum[it] > th && (maxOf(r, g, bl) - minOf(r, g, bl)) < 70
    }
    val seen = BooleanArray(w * h)
    val q = IntArray(w * h)
    var bestN = 0; var bx0 = 0; var by0 = 0; var bx1 = 0; var by1 = 0
    for (st in 0 until w * h) {
        if (!mask[st] || seen[st]) continue
        var head = 0; var tail = 0
        q[tail++] = st; seen[st] = true
        var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
        while (head < tail) {
            val p = q[head++]; val x = p % w; val y = p / w
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
            if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; q[tail++] = p - 1 }
            if (x < w - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; q[tail++] = p + 1 }
            if (y > 0 && mask[p - w] && !seen[p - w]) { seen[p - w] = true; q[tail++] = p - w }
            if (y < h - 1 && mask[p + w] && !seen[p + w]) { seen[p + w] = true; q[tail++] = p + w }
        }
        if (tail > bestN) { bestN = tail; bx0 = x0; by0 = y0; bx1 = x1; by1 = y1 }
    }
    if (bestN < w * h * 0.08) return b
    val fx = b.width.toFloat() / w
    val fy = b.height.toFloat() / h
    val padX = (b.width * 0.03f).toInt()
    val padY = (b.height * 0.03f).toInt()
    val l = maxOf(0, (bx0 * fx).toInt() - padX)
    val t = maxOf(0, (by0 * fy).toInt() - padY)
    val r = minOf(b.width, ((bx1 + 1) * fx).toInt() + padX)
    val bt = minOf(b.height, ((by1 + 1) * fy).toInt() + padY)
    if (r - l < b.width * 0.1 || bt - t < b.height * 0.1) return b
    return Bitmap.createBitmap(b, l, t, r - l, bt - t)
}

fun saveImage(c: Context, u: Uri): String = try {
    val name = "slip_${System.currentTimeMillis()}.jpg"
    File(c.filesDir, "slips").mkdirs()
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / sample > 2000) sample *= 2
    val bmp = c.contentResolver.openInputStream(u)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }!!
    val rot = c.contentResolver.openInputStream(u)!!.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
    }
    val m = Matrix().apply {
        postRotate(rot)
        val sc = minOf(1f, 1200f / maxOf(bmp.width, bmp.height))
        postScale(sc, sc)
    }
    val out = cropToSlip(Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true))
    imgFile(c, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    name
} catch (e: Exception) { "" }

@Composable
fun Photo(name: String, modifier: Modifier, sample: Int, fit: Boolean) {
    val c = LocalContext.current
    val bmp = remember(name) {
        BitmapFactory.decodeFile(imgFile(c, name).path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
    bmp?.let { Image(it, null, modifier, contentScale = if (fit) ContentScale.Fit else ContentScale.Crop) }
}

fun drawingXml(ns: String, p: List<Pic>): String {
    val sb = StringBuilder("""<xdr:wsDr xmlns:xdr="${ns}drawingml/2006/spreadsheetDrawing" xmlns:a="${ns}drawingml/2006/main" xmlns:r="${ns}officeDocument/2006/relationships">""")
    p.forEachIndexed { k, q ->
        val cy = 160L * 9525
        val cx = cy * q.w / q.h
        sb.append("""<xdr:oneCellAnchor><xdr:from><xdr:col>8</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${q.row}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx="$cx" cy="$cy"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${k + 2}" name="Slip ${k + 1}"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${k + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>""")
    }
    return sb.append("</xdr:wsDr>").toString()
}

fun exportXlsx(c: Context, l: List<Slip>, labels: List<String>): Uri {
    val used = (0 until NV).filter { v -> l.any { it.veh == v } }.ifEmpty { listOf(0) }
    val names = mutableListOf<String>()
    used.forEach { v ->
        var n = labels[v].replace(Regex("""[\[\]:*?/\\"']"""), "").trim().take(31).ifBlank { "Vehicle ${v + 1}" }
        if (names.any { it.equals(n, true) }) n = n.take(27) + " (${v + 1})"
        names.add(n)
    }
    val ns = "http://schemas.openxmlformats.org/"
    val cols = """<col min="1" max="1" width="12" customWidth="1"/><col min="2" max="2" width="16" customWidth="1"/><col min="3" max="3" width="24" customWidth="1"/><col min="4" max="4" width="22" customWidth="1"/><col min="5" max="7" width="13" customWidth="1"/><col min="8" max="8" width="30" customWidth="1"/><col min="9" max="9" width="24" customWidth="1"/>"""
    val head = listOf("Date", "Vehicle", "Station", "Fuel type", "Litres", "Total (R)", "Odometer (km)", "Location", "Photo")
    val media = mutableListOf<File>()

    fun sheet(v: Int, pics: MutableList<Pic>): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="${ns}spreadsheetml/2006/main" xmlns:r="${ns}officeDocument/2006/relationships"><cols>$cols</cols><sheetData>""")
        fun row(r: Int, x: List<String>, tall: Boolean) {
            sb.append("<row r=\"${r + 1}\"${if (tall) " ht=\"120\" customHeight=\"1\"" else ""}>")
            x.forEachIndexed { i, s ->
                val ref = "${'A' + i}${r + 1}"
                if (r > 0 && i in 4..6 && s.toDoubleOrNull() != null) sb.append("<c r=\"$ref\"><v>$s</v></c>")
                else if (r > 0 && i == 7 && s.substringBefore("|").isNotBlank()) {
                    val tx = s.substringAfter("|").replace("\"", "'")
                    sb.append("<c r=\"$ref\" t=\"str\"><f>HYPERLINK(\"https://maps.google.com/?q=${s.substringBefore("|")}\",\"${esc(tx)}\")</f><v>${esc(tx)}</v></c>")
                } else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(if (r > 0 && i == 7) s.substringAfter("|") else s)}</t></is></c>")
            }
            sb.append("</row>")
        }
        row(0, head, false)
        l.filter { it.veh == v }.sortedBy { it.date }.forEachIndexed { k, s ->
            val pf = imgFile(c, s.img)
            var ok = false
            if (s.img.isNotBlank() && pf.exists()) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(pf.path, o)
                if (o.outWidth > 0 && o.outHeight > 0) { media.add(pf); pics.add(Pic(k + 1, media.size, o.outWidth, o.outHeight)); ok = true }
            }
            row(k + 1, listOf(s.date, labels[v], s.station, s.fuel, s.litres, s.total, s.odo, s.loc + "|" + s.place.ifBlank { s.loc }, ""), ok)
        }
        sb.append("</sheetData>")
        if (pics.isNotEmpty()) sb.append("<drawing r:id=\"rId1\"/>")
        return sb.append("</worksheet>").toString()
    }

    val pics = used.map { mutableListOf<Pic>() }
    val xmls = used.mapIndexed { i, v -> sheet(v, pics[i]) }
    val f = File(c.cacheDir, "Km_Capture.xlsx")
    ZipOutputStream(f.outputStream()).use { z ->
        fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
        val sheetOv = used.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" }
        val drawOv = used.indices.filter { pics[it].isNotEmpty() }.joinToString("") { """<Override PartName="/xl/drawings/drawing${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""" }
        put("[Content_Types].xml", """<Types xmlns="${ns}package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>$sheetOv$drawOv</Types>""")
        put("_rels/.rels", """<Relationships xmlns="${ns}package/2006/relationships"><Relationship Id="rId1" Type="${ns}officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        val sheets = names.indices.joinToString("") { """<sheet name="${esc(names[it])}" sheetId="${it + 1}" r:id="rId${it + 1}"/>""" }
        put("xl/workbook.xml", """<workbook xmlns="${ns}spreadsheetml/2006/main" xmlns:r="${ns}officeDocument/2006/relationships"><sheets>$sheets</sheets></workbook>""")
        val rels = used.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="${ns}officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" }
        put("xl/_rels/workbook.xml.rels", """<Relationships xmlns="${ns}package/2006/relationships">$rels</Relationships>""")
        used.indices.forEach { i ->
            put("xl/worksheets/sheet${i + 1}.xml", xmls[i])
            if (pics[i].isNotEmpty()) {
                put("xl/worksheets/_rels/sheet${i + 1}.xml.rels", """<Relationships xmlns="${ns}package/2006/relationships"><Relationship Id="rId1" Type="${ns}officeDocument/2006/relationships/drawing" Target="../drawings/drawing${i + 1}.xml"/></Relationships>""")
                put("xl/drawings/drawing${i + 1}.xml", drawingXml(ns, pics[i]))
                val ir = pics[i].mapIndexed { k, q -> """<Relationship Id="rId${k + 1}" Type="${ns}officeDocument/2006/relationships/image" Target="../media/image${q.media}.jpg"/>""" }.joinToString("")
                put("xl/drawings/_rels/drawing${i + 1}.xml.rels", """<Relationships xmlns="${ns}package/2006/relationships">$ir</Relationships>""")
            }
        }
        media.forEachIndexed { i, m -> z.putNextEntry(ZipEntry("xl/media/image${i + 1}.jpg")); z.write(m.readBytes()); z.closeEntry() }
    }
    return FileProvider.getUriForFile(c, "${c.packageName}.files", f)
}

// Current position from Google's fused location provider (same source as Google Maps), plus street address.
@SuppressLint("MissingPermission")
fun getLocation(c: Context, done: (String, String) -> Unit) {
    val granted = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) { done("", ""); return }
    val main = Handler(Looper.getMainLooper())
    var finished = false
    val finish: (String, String) -> Unit = { a, b -> if (!finished) { finished = true; done(a, b) } }
    main.postDelayed({ finish("", "") }, 10000)
    LocationServices.getFusedLocationProviderClient(c)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
        .addOnSuccessListener { loc ->
            if (loc == null) { finish("", ""); return@addOnSuccessListener }
            val ll = String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)
            Thread {
                val place = try {
                    @Suppress("DEPRECATION")
                    Geocoder(c, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.getAddressLine(0) ?: ""
                } catch (e: Exception) { "" }
                main.post { finish(ll, place) }
            }.start()
        }
        .addOnFailureListener { finish("", "") }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun App() {
    val c = LocalContext.current
    val slips = remember { mutableStateListOf<Slip>().apply { addAll(load(c)) } }
    val labels = remember { mutableStateListOf<String>().apply { addAll(loadLabels(c)) } }
    var tab by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    var draft by remember { mutableStateOf<Slip?>(null) }
    var busy by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf("") }

    fun scan(u: Uri, veh: Int) {
        busy = true
        val img = saveImage(c, u)
        getLocation(c) { loc, place ->
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromFilePath(c, u))
                .addOnSuccessListener { draft = parse(it.text).copy(veh = veh, img = img, loc = loc, place = place); busy = false }
                .addOnFailureListener { draft = Slip("", "", "", "", "", "", veh, img, loc, place); busy = false }
        }
    }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) picked = photo }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) picked = u }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Km Capture V2", style = MaterialTheme.typography.headlineMedium)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            labels.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val f = File.createTempFile("slip", ".jpg", c.cacheDir)
                val u = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
                photo = u; cam.launch(u)
            }) { Text("Take photo") }
            Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Gallery") }
            TextButton(onClick = { renaming = true }) { Text("Rename tab") }
        }
        Button(enabled = slips.isNotEmpty(), onClick = {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, exportXlsx(c, slips, labels))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            c.startActivity(Intent.createChooser(i, "Export Excel"))
        }) { Text("Export to Excel (sheet per vehicle, by date)") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(slips.filter { it.veh == tab }.sortedBy { it.date }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (s.img.isNotBlank()) {
                            Photo(s.img, Modifier.size(56.dp).clickable { viewing = s.img }, 4, false)
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${s.date}  ·  ${s.station}")
                            Text("${s.fuel}  ·  ${s.litres} L  ·  R${s.total}  ·  ${s.odo} km")
                            if (s.loc.isNotBlank()) Text(
                                "\uD83D\uDCCD " + s.place.ifBlank { s.loc },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable {
                                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${s.loc}")))
                                }
                            )
                        }
                        TextButton(onClick = { slips.remove(s); dropImg(c, s.img); save(c, slips) }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (picked != null) {
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Which vehicle?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEachIndexed { i, t ->
                        Button(modifier = Modifier.fillMaxWidth(), onClick = {
                            val u = picked!!; picked = null; tab = i; scan(u, i)
                        }) { Text(t) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } }
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(labels[tab]) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Vehicle label") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("e.g. registration") }) },
            confirmButton = {
                TextButton(onClick = {
                    labels[tab] = name.trim().ifBlank { "Vehicle ${tab + 1}" }
                    saveLabels(c, labels); renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }
    if (viewing.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { viewing = "" },
            confirmButton = { TextButton(onClick = { viewing = "" }) { Text("Close") } },
            text = { Photo(viewing, Modifier.fillMaxWidth().heightIn(max = 480.dp), 1, true) }
        )
    }
    draft?.let { d ->
        EditDialog(d, labels[d.veh], onCancel = { dropImg(c, d.img); draft = null }) { s ->
            slips.add(s); save(c, slips); draft = null
        }
    }
}

@Composable
fun EditDialog(init: Slip, vehicle: String, onCancel: () -> Unit, onSave: (Slip) -> Unit) {
    var date by remember { mutableStateOf(init.date) }
    var st by remember { mutableStateOf(init.station) }
    var f by remember { mutableStateOf(init.fuel) }
    var l by remember { mutableStateOf(init.litres) }
    var t by remember { mutableStateOf(init.total) }
    var o by remember { mutableStateOf(init.odo) }
    var pl by remember { mutableStateOf(init.place.ifBlank { init.loc }) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Check slip: $vehicle") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (init.img.isNotBlank()) Photo(init.img, Modifier.fillMaxWidth().height(160.dp), 2, true)
                OutlinedTextField(date, { date = it }, label = { Text("Date (yyyy-mm-dd)") }, singleLine = true)
                OutlinedTextField(st, { st = it }, label = { Text("Station") }, singleLine = true)
                OutlinedTextField(f, { f = it }, label = { Text("Fuel type") }, singleLine = true)
                OutlinedTextField(l, { l = it }, label = { Text("Litres") }, singleLine = true)
                OutlinedTextField(t, { t = it }, label = { Text("Total (R)") }, singleLine = true)
                OutlinedTextField(o, { o = it }, label = { Text("Odometer (km)") }, singleLine = true)
                OutlinedTextField(pl, { pl = it }, label = { Text(if (init.loc.isBlank()) "Location (not available)" else "Location") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Slip(date, st, l, t, o, f, init.veh, init.img, init.loc, pl)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
