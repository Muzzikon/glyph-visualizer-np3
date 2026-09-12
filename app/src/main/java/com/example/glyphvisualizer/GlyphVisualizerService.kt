package com.example.glyphvisualizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import kotlin.math.*
import kotlin.random.Random

class GlyphVisualizerService : Service() {

    companion object {
        var liveFrameListener: ((IntArray, String, Float, Float, Float, Float) -> Unit)? = null
    }

    private var glyphManager: GlyphMatrixManager? = null
    private var visualizer: Visualizer? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val matrixSide = 25
    private val totalLeds = matrixSide * matrixSide
    private val frameBuffer = IntArray(totalLeds)

    private val renderHandler = Handler(Looper.getMainLooper())
    private val frameIntervalMs = 28L // ~35 FPS

    @Volatile private var targetBass = 0f
    @Volatile private var targetMid = 0f
    @Volatile private var targetHigh = 0f
    private var prevRawBass = 0f
    private var prevRawHigh = 0f

    private var rollingMaxSignal = 15f

    private var savedPeakBass = 28f
    private var savedFloorBass = 2f
    private var savedGrowlBias = 1.0f
    private var dynamicPeak = 30f
    private var dynamicFloor = 2f

    private var growlIntensity = 0f
    private var rhythmFlux = 0f
    private var subToMidRatio = 1.0f

    private val melodicRoute = intArrayOf(4, 2, 6, 7)
    private val houseRoute = intArrayOf(1, 3, 2, 5)
    private val dubstepRoute = intArrayOf(0, 8, 1, 5, 3)

    private var activeRoute = melodicRoute
    private var routeStep = 0
    private var detectedGenreName = "МЕЛОДИКА / FNAF"

    private var hitDensity = 0f
    private var buildupHitStrobe = 0f
    private var smoothBass = 0f
    private var smoothMid = 0f
    private var smoothHigh = 0f
    private var tension = 0f

    private var isMusicSilent = false
    private var silenceCounter = 0
    private var isPreDropVoid = false
    private var voidTimer = 0L

    private var isSilentDemoMode = false
    private var demoStartTime = 0L

    private var testModeOverride = 0
    private var isManualPatternOverride = false

    private val whitelistedPackages = mutableSetOf<String>()
    private var lastFftTime = 0L

    private var shurikenContinuousAngle = 0.0
    private var outerRingAngle = 0.0
    private var kineticVelocityBoost = 0.0
    private var rhythmicBrake = 1.0
    private var polyAngle3D = 0.0
    private var tearoutSawAngle = 0.0
    private var timeSec = 0.0

    private var shakeImpulse = 0.0
    private var shakeX = 0.0
    private var shakeY = 0.0
    private var kickFlash = 0f

    private var isStrobeGating = false
    private var strobeTick = 0
    private var peakDropEnergy = 10f

    private var currentDropPattern = 0
    private var prevDropPattern = 0
    private var morphProgress = 1.0f
    private var morphStartTime = 0L
    private val morphDurationMs = 600L
    private var nextPatternQueued = -1
    private var patternCooldown = 0L

    private var calmPatternType = 0
    private var calmPatternTimer = 0L

    private val patternNames = arrayOf(
        "[0] Толстый Сюрикен 360° + Hyper-Core",
        "[1] Квантовый Гекса-Щит (Smart Gap)",
        "[2] Акустический Пульсар: Джеты",
        "[3] Кибер-Реактор: Обруч + Спутники",
        "[4] Лотос: Расщепление Лезвий",
        "[5] 3D-Октаэдр NSD (Volumetric)",
        "[6] Небула-Роза: Спирограф",
        "[7] 3D-Калейдоскоп: Био-Кристалл",
        "[8] Чёткая Tearout-Пила (HD Disc)",
        "[9] Калейдоскоп Хабстракта (The Storm)" // НОВЫЙ
    )

    private val octVertices = arrayOf(
        doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0),
        doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0)
    )
    private val octEdges = arrayOf(
        Pair(0, 2), Pair(0, 3), Pair(0, 4), Pair(0, 5),
        Pair(1, 2), Pair(1, 3), Pair(1, 4), Pair(1, 5),
        Pair(2, 4), Pair(4, 3), Pair(3, 5), Pair(5, 2)
    )
    private val projOctX = DoubleArray(6)
    private val projOctY = DoubleArray(6)
    private val projOctZ = DoubleArray(6)

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            reconnectVisualizerOnAudioChange()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            reconnectVisualizerOnAudioChange()
        }
    }

    private fun reconnectVisualizerOnAudioChange() {
        renderHandler.postDelayed({
            initAudioVisualizer()
            rollingMaxSignal = 15f
        }, 500L)
    }

    private val renderRunnable = object : Runnable {
        override fun run() {
            processPhysicsAndRender()
            renderHandler.postDelayed(this, frameIntervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, renderHandler)
        acquireWakeLock()
        loadCalibration()
        loadWhitelistedApps()
        startForegroundNotification()
        initGlyph()
        initAudioVisualizer()
        lastFftTime = SystemClock.elapsedRealtime()
        renderHandler.post(renderRunnable)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphVisualizer::PocketRenderLock")
            wakeLock?.acquire(2 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("ACTION")) {
            "PREV_PATTERN" -> {
                isManualPatternOverride = true
                prevDropPattern = currentDropPattern
                currentDropPattern = (currentDropPattern - 1 + 10) % 10 // ТУТ 10
                startMorph()
            }
            "NEXT_PATTERN" -> {
                isManualPatternOverride = true
                prevDropPattern = currentDropPattern
                currentDropPattern = (currentDropPattern + 1) % 10 // И ТУТ 10
                startMorph()
            }
            "MODE_AUTO" -> {
                testModeOverride = 0
                isManualPatternOverride = false
            }
            "MODE_FORCE_CALM" -> {
                testModeOverride = 1
                isManualPatternOverride = false
            }
            "MODE_FORCE_DROP" -> {
                testModeOverride = 2
                isManualPatternOverride = false
            }
            "TOGGLE_SILENT_MODE" -> {
                isSilentDemoMode = intent.getBooleanExtra("BOOL_EXTRA", false)
                demoStartTime = SystemClock.elapsedRealtime()
            }
            "RELOAD_APPS" -> loadWhitelistedApps()
            "RELOAD_PROFILE" -> loadCalibration()
        }
        return START_STICKY
    }

    private fun startMorph() {
        morphStartTime = SystemClock.elapsedRealtime()
        morphProgress = 0.0f
    }

    private fun loadWhitelistedApps() {
        val prefs = getSharedPreferences("glyph_profile", Context.MODE_PRIVATE)
        val apps = prefs.getStringSet("whitelisted_apps", null)
        whitelistedPackages.clear()
        if (apps != null) whitelistedPackages.addAll(apps)
    }

    private fun loadCalibration() {
        val prefs = getSharedPreferences("glyph_profile", Context.MODE_PRIVATE)
        savedPeakBass = prefs.getFloat("peak_bass", 28f)
        savedFloorBass = prefs.getFloat("floor_bass", 2f)
        savedGrowlBias = prefs.getFloat("growl_bias", 1.0f)
        dynamicPeak = savedPeakBass
        dynamicFloor = savedFloorBass
    }

    private fun startForegroundNotification() {
        val channelId = "glyph_visualizer_channel"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "Glyph Audio Visualizer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Визуализация музыки на матрице Glyph"
            setShowBadge(false)
        }
        manager?.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("GLYPH // PURE MOTION MATRIX")
            .setContentText("Автономный визуализатор активен")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(101, notification)
        }
    }

    private fun initGlyph() {
        try {
            glyphManager = GlyphMatrixManager.getInstance(applicationContext)
            glyphManager?.init(object : GlyphMatrixManager.Callback {
                override fun onServiceConnected(name: ComponentName?) {
                    glyphManager?.register(Glyph.DEVICE_23112)
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initAudioVisualizer() {
        try {
            visualizer?.release()
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        lastFftTime = SystemClock.elapsedRealtime()
                        if (!isSilentDemoMode) fft?.let { analyzeSound(it) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun analyzeSound(fft: ByteArray) {
        if (whitelistedPackages.isNotEmpty()) {
            val activeApp = MediaNotificationListener.activeMediaAppPackage
            if (activeApp == null || !whitelistedPackages.contains(activeApp)) {
                isMusicSilent = true
                targetBass = 0f; targetMid = 0f; targetHigh = 0f
                return
            }
        }

        var b = 0f
        for (i in 2..16 step 2) b += hypot(fft[i].toDouble(), fft[i + 1].toDouble()).toFloat()
        b /= 8f

        var m = 0f
        for (i in 18..80 step 2) m += hypot(fft[i].toDouble(), fft[i + 1].toDouble()).toFloat()
        m /= 31f

        var h = 0f
        for (i in 82..180 step 2) h += hypot(fft[i].toDouble(), fft[i + 1].toDouble()).toFloat()
        h /= 50f

        val isMusicActiveInSystem = audioManager?.isMusicActive ?: false
        val rawSum = b + m + h

        if (!isMusicActiveInSystem && rawSum < 0.6f) {
            silenceCounter++
            if (silenceCounter > 50) {
                isMusicSilent = true
                targetBass = 0f; targetMid = 0f; targetHigh = 0f
                return
            }
        } else {
            silenceCounter = 0
            isMusicSilent = false
        }

        rollingMaxSignal = max(rollingMaxSignal * 0.996f, rawSum)
        val bluetoothAgcBoost = if (rollingMaxSignal < 16f && rawSum > 0.4f) {
            (26f / max(1.8f, rollingMaxSignal)).coerceIn(1.0f, 6.0f)
        } else 1.0f

        val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10
        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val safeCompensator = if (currentVol > 0) (maxVol.toFloat() / currentVol.toFloat()).coerceIn(1.0f, 1.5f) else 1.0f

        val finalGain = safeCompensator * bluetoothAgcBoost
        b = (b * finalGain).coerceAtMost(65f)
        m = (m * finalGain).coerceAtMost(55f)
        h = (h * finalGain).coerceAtMost(45f)

        targetBass = b; targetMid = m; targetHigh = h

        val kickDelta = b - prevRawBass
        val snareDelta = h - prevRawHigh
        prevRawBass = b; prevRawHigh = h

        val kickHitThreshold = max(4.5f, dynamicPeak * 0.20f)
        val snareHitThreshold = max(4.0f, dynamicPeak * 0.16f)

        if (snareDelta > snareHitThreshold || kickDelta > kickHitThreshold) {
            hitDensity = (hitDensity + 0.28f).coerceAtMost(2.0f)
            buildupHitStrobe = 1.0f
        } else {
            hitDensity *= 0.94f
        }

        val instantHit = max(kickDelta, 0f) * 1.5f + max(snareDelta, 0f)
        rhythmFlux = rhythmFlux * 0.72f + instantHit * 0.28f

        if (b > dynamicPeak) dynamicPeak = dynamicPeak * 0.90f + b * 0.10f
        else dynamicPeak = max(16f, dynamicPeak * 0.9992f)

        if (b in 0.5f..dynamicFloor) dynamicFloor = b
        else dynamicFloor = min(savedFloorBass * 1.5f, dynamicFloor * 1.0005f)

        subToMidRatio = b / (m + 0.1f)
        growlIntensity = (m / (16f * savedGrowlBias)).coerceIn(0f, 2.5f)

        // АБСОЛЮТНЫЙ АНТИ-ВОКАЛ: Гроулы и глитчи работают ТОЛЬКО если есть четкий бит (удары)
        // Если ритмики нет, мы считаем любой сигнал в середине голосом/падом и умножаем на ноль.
        if (hitDensity < 0.45f || subToMidRatio < 0.85f || activeRoute != dubstepRoute) {
            growlIntensity *= 0.02f
        }

        val normBass = ((b - dynamicFloor) / (dynamicPeak - dynamicFloor + 1e-3f)).coerceIn(0f, 1.8f)

        val hasRhythmicDrive = hitDensity > 0.35f || rhythmFlux > 5.5f

        when {
            !hasRhythmicDrive || subToMidRatio < 1.05f || (m > 12f && b < 22f) -> {
                activeRoute = melodicRoute
                detectedGenreName = "МЕЛОДИКА / FNAF"
            }
            subToMidRatio in 1.05f..1.40f && growlIntensity < 1.15f -> {
                activeRoute = houseRoute
                detectedGenreName = "ХАУС / ГРУВ"
            }
            else -> {
                activeRoute = dubstepRoute
                detectedGenreName = if (growlIntensity > 1.35f) "DEATHSTEP / TEAROUT" else "ДАБСТЕП / ДРОП"
            }
        }

        // Блок агрессивного Whiplash (Паралич от Snare и рывок на Kick)
        if (kickDelta > kickHitThreshold * 1.4f && tension > 0.45f) {
            kineticVelocityBoost = 0.28 // Дикий рывок вращения вперед
            kickFlash = 1.0f
            if (activeRoute == dubstepRoute) {
                shakeImpulse = (b / dynamicPeak * 1.1).coerceIn(0.0, 1.4) // Матрицу трясет ощутимо сильнее!
            }
        } else if (snareDelta > snareHitThreshold * 1.3f && tension > 0.45f) {
            kineticVelocityBoost = -0.05 // Микро-рывок в обратную сторону
            rhythmicBrake = -0.35 // Хлесткий паралич! На долю секунды анимация бьется о стену и дергается назад
        }

        val now = SystemClock.elapsedRealtime()
        val instantEnergy = b * 0.6f + m * 0.25f + h * 0.15f
        if (instantEnergy > peakDropEnergy) peakDropEnergy = instantEnergy
        else peakDropEnergy = max(12f, peakDropEnergy * 0.985f)

        if (tension > 0.50f && instantEnergy < 2.0f && !isPreDropVoid && hasRhythmicDrive) {
            isPreDropVoid = true
            voidTimer = now
        }
        if (isPreDropVoid && now - voidTimer > 2000L) isPreDropVoid = false
        if (isPreDropVoid && (kickDelta > 6f || normBass > 0.45f)) {
            isPreDropVoid = false
            tension = 1.0f
            kickFlash = 1.0f
        }

        isStrobeGating = (activeRoute == dubstepRoute && tension > 0.80f && normBass > 0.55f &&
                instantEnergy < peakDropEnergy * 0.32f && !isPreDropVoid)

        if (tension > 0.45f && now - patternCooldown > 6000L && !isManualPatternOverride && morphProgress >= 1.0f) {
            if (nextPatternQueued < 0) {
                routeStep = (routeStep + 1) % activeRoute.size
                nextPatternQueued = activeRoute[routeStep]
            }

            if (kickDelta > kickHitThreshold && nextPatternQueued >= 0) {
                prevDropPattern = currentDropPattern
                currentDropPattern = nextPatternQueued
                nextPatternQueued = -1
                startMorph()
                patternCooldown = now
            }
        }

        if (activeRoute == dubstepRoute && normBass > 0.65f && hasRhythmicDrive) {
            tension = max(tension, 0.98f)
        } else if (hasRhythmicDrive && tension > 0.35f) {
            tension = (tension + 0.03f).coerceAtMost(0.85f)
        } else {
            tension = (tension - 0.055f).coerceAtLeast(0.12f)
        }
    }

    private fun updateSynthetic140BpmEngine() {
        val elapsed = (SystemClock.elapsedRealtime() - demoStartTime) / 1000.0
        val beatDuration = 60.0 / 140.0
        val totalBeats = elapsed / beatDuration
        val beatPhase = totalBeats % 1.0
        val currentBeatInBar = (totalBeats.toInt() % 4)
        val barIndex = (totalBeats.toInt() / 4) % 16

        isMusicSilent = false
        detectedGenreName = "СИНТЕТИКА // TEAROUT 140"
        activeRoute = dubstepRoute

        val isKick = (currentBeatInBar == 0 || currentBeatInBar == 2) && beatPhase < 0.25
        val isSnare = (currentBeatInBar == 1 || currentBeatInBar == 3) && beatPhase < 0.20
        val isHiHat = (totalBeats * 4) % 1.0 < 0.15

        val kickPower = if (isKick) (1.0 - beatPhase / 0.25).toFloat() * 45f else 0f
        val snarePower = if (isSnare) (1.0 - beatPhase / 0.20).toFloat() * 38f else 0f
        val hatPower = if (isHiHat) 25f else 5f

        if (barIndex == 15 && currentBeatInBar >= 2) {
            isPreDropVoid = true
            targetBass = 0f; targetMid = 0f; targetHigh = 0f
            tension = 0.95f
        } else {
            if (isPreDropVoid && barIndex == 0) {
                isPreDropVoid = false
                kickFlash = 1.0f
                tension = 1.0f
            }
            targetBass = kickPower + 4f
            targetMid = (sin(elapsed * 8.0) * 15.0 + 20.0).toFloat().coerceAtLeast(0f)
            targetHigh = snarePower + hatPower

            tension = when {
                barIndex < 6 -> 0.20f
                barIndex < 14 -> 0.25f + ((barIndex - 6) / 8f) * 0.55f
                else -> 0.98f
            }
        }

        if (isKick && beatPhase < 0.08) {
            kineticVelocityBoost = 0.16
            shakeImpulse = 0.60
            kickFlash = 0.95f
        } else if (isSnare && beatPhase < 0.08) {
            kineticVelocityBoost = -0.14
            rhythmicBrake = 0.20
        }

        if (currentBeatInBar == 0 && beatPhase < 0.10 && morphProgress >= 1.0f) {
            val nextP = (currentDropPattern + 1) % 9
            prevDropPattern = currentDropPattern
            currentDropPattern = nextP
            startMorph()
        }
    }

    private fun processPhysicsAndRender() {
        val now = SystemClock.elapsedRealtime()

        if (!isSilentDemoMode && (audioManager?.isMusicActive == true) && (now - lastFftTime > 1200L)) {
            lastFftTime = now
            initAudioVisualizer()
        }

        if (isSilentDemoMode) {
            updateSynthetic140BpmEngine()
        }

        if (isMusicSilent && !isSilentDemoMode) {
            renderBlank()
            liveFrameListener?.invoke(frameBuffer, "ПАУЗА // ТИШИНА", 0f, 0f, 0f, 0f)
            return
        }

        if (now - calmPatternTimer > 18000L) {
            calmPatternTimer = now
            calmPatternType = (calmPatternType + 1) % 3
        }

        if (morphProgress < 1.0f) {
            val elapsed = now - morphStartTime
            morphProgress = (elapsed.toFloat() / morphDurationMs).coerceIn(0.0f, 1.0f)
        }

        var isTimeFrozen = false
        var strobeDim = 1.0

        if (isStrobeGating) {
            strobeTick++
            val isStrobeOffPhase = (strobeTick % 6) < 3
            if (isStrobeOffPhase) {
                isTimeFrozen = true
                strobeDim = 0.50
            }
        } else {
            strobeTick = 0
        }

        if (!isTimeFrozen && !isPreDropVoid) {
            // Экспоненциальные атаки и тугие спады (Black Label Pump)
            smoothBass = if (targetBass > smoothBass) {
                smoothBass * 0.20f + targetBass * 0.80f // Моментальный пробой матрицы
            } else {
                smoothBass * 0.82f + targetBass * 0.18f // Тугая натяжка при возврате
            }

            smoothHigh = if (targetHigh > smoothHigh) {
                smoothHigh * 0.10f + targetHigh * 0.90f // Хлесткий удар рабочего барабана
            } else {
                smoothHigh * 0.75f + targetHigh * 0.25f
            }

            smoothMid = smoothMid * 0.78f + targetMid * 0.22f // Скрежет оставляем тягучим и плавным

            val normB = (smoothBass / dynamicPeak).coerceIn(0f, 1f)
            val calculatedT = if (activeRoute == melodicRoute) (normB * 0.35f + (smoothMid / 40f) * 0.25f).coerceIn(0.12f, 0.45f)
            else (normB * 0.75f + (rhythmFlux / 20f).coerceIn(0f, 1f) * 0.25f).coerceIn(0f, 1f)

            val rate = if (calculatedT > tension) 0.08f else 0.045f
            tension = tension * (1f - rate) + calculatedT * rate

            val baseSpeed = when {
                tension <= 0.25f -> 0.002
                tension <= 0.75f -> {
                    val p = (tension - 0.25) / 0.50
                    0.015 + p * 0.075
                }
                else -> 0.035
            }

            // Инерция стала более вязкой для паралича
            kineticVelocityBoost *= 0.82
            rhythmicBrake = rhythmicBrake * 0.80 + 1.0 * 0.20

            // ФИЛЬТР СПОКОЙСТВИЯ: Жесткий блок любой агрессии на вокале
            if ((tension < 0.35f || hitDensity < 0.3f) && testModeOverride != 2) {
                kineticVelocityBoost = 0.0
                shakeImpulse = 0.0
                rhythmicBrake = 1.0
            }

            val totalSpeed = (baseSpeed + kineticVelocityBoost) * rhythmicBrake
            shurikenContinuousAngle += totalSpeed
            outerRingAngle -= baseSpeed * 0.85
            polyAngle3D += (totalSpeed * 0.9)

            val tearoutWave = abs(sin(timeSec * 4.0)).pow(5.0)
            val tearoutVelocity = (baseSpeed * 0.3 + tearoutWave * 0.14 + kineticVelocityBoost * 1.5) * rhythmicBrake
            tearoutSawAngle += tearoutVelocity

            timeSec += 0.025
        }

        if (shakeImpulse > 0.04) {
            shakeX = (Random.nextDouble() - 0.5) * shakeImpulse
            shakeY = (Random.nextDouble() - 0.5) * shakeImpulse
            shakeImpulse *= 0.65
        } else {
            shakeX = 0.0; shakeY = 0.0
            shakeImpulse = 0.0
        }

        kickFlash *= 0.6f
        buildupHitStrobe *= 0.75f

        precalculate3DOctahedron(polyAngle3D, 6.2 + (smoothBass / dynamicPeak) * 2.0 + kickFlash * 1.8)

        renderMasterVisual(tension, strobeDim)
    }

    private fun precalculate3DOctahedron(angle: Double, scale: Double) {
        val cosY = cos(angle); val sinY = sin(angle)
        val cosX = cos(angle * 0.7); val sinX = sin(angle * 0.7)

        for (i in 0 until 6) {
            val v = octVertices[i]
            val x1 = v[0] * cosY + v[2] * sinY
            val z1 = -v[0] * sinY + v[2] * cosY
            val y2 = v[1] * cosX - z1 * sinX
            val z2 = v[1] * sinX + z1 * cosX
            projOctX[i] = x1 * scale
            projOctY[i] = y2 * scale
            projOctZ[i] = z2
        }
    }

    private fun renderMasterVisual(t: Float, strobeDim: Double) {
        val centerX = 12.0 + shakeX
        val centerY = 12.0 + shakeY
        val bNorm = (smoothBass / dynamicPeak).coerceIn(0.1f, 3.0f).toDouble()
        val mNorm = (smoothMid / 16.0).coerceIn(0.1, 2.5)
        val hNorm = (smoothHigh / 14.0).coerceIn(0.0, 2.5)

        val smoothMorph = 0.5 - 0.5 * cos(morphProgress * PI)

        val wCalm: Double
        val wBld: Double
        val wDrp: Double

        when (testModeOverride) {
            1 -> { wCalm = 1.0; wBld = 0.0; wDrp = 0.0 }
            2 -> { wCalm = 0.0; wBld = 0.0; wDrp = 1.0 }
            else -> {
                wCalm = (1.0 - t * 2.0).coerceIn(0.0, 1.0)
                wBld = (1.0 - abs(t - 0.5) * 2.4).coerceIn(0.0, 1.0)
                wDrp = ((t - 0.45) * 2.0).coerceIn(0.0, 1.0)
            }
        }
        val sumW = wCalm + wBld + wDrp + 1e-5

        for (y in 0 until matrixSide) {
            for (x in 0 until matrixSide) {
                val idx = y * matrixSide + x
                var dx = x - centerX
                var dy = y - centerY

                // === SPATIAL DISTORTION LAYER (BLACK LABEL) ===

                // 1. Tearout Glitch Slicer (Горизонтальные разрывы пространства)
                if (activeRoute == dubstepRoute && growlIntensity > 1.3f) {
                    val stripe = (y.toDouble() + timeSec * 22.0).toInt() % 5
                    if (stripe == 0) {
                        dx += sin(y * 2.0 + timeSec * 15.0) * growlIntensity * 1.7 // Ослабили!
                    } else if (stripe == 2) {
                        dx -= cos(y * 1.5 - timeSec * 12.0) * growlIntensity * 1.1 // Сделали аккуратнее
                    }
                }

                var r = hypot(dx, dy)
                var theta = atan2(dy, dx)

                // 2. Black Hole Implosion (Засасываем фигуру в вакуум перед дропом)
                var singularityDot = 0.0
                if (isPreDropVoid) {
                    val elapsedVoid = (SystemClock.elapsedRealtime() - voidTimer) / 1000.0

                    // Фигура сжимается в черную дыру (читаем данные всё дальше от центра)
                    r += (elapsedVoid * elapsedVoid) * 20.0
                    // Жестко закручиваем свет (эффект гравитационного линзирования)
                    theta += (elapsedVoid * 12.0) / (r + 1.0)

                    // Обновляем оси под 3D-Октаэдр и HD Пилу
                    dx = r * cos(theta)
                    dy = r * sin(theta)

                    // Оставляем только яркую светящуюся точку (ядро сингулярности)
                    val realR = hypot(x - centerX, y - centerY)
                    singularityDot = exp(-realR * 3.5) * 0.8
                }

                // === END DISTORTION LAYER ===

                // Отсекаем всё, что улетело за края матрицы, если это не центральная точка вакуума
                if (r > 12.5 && singularityDot == 0.0) {
                    frameBuffer[idx] = 0
                    continue
                }

                // 1. СПОКОЙНЫЙ РЕЖИМ
                val vCalm = when (calmPatternType) {
                    0 -> {
                        val zoom = (ln(r + 0.1) * 1.8 - timeSec * 0.4)
                        val recursiveRing = abs(sin(zoom * PI)).pow(3.0)
                        (recursiveRing * exp(-r * 0.12) * (0.4 + bNorm * 0.3)).coerceIn(0.0, 1.0)
                    }
                    1 -> {
                        // "Лунное Гало" - органическое плазменное кольцо
                        val baseHalo = 6.5 + bNorm * 1.5
                        // Плавные искажения по кругу от вокала (Mid) и высоких (High)
                        val organicWarp = sin(theta * 3.0 + timeSec * 1.4) * (1.0 + mNorm * 1.5) +
                                cos(theta * 5.0 - timeSec * 0.9) * (hNorm * 1.2)

                        val deformedR = baseHalo + organicWarp
                        // Само кольцо с очень мягкими краями
                        val plasmaRing = exp(-abs(r - deformedR) * 1.5) * (0.5 + mNorm * 0.4)
                        // Легкое дышащее ядро внутри
                        val innerGlow = exp(-r * 1.1) * (0.25 + hNorm * 0.25)

                        max(plasmaRing, innerGlow).coerceIn(0.0, 1.0)
                    }
                    else -> {
                        val irisRot = theta + timeSec * 0.35
                        val irisBlades = abs(sin(irisRot * 6.0 + r * 0.3)).pow(2.2)
                        val ring1 = exp(-abs(r - 5.0) * 1.5) * irisBlades * 1.2
                        val ring2 = exp(-abs(r - 8.8) * 1.6) * (sin(irisRot * 12.0) * 0.4 + 0.6) * 1.1
                        val centerLens = exp(-r * 1.4) * 0.9
                        max(max(ring1, ring2), centerLens) * (0.6 + bNorm * 0.4)
                    }
                }

                // 2. РАЗГОН: Рельсотрон
                val inSpeed = timeSec * (6.0 + t * 14.0)
                val inWaves = abs(sin((r + inSpeed) * 0.9)).pow(4.0)
                val cardinalAxes = min(abs(dx), abs(dy))
                val railBeams = exp(-cardinalAxes * 1.8) * (1.0 - r / 12.5)
                val centerCoreCharge = exp(-r * (1.5 - t * 0.8)) * (0.4 + t * 0.6)
                val hitPulse = exp(-r * 0.6) * (buildupHitStrobe * 0.8)
                val vBuildup = (railBeams * inWaves * 1.2 + centerCoreCharge + hitPulse).coerceIn(0.0, 1.0)

                // 3. БОЕВОЙ ДРОП
                val valOld = evaluateDynamicDropVisual(prevDropPattern, dx, dy, r, theta, bNorm, mNorm, hNorm, t)
                val valNew = evaluateDynamicDropVisual(currentDropPattern, dx, dy, r, theta, bNorm, mNorm, hNorm, t)
                val vMeat = valOld * (1.0 - smoothMorph) + valNew * smoothMorph

                val flash = if (kickFlash > 0.1f) exp(-r * 0.45) * kickFlash.toDouble() * 1.2 else 0.0
                val vDropFull = max(vMeat, flash).coerceIn(0.0, 1.0)

                var blended = (vCalm * (wCalm / sumW) + vBuildup * (wBld / sumW) + vDropFull * (wDrp / sumW)) * strobeDim

                // Перекрываем всё это "Черной дырой", если она сейчас активна
                blended = max(blended, singularityDot)

                val gamma = 2.0 + t * 1.1
                val bright = (blended.pow(gamma) * 255.0).toInt().coerceIn(0, 255)

                frameBuffer[idx] = bright
            }
        }

        try {
            glyphManager?.setAppMatrixFrame(frameBuffer)

            val activeName = when {
                testModeOverride == 1 -> "[ТЕСТ СПОКОЙНЫЙ] " + (if (calmPatternType == 0) "Дзен-Туннель" else if (calmPatternType == 1) "Лунное Гало" else "Кибер-Затвор")
                testModeOverride == 2 -> "[ТЕСТ ДРОП] " + patternNames.getOrElse(currentDropPattern) { "Дроп" }
                isManualPatternOverride -> "[РУЧНОЙ] " + patternNames.getOrElse(currentDropPattern) { "Узор" }
                t < 0.35f -> "СПОКОЙНЫЙ [$detectedGenreName]: " + (if (calmPatternType == 0) "Дзен-Туннель" else if (calmPatternType == 1) "Лунное Гало" else "Кибер-Затвор")
                t < 0.75f -> "РАЗГОН: Зарядка Рельсотрона"
                else -> "ДРОП [$detectedGenreName]: " + patternNames.getOrElse(currentDropPattern) { "Дроп" }
            }

            liveFrameListener?.invoke(frameBuffer, activeName, t, targetBass, targetMid, targetHigh)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun evaluateDynamicDropVisual(
        patternId: Int,
        dx: Double,
        dy: Double,
        r: Double,
        theta: Double,
        bNorm: Double,
        mNorm: Double,
        hNorm: Double,
        t: Float
    ): Double {
        return when (patternId) {
            // [0] ТОЛСТЫЙ СЮРИКЕН 360° + HYPER-CORE
            0 -> {
                val twist = theta + shurikenContinuousAngle + (r * 0.12 * growlIntensity)
                val bladeDist = abs(r * sin(twist * 2.0))
                val bladeLen = 8.2 + bNorm * 2.5 + kickFlash * 1.8
                val bladeSharpness = exp(-bladeDist * (0.50 - kickFlash * 0.15)) * if (r < bladeLen) 1.35 else 0.0

                val innerTwist = theta - shurikenContinuousAngle * 1.8
                val innerBladeDist = abs(r * sin(innerTwist * 2.0))
                val innerCoreBlade = exp(-innerBladeDist * 0.95) * if (r < 4.4) 1.25 else 0.0

                val ringTheta = theta + outerRingAngle
                val notchedRing = exp(-abs(r - (9.6 + bNorm * 1.4)) * 1.3) * (sin(ringTheta * 8.0) * 0.5 + 0.5)
                val corePulse = exp(-r * 0.8) * (0.85 + bNorm * 0.6)

                max(max(max(bladeSharpness, innerCoreBlade), corePulse), notchedRing) * (0.85 + bNorm * 0.45)
            }

            // [1] КВАНТОВЫЙ ГЕКСА-ЩИТ: СВЕРХСЖАТИЕ В ДАБСТЕПЕ «ДО ПОСЛЕДНЕГО РЯДА»
            1 -> {
                val rotTheta = theta + shurikenContinuousAngle
                val hexSector = PI / 3.0
                val hexMir = abs((rotTheta % hexSector) - (hexSector / 2.0))
                val hexDist = r * cos(hexMir)

                val dubstepSqueeze = (growlIntensity * 1.6 + hNorm * 0.8).coerceIn(0.0, 4.2)
                val kickExplosion = kickFlash * 2.2 + bNorm * 1.4

                val outerRadius = (11.5 - dubstepSqueeze + kickExplosion).coerceIn(6.5, 12.0)
                val midBaseRadius = (6.5 - dubstepSqueeze * 0.75 + kickExplosion * 0.8).coerceIn(3.8, 8.5)
                val outerOverlap = max(0.0, 1.4 - (outerRadius - midBaseRadius))
                val platesRadius = midBaseRadius - outerOverlap * 0.7

                val coreBaseRadius = 1.8 + (bNorm * 0.6 + kickFlash * 0.8)
                val midOverlap = max(0.0, 1.2 - (platesRadius - coreBaseRadius))
                val coreRadius = (coreBaseRadius - midOverlap * 0.5).coerceIn(1.1, 2.5)

                val innerHexCore = exp(-abs(hexDist - coreRadius) * 2.6) * (1.1 + bNorm * 0.7 + kickFlash * 0.8)
                val centerLightDot = exp(-r * 1.8) * (1.1 + kickFlash * 0.9)

                val chevron = abs(sin(rotTheta * 3.0)).pow(3.0) * (0.8 + bNorm * 0.5)
                val mainPlates = exp(-abs(hexDist - (platesRadius + chevron)) * 1.8) * (1.2 + mNorm * 0.5)

                val outerHexMir = abs(((theta - outerRingAngle) % hexSector) - (hexSector / 2.0))
                val outerHexDist = r * cos(outerHexMir)
                val outerBarrier = exp(-abs(outerHexDist - outerRadius) * 2.0) * (0.9 + hNorm * 0.6)
                val cornerClamps = exp(-abs(r - outerRadius) * 1.8) * abs(sin(rotTheta * 3.0)).pow(5.0) * (1.3 + growlIntensity * 0.5)

                val bastion = max(max(mainPlates, innerHexCore), max(outerBarrier, cornerClamps))
                max(bastion, centerLightDot).coerceIn(0.0, 1.0)
            }

            // [2] АКУСТИЧЕСКИЙ ПУЛЬСАР -> КИНЕМАТИЧЕСКИЙ РАЗРЕЗ СФЕРЫ
            2 -> {
                // Угол разреза меняется со временем, но резко
                val slashAngle = floor(timeSec * 1.5) * (PI / 3.0) + (polyAngle3D * 0.1)
                val nx = -sin(slashAngle)
                val ny = cos(slashAngle)

                // На какой стороне от разреза находится пиксель
                val distToCut = dx * nx + dy * ny
                val side = if (distToCut > 0) 1.0 else -1.0

                // Полусферы разлетаются при ударе бочки!
                val splitOffset = kickFlash.pow(1.5f) * 3.5
                val sphereDx = dx - (nx * side * splitOffset)
                val sphereDy = dy - (ny * side * splitOffset)
                val sphereR = hypot(sphereDx, sphereDy)

                // Рисуем текстурированную сферу
                val texture = 0.6 + 0.4 * sin(sphereR * 2.5 - timeSec * 6.0)
                val sphere = if (sphereR <= 6.5) texture * (0.5 + bNorm * 0.5) else 0.0

                // Сам луч разреза (сделали толще: 1.1 вместо 1.5)
                val beam = exp(-abs(distToCut) * 1.1) * (kickFlash * 1.8 + 0.2) * (if (r < 11.0) 1.0 else 0.0)

                max(sphere, beam).coerceIn(0.0, 1.0)
            }

            // [3] КИБЕР-РЕАКТОР: КРАЙ -> ТОЧКИ 1 -> ТОЛСТЫЙ ОБРУЧ -> ТОЧКИ 2 -> ЯДРО
            3 -> {
                val outerDotsRadius = 10.8
                val outerDotTheta = theta + (shurikenContinuousAngle * 1.8)
                val outerDotsShape = (sin(outerDotTheta * 12.0) * 0.5 + 0.5).pow(8.0)
                val outerDotsRing = exp(-abs(r - outerDotsRadius) * 2.2) * outerDotsShape * 1.5

                val hoopRadius = 7.8 + (sin(timeSec * 4.0) * 0.3)
                val hoopThickness = 1.4 + (bNorm * 0.5)
                val hoopBody = exp(-abs(r - hoopRadius) * (1.5 / hoopThickness)) * (1.3 + kickFlash * 0.9)
                val hoopTexture = (sin((theta - shurikenContinuousAngle * 0.8) * 8.0) * 0.25 + 0.75)
                val thickHoop = hoopBody * hoopTexture

                val innerDotsRadius = 5.0
                val innerDotTheta = theta - (shurikenContinuousAngle * 2.4)
                val innerDotsShape = (sin(innerDotTheta * 8.0) * 0.5 + 0.5).pow(8.0)
                val innerDotsRing = exp(-abs(r - innerDotsRadius) * 2.2) * innerDotsShape * 1.4

                val coreRadius = (1.4 + bNorm * 0.8 + kickFlash * 0.8).coerceIn(1.2, 2.5)
                val coreCorona = exp(-abs(r - coreRadius) * 2.5) * (1.1 + bNorm * 0.6)
                val coreCenter = exp(-r * 2.0) * (1.2 + kickFlash * 1.0)
                val reactorCore = max(coreCorona, coreCenter)

                max(max(outerDotsRing, thickHoop), max(innerDotsRing, reactorCore)).coerceIn(0.0, 1.0)
            }

            // [4] САКРАЛЬНЫЙ ЛОТОС: ЖИВОЕ РАСПУСКАНИЕ ЦВЕТКА
            4 -> {
                val rotTheta = theta + shurikenContinuousAngle * 0.38
                val bloomProgress = (t * 0.65 + bNorm * 0.45 + kickFlash * 0.35).coerceIn(0.0, 1.0)

                val petalProfile1 = cos(rotTheta * 6.0)
                val rTier1 = 3.8 + (bloomProgress * 5.2) + petalProfile1 * (1.0 + bloomProgress * 1.4)
                val tier1Petals = exp(-abs(r - rTier1) * 1.5) * (1.0 + bloomProgress * 0.45)

                val tier2Growth = ((bloomProgress - 0.20) / 0.80).coerceIn(0.0, 1.0)
                val petalProfile2 = cos((rotTheta + PI / 6.0) * 6.0)
                val rTier2 = 2.0 + (tier2Growth * 5.2) + petalProfile2 * (0.8 + tier2Growth * 1.6)
                val tier2Petals = exp(-abs(r - rTier2) * 1.8) * (tier2Growth.pow(1.4) * (1.3 + bNorm * 0.5))

                val coreRadius = 1.4 + (bloomProgress * 1.0)
                val budCorona = exp(-abs(r - coreRadius) * 2.2) * (0.9 + bNorm * 0.5)
                val centerPearl = exp(-r * 2.0) * (1.1 + kickFlash * 0.8)

                val flower = max(max(tier1Petals, tier2Petals), max(budCorona, centerPearl))
                val aura = exp(-abs(r - 10.8) * 1.8) * (bloomProgress * 0.25 * (hNorm * 0.3 + 0.1))
                max(flower, aura).coerceIn(0.0, 1.0)
            }

            // [5] 3D-ОКТАЭДР NSD: VOLUMETRIC Z-DEPTH + ДВОЙНАЯ ДИАГОНАЛЬ + ОСИ КООРДИНАТ
            5 -> {
                var maxEdgeVal = 0.0

                for (edge in octEdges) {
                    val idx1 = edge.first; val idx2 = edge.second
                    val d = distToSegment(dx, dy, projOctX[idx1], projOctY[idx1], projOctX[idx2], projOctY[idx2])
                    val avgZ = (projOctZ[idx1] + projOctZ[idx2]) / 12.0
                    val depthWeight = (0.95 + avgZ * 0.48).coerceIn(0.52, 1.48)
                    val edgeLine = exp(-d * 1.55) * depthWeight
                    if (edgeLine > maxEdgeVal) maxEdgeVal = edgeLine
                }

                val axisX = distToSegment(dx, dy, projOctX[2], projOctY[2], projOctX[3], projOctY[3])
                val axisY = distToSegment(dx, dy, projOctX[0], projOctY[0], projOctX[1], projOctY[1])
                val axisZ = distToSegment(dx, dy, projOctX[4], projOctY[4], projOctX[5], projOctY[5])
                val internalAxes = (exp(-axisX * 2.2) + exp(-axisY * 2.2) + exp(-axisZ * 2.2)) * 0.28

                var maxNodeVal = 0.0
                for (i in 0 until 6) {
                    val distNode = hypot(dx - projOctX[i], dy - projOctY[i])
                    val zWeight = (1.0 + (projOctZ[i] / 6.0)).coerceIn(0.5, 1.8)
                    val nodeGlow = exp(-distNode * 1.8) * zWeight * (0.9 + kickFlash * 0.6)
                    if (nodeGlow > maxNodeVal) maxNodeVal = nodeGlow
                }

                val diag1 = sin((dx + dy) * 1.6 + timeSec * 2.2)
                val diag2 = cos((dx - dy) * 1.6 - timeSec * 1.8)
                val dualDiagonalGrid = abs(diag1 * diag2).pow(4.0) * (0.24 + mNorm * 0.16)

                val centerAnchor = exp(-r * 1.8) * (0.75 + kickFlash * 0.8)

                max(max(max(maxEdgeVal, maxNodeVal), internalAxes), max(centerAnchor, dualDiagonalGrid)).coerceIn(0.0, 1.0)
            }

            // [6] НЕБУЛА-РОЗА: СПИРОГРАФ
            6 -> {
                val kineticSpin = shurikenContinuousAngle * 0.75 + (kineticVelocityBoost * 1.8)
                val rotTheta = theta + kineticSpin

                val kickSpring = (kickFlash * 2.2 + bNorm * 1.2).coerceIn(0.0, 3.4)

                val petalWave = cos(rotTheta * 8.0) * (1.8 + mNorm * 0.9)
                val roseRadius = 4.8 + kickSpring + petalWave
                val roseBody = exp(-abs(r - roseRadius) * 1.5) * (1.25 + mNorm * 0.5)

                val innerTheta = theta - kineticSpin * 1.5
                val innerWave = cos(innerTheta * 4.0) * 1.3
                val innerBud = exp(-abs(r - (2.6 + innerWave + kickFlash * 0.6)) * 2.2) * (1.1 + bNorm * 0.7)

                val outerAuraTheta = rotTheta * 2.0
                val stardustSparks = exp(-abs(r - (roseRadius + 1.4)) * 1.8) * abs(sin(outerAuraTheta * 4.0)).pow(5.0) * (hNorm * 0.8)
                val centerStar = exp(-r * 1.6) * (1.15 + kickFlash * 0.9)

                max(max(roseBody, innerBud), max(stardustSparks, centerStar)).coerceIn(0.0, 1.0)
            }

            // [7] 3D-КАЛЕЙДОСКОП: БИО-КРИСТАЛЛ
            7 -> {
                val melodicMood = (mNorm * 0.6 + hNorm * 0.4 - bNorm * 0.2).coerceIn(-0.5, 1.5)
                val isLyrical = melodicMood > 0.3

                val foldAngle = polyAngle3D * (if (isLyrical) 0.85 else 1.25)
                val cosP = cos(foldAngle); val sinP = sin(foldAngle)
                val rotX = dx * cosP - dy * sinP
                val rotY = dx * sinP + dy * cosP

                val zMod = if (isLyrical) (1.2 + mNorm * 1.2) else (2.0 + bNorm * 2.2)
                val rotZ = sin(r * 0.45 + timeSec * (if (isLyrical) 2.0 else 3.5)) * zMod

                val creaseShift = 0.55 + (sin(timeSec * 2.2 + mNorm) * 0.08)
                val fold3D = abs(rotX * rotY) / (rotZ * rotZ + 1.0)
                val hyperOrigami = exp(-abs(fold3D - creaseShift) * 3.0) * (1.35 + bNorm * 0.5)

                val bioGlow = (0.20 + melodicMood * 0.15).coerceIn(0.12, 0.35)
                val bioVeins = abs(sin(r * 1.3 - timeSec * 3.0 + sin(theta * 3.0) * 1.2)).pow(4.0) * bioGlow
                val bioPulse = exp(-abs(r - 6.0) * 2.0) * (bioGlow * 0.65)

                val corePulsar = exp(-r * 1.2) * (0.8 + bNorm * 0.5)

                max(hyperOrigami, corePulsar) + (bioVeins + bioPulse).coerceIn(0.0, 1.0)
            }

            // [8] ЧЁТКАЯ TEAROUT-ПИЛА: HD ДИСК (Исправленная геометрия)
            8 -> {
                val gTheta = atan2(dy, dx) + tearoutSawAngle
                val tearRage = (bNorm * 0.7 + growlIntensity * 0.5).coerceIn(0.0, 1.8)

                // 6 четких, агрессивных зубьев
                val teethBase = abs(sin(gTheta * 3.0)).pow(3.0)
                val tooth = teethBase * (1.2 + tearRage * 2.0)
                val bladeRadius = 5.5 + tooth + (kickFlash * 0.8)

                // Тонкое, но яркое лезвие (коэффициент 2.5 делает линию тонкой)
                val sawBlade = exp(-abs(r - bladeRadius) * 2.5) * (1.1 + kickFlash * 0.6)

                // Механическое кольцо в центре (ось)
                val coreDist = abs(r - 2.5)
                val coreRing = exp(-coreDist * 3.0) * (0.8 + mNorm * 0.6)

                // Челюсти только на жестком рычании
                val jaws = exp(-abs(r - (9.5 - kickFlash)) * 2.5) * abs(sin(gTheta * 2.0)).pow(5.0) * growlIntensity

                max(sawBlade, max(coreRing, jaws)).coerceIn(0.0, 1.0)
            }

            // [9] ИСТИННЫЙ КАЛЕЙДОСКОП (Неразрывный Полярный Морфинг а-ля Yabba Dabs)
            else -> {
                val nPoints = 8.0 // 8 лучей мандалы/снежинки
                // Базовое вращение, которое дергается (Whiplash) вместе с физикой
                val rotTheta = theta + shurikenContinuousAngle * 0.6

                // ФАЗА МОРФИНГА: плавно перетекает от 0.0 до 1.0
                // 0.0 = Мягкий Лотос (Цветок)
                // 1.0 = Острая жесткая Снежинка/Звезда
                val morphState = (sin(timeSec * 2.0 + growlIntensity * 0.5).pow(2)).coerceIn(0.0, 1.0)

                // СУПЕРФОРМУЛА: Гарантирует единую, замкнутую и симметричную линию
                val lobeBase = cos(rotTheta * (nPoints / 2.0))
                val lobeDist = abs(lobeBase)

                // Меняем форму "зубьев" в зависимости от морфинга
                val sharpness = 1.0 + (morphState * 5.0)
                val wave = lobeDist.pow(sharpness)

                // Внутренний и внешний размах лучей
                val rMin = 3.5 + bNorm
                val rMax = 7.0 + (bNorm * 1.5) + (morphState * 3.5) + (kickFlash * 2.0)

                val targetR = rMin + (rMax - rMin) * wave

                // Рисуем ТОЛСТУЮ неразрывную линию контура.
                // Используем линейный градиент, чтобы на 25x25 она не била на пиксели!
                val thickness = 1.6 + (kickFlash * 0.6) // Линия жирнеет на басах
                val dist = abs(r - targetR)
                val outline = (1.0 - (dist / thickness)).coerceIn(0.0, 1.0)

                // Ядро (мягкое свечение внутри снежинки)
                val core = if (r < targetR - 0.5) (0.2 + bNorm * 0.15) * (1.0 - r / targetR) else 0.0

                // ОСКОЛКИ: Те самые точки, которые вылетают за пределы снежинки и втягиваются обратно
                val shardRadius = 11.5 - (kickFlash * 4.0) // На удар бочки летят к центру
                // Выделяем оси, на которых должны быть осколки
                val axisWave = abs(sin(rotTheta * nPoints))
                val isOnAxis = if (axisWave < 0.2) (1.0 - axisWave * 5.0) else 0.0
                val shardDistDelta = abs(r - shardRadius)
                val shards = if (shardDistDelta < 2.0) isOnAxis * (1.0 - shardDistDelta / 2.0) * (0.8 + hNorm) else 0.0

                // Финальный микс: Жирная снежинка + свечение внутри + летящие осколки снаружи
                max(outline * (1.2 + kickFlash) + core, shards).coerceIn(0.0, 1.0)
            }
        }
    }

    private fun distToSegment(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1; val dy = y2 - y1
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / lenSq).coerceIn(0.0, 1.0)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    private fun renderBlank() {
        frameBuffer.fill(0)
        try { glyphManager?.setAppMatrixFrame(frameBuffer) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        renderHandler.removeCallbacks(renderRunnable)
        visualizer?.enabled = false
        visualizer?.release()
        renderBlank()
        glyphManager?.unInit()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}