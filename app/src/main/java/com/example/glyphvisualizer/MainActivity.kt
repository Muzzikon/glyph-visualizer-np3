package com.example.glyphvisualizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
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

class MainActivity : AppCompatActivity() {

    private var trainingVisible = false
    private var resumeCalibrationOnReturn = false
    private var pickFilesLabel = ""
    private var pickFolderLabel = ""
    private var isRunning = false
    private var testModeState = 0
    private var manualPatternState = false
    private val stateHandler = Handler(Looper.getMainLooper())
    private val refreshServiceState = object : Runnable {
        override fun run() {
            val runningNow = GlyphVisualizerService.isRunning
            if (runningNow != isRunning) { isRunning = runningNow; updateServiceButton() }
            stateHandler.postDelayed(this, 500L)
        }
    }

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
    private lateinit var frameListener: LiveFrameListener
    private var engineDiagnostics = "VJ 3.25 · измерение частоты кадров…"

    private val pickAudioFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { retainReadAccess(it) }
            processTrackUrisStreamingSafe(uris)
        }
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        if (treeUri != null) {
            retainReadAccess(treeUri)
            scanFolderStreamingSafe(treeUri)
        }
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

        pickFilesLabel = btnPickFiles.text.toString()
        pickFolderLabel = btnPickFolder.text.toString()
        loadProfileInfo()

        btnToggle.setOnClickListener { checkPermissionsAndToggle() }
        btnPickFiles.setOnClickListener {
            resumeCalibrationOnReturn=false
            if (CalibrationJobs.isBusy) {
                CalibrationJobs.pause("Пауза по запросу")
                trainingProgressText.text="Остановка… сохранённые треки останутся"
            } else try {
                val saved=CalibrationJobs.checkpoint(applicationContext)
                if(saved!=null && (!saved.complete || saved.analysisVersion<2 || LearningStore(applicationContext).needsTraining())) resumeCalibration()
                else pickAudioFilesLauncher.launch(arrayOf("audio/*"))
            } catch(error:Exception) {showCalibrationError(error)}
        }
        btnPickFolder.setOnClickListener {
            showTrainingGenreDialog { pickFolderLauncher.launch(null) }
        }

        btnPickFolder.setOnLongClickListener {showLearningDialog();true}
        btnAutoPattern.setOnLongClickListener {showTeachingDialog();true}
        btnSilentMode.visibility = View.GONE

        btnSelectApps.setOnClickListener { showSupportedAppsDialog() }

        glyphPreview.setOnClickListener {
            controlsPanel.visibility = if (controlsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnPrevPattern.setOnClickListener { sendServiceAction("PREV_PATTERN") }
        btnNextPattern.setOnClickListener { sendServiceAction("NEXT_PATTERN") }

        btnAutoPattern.setOnClickListener {
            testModeState = if(manualPatternState) 0 else (testModeState + 1) % 3
            when (testModeState) {
                0 -> {
                    btnAutoPattern.text = "AUTO"
                    btnAutoPattern.setBackgroundColor(getColor(android.R.color.holo_blue_bright))
                    sendServiceAction("MODE_AUTO")
                    Toast.makeText(this, "Режим: авто", Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    btnAutoPattern.text = "TEST: CALM"
                    btnAutoPattern.setBackgroundColor(getColor(android.R.color.holo_green_light))
                    sendServiceAction("MODE_FORCE_CALM")
                    Toast.makeText(this, "Режим: спокойный", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    btnAutoPattern.text = "TEST: DROP"
                    btnAutoPattern.setBackgroundColor(getColor(android.R.color.holo_red_light))
                    sendServiceAction("MODE_FORCE_DROP")
                    Toast.makeText(this, "Режим: энергичный", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnPickFiles.setOnLongClickListener {
            resumeCalibrationOnReturn=false
            if (CalibrationJobs.isBusy) {
                CalibrationJobs.pause("Пауза по запросу")
                Toast.makeText(this, "Остановка после освобождения декодера…", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this).setTitle("Сбросить калибровку?")
                    .setMessage("Удалятся сохранённая очередь, профиль, карты, оценки фрагментов и обученная модель. Обычная остановка сохраняет прогресс.")
                    .setPositiveButton("Сбросить") { _, _ ->
                        try {
                            CalibrationJobs.clear(applicationContext)
                            getSharedPreferences("glyph_profile", Context.MODE_PRIVATE).edit()
                                .remove("vj2_tracks").remove("vj2_sensitivity").apply()
                            sendServiceAction("RELOAD_PROFILE"); loadProfileInfo(); refreshTrainingState()
                        } catch (error: Exception) { showCalibrationError(error) }
                    }.setNegativeButton("Отмена", null).show()
            }
            true
        }

        // --- ВЗАИМОДЕЙСТВИЕ ZERO-GC INTERFACE (TELEMETRY STRINGS РАСКРЫВАЮТСЯ ТОЛЬКО ТУТ!) ---
        frameListener = object : LiveFrameListener {
            override fun onModeState(forceMode: Int, manualPattern: Boolean) {
                manualPatternState=manualPattern
                testModeState=forceMode.coerceIn(0,2)
                btnAutoPattern.text=when(testModeState) {
                    1 -> "TEST: CALM"
                    2 -> "TEST: DROP"
                    else -> if(manualPattern) "MANUAL · AUTO" else "AUTO"
                }
                btnAutoPattern.setBackgroundColor(getColor(when(testModeState) {
                    1 -> android.R.color.holo_green_light
                    2 -> android.R.color.holo_red_light
                    else -> android.R.color.holo_blue_bright
                }))
            }
            override fun onPatternSelected(pattern:Int) {
                val names=arrayOf("Shuriken","QHex Shield","Acoustic Slasher","Reactor","Lotus","Volum Octa","Mechanical Iris","Kaleido Transformer","Tearout Saw","True Kaleidoscope")
                patternNameText.text="[$pattern] ${names.getOrElse(pattern){"?"}} · MANUAL"
            }
            override fun onEngineDiagnostics(message: String) {
                engineDiagnostics = message
                val runningNow = GlyphVisualizerService.isRunning
                if (isRunning != runningNow) { isRunning = runningNow; updateServiceButton() }
            }
            override fun onFrame(frame: IntArray, tension: Float, bass: Float, mid: Float, high: Float, pattern: Int) {
                runOnUiThread {
                    glyphPreview.updateLeds(frame)
                    progressTension.progress = (tension * 100).toInt().coerceIn(0, 100)
                }
            }

            override fun onAdvancedTelemetry(profCtx: Int, tension: Float, momentName: String, pCur: Int, mProg: Float, dwell: Float, bSal: Float, mSal: Float, hSal: Float, glitchImp: Float) {
                runOnUiThread {
                    val pName = arrayOf("[0] Shuriken", "[1] QHex Shield", "[2] Acoustic Slasher", "[3] CyberSat (Reactor)", "[4] Lotus", "[5] Volum Octa", "[6] Mechanical Iris", "[7] Kaleido Transformer", "[8] Tearout Saw", "[9] True Kaleidoscope").getOrElse(pCur){"U/K"}
                    

                    tensionValText.text = "%.2f".format(tension)
                    patternNameText.text = "$pName [AGGRO: ${"%.2f".format(tension)}]"
                    freqStatsText.text = "Анализ текущей секции · $momentName"

                    // Существующая разметка имеет ровно шесть строк: диагностика должна помещаться.
                    val lastLine = if (GlyphVisualizerService.lastError.isNotEmpty()) GlyphVisualizerService.lastError.take(55)
                        else "B/M/H ${"%.2f".format(bSal)}/${"%.2f".format(mSal)}/${"%.2f".format(hSal)} morph ${(mProg*100).toInt()}%"
                    debugDataText.text = "$engineDiagnostics\n$lastLine"
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        trainingVisible = true
        CalibrationJobs.observe { message ->
            runOnUiThread {
                if (trainingVisible && !isDestroyed) {
                    trainingProgressText.text = message
                    refreshTrainingButtons()
                    loadProfileInfo()
                }
            }
        }
        refreshTrainingState()
        tryResumeCalibrationOnReturn()
        if (::frameListener.isInitialized) GlyphVisualizerService.liveFrameListener = frameListener
        stateHandler.post(refreshServiceState)
    }

    override fun onStop() {
        trainingVisible = false
        stateHandler.removeCallbacks(refreshServiceState)
        if(!TrackAnalysisService.isRunning && CalibrationJobs.mayResumeOnReturn) resumeCalibrationOnReturn=true
        CalibrationJobs.observe(null)
        if(!TrackAnalysisService.isRunning && (CalibrationJobs.mayResumeOnReturn || CalibrationJobs.scanning))CalibrationJobs.pause("Пауза: приложение не на экране")
        if (GlyphVisualizerService.liveFrameListener === frameListener) GlyphVisualizerService.liveFrameListener = null
        super.onStop()
    }

    private fun showLearningDialog() {
        if(CalibrationJobs.isBusy){Toast.makeText(this,"Сначала приостановите обработку",Toast.LENGTH_SHORT).show();return}
        val info=try{LearningStore(applicationContext).status()}catch(error:Exception){error.message ?: "Ошибка профиля"}
        AlertDialog.Builder(this).setTitle("Обучение реакции")
            .setMessage("$info\n\nДля оценки текущего фрагмента удерживайте AUTO. Оценка относится к четырём секундам звука, а не к жанру целого трека. Общая модель включается после проверки на других треках.")
            .setPositiveButton("Обновить модель") {_,_->
                try {
                    check(CalibrationJobs.checkpoint(applicationContext)!=null){"Сначала выберите и проанализируйте локальные треки"}
                    stopVisualizationForCalibration()
                    ContextCompat.startForegroundService(this,Intent(this,TrackAnalysisService::class.java).putExtra("ACTION","LEARN"))
                    refreshTrainingState()
                }catch(error:Exception){showCalibrationError(error)}
            }.setNegativeButton("Закрыть",null).show()
    }
    private fun showTeachingDialog() {
        if(CalibrationJobs.isBusy){Toast.makeText(this,"Оценки доступны после паузы обработки",Toast.LENGTH_SHORT).show();return}
        val track=MediaNotificationListener.playback
        val position=track?.position(android.os.SystemClock.elapsedRealtime())
        if(track==null || position==null){Toast.makeText(this,"Нужен играющий локальный трек и его позиция из плеера",Toast.LENGTH_SHORT).show();return}
        val choices=arrayOf("Плавная мелодичная реакция","Спокойная ритмичная реакция","Жёсткая реакция с акцентами","Удалить оценку этого фрагмента")
        val targets=arrayOf(doubleArrayOf(.20,.95,.50),doubleArrayOf(.15,.25,.30),doubleArrayOf(.90,.05,.90))
        AlertDialog.Builder(this).setTitle("${track.title.take(50)} · ${position/4000*4}–${position/4000*4+4} с")
            .setItems(choices){_,which->
                val app=applicationContext
                thread(name="GlyphTeaching") {
                    val message=try{LearningStore(app).teach(track,position,targets.getOrNull(which))}catch(error:Exception){"Оценка не сохранена: ${error.message}"}
                    runOnUiThread {if(!isDestroyed){Toast.makeText(this,message,Toast.LENGTH_LONG).show();loadProfileInfo();refreshTrainingState()}}
                }
            }.setNegativeButton("Отмена",null).show()
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
        resumeCalibrationOnReturn=false
        if (CalibrationJobs.isBusy) {
            CalibrationJobs.pause("Пауза по запросу")
            trainingProgressText.text = "Остановка… сохранённые треки останутся"
            return
        }
        try {
            val saved = CalibrationJobs.checkpoint(applicationContext)
            if (saved != null && (!saved.complete || saved.analysisVersion<2)) {
                AlertDialog.Builder(this).setTitle("Продолжить калибровку?")
                    .setMessage("Сохранено ${saved.next}/${saved.total}, успешно ${saved.successes}. Треки анализируются целиком и сохраняются. При нагреве — остывание, с выключенным экраном анализ продолжается через уведомление. Результаты старой калибровки заменятся полными картами треков.")
                    .setPositiveButton("Продолжить") { _, _ ->
                        resumeCalibration()
                    }.setNeutralButton("Новый набор") { _, _ -> onProceed() }
                    .setNegativeButton("Отмена", null).show()
            } else onProceed()
        } catch (error: Exception) { showCalibrationError(error) }
    }

    private fun retainReadAccess(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (error: SecurityException) {
            android.util.Log.w("GlyphCalibration", "Provider cannot retain read access", error)
        }
    }

    private fun showCalibrationError(error: Exception) {
        trainingProgressText.text = "Анализ: ${error.message ?: "ошибка"}"
        Toast.makeText(this, "Не удалось запустить калибровку", Toast.LENGTH_LONG).show()
        refreshTrainingButtons()
    }

    private fun stopVisualizationForCalibration() {
        if (GlyphVisualizerService.isRunning) stopService(Intent(this, GlyphVisualizerService::class.java))
        isRunning = false; updateServiceButton()
        glyphPreview.updateLeds(IntArray(625));progressTension.progress=0
    }

    private fun resumeCalibration() {
        if(!trainingVisible || CalibrationJobs.isBusy) return
        try {
            stopVisualizationForCalibration()
            val saved=CalibrationJobs.checkpoint(applicationContext)
            ContextCompat.startForegroundService(this,Intent(this,TrackAnalysisService::class.java).apply {
                if(saved?.complete==true && saved.analysisVersion>=2)putExtra("ACTION","LEARN")
            })
            refreshTrainingState()
        } catch(error:Exception) {showCalibrationError(error)}
    }

    private fun tryResumeCalibrationOnReturn() {
        if(!resumeCalibrationOnReturn || !trainingVisible || CalibrationJobs.isBusy) return
        resumeCalibrationOnReturn=false
        try {
            val saved=CalibrationJobs.checkpoint(applicationContext)
            // Only a lifecycle pause requested in this Activity resumes automatically.
            // A cold start, thermal stop, batch limit or manual pause requires a tap.
            if(saved!=null && (!saved.complete || saved.analysisVersion<2) &&
                (saved.reason=="Пауза: приложение не на экране" || saved.reason=="Пауза: экран приложения закрыт") &&
                !GlyphVisualizerService.isRunning) resumeCalibration()
        } catch(error:Exception) {showCalibrationError(error)}
    }

    private fun refreshTrainingButtons() {
        btnPickFiles.isEnabled = true
        btnPickFolder.isEnabled = !CalibrationJobs.isBusy
        val pending=try { CalibrationJobs.checkpoint(applicationContext)?.let { !it.complete || it.analysisVersion<2 || LearningStore(applicationContext).needsTraining() } ?: false }
            catch (_:Exception) {false}
        btnPickFiles.text = when {CalibrationJobs.isBusy -> "ПАУЗА";pending -> "ПРОДОЛЖИТЬ";else -> pickFilesLabel}
        btnPickFolder.text = pickFolderLabel
        tryResumeCalibrationOnReturn()
    }

    private fun refreshTrainingState() {
        refreshTrainingButtons()
        try {
            val saved = CalibrationJobs.checkpoint(applicationContext)
            trainingProgressText.text = if(GlyphAnalysisStatus.error.isNotEmpty()) GlyphAnalysisStatus.error
                else if (CalibrationJobs.isBusy) CalibrationJobs.status.ifEmpty { "Калибровка…" }
                else if(saved!=null && saved.analysisVersion<2) "Полный анализ ещё не выполнен · продолжите очередь из ${saved.total} треков"
                else if (saved != null) "${saved.reason} · ${saved.next}/${saved.total}; готово: ${saved.successes}; пропущено: ${saved.failures}"
                else "Полный анализ необязателен; известные треки работают из сохранённых карт"
            if(saved?.complete==true && !CalibrationJobs.isBusy)trainingProgressText.text="${trainingProgressText.text}\n${LearningStore(applicationContext).status()}"
        } catch (error: Exception) { trainingProgressText.text = "Ошибка чтения прогресса: ${error.message}" }
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
        if (!CalibrationJobs.beginScan()) return
        trainingProgressText.text = "Поиск аудиофайлов…"
        refreshTrainingButtons()
        thread(name = "GlyphCalibrationScan") {
            val audioUris = mutableListOf<Uri>()
            var failure: Exception? = null
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val pending = java.util.ArrayDeque<String>()
                val visited = HashSet<String>()
                pending.add(DocumentsContract.getTreeDocumentId(treeUri))
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (pending.isNotEmpty() && !CalibrationJobs.scanCancelled()) {
                    val docId = pending.removeFirst()
                    if (!visited.add(docId)) continue
                    require(visited.size <= 100_000) { "Слишком много каталогов" }
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                    contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        while (cursor.moveToNext() && !CalibrationJobs.scanCancelled()) {
                            val id = cursor.getString(idCol)
                            val mime = cursor.getString(mimeCol) ?: ""
                            val name = cursor.getString(nameCol)?.lowercase(java.util.Locale.ROOT) ?: ""
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(id)
                            else if (mime.startsWith("audio/") || listOf(".mp3", ".wav", ".flac", ".m4a", ".ogg", ".opus", ".aac").any { name.endsWith(it) }) {
                                audioUris.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                                require(audioUris.size <= 100_000) { "Слишком много треков" }
                            }
                        }
                    }
                }
            } catch (error: Exception) { failure = error }
            val stopped = CalibrationJobs.scanCancelled()
            CalibrationJobs.endScan()
            runOnUiThread {
                if (trainingVisible && !isDestroyed) {
                    when {
                        stopped -> { trainingProgressText.text = "Поиск приостановлен"; refreshTrainingButtons() }
                        failure != null -> showCalibrationError(requireNotNull(failure))
                        audioUris.isEmpty() -> { trainingProgressText.text = "Аудиофайлы не найдены"; refreshTrainingButtons() }
                        else -> processTrackUrisStreamingSafe(audioUris)
                    }
                }
            }
        }
    }

    private fun processTrackUrisStreamingSafe(uris: List<Uri>) {
        if (!trainingVisible || CalibrationJobs.isBusy) return
        stopVisualizationForCalibration()
        // Большая очередь записывается на диск вне UI-потока.
        if (!CalibrationJobs.beginScan()) return
        refreshTrainingButtons()
        thread(name = "GlyphCalibrationQueue") {
            try {
                // beginScan не даёт параллельно запустить второй декодер/очередь.
                synchronized(CalibrationJobs) {
                    val stopped = CalibrationJobs.scanCancelled()
                    CalibrationJobs.endScan()
                    if (!stopped) {
                        CalibrationJobs.start(applicationContext, uris.distinct(),launchWorker=false)
                        runOnUiThread {if(trainingVisible && !isDestroyed)resumeCalibration()}
                    }
                }
            } catch (error: Exception) {
                CalibrationJobs.endScan()
                runOnUiThread { if (trainingVisible && !isDestroyed) showCalibrationError(error) }
            } finally {
                runOnUiThread { if (trainingVisible && !isDestroyed) refreshTrainingState() }
            }
        }
    }

    private fun sendServiceAction(action: String, boolExtra: Boolean = false) {
        if (GlyphVisualizerService.isRunning) startService(Intent(this, GlyphVisualizerService::class.java).apply { putExtra("ACTION", action); putExtra("BOOL_EXTRA", boolExtra) })
    }

    private fun loadProfileInfo() {
        val prefs = getSharedPreferences("glyph_profile", Context.MODE_PRIVATE)
        val count = prefs.getInt("vj2_tracks", 0)
        statusText.text = if (count > 0) "VJ Engine · профиль: $count треков" else "VJ Engine · адаптивный анализ готов\nКалибровка файлами необязательна"
    }

    override fun onResume() {
        super.onResume()
        isRunning = GlyphVisualizerService.isRunning
        updateServiceButton()
    }

    private fun updateServiceButton() {
        btnToggle.text=if(isRunning) "STOP" else "START"
        btnToggle.setBackgroundColor(getColor(if(isRunning) android.R.color.holo_red_dark else android.R.color.white))
    }

    override fun onDestroy() {
        if(!TrackAnalysisService.isRunning && (CalibrationJobs.mayResumeOnReturn || CalibrationJobs.scanning))CalibrationJobs.pause("Пауза: экран приложения закрыт")
        if (::frameListener.isInitialized && GlyphVisualizerService.liveFrameListener === frameListener) GlyphVisualizerService.liveFrameListener = null
        super.onDestroy()
    }

    private fun checkPermissionsAndToggle() {
        // STOP is always available. A revoked permission must not trap a running service.
        if (GlyphVisualizerService.isRunning) { toggleService(); return }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) toggleService()
        else requestPermissionsLauncher.launch(perms.toTypedArray())
    }

    private fun toggleService() {
        resumeCalibrationOnReturn=false
        if (CalibrationJobs.isBusy) {
            Toast.makeText(this, "Сначала приостановите калибровку", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, GlyphVisualizerService::class.java)
        isRunning = GlyphVisualizerService.isRunning
        try {
            if (!isRunning) ContextCompat.startForegroundService(this, intent)
            else stopService(intent)
            // startForegroundService() is asynchronous. Do not claim success until
            // the Service actually publishes its running state (onResume/telemetry).
            isRunning = GlyphVisualizerService.isRunning
            updateServiceButton()
        } catch (error: Exception) {
            isRunning = GlyphVisualizerService.isRunning
            updateServiceButton()
            Toast.makeText(this, "Не удалось переключить визуализацию: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }
}



