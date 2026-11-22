package com.randaladams.bingo2playlist

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Full-size JPEGs kept natively (by id) so big base64 strings never cross into JS. */
    private val jpegs = ConcurrentHashMap<String, ByteArray>()

    private var cameraUri: Uri? = null
    private var continuousCamera = false
    private var pendingSave: Pair<String, String>? = null
    private var pageReady = false
    private val pendingShared = mutableListOf<Uri>()

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> uris.forEach { processUri(it) } }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) {
            processUri(uri)
            if (continuousCamera) main.postDelayed({ launchCamera() }, 350) else js("window.onCameraDone && onCameraDone()")
        } else {
            continuousCamera = false
            js("window.onCameraDone && onCameraDone()")
        }
    }

    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/*")) { uri ->
        val p = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(p.second.toByteArray(Charsets.UTF_8)) }
            toast("Saved ${p.first}")
        } catch (e: Exception) {
            toast("Couldn't save: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                openExternal(u)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                synchronized(pendingShared) { pendingShared.forEach { processUri(it) }; pendingShared.clear() }
            }
        }
        setContentView(web)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.onBack && onBack()) ? 1 : 0") { r ->
                    if (r != "1") { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /** Photos shared into the app from the gallery or camera app. */
    @Suppress("DEPRECATION")
    private fun handleShareIntent(i: Intent?) {
        if (i == null) return
        val uris = mutableListOf<Uri>()
        if (i.action == Intent.ACTION_SEND) {
            i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        } else if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) return
        if (pageReady) uris.forEach { processUri(it) } else synchronized(pendingShared) { pendingShared.addAll(uris) }
    }

    private fun launchCamera() {
        try {
            val f = File(cacheDir, "cam-${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            cameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            continuousCamera = false
            toast("No camera app available")
            js("window.onCameraDone && onCameraDone()")
        }
    }

    /** Decode, shrink, run on-device OCR, then hand the result to the page. */
    private fun processUri(uri: Uri) {
        val id = UUID.randomUUID().toString().substring(0, 8)
        js("window.onImageStart && onImageStart(${JSONObject.quote(id)})")
        io.execute {
            try {
                val src = ImageDecoder.createSource(contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width; val h = info.size.height
                    val max = 2000
                    val sc = minOf(1f, max.toFloat() / maxOf(w, h))
                    if (sc < 1f) dec.setTargetSize((w * sc).toInt(), (h * sc).toInt())
                }
                val big = ByteArrayOutputStream()
                scaled(bmp, 1600).compress(Bitmap.CompressFormat.JPEG, 85, big)
                jpegs[id] = big.toByteArray()
                val th = ByteArrayOutputStream()
                scaled(bmp, 160).compress(Bitmap.CompressFormat.JPEG, 70, th)
                val thumb = "data:image/jpeg;base64," + Base64.encodeToString(th.toByteArray(), Base64.NO_WRAP)

                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { text ->
                        val lines = JSONArray()
                        for (b in text.textBlocks) for (l in b.lines) {
                            val r = l.boundingBox ?: continue
                            lines.put(JSONObject().put("t", l.text).put("x", r.left).put("y", r.top)
                                .put("w", r.width()).put("h", r.height()))
                        }
                        val o = JSONObject().put("id", id).put("thumb", thumb)
                            .put("w", bmp.width).put("h", bmp.height).put("lines", lines)
                        js("window.onImage && onImage($o)")
                    }
                    .addOnFailureListener { e -> jsError(id, "Text reading failed: ${e.message}") }
            } catch (e: Exception) {
                jsError(id, "Couldn't open photo: ${e.message}")
            }
        }
    }

    private fun scaled(b: Bitmap, max: Int): Bitmap {
        val sc = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
        return if (sc >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * sc).toInt(), (b.height * sc).toInt(), true)
    }

    private fun jsError(id: String, msg: String) =
        js("window.onImageError && onImageError(${JSONObject.quote(id)}, ${JSONObject.quote(msg)})")

    private fun js(code: String) = main.post { web.evaluateJavascript(code, null) }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun openExternal(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { toast("No app can open this link") }
    }

    private fun writeCache(name: String, text: String): Uri {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, name.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
        f.writeText(text, Charsets.UTF_8)
        return FileProvider.getUriForFile(this, "$packageName.files", f)
    }

    inner class Bridge {
        @JavascriptInterface fun pickImages() = main.post {
            pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        /** continuous = reopen the camera after each shot until the user backs out. */
        @JavascriptInterface fun takePhoto(continuous: Boolean) = main.post {
            continuousCamera = continuous
            launchCamera()
        }

        @JavascriptInterface fun stopCamera() { continuousCamera = false }

        @JavascriptInterface fun forget(id: String) { jpegs.remove(id) }

        @JavascriptInterface fun share(filename: String, mime: String, text: String, subject: String) = main.post {
            try {
                val uri = writeCache(filename, text)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, if (text.length < 60000) text else subject)
                    clipData = ClipData.newRawUri(filename, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share playlist"))
            } catch (e: Exception) { toast("Couldn't share: ${e.message}") }
        }

        @JavascriptInterface fun save(filename: String, text: String) = main.post {
            pendingSave = filename to text
            saveDoc.launch(filename)
        }

        @JavascriptInterface fun copy(text: String) = main.post {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Playlist", text))
            if (Build.VERSION.SDK_INT < 33) toast("Copied")
        }

        @JavascriptInterface fun open(url: String) = main.post { openExternal(Uri.parse(url)) }

        @JavascriptInterface fun version(): String =
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""

        /** Send one stored photo to the Claude API. Result arrives at window.onClaude(id, ok, text). */
        @JavascriptInterface fun claudeRead(id: String, apiKey: String, model: String, prompt: String) {
            io.execute {
                val img = jpegs[id]
                if (img == null) { js("onClaude(${JSONObject.quote(id)}, false, ${JSONObject.quote("Photo no longer in memory — add it again")})"); return@execute }
                try {
                    val body = JSONObject()
                        .put("model", model)
                        .put("max_tokens", 4000)
                        .put("messages", JSONArray().put(JSONObject()
                            .put("role", "user")
                            .put("content", JSONArray()
                                .put(JSONObject().put("type", "image").put("source", JSONObject()
                                    .put("type", "base64").put("media_type", "image/jpeg")
                                    .put("data", Base64.encodeToString(img, Base64.NO_WRAP))))
                                .put(JSONObject().put("type", "text").put("text", prompt)))))
                    val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 20000; c.readTimeout = 120000
                    c.doOutput = true
                    c.setRequestProperty("content-type", "application/json")
                    c.setRequestProperty("x-api-key", apiKey.trim())
                    c.setRequestProperty("anthropic-version", "2023-06-01")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = c.responseCode
                    val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                    if (code !in 200..299) {
                        val msg = try { JSONObject(resp).getJSONObject("error").getString("message") } catch (e: Exception) { "HTTP $code" }
                        js("onClaude(${JSONObject.quote(id)}, false, ${JSONObject.quote("Claude: $msg")})")
                        return@execute
                    }
                    val content = JSONObject(resp).getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val part = content.getJSONObject(i)
                        if (part.optString("type") == "text") sb.append(part.optString("text"))
                    }
                    js("onClaude(${JSONObject.quote(id)}, true, ${JSONObject.quote(sb.toString())})")
                } catch (e: Exception) {
                    js("onClaude(${JSONObject.quote(id)}, false, ${JSONObject.quote("Network error: ${e.message}")})")
                }
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
