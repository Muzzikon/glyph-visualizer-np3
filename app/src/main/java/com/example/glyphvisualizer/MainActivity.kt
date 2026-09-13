package com.example.glyphvisualizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private var isRunning = false
    private var isSilentDemoMode = false
    private var testModeState = 0
    private var trainingGenreContext: Int = 2 // 0: Chill, 1: Rock/Metal, 2: Dubstep/Tearout

    private lateinit var btnToggle: Button
    private lateinit var btnSilentMode: Button
    private lateinit var btnSelectApps: Button
    private lateinit var btnPickFiles: Button
    private lateinit var btnPickFolder: Button
    private lateinit var btnPrevPattern: Button
    private lateinit var btnNextPattern: Button
    private lateinit var btnAutoPattern: Button
    private lateinit var statusText: TextView
    private lateinit var trainingProgressText: TextView
    private lateinit var patternNameText: TextView
    private lateinit var tensionValText: TextView
    private lateinit var freqStatsText: TextView
    private lateinit var debugDataText: TextView // НОВОЕ ТЕКСТОВОЕ ПОЛЕ ДЛЯ ТЕЛЕМЕТРИИ
    private lateinit var progressTension: ProgressBar
    private lateinit var glyphPreview: GlyphPreviewView
    private lateinit var controlsPanel: View

    private val pickAudioFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) processTrackUrisStreamingSafe(uris)
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        if (treeUri != null) scanFolderStreamingSafe(treeUri)
    }

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) toggleService()
        else Toast.makeText(this, "Audio permission is required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        btnSilentMode = findViewById(R.id.btnSilentMode)
        btnSelectApps = findViewById(R.id.btnSelectApps)
        btnPickFiles = findViewById(R.id.btnPickFiles)
        btnPickFolder = findViewById(R.id.btnPickFolder)
        btnPrevPattern = findViewById(R.id.btnPrevPattern)
        btnNextPattern = findViewById(R.id.btnNextPattern)
        btnAutoPattern = findViewById(R.id.btnAutoPattern)
        statusText = findViewById(R.id.statusText)
        trainingProgressText = findViewById(R.id.trainingProgressText)
        patternNameText = findViewById(R.id.patternNameText)
        tensionValText = findViewById(R.id.tensionValText)
        freqStatsText = findViewById(R.id.freqStatsText)
        debugDataText = findViewById(R.id.debugDataText) // ИНИЦИАЛИЗАЦИЯ
        progressTension = findViewById(R.id.progressTension)
        glyphPreview = findViewById(R.id.glyphPreview)
        controlsPanel = findViewById(R.id.controlsPanel)

        loadProfileInfo()

        btnToggle.setOnClickListener { checkPermissionsAndToggle() }
        btnPickFiles.setOnClickListener {
            showTrainingGenreDialog { pickAudioFilesLauncher.launch(arrayOf("audio/*")) }
        }
        btnPickFolder.setOnClickListener {
            showTrainingGenreDialog { pickFolderLauncher.launch(null) }
        }

        btnSilentMode.visibility = View.GONE

        btnSelectApps.setOnClickListener { showSupportedAppsDialog() }

        glyphPreview.setOnClickListener {
            controlsPanel.visibility = if (controlsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnPrevPattern.setOnClickListener { sendServiceAction("PREV_PATTERN") }
        btnNextPattern.setOnClickListener { sendServiceAction("NEXT_PATTERN") }

        btnAutoPattern.setOnClickListener {
            testModeState = (testModeState + 1) % 3
            when (testModeState) {
                0 -> {
                    btnAutoPattern.text = "AUTO"
                    btnAutoPattern.setBackgroundColor(getColor(android.R.color.holo_blue_bright))
                    sendServiceAction("MODE_AUTO")
                    Toast.makeText(this, "Mode: Smart Auto-Analysis", Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    btnAutoPattern.text = "TEST: CALM"
                    btnAutoPattern.setBackgroundColor(getColor(android.R.color.holo_green_light))
                    sendServiceAction("MODE_FORCE_CALM")
                    Toast.makeText(this, "Test: Calm Arsenal", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    btnAutoPattern.text = "TEST: DROP"
                    btnAutoPattern.setBackgroundColor(getColor(android.R.color.holo_red_light))
                    sendServiceAction("MODE_FORCE_DROP")
                    Toast.makeText(this, "Test: Combat Drop", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnPickFiles.setOnLongClickListener {
            val prefs = getSharedPreferences("glyph_profile", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            loadProfileInfo()
            Toast.makeText(this, "Knowledge Base Cleared", Toast.LENGTH_SHORT).show()
            true
        }

        // --- ПЕРЕДАЕМ ТЕЛЕМЕТРИЮ ИЗ SERVICE В UI ---
        GlyphVisualizerService.liveFrameListener = { frame, patternName, tension, b, m, h, telemetry ->
            runOnUiThread {
                glyphPreview.updateLeds(frame)
                patternNameText.text = patternName
                tensionValText.text = String.format("%.2f", tension)
                progressTension.progress = (tension * 100).toInt().coerceIn(0, 100)
                freqStatsText.text = "SUB: ${b.toInt()} | GROWL: ${m.toInt()} | HIGH: ${h.toInt()}"

                // ВЫВОДИМ ОТЛАДОЧНУЮ ИНФУ НА ЭКРАН
                debugDataText.text = telemetry
            }
        }
    }

    private fun showSupportedAppsDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolved = pm.queryIntentActivities(mainIntent, 0)
        data class AppEntry(val name: String, val packageName: String)

        val installedApps = resolved.map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }.filter { it.packageName != packageName }.sortedBy { it.name.lowercase() }

        if (installedApps.isEmpty()) { Toast.makeText(this, "No apps found", Toast.LENGTH_SHORT).show(); return }

        val appNames = installedApps.map { it.name }.toTypedArray()
        val prefs = getSharedPreferences("glyph_profile", Context.MODE_PRIVATE)
        val selectedPackages = prefs.getStringSet("whitelisted_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        val checkedItems = BooleanArray(installedApps.size) { i -> selectedPackages.contains(installedApps[i].packageName) }

        AlertDialog.Builder(this).setTitle("Player Whitelist")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                val pkg = installedApps[which].packageName
                if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
            }.setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet("whitelisted_apps", selectedPackages).apply()
                sendServiceAction("RELOAD_APPS")
                checkNotificationListenerPermission()
                Toast.makeText(this, "Selected: ${selectedPackages.size}", Toast.LENGTH_SHORT).show()
            }.setNeutralButton("Reset All") { _, _ ->
                prefs.edit().remove("whitelisted_apps").apply()
                sendServiceAction("RELOAD_APPS")
                Toast.makeText(this, "Filter disabled", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showTrainingGenreDialog(onProceed: () -> Unit) {
        val genres = arrayOf(
            "🌸 СПОКОЙНАЯ (Chill / Ambient / Lo-Fi)",
            "🎸 ГИБРИДНАЯ (Live Drums / Metal / Trap)",
            "💀 АГРЕССИВНАЯ (Tearout / Riddim / Bass)"
        )
        var selected = trainingGenreContext

        AlertDialog.Builder(this)
            .setTitle("Обучение Матрицы. Выберите тип музыки:")
            .setSingleChoiceItems(genres, selected) { _, which -> selected = which }
            .setPositiveButton("Загрузить (Analyze)") { _, _ ->
                trainingGenreContext = selected
                onProceed()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun checkNotificationListenerPermission() {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (enabledListeners == null || !enabledListeners.contains(packageName)) {
            AlertDialog.Builder(this).setTitle("Notification Access")
                .setMessage("Please grant notification access to detect the active media player.")
                .setPositiveButton("Enable") { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .setNegativeButton("Later", null).show()
        }
    }

    private fun scanFolderStreamingSafe(treeUri: Uri) {
        btnPickFiles.isEnabled = false; btnPickFolder.isEnabled = false
        trainingProgressText.text = "Scanning for media files..."

        thread {
            val audioUris = mutableListOf<Uri>()
            try {
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                    while (cursor.moveToNext()) {
                        val mime = cursor.getString(mimeCol) ?: ""
                        val name = cursor.getString(nameCol)?.lowercase() ?: ""
                        if (mime.startsWith("audio/") || name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac") || name.endsWith(".m4a") || name.endsWith(".ogg")) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                            audioUris.add(fileUri)
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            if (audioUris.isNotEmpty()) processTrackUrisStreamingSafe(audioUris)
            else runOnUiThread { btnPickFiles.isEnabled = true; btnPickFolder.isEnabled = true; trainingProgressText.text = "No audio files found" }
        }
    }

    private class WelfordAccumulator {
        var count = 0.0; var mean = 0.0; var m2 = 0.0
        fun update(value: Double) {
            count += 1.0
            val delta = value - mean
            mean += delta / count
            m2 += delta * (value - mean)
        }
        fun getVariance(): Double = if (count > 1) m2 / (count - 1) else 0.0
        fun getStdDev(): Double = sqrt(max(0.0, getVariance()))
    }

    private data class TrackSpectralProfile(val peakSub: Float, val floorSub: Float, val growlRatio: Float)

    private fun processTrackUrisStreamingSafe(uris: List<Uri>) {
        btnPickFiles.isEnabled = false; btnPickFolder.isEnabled = false
        val prefix = arrayOf("chill_", "band_", "edm_")[trainingGenreContext]
        val labelName = arrayOf("CHILL", "ROCK", "TEAROUT")[trainingGenreContext]

        thread {
            val prefs = getSharedPreferences("glyph_profile", Context.MODE_PRIVATE)
            val subPeakStat = WelfordAccumulator().apply {
                count = prefs.getInt("${prefix}count", 0).toDouble()
                mean = prefs.getFloat("${prefix}mean_p", if(prefix=="chill_") 14f else 28f).toDouble()
                m2 = prefs.getFloat("${prefix}m2_p", 64f).toDouble()
            }
            val subFloorStat = WelfordAccumulator().apply {
                count = subPeakStat.count
                mean = prefs.getFloat("${prefix}mean_f", if(prefix=="chill_") 1.0f else 2.2f).toDouble()
                m2 = prefs.getFloat("${prefix}m2_f", 4.0f).toDouble()
            }
            val growlStat = WelfordAccumulator().apply {
                count = subPeakStat.count
                mean = prefs.getFloat("${prefix}mean_g", if(prefix=="edm_") 1.4f else 0.8f).toDouble()
                m2 = prefs.getFloat("${prefix}m2_g", 0.5f).toDouble()
            }

            val checkpointsFractions = doubleArrayOf(0.05, 0.12, 0.18, 0.25, 0.32, 0.40, 0.48, 0.55, 0.62, 0.70, 0.78, 0.84, 0.90, 0.94, 0.97)
            var successfullyAdded = 0

            for ((index, uri) in uris.withIndex()) {
                runOnUiThread { trainingProgressText.text = "Analysis [$labelName]: ${index + 1} / ${uris.size} (Parsed: $successfullyAdded)..." }
                val profile = analyzeTrackSpectralProfile(uri, checkpointsFractions)
                if (profile != null) {
                    subPeakStat.update(profile.peakSub.toDouble())
                    subFloorStat.update(profile.floorSub.toDouble())
                    growlStat.update(profile.growlRatio.toDouble())
                    successfullyAdded++
                    if (successfullyAdded % 20 == 0) saveCalibrationProfile(prefs, prefix, subPeakStat, subFloorStat, growlStat)
                }
            }

            saveCalibrationProfile(prefs, prefix, subPeakStat, subFloorStat, growlStat)
            val totalAllGenres = prefs.getInt("chill_count",0) + prefs.getInt("band_count",0) + prefs.getInt("edm_count",0)
            prefs.edit().putInt("trained_tracks_count", totalAllGenres).apply()

            runOnUiThread {
                btnPickFiles.isEnabled = true; btnPickFolder.isEnabled = true
                trainingProgressText.text = "Тренировка [$labelName] окончена. Успешно: $successfullyAdded треков"
                loadProfileInfo()
                Toast.makeText(this@MainActivity, "Сетка обновлена: Тотально $totalAllGenres треков", Toast.LENGTH_SHORT).show()
                if (isRunning) sendServiceAction("RELOAD_PROFILE")
            }
        }
    }

    private fun saveCalibrationProfile(prefs: android.content.SharedPreferences, prefix: String, peak: WelfordAccumulator, floor: WelfordAccumulator, growl: WelfordAccumulator) {
        // Жесткая адаптация: если обучали Амбиенту - не натягиваем искусственно нижнюю планку!
        val peakClampRange = if (prefix == "chill_") 8f..35f else 22f..80f

        val robustPeak = (peak.mean + 1.2 * peak.getStdDev()).toFloat().coerceIn(peakClampRange)
        val robustFloor = (floor.mean - 0.4 * floor.getStdDev()).toFloat().coerceIn(0.5f, 5.0f)
        val robustGrowl = growl.mean.toFloat().coerceIn(0.4f, 2.5f)

        prefs.edit().putBoolean("is_trained", true).putInt("${prefix}count", peak.count.toInt())
            .putFloat("${prefix}mean_p", peak.mean.toFloat()).putFloat("${prefix}m2_p", peak.m2.toFloat())
            .putFloat("${prefix}mean_f", floor.mean.toFloat()).putFloat("${prefix}m2_f", floor.m2.toFloat())
            .putFloat("${prefix}mean_g", growl.mean.toFloat()).putFloat("${prefix}m2_g", growl.m2.toFloat())
            .putFloat("${prefix}peak_bass", robustPeak).putFloat("${prefix}floor_bass", robustFloor)
            .putFloat("${prefix}growl_bias", robustGrowl).apply()
    }

    private fun analyzeTrackSpectralProfile(uri: Uri, checkpoints: DoubleArray): TrackSpectralProfile? {
        var extractor: MediaExtractor? = null; var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            try { contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> extractor.setDataSource(pfd.fileDescriptor) } ?: run { extractor.setDataSource(applicationContext, uri, null) }
            } catch (_: Exception) { extractor.setDataSource(applicationContext, uri, null) }

            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                if ((extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { audioTrackIndex = i; break }
            }
            if (audioTrackIndex < 0) return null

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: "")
            codec.configure(format, null, null, 0); codec.start()

            val checkPointsUs = if (durationUs > 10_000_000L) checkpoints.map { (durationUs * it).toLong() }.toLongArray() else longArrayOf(0L)
            val info = MediaCodec.BufferInfo(); val fftSize = 1024
            val fftReal = FloatArray(fftSize); val fftImag = FloatArray(fftSize)
            val hanningWindow = FloatArray(fftSize) { 0.5f * (1f - cos(2f * PI.toFloat() * it / (fftSize - 1))) }

            var maxSubEnergy = 0f; var minSubEnergy = 999f
            var sumMidEnergy = 0.0; var sumSubEnergy = 0.0
            var totalFftFrames = 0

            for (seekTime in checkPointsUs) {
                extractor.seekTo(seekTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                try { codec.flush() } catch (_: Exception) {}
                var buffersRead = 0
                while (buffersRead < 8) {
                    val inIdx = codec.dequeueInputBuffer(2000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = buf?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (size > 0) { codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        else { codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 2000)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val shortBuf = outBuf.asShortBuffer()
                            if (shortBuf.remaining() >= fftSize * channels) {
                                for (k in 0 until fftSize) {
                                    val left = shortBuf.get() / 32768f
                                    val right = if (channels > 1) shortBuf.get() / 32768f else left
                                    fftReal[k] = ((left + right) * 0.5f) * hanningWindow[k]; fftImag[k] = 0f
                                }
                                computeRadix2Fft(fftReal, fftImag, fftSize)
                                val normScale = 250f / fftSize
                                var sub = 0f; for (bin in 1..4) sub += hypot(fftReal[bin], fftImag[bin])
                                sub = (sub / 4f) * normScale * 3.5f
                                var mid = 0f; for (bin in 5..46) mid += hypot(fftReal[bin], fftImag[bin])
                                mid = (mid / 42f) * normScale * 3.0f

                                if (sub > maxSubEnergy) maxSubEnergy = sub
                                if (sub in 0.4f..minSubEnergy) minSubEnergy = sub
                                sumSubEnergy += sub; sumMidEnergy += mid; totalFftFrames++
                            }
                            buffersRead++
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inIdx < 0) { buffersRead++ }
                }
            }

            if (maxSubEnergy in 1.2f..160f && totalFftFrames > 0) {
                return TrackSpectralProfile(maxSubEnergy, if (minSubEnergy < 900f) minSubEnergy else 1.0f, ((sumMidEnergy / totalFftFrames).toFloat() / ((sumSubEnergy / totalFftFrames).toFloat() + 0.1f)).coerceIn(0.4f, 2.8f))
            }
        } catch (e: Exception) { Log.e("GlyphTraining", "Track Error $uri: ${e.message}"); return null }
        finally { try { codec?.stop() } catch (_: Exception) {}; try { codec?.release() } catch (_: Exception) {}; try { extractor?.release() } catch (_: Exception) {} }
        return null
    }

    private fun computeRadix2Fft(real: FloatArray, imag: FloatArray, n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) { val tempR = real[i]; real[i] = real[j]; real[j] = tempR; val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI }
            var k = n shr 1; while (k > 0 && k <= j) { j -= k; k = k shr 1 }; j += k
        }
        var l = 2
        while (l <= n) {
            val halfL = l shr 1
            val angle = (-2.0 * PI / l).toFloat()
            val wStepR = cos(angle); val wStepI = sin(angle)
            var i = 0
            while (i < n) {
                var wR = 1.0f; var wI = 0.0f
                for (m in 0 until halfL) {
                    val pos = i + m; val match = pos + halfL
                    val tr = wR * real[match] - wI * imag[match]
                    val ti = wR * imag[match] + wI * real[match]
                    real[match] = real[pos] - tr; imag[match] = imag[pos] - ti
                    real[pos] += tr; imag[pos] += ti
                    val nextWR = wR * wStepR - wI * wStepI
                    wI = wR * wStepI + wI * wStepR; wR = nextWR
                }
                i += l
            }
            l = l shl 1
        }
    }

    private fun sendServiceAction(action: String, boolExtra: Boolean = false) {
        startService(Intent(this, GlyphVisualizerService::class.java).apply { putExtra("ACTION", action); putExtra("BOOL_EXTRA", boolExtra) })
    }

    private fun loadProfileInfo() {
        val prefs = getSharedPreferences("glyph_profile", Context.MODE_PRIVATE)
        val cC = prefs.getInt("chill_count", 0)
        val bC = prefs.getInt("band_count", 0)
        val eC = prefs.getInt("edm_count", 0)
        val total = cC + bC + eC

        if (total > 0) {
            statusText.text = "Matrix Knowledge Base\nCHILL: $cC | BAND: $bC | TEAROUT: $eC (Общее: $total)"
            statusText.setTextColor(getColor(android.R.color.holo_blue_light))
        } else {
            statusText.text = "База Обучения пуста. (0)\nИспользуйте Files или Folder."
            statusText.setTextColor(getColor(android.R.color.darker_gray))
        }
    }

    override fun onDestroy() { super.onDestroy(); GlyphVisualizerService.liveFrameListener = null }

    private fun checkPermissionsAndToggle() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) toggleService() else requestPermissionsLauncher.launch(perms.toTypedArray())
    }

    private fun toggleService() {
        val intent = Intent(this, GlyphVisualizerService::class.java)
        if (!isRunning) { ContextCompat.startForegroundService(this, intent); isRunning = true; btnToggle.text = "STOP"; btnToggle.setBackgroundColor(getColor(android.R.color.holo_red_dark)) }
        else { stopService(intent); isRunning = false; btnToggle.text = "START"; btnToggle.setBackgroundColor(getColor(android.R.color.white)) }
    }
}