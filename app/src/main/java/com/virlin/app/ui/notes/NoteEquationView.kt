package com.virlin.app.ui.notes

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.virlin.app.domain.notedoc.NoteMath
import org.json.JSONObject

/** Offline typesetting only. TeX stays in the note model; this WebView never loads remote content. */
@Composable
internal fun NoteEquationView(source: String, modifier: Modifier = Modifier) {
    var height by remember(source) { mutableIntStateOf(72) }
    val html = remember(source) {
        val escaped = JSONObject.quote(NoteMath.renderSource(source))
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src file: 'unsafe-inline'; style-src file: 'unsafe-inline'; font-src file:">
        <link rel="stylesheet" href="katex.min.css"><style>
        html,body{margin:0;padding:0;background:transparent;color:#17231d;overflow-x:auto;overflow-y:hidden}
        #math{min-height:42px;padding:10px 4px;box-sizing:border-box;width:max-content;min-width:100%}
        .katex-display{margin:0;text-align:left}.katex-display>.katex{white-space:nowrap}
        </style><script src="katex.min.js"></script></head><body><div id="math"></div><script>
        try{katex.render($escaped,document.getElementById('math'),{displayMode:true,throwOnError:false,trust:false,strict:'warn'});}
        catch(e){document.getElementById('math').textContent=$escaped;}
        </script></body></html>""".trimIndent()
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setSupportZoom(false)
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("document.getElementById('math').scrollHeight.toString()") {
                            val measured = it.trim('"').toIntOrNull()
                            if (measured != null) height = measured.coerceIn(48, 4000)
                        }
                    }
                }
            }
        },
        update = { view ->
            if (view.tag != source) {
                view.tag = source
                view.loadDataWithBaseURL("file:///android_asset/katex/", html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier.fillMaxWidth().height(height.dp),
    )
}
