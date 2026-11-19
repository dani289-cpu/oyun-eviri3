package com.oyunceviri.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("oyun_ceviri_prefs", Context.MODE_PRIVATE) }
    private val netExecutor = Executors.newSingleThreadExecutor()

    private lateinit var etApiKey: EditText
    private lateinit var spinnerModel: Spinner
    private lateinit var btnFetchModels: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private val defaultModels = mutableListOf(
        "gemini-flash-lite-latest",
        "gemini-flash-latest",
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash"
    )

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val apiKey = etApiKey.text.toString().trim()
            val selectedModel = spinnerModel.selectedItem?.toString()?.trim() ?: "gemini-flash-lite-latest"

            if (apiKey.isEmpty()) {
                tvStatus.text = "Lütfen önce API anahtarını gir."
                return@registerForActivityResult
            }

            prefs.edit()
                .putString("api_key", apiKey)
                .putString("selected_model", selectedModel)
                .apply()

            startOverlayWithProjection(result.resultCode, result.data!!, apiKey, selectedModel)
        } else {
            tvStatus.text = "Ekran yakalama izni verilmedi."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101216"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 56, 40, 56)
            gravity = Gravity.TOP
        }

        val title = TextView(this).apply {
            text = "Oyun Çeviri"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }

        val subtitle = TextView(this).apply {
            text = "API anahtarını gir, modeli seç ve ekran çevirisini başlat."
            textSize = 14f
            setTextColor(Color.parseColor("#A0A8B8"))
            setPadding(0, 0, 0, 28)
        }

        val keyLabel = TextView(this).apply {
            text = "Gemini API Anahtarı"
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 0, 0, 8)
        }

        etApiKey = EditText(this).apply {
            hint = "AQ... veya AIza... ile başlayan anahtar"
            setText(prefs.getString("api_key", "") ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1F29"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#2E3748"))
            }
        }

        val spacer1 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) }

        val modelLabel = TextView(this).apply {
            text = "Model Seçimi"
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 0, 0, 8)
        }

        val savedModel = prefs.getString("selected_model", "gemini-flash-lite-latest") ?: "gemini-flash-lite-latest"
        if (!defaultModels.contains(savedModel)) {
            defaultModels.add(0, savedModel)
        }

        spinnerModel = Spinner(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1F29"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#2E3748"))
            }
            setPadding(20, 18, 20, 18)
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                defaultModels
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val pos = defaultModels.indexOf(savedModel)
            if (pos >= 0) setSelection(pos)
        }

        val spacer2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 18) }

        btnFetchModels = Button(this).apply {
            text = "Hesaptaki Modelleri Getir"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#263248"))
                cornerRadius = 20f
            }
            setPadding(24, 22, 24, 22)
            setOnClickListener { fetchModelsFromGoogle() }
        }

        val spacer3 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 28) }

        btnStart = Button(this).apply {
            text = "Çeviriyi Başlat"
            setTextColor(Color.WHITE)
            textSize = 16f
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#16A34A"))
                cornerRadius = 22f
            }
            setPadding(24, 28, 24, 28)
            setOnClickListener { checkPermissionsAndStart() }
        }

        val spacer4 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 16) }

        btnStop = Button(this).apply {
            text = "Çeviriyi Durdur"
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#991B1B"))
                cornerRadius = 22f
            }
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                tvStatus.text = "Çeviri durduruldu."
            }
        }

        val spacer5 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) }

        tvStatus = TextView(this).apply {
            text = "Hazır."
            textSize = 14f
            setTextColor(Color.parseColor("#66FF99"))
        }

        container.addView(title)
        container.addView(subtitle)
        container.addView(keyLabel)
        container.addView(etApiKey)
        container.addView(spacer1)
        container.addView(modelLabel)
        container.addView(spinnerModel)
        container.addView(spacer2)
        container.addView(btnFetchModels)
        container.addView(spacer3)
        container.addView(btnStart)
        container.addView(spacer4)
        container.addView(btnStop)
        container.addView(spacer5)
        container.addView(tvStatus)

        root.addView(container)
        setContentView(root)
    }

    private fun fetchModelsFromGoogle() {
        val key = etApiKey.text.toString().trim()
        if (key.isEmpty()) {
            tvStatus.text = "Önce API anahtarını yaz."
            return
        }

        tvStatus.text = "Modeller kontrol ediliyor..."
        netExecutor.execute {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("x-goog-api-key", key)
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                if (conn.responseCode !in 200..299) {
                    runOnUiThread {
                        tvStatus.text = "Model listesi alınamadı (Kod: ${conn.responseCode})"
                    }
                    return@execute
                }

                val body = conn.inputStream.bufferedReader().readText()
                val arr = JSONObject(body).optJSONArray("models")
                val found = mutableListOf<String>()

                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val name = item.optString("name", "").removePrefix("models/")
                        val methods = item.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                        if (methods.contains("generateContent") && name.contains("gemini") &&
                            !name.contains("tts") && !name.contains("image") && !name.contains("embedding")
                        ) {
                            found.add(name)
                        }
                    }
                }

                if (found.isNotEmpty()) {
                    found.sortBy { if (it.contains("lite")) 0 else if (it.contains("flash")) 1 else 2 }
                    runOnUiThread {
                        defaultModels.clear()
                        defaultModels.addAll(found)
                        spinnerModel.adapter = ArrayAdapter(
                            this@MainActivity,
                            android.R.layout.simple_spinner_dropdown_item,
                            defaultModels
                        )
                        val liteIndex = defaultModels.indexOfFirst { it.contains("flash-lite") }
                        if (liteIndex >= 0) spinnerModel.setSelection(liteIndex)
                        tvStatus.text = "${found.size} çalışan model bulundu."
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "Uygun model bulunamadı, varsayılanlar duruyor."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Bağlantı hatası: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val key = etApiKey.text.toString().trim()
        if (key.isEmpty()) {
            tvStatus.text = "Lütfen API anahtarını gir."
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            tvStatus.text = "Önce 'Diğer uygulamaların üzerinde gösterme' iznini aç."
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun startOverlayWithProjection(resultCode: Int, data: Intent, apiKey: String, model: String) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("API_KEY", apiKey)
            putExtra("MODEL", model)
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        tvStatus.text = "Çeviri başlatıldı ($model). Oyuna geçebilirsin."
    }

    override fun onDestroy() {
        netExecutor.shutdownNow()
        super.onDestroy()
    }
}
