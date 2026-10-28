package com.sachin.chhotu

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saari yaadein dikhana + export. Yahan delete ka koi button nahi hai. */
class MemoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val store = MemoryStore.get(this)
        val d = { v: Int -> Ui.dp(this, v) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(18), d(18), d(18), d(30)) }
        col.addView(Ui.text(this, "छोटू की यादें", 24f, Ui.TEXT, true))
        col.addView(Ui.text(this, "कुल ${store.count()} यादें। ये कभी अपने-आप नहीं मिटतीं। बैकअप फ़ाइल Downloads/Chhotu में जाती है।", 13f, Ui.MUTED).apply { setPadding(0, d(6), 0, d(12)) })
        col.addView(Ui.button(this, "📤 बैकअप Export करो").apply {
            setOnClickListener {
                val where = store.exportToDownloads()
                AlertDialog.Builder(this@MemoryActivity).setMessage("बैकअप: $where").setPositiveButton("ठीक", null).show()
            }
        })

        val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale("hi", "IN"))
        val label = mapOf("fact" to "📌 याद", "chat_user" to "आप", "chat_chhotu" to "छोटू", "action" to "⚡ काम")
        val all = store.all()
        val facts = all.filter { it.type == "fact" }
        if (facts.isNotEmpty()) {
            col.addView(Ui.text(this, "पक्की यादें", 13f, Ui.ACCENT, true).apply { setPadding(0, d(20), 0, d(6)) })
            for (m in facts.reversed()) col.addView(card(m.text, fmt.format(Date(m.time))))
        }
        col.addView(Ui.text(this, "बातचीत और काम", 13f, Ui.ACCENT, true).apply { setPadding(0, d(20), 0, d(6)) })
        for (m in all.filter { it.type != "fact" }.reversed().take(400)) {
            col.addView(card(m.text, "${label[m.type] ?: m.type} · ${fmt.format(Date(m.time))}"))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    private fun card(text: String, meta: String): LinearLayout {
        val d = { v: Int -> Ui.dp(this, v) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Ui.CARD, d(14).toFloat())
            setPadding(d(14), d(10), d(14), d(10))
            addView(Ui.text(this@MemoryActivity, meta, 11f, Ui.MUTED))
            addView(Ui.text(this@MemoryActivity, text, 15f, Ui.TEXT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = d(8) }
        }
    }
}
