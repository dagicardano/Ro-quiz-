package com.roquiz.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var chooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExtractId: String? = null
    private val pickCode = 2401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                chooserCallback?.onReceiveValue(null)
                chooserCallback = callback
                launchPicker()
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "RoQuizNative")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("if(window.roquizBack){window.roquizBack()}else{window.RoQuizNative.exitApp()}", null)
            }
        })
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint", "image/jpeg", "image/png"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        startActivityForResult(intent, pickCode)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickCode) {
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            val callbackId = pendingExtractId
            pendingExtractId = null
            if (callbackId != null) {
                if (uri != null) processDocument(uri, callbackId) else sendResult(callbackId, JSONObject().put("ok", false).put("error", "No file selected"))
            } else {
                chooserCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
                chooserCallback = null
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface fun pickDocument(callbackId: String) {
            runOnUiThread {
                pendingExtractId = callbackId
                launchPicker()
            }
        }
        @JavascriptInterface fun shareText(title: String, body: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                startActivity(Intent.createChooser(send, "Export from RoQuiz"))
            }
        }
        @JavascriptInterface fun exitApp() { runOnUiThread { finish() } }
    }

    private fun processDocument(uri: Uri, callbackId: String) {
        Thread {
            try {
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else "Document"
                } ?: "Document"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open file")
                val ext = name.substringAfterLast('.', "").lowercase()
                val result = when (ext) {
                    "pdf" -> extractPdf(bytes)
                    "pptx" -> extractPptx(bytes)
                    "ppt" -> error("Legacy .ppt files are not supported yet. Save the presentation as .pptx and import it again.")
                    "jpg", "jpeg", "png" -> extractImage(bytes)
                    else -> error("Unsupported file. Choose PDF, PPTX, JPG or PNG.")
                }
                sendResult(callbackId, JSONObject().put("name", name).put("text", result).put("ok", true))
            } catch (e: Exception) {
                sendResult(callbackId, JSONObject().put("ok", false).put("error", e.message ?: "Could not read this file"))
            }
        }.start()
    }

    private fun sendResult(callbackId: String, payload: JSONObject) {
        val safeId = JSONObject.quote(callbackId)
        runOnUiThread {
            web.evaluateJavascript("window.onRoQuizExtract && window.onRoQuizExtract($safeId, ${JSONObject.quote(payload.toString())})", null)
        }
    }

    private fun extractPdf(bytes: ByteArray): String {
        PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
            val out = StringBuilder()
            for (p in 1..doc.numberOfPages) {
                val stripper = PDFTextStripper().apply { startPage = p; endPage = p }
                out.append("\n\n[Page $p]\n").append(stripper.getText(doc))
            }
            return out.toString().trim().ifBlank { "No selectable text found in this PDF. Scanned-PDF OCR is not supported yet." }
        }
    }

    private fun extractPptx(bytes: ByteArray): String {
        val slides = mutableListOf<Pair<Int, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val match = Regex("ppt/slides/slide(\\d+)\\.xml").matchEntire(entry.name)
                if (match != null) {
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(ByteArrayInputStream(zip.readBytes()), "UTF-8")
                    val text = StringBuilder()
                    var event = parser.eventType
                    while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "t") {
                            val value = parser.nextText()
                            if (value.isNotBlank()) text.append(value).append(' ')
                        }
                        event = parser.eventType
                    }
                    slides.add(match.groupValues[1].toInt() to text.toString().trim())
                }
                zip.closeEntry()
            }
        }
        return slides.sortedBy { it.first }.joinToString("\n\n") { "[Slide ${it.first}]\n${it.second}" }
            .ifBlank { "No slide text found in this presentation." }
    }

    private fun extractImage(bytes: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Could not decode image")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val latch = CountDownLatch(1)
        var text = ""
        var failure: Exception? = null
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text = it.text }
            .addOnFailureListener { failure = it }
            .addOnCompleteListener { latch.countDown() }
        if (!latch.await(45, TimeUnit.SECONDS)) {
            recognizer.close()
            error("OCR timed out")
        }
        recognizer.close()
        failure?.let { throw it }
        return text.ifBlank { "No readable text detected in this image." }
    }
}
