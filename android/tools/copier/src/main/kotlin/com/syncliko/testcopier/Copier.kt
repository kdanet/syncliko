package com.syncliko.testcopier

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** A plain app with selectable text and a "Copy" button, like any app a user copies in. */
class CopierActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply { setText(intent.getStringExtra("text") ?: "Text from the copier app") }
        val selectable = TextView(this).apply {
            text = "Selectable: long-press me and choose Copy"
            textSize = 22f
            setTextIsSelectable(true)
            setPadding(0, 48, 0, 48)
        }
        val button = Button(this).apply {
            text = "Copy"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("copier", field.text))
                Toast.makeText(this@CopierActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
            addView(field)
            addView(selectable)
            addView(button)
        })
    }
}

/** `adb shell am broadcast -n com.syncliko.testcopier/.CopyReceiver --es text "…"` copies from the background. */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("copier", text))
    }
}
