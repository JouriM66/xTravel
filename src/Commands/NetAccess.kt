// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Сетевой доступ к данным: окно с адресами, пока оно открыто - HTTP-сервер на всех адресах телефона.
    Браузер другого устройства скачивает весь набор в формате обмена или загружает файл,
    который сохраняется в каталог приёма без импорта.
*/
object NetAccess {
  fun open() = AppDialog.show { NetAccessDialog() }
}

private const val FIRST_PORT = 8080
private const val PORT_COUNT = 20 /** Сколько портов подряд пробовать, если первый занят */
private const val ADDRESS_CHECK_MS = 3000L /** Адреса меняются при подключении сетей и раздачи, поэтому перечитываются */
private const val STATUS_PERIOD_MS = 300L
private const val READ_TIMEOUT_MS = 60_000
private const val MAX_HEAD = 16 * 1024
private const val TEXT_TYPE = "text/plain; charset=utf-8"

/** FOR LOCAL USE
    Окно сетевого доступа. Сервер живёт, пока окно в композиции; на это время экран не гаснет.
*/
@Composable
private fun NetAccessDialog() {
  val server = remember { NetServer() }
  val view = LocalView.current
  val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) UploadFolder.select(uri)
  }
  var addresses by remember { mutableStateOf(emptyList<String>()) }
  DisposableEffect(server) {
    val screenOn = view.keepScreenOn
    view.keepScreenOn = true
    server.start()
    onDispose {
      server.stop()
      view.keepScreenOn = screenOn
    }
  }
  LaunchedEffect(server) {
    while (true) {
      addresses = withContext(Dispatchers.IO) { localAddresses() }
      delay(ADDRESS_CHECK_MS)
    }
  }
  val urls = if (server.port == 0) emptyList() else addresses.map { "http://$it:${server.port}/" }
  val subject = stringResource(R.string.net_share_subject)
  val intro = stringResource(R.string.net_share_text)
  AlertDialog(
    onDismissRequest = AppDialog::close,
    properties = DialogProperties(dismissOnClickOutside = false),
    icon = { Icon(Icons.Outlined.Wifi, contentDescription = null) },
    title = { Text(stringResource(R.string.net_access)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FramedGroup {
          Text(stringResource(R.string.net_hint))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              when {
                server.error.isNotEmpty() -> Text(server.error, color = MaterialTheme.colorScheme.error)
                server.port == 0 -> Text(stringResource(R.string.net_starting))
                urls.isEmpty() -> Text(stringResource(R.string.net_no_address))
                else -> SelectionContainer {
                  Column { urls.forEach { Text(it, style = MaterialTheme.typography.titleMedium, color = LINK_COLOR) } }
                }
              }
            }
          }
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DialogButton(Icons.Outlined.Share, R.string.share, enabled = urls.isNotEmpty()) {
              Sharing.shareText(intro + "\n" + urls.joinToString("\n"), subject)
            }
          }
        }
        FramedGroup {
          Text(stringResource(R.string.net_upload_title), style = MaterialTheme.typography.titleSmall)
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(stringResource(R.string.net_folder), style = MaterialTheme.typography.bodySmall)
              Text(UploadFolder.label(UploadFolder.tree()) ?: stringResource(R.string.net_folder_none))
            }
            DialogButton(Icons.Outlined.FolderOpen, R.string.net_folder_choose) { chooseFolder.launch(UploadFolder.tree()) }
          }
        }
        FramedGroup {
          Text(server.status.ifEmpty { stringResource(R.string.net_status_wait) })
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          DialogButton(Icons.Outlined.Close, R.string.close, danger = true, onClick = AppDialog::close)
        }
      }
    },
    confirmButton = {}
  )
}

/** FOR LOCAL USE
    Каталог для принятых файлов: выбранный через SAF (Settings.uploadFolder) или Download устройства.
    При совпадении имён новое имя файлу даёт система.
*/
private object UploadFolder {

  fun tree(): Uri? = Settings.uploadFolder.value.takeIf { it.isNotEmpty() }?.let(Uri::parse)

  /** Путь внутри хранилища; null - каталог не выбран, а Download без выбора недоступен (до Android 10) */
  fun label(tree: Uri?): String? {
    if (tree == null) return if (Build.VERSION.SDK_INT >= 29) Environment.DIRECTORY_DOWNLOADS else null
    val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
    return id.substringAfter(':').ifEmpty { id }
  }

  fun select(uri: Uri) {
    val resolver = AppSession.app.contentResolver
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    tree()?.takeIf { it != uri }?.let { old -> runCatching { resolver.releasePersistableUriPermission(old, flags) } }
    runCatching { resolver.takePersistableUriPermission(uri, flags) }
    Settings.uploadFolder.value = uri.toString()
  }

  /** Пишет тело запроса файлом; недочитанное тело - ошибка, созданный файл удаляется. Вернёт итоговое имя файла */
  fun save(name: String, body: BodyStream): String {
    val copy: (OutputStream) -> Unit = { out ->
      body.copyTo(out, 64 * 1024)
      if (body.received < body.length) throw IOException("Upload interrupted at ${body.received} of ${body.length}")
    }
    val tree = tree() ?: return saveToDownloads(name, write = copy)
    val resolver = AppSession.app.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val target = DocumentsContract.createDocument(resolver, parent, mimeTypeOf(name), name) ?: throw IOException("File is not created")
    try {
      (resolver.openOutputStream(target) ?: throw IOException("File is not opened")).use(copy)
    } catch (e: Exception) {
      runCatching { DocumentsContract.deleteDocument(resolver, target) }
      throw e
    }
    return displayNameOf(target) ?: name
  }
}

/** FOR LOCAL USE: имена интерфейсов Wi-Fi и раздачи точки доступа, которых нет среди сетей ConnectivityManager */
private val WIFI_INTERFACE = Regex("(s?wlan|softap|ap|wifi|wigig)[0-9_].*")

/** FOR LOCAL USE
    IPv4-адреса телефона, до которых доберётся другое устройство его Wi-Fi: подключение к Wi-Fi и раздача точки доступа.
    Сотовые, VPN, USB и прочие интерфейсы отбрасываются.
*/
private fun localAddresses(): List<String> = runCatching {
  val wifi = transportInterfaces(NetworkCapabilities.TRANSPORT_WIFI)
  val foreign = transportInterfaces(NetworkCapabilities.TRANSPORT_CELLULAR) + transportInterfaces(NetworkCapabilities.TRANSPORT_VPN)
  NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback && it.name !in foreign && (it.name in wifi || WIFI_INTERFACE.matches(it.name)) }
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>()
    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    .mapNotNull { it.hostAddress }
    .distinct()
}.getOrDefault(emptyList())

/** FOR LOCAL USE: имена интерфейсов сетей телефона с указанным транспортом */
@Suppress("DEPRECATION")
private fun transportInterfaces(transport: Int): Set<String> {
  val manager = AppSession.app.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
  return manager.allNetworks
    .filter { manager.getNetworkCapabilities(it)?.hasTransport(transport) == true }
    .mapNotNull { manager.getLinkProperties(it)?.interfaceName }
    .toSet()
}

/** FOR LOCAL USE: заголовок запроса; имена полей в нижнем регистре, путь без адреса сервера, параметров и замыкающей "/" */
private class HttpRequest(val method: String, val path: String, val headers: Map<String, String>)

/** FOR LOCAL USE: путь из цели запроса; полный адрес (запрос через прокси) сводится к пути, пустой путь - "/" */
private fun requestPath(target: String): String {
  var path = target.substringBefore('?').substringBefore('#')
  val scheme = path.indexOf("://")
  if (scheme >= 0) path = path.substring(scheme + 3).let { rest -> rest.indexOf('/').let { if (it < 0) "" else rest.substring(it) } }
  return "/" + path.trim('/')
}

/** FOR LOCAL USE
    HTTP-сервер сетевого доступа: страница, отдача всего набора данных и приём файла для импорта.
    Каждое подключение в своём потоке, после ответа соединение закрывается. Состояние для окна - в главном потоке.
*/
private class NetServer {
  var port by mutableIntStateOf(0) /** 0 - сервер ещё не слушает */
    private set
  var status by mutableStateOf("") /** Последняя передача; пусто - передач не было */
    private set
  var error by mutableStateOf("") /** Сервер не запустился */
    private set

  private val main = Handler(Looper.getMainLooper())
  private val clients = ConcurrentHashMap.newKeySet<Socket>()
  @Volatile private var socket: ServerSocket? = null
  @Volatile private var stopped = false
  @Volatile private var shownAt = 0L

  fun start() {
    thread(name = "net-server") { listen() }
  }

  fun stop() {
    stopped = true
    runCatching { socket?.close() }
    clients.forEach { runCatching { it.close() } }
  }

  private fun listen() {
    val server = bind() ?: return
    socket = server
    if (stopped) {
      server.close()
      return
    }
    main.post { port = server.localPort }
    while (!stopped) {
      val client = runCatching { server.accept() }.getOrNull() ?: break
      clients += client
      thread(name = "net-client") {
        try {
          serve(client)
        } finally {
          clients -= client
          runCatching { client.close() }
        }
      }
    }
  }

  private fun bind(): ServerSocket? {
    var failure: Exception? = null
    for (candidate in FIRST_PORT until FIRST_PORT + PORT_COUNT) {
      val server = ServerSocket()
      try {
        server.reuseAddress = true
        server.bind(InetSocketAddress(candidate))
        return server
      } catch (e: IOException) {
        server.close()
        failure = e
      }
    }
    val text = string(R.string.net_start_failed, failure?.message.orEmpty())
    main.post { error = text }
    return null
  }

  private fun serve(client: Socket) {
    try {
      client.soTimeout = READ_TIMEOUT_MS
      val input = client.getInputStream().buffered()
      val out = client.getOutputStream()
      val request = readRequest(input) ?: return
      val head = request.method == "HEAD"
      // Любой другой путь GET получает страницу: адрес могут набрать с лишним хвостом.
      when {
        request.method == "POST" && request.path == "/upload" -> upload(request, input, out)
        request.method != "GET" && !head -> respond(out, "405 Method Not Allowed", TEXT_TYPE, "Method not allowed")
        request.path == "/download" || request.path.startsWith("/download/") ->
          if (head) writeHead(out, "200 OK", "Content-Type: application/zip") else download(out)
        request.path == "/favicon.ico" -> respond(out, "404 Not Found", TEXT_TYPE, "Not found", !head)
        else -> respond(out, "200 OK", "text/html; charset=utf-8", page(), !head)
      }
    } catch (e: Exception) {
      if (stopped) return
      Log.w("xTravel", "Network access failed", e)
      showStatus(string(R.string.net_status_failed))
    }
  }

  private fun readRequest(input: InputStream): HttpRequest? {
    val head = ByteArrayOutputStream()
    var tail = 0
    while (tail != 0x0D0A0D0A) {
      val byte = input.read()
      if (byte < 0 || head.size() >= MAX_HEAD) return null
      head.write(byte)
      tail = (tail shl 8) or byte
    }
    val lines = head.toString("ISO-8859-1").split("\r\n").dropWhile { it.isBlank() }
    val start = lines.firstOrNull()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
    if (start.size < 2) return null
    val headers = lines.drop(1).mapNotNull { line ->
      val colon = line.indexOf(':')
      if (colon <= 0) null else line.take(colon).trim().lowercase() to line.substring(colon + 1).trim()
    }.toMap()
    return HttpRequest(start[0].uppercase(), requestPath(start[1]), headers)
  }

  private fun respond(out: OutputStream, code: String, type: String, body: String, withBody: Boolean = true) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    writeHead(out, code, "Content-Type: $type\r\nContent-Length: ${bytes.size}")
    if (withBody) out.write(bytes)
    out.flush()
  }

  private fun writeHead(out: OutputStream, code: String, fields: String) {
    out.write("HTTP/1.1 $code\r\n$fields\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
  }

  // Длина архива заранее неизвестна, конец ответа - закрытие соединения.
  private fun download(out: OutputStream) {
    val set = onMain { DataSelectors.all().let { DataSet(it.points, it.routes, it.tracks, it.notes) } }
    val name = "${XTravelShare.fileName()}.$EXCHANGE_EXT"
    val counted = CountingOutputStream(out) { sent -> throttled { showStatus(string(R.string.net_status_sending, formatCount(sent))) } }
    var started = false
    try {
      DataIO.writeExport(set, counted) {
        writeHead(out, "200 OK", "Content-Type: application/zip\r\nContent-Disposition: attachment; filename=\"$name\"")
        started = true
      }
    } catch (e: Exception) {
      // Пока заголовок не ушёл, браузер получит ошибку, а не пустой файл.
      if (!started) runCatching { respond(out, "500 Internal Server Error", TEXT_TYPE, string(R.string.net_page_failed)) }
      throw e
    }
    counted.flush()
    showStatus(string(R.string.net_status_sent, formatCount(counted.count)))
  }

  // Файл пишется в каталог приёма прямо из соединения под переданным именем.
  private fun upload(request: HttpRequest, input: InputStream, out: OutputStream) {
    val length = request.headers["content-length"]?.toLongOrNull()
    if (length == null) {
      respond(out, "411 Length Required", TEXT_TYPE, string(R.string.net_page_failed))
      return
    }
    val name = uploadName(request.headers["x-file-name"])
    val body = BodyStream(input, length) { received ->
      throttled { showStatus(string(R.string.net_status_receiving, formatCount(received), formatCount(length))) }
    }
    val saved = try {
      UploadFolder.save(name, body)
    } catch (e: Exception) {
      if (stopped) return
      Log.w("xTravel", "Upload not saved", e)
      showStatus(string(R.string.net_status_failed))
      runCatching { body.drain() }
      respond(out, "500 Internal Server Error", TEXT_TYPE, string(R.string.net_page_not_saved, e.message.orEmpty()))
      return
    }
    val place = "${UploadFolder.label(UploadFolder.tree()).orEmpty()}/$saved"
    respond(out, "200 OK", TEXT_TYPE, string(R.string.net_page_saved, place))
    showStatus(string(R.string.net_status_saved, saved, formatCount(length)))
  }

  /** Имя файла из заголовка без каталогов; без имени - upload */
  private fun uploadName(header: String?): String {
    val decoded = header?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()
    val name = decoded.substringAfterLast('/').substringAfterLast('\\').trim()
    return if (name.isEmpty() || name == "." || name == "..") "upload" else name
  }

  private fun page(): String {
    fun html(@StringRes id: Int) = string(id).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun js(@StringRes id: Int) = "\"" + string(id).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    return """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>xTravel</title><style>
body{font-family:sans-serif;max-width:40em;margin:2em auto;padding:0 1em;color:#222}
.g{display:flex;align-items:center;gap:1em;border:1px solid #bbb;border-radius:8px;padding:1em;margin:1em 0}
.i{flex:1;min-width:0}.i h3{margin:0 0 .4em}.i p{margin:.4em 0}
.a{flex:0 0 9em}
a.b,button{display:block;width:100%;box-sizing:border-box;text-align:center;padding:.7em 1em;font-size:1em;background:#1565c0;
color:#fff;border:0;border-radius:6px;text-decoration:none;cursor:pointer}
button:disabled{background:#999}
label.c{display:inline-block;padding:.4em 1em;border:1px solid #1565c0;border-radius:6px;color:#1565c0;cursor:pointer}
#n{margin-left:.5em;word-break:break-all}progress{width:100%}
</style></head><body>
<h2>${html(R.string.net_page_title)}</h2>
<div class="g"><div class="i"><h3>${html(R.string.net_page_download_title)}</h3><p>${html(R.string.net_page_download_hint)}</p></div>
<div class="a"><a class="b" href="/download/${XTravelShare.fileName()}.$EXCHANGE_EXT">${html(R.string.net_page_download)}</a></div></div>
<div class="g"><div class="i"><h3>${html(R.string.net_page_upload)}</h3><p>${html(R.string.net_page_upload_hint)}</p>
<p><label class="c"><input type="file" id="f" hidden onchange="pick()">${html(R.string.net_page_choose)}</label>
<span id="n">${html(R.string.net_page_no_file)}</span></p>
<progress id="p" max="1" value="0" hidden></progress><p id="m"></p></div>
<div class="a"><button id="b" onclick="up()">${html(R.string.net_page_send)}</button></div></div>
<script>
function pick(){
var f=document.getElementById('f').files[0];
document.getElementById('n').textContent=f?f.name:${js(R.string.net_page_no_file)};
}
function up(){
var f=document.getElementById('f').files[0];if(!f)return;
var p=document.getElementById('p'),m=document.getElementById('m'),b=document.getElementById('b');
var x=new XMLHttpRequest();x.open('POST','/upload');x.setRequestHeader('X-File-Name',encodeURIComponent(f.name));
p.hidden=false;p.value=0;b.disabled=true;m.textContent=${js(R.string.net_page_sending)};
x.upload.onprogress=function(e){if(e.lengthComputable)p.value=e.loaded/e.total};
x.onload=function(){b.disabled=false;m.textContent=x.responseText};
x.onerror=function(){b.disabled=false;m.textContent=${js(R.string.net_page_failed)}};
x.send(f);
}
</script></body></html>"""
  }

  private inline fun throttled(show: () -> Unit) {
    val now = SystemClock.uptimeMillis()
    if (now - shownAt < STATUS_PERIOD_MS) return
    shownAt = now
    show()
  }

  private fun showStatus(text: String) {
    main.post { status = text }
  }

  /** Строки по языку активити: у контекста приложения язык, выбранный в приложении, может не действовать */
  private fun string(@StringRes id: Int, vararg args: Any): String = AppSession.context.getString(id, *args)

  private fun <T> onMain(block: () -> T): T {
    val result = CompletableFuture<T>()
    main.post { runCatching(block).fold(result::complete, result::completeExceptionally) }
    return result.get()
  }
}

/** FOR LOCAL USE
    Тело запроса длиной length поверх потока соединения. Закрытие соединение не закрывает:
    его закрывает распаковщик, а после тела ещё пишется ответ.
*/
private class BodyStream(private val source: InputStream, val length: Long, private val onRead: (Long) -> Unit) : InputStream() {
  var received = 0L
    private set

  override fun read(): Int {
    if (received >= length) return -1
    val byte = source.read()
    if (byte >= 0) count(1)
    return byte
  }

  override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
    if (received >= length) return -1
    val read = source.read(buffer, offset, minOf(size.toLong(), length - received).toInt())
    if (read > 0) count(read)
    return read
  }

  override fun close() {}

  /** Дочитывает тело до конца, чтобы ответ не ушёл, пока браузер ещё отправляет */
  fun drain() {
    val buffer = ByteArray(64 * 1024)
    while (read(buffer, 0, buffer.size) >= 0) continue
  }

  private fun count(bytes: Int) {
    received += bytes
    onRead(received)
  }
}

/** FOR LOCAL USE: счётчик отданных байтов поверх потока соединения */
private class CountingOutputStream(out: OutputStream, private val onWrite: (Long) -> Unit) : FilterOutputStream(out) {
  var count = 0L
    private set

  override fun write(b: Int) {
    out.write(b)
    count(1)
  }

  override fun write(b: ByteArray, off: Int, len: Int) {
    out.write(b, off, len)
    count(len)
  }

  private fun count(bytes: Int) {
    count += bytes
    onWrite(count)
  }
}
