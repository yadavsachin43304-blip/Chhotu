package com.sachin.chhotu

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saari yaadein dikhana + export. Yahan delete ka koi button nahi hai. */
class MemoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "छोटू की यादें"
        val store = MemoryStore.get(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        col.addView(TextView(this).apply {
            text = "कुल यादें: ${store.count()}। ये कभी अपने-आप नहीं मिटतीं। बैकअप के लिए नीचे Export दबाओ, फ़ाइल Downloads/Chhotu में जाएगी।"
            textSize = 14f
        })
        col.addView(Button(this).apply {
            text = "📤 Export (JSON)"
            setOnClickListener {
                val where = store.exportToDownloads()
                AlertDialog.Builder(this@MemoryActivity).setMessage("बैकअप: $where").setPositiveButton("ठीक", null).show()
            }
        })

        val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale("hi", "IN"))
        val label = mapOf("fact" to "📌 याद", "chat_user" to "🧑 आप", "chat_chhotu" to "🤖 छोटू", "action" to "⚡ काम")
        for (m in store.all().reversed().take(500)) {
            col.addView(TextView(this).apply {
                text = "${label[m.type] ?: m.type} · ${fmt.format(Date(m.time))}\n${m.text}"
                textSize = 14f
                setPadding(0, pad, 0, 0)
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
