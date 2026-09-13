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

// Внутренняя Inline-математика: Без аллокаций. Быстро и Лаконично.
private inline fun Double.pow2(): Double = this * this
private inline fun Double.pow3(): Double = this * this * this
private inline fun Double.pow4(): Double { val p = this.pow2(); return p * p }
private inline fun Double.pow5(): Double { val p = this.pow2(); return p * p * this }
private inline fun Double.pow6(): Double { val p = this.pow3(); return p * p }

// Функция создания идеальных лазерных краев для SDF-объектов.
// Четче чем exp(-x). Почти полностью устраняет "мыльное свечение".
private inline fun sharpGlow(sdfValue: Double, spread: Double): Double {
    return exp(-(sdfValue * sdfValue) * spread)
}

// Супербыстрый PRNG без создания Random().
// Создает идеальную, плавно летящую звездную пыль. (Сиды непредсказуемые).
private fun hashDust(x: Double, y: Double, id: Double): Double {
    val st = sin(x * 12.9898 + y * 78.233 + id * 39.816) * 43758.5453123
    return st - floor(st)
}
class GlyphVisualizerService : Service() {

    companion object {
        var liveFrameListener: ((IntArray, String, Float, Float, Float, Float, String) -> Unit)? = null
    }

    private var glyphManager: GlyphMatrixManager? = null
    private var visualizer: Visualizer? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val matrixSide = 25
    private val totalLeds = matrixSide * matrixSide
    private val frameBuffer = IntArray(totalLeds)

    private val renderHandler = Handler(Looper.getMainLooper())
    private val frameIntervalMs = 28L

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

    private var lastKickDelta = 0f
    private var lastSnareDelta = 0f
    private var rawVolumeEnergy = 0f
    private var lastFlyOutTime = 0L

    private val melodicRoute = intArrayOf(4, 2, 6, 7)
    private val houseRoute = intArrayOf(1, 3, 2, 5)
    private val dubstepRoute = intArrayOf(0, 8, 1, 5, 3)

    private var activeRoute = melodicRoute
    private var routeStep = 0
    private var detectedGenreName = "MELODIC / CHILL"

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

    // ГЛОБАЛЬНАЯ ОШИБКА ИСПРАВЛЕНА: Накапливаем углы всегда в ПЛЮС (+).
    // Минусы раздаем только в рендере (pattern id).
    private var absContinuousAngle = 0.0
    private var kineticVelocityBoost = 0.0
    private var rhythmicBrake = 1.0

    // Вектор скретча (+1.0 или -1.0) с физическим затуханием (моментом инерции)
    private var targetSpinDirection = 1.0
    private var actualSpinDirection = 1.0

    private var shakeImpulse = 0.0
    private var shakeX = 0.0
    private var shakeY = 0.0
    private var kickFlash = 0f

    private var flyOutTrigger = 0.0
    private var flyOutPanX = 0.0
    private var flyOutPanY = 0.0

    private var isStrobeGating = false
    private var strobeTick = 0
    private var peakDropEnergy = 10f

    private var currentDropPattern = 0
    private var prevDropPattern = 0
    private var morphProgress = 1.0f
    private var morphStartTime = 0L
    private val morphDurationMs = 500L
    private var nextPatternQueued = -1
    private var patternCooldown = 0L

    private var calmPatternType = 0
    private var calmPatternTimer = 0L

    private val patternNames = arrayOf(
        "[0] 360° Shuriken + Hyper-Core",
        "[1] Quantum Hexa-Shield (Smart Gap)",
        "[2] Acoustic Pulsar: Spherical Slash",
        "[3] Cyber-Reactor: Hoop + Satellites",
        "[4] Sacred Lotus: Blade Bloom",
        "[5] 3D-Octahedron NSD (Volumetric)",
        "[6] Nebula-Rose: Spirograph",
        "[7] 3D-Kaleidoscope: Bio-Crystal",
        "[8] Tearout-Saw (HD Disc)",
        "[9] True Kaleidoscope (Yabba Dabs)"
    )

    private val octVertices = arrayOf(
        doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0),
        doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0)
    )
    private val octEdgesFlat = intArrayOf(
        0, 2,  0, 3,  0, 4,  0, 5,
        1, 2,  1, 3,  1, 4,  1, 5,
        2, 4,  4, 3,  3, 5,  5, 2
    )

    private var bassVel = 0f
    private val springForce = 0.55f
    private val friction = 0.40f
    private var glitchFrames = 0
    private var timeSec = 0.0

    private val projOctX = DoubleArray(6)
    private val projOctY = DoubleArray(6)
    private val projOctZ = DoubleArray(6)

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { reconnectVisualizerOnAudioChange() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { reconnectVisualizerOnAudioChange() }
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
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("ACTION")) {
            "PREV_PATTERN" -> {
                isManualPatternOverride = true
                prevDropPattern = currentDropPattern
                currentDropPattern = (currentDropPattern - 1 + 10) % 10
                startMorph()
            }
            "NEXT_PATTERN" -> {
                isManualPatternOverride = true
                prevDropPattern = currentDropPattern
                currentDropPattern = (currentDropPattern + 1) % 10
                startMorph()
            }
            "MODE_AUTO" -> { testModeOverride = 0; isManualPatternOverride = false }
            "MODE_FORCE_CALM" -> { testModeOverride = 1; isManualPatternOverride = false }
            "MODE_FORCE_DROP" -> { testModeOverride = 2; isManualPatternOverride = false }
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
        whitelistedPackages.clear()
        prefs.getStringSet("whitelisted_apps", null)?.let { whitelistedPackages.addAll(it) }
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
        val channel = NotificationChannel(channelId, "Glyph Audio Visualizer", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Matrix Glyph Visualizer"
            setShowBadge(false)
        }
        manager?.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("GLYPH // PURE MOTION MATRIX")
            .setContentText("Autonomous visualizer is running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setSilent(true).build()

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
                override fun onServiceConnected(name: ComponentName?) { glyphManager?.register(Glyph.DEVICE_23112) }
                override fun onServiceDisconnected(name: ComponentName?) {}
            })
        } catch (e: Exception) { e.printStackTrace() }
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
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun analyzeSound(fft: ByteArray) {
        var localAllowed = true
        if (whitelistedPackages.isNotEmpty()) {
            val activeApp = MediaNotificationListener.activeMediaAppPackage
            if (activeApp == null || !whitelistedPackages.contains(activeApp)) localAllowed = false
        }

        // Вычисляем RMS корня из бинов для выравнивания человеческого восприятия спектра
        var b = 0f; for (i in 2..16 step 2) b += (fft[i].toFloat().pow(2) + fft[i+1].toFloat().pow(2)).pow(0.5f)
        var m = 0f; for (i in 18..80 step 2) m += (fft[i].toFloat().pow(2) + fft[i+1].toFloat().pow(2)).pow(0.5f)
        var h = 0f; for (i in 82..220 step 2) h += (fft[i].toFloat().pow(2) + fft[i+1].toFloat().pow(2)).pow(0.5f)
        b /= 8f; m /= 31f; h /= 70f // h увеличен для большей чистоты верхних гармоник (снейр)

        val isMusicActiveInSystem = audioManager?.isMusicActive ?: false
        val rawSum = b + m + h

        if (!localAllowed || rawSum < 0.4f || (!isMusicActiveInSystem && rawSum < 2.5f)) {
            b = 0f; m = 0f; h = 0f
            silenceCounter++
            if (silenceCounter > 15) { // Сброс агрессии матрицы наступает быстрее для идеальных пауз в Тренировочной Группе 2 (Dr. Ozi)
                isMusicSilent = true; tension *= 0.35f
            }
        } else {
            silenceCounter = 0; isMusicSilent = false
        }

        if (isMusicSilent) {
            targetBass = 0f; targetMid = 0f; targetHigh = 0f; return
        }

        // Обновление Авто-Реквалификатора (АРУ)
        rollingMaxSignal = max(rollingMaxSignal * 0.995f, rawSum)
        val gainCompensator = if (rollingMaxSignal < 16f && rawSum > 0.4f) (24f / max(1.5f, rollingMaxSignal)).coerceIn(1.0f, 6.0f) else 1.0f

        b = (b * gainCompensator).coerceAtMost(70f)
        m = (m * gainCompensator).coerceAtMost(60f)
        h = (h * gainCompensator).coerceAtMost(55f)

        targetBass = b; targetMid = m; targetHigh = h

        // Транзиентная дифференциация (Реагируем на УДАР, а не просто громкость!)
        val kickDelta = b - prevRawBass
        val snareDelta = h - prevRawHigh
        prevRawBass = b; prevRawHigh = h

        // Экстремальные точки
        val kickHitThreshold = max(4.5f, dynamicPeak * 0.22f)
        val snareHitThreshold = max(5.0f, 15.0f * 0.2f)

        val isRealKick = kickDelta > kickHitThreshold && b > (dynamicFloor * 1.5f)
        val isRealSnare = snareDelta > snareHitThreshold

        lastKickDelta = kickDelta
        rawVolumeEnergy = rawSum

        val currentGrowl = (m / (15f * savedGrowlBias)).coerceIn(0f, 2.5f)
        growlIntensity = if (currentGrowl > growlIntensity) currentGrowl else growlIntensity * 0.90f // Жесткий Decay для Metalstep вокала

        subToMidRatio = b / (m + 0.1f)

        if (isRealKick || isRealSnare) {
            hitDensity = (hitDensity + 0.35f).coerceAtMost(2.0f)
            buildupHitStrobe = 1.0f
        } else {
            hitDensity *= 0.95f
        }

        rhythmFlux = rhythmFlux * 0.70f + (max(kickDelta, 0f) * 1.3f + max(snareDelta, 0f)) * 0.30f

        val normBass = ((b - dynamicFloor) / (dynamicPeak - dynamicFloor + 1e-3f)).coerceIn(0f, 1.5f)
        val hasRhythmicDrive = hitDensity > 0.35f || rhythmFlux > 5.0f
        val now = SystemClock.elapsedRealtime()

        if (isRealKick && tension > 0.40f) {
            kineticVelocityBoost = 0.55 // Агрессивный импульс вращения (учитывает Group 6 "Trench"!)
            kickFlash = 1.0f
            if (activeRoute == dubstepRoute && growlIntensity > 1.4f) glitchFrames = 2
        } else if (isRealSnare && tension > 0.65f && (now - lastFlyOutTime > 1500L)) {
            // Мгновенный сброс FlyOut-линзы (Удар Снейра) - Олдскул дабстеп триггер (Group 4)
            flyOutTrigger = 1.35
            val panAngle = hashDust(timeSec.toDouble(), h.toDouble(), now.toDouble()) * PI * 2.0
            flyOutPanX = cos(panAngle) * 55.0
            flyOutPanY = sin(panAngle) * 55.0
            lastFlyOutTime = now
        } else if (isRealSnare && !isRealKick) {
            rhythmicBrake = -0.6 // Обратный тормоз для синкопированного Riddim
        }

        // Резистентная адаптация спектральных окон
        if (b > dynamicPeak) dynamicPeak = dynamicPeak * 0.85f + b * 0.15f
        else dynamicPeak = max(18f, dynamicPeak * 0.999f)
        if (b in 0.5f..dynamicFloor) dynamicFloor = b
        else dynamicFloor = min(savedFloorBass * 1.5f, dynamicFloor * 1.0006f)

        val instantEnergy = b * 0.6f + m * 0.3f + h * 0.15f
        if (instantEnergy > peakDropEnergy) peakDropEnergy = instantEnergy
        else peakDropEnergy = max(15f, peakDropEnergy * 0.982f)

        // Machine-Logic Voids (Паузы) для Group 2 (Ломаные структуры Dr. Ozi / Space Laces)
        if (tension > 0.60f && instantEnergy < peakDropEnergy * 0.12f && !isPreDropVoid && hitDensity > 0.4f) {
            isPreDropVoid = true; voidTimer = now
            targetSpinDirection *= -1.0 // Реверс катушек! Машина понимает что это Яма перед дропом.
        }

        if (isPreDropVoid && (now - voidTimer > 1800L)) isPreDropVoid = false
        if (isPreDropVoid && (isRealKick || normBass > 0.65f)) {
            isPreDropVoid = false; tension = 1.0f; kickFlash = 1.0f
        }

        isStrobeGating = (activeRoute == dubstepRoute && tension > 0.85f && m > 25f &&
                instantEnergy < peakDropEnergy * 0.5f && !isPreDropVoid && snareDelta > 3f)

        if (activeRoute == dubstepRoute && (normBass > 0.65f || instantEnergy > peakDropEnergy * 0.75f)) {
            tension = 1.0f
        } else if (hasRhythmicDrive) {
            tension = (tension - 0.02f).coerceIn(0.40f, 0.98f)
        } else {
            tension = (tension - 0.05f).coerceAtLeast(0.0f)
        }

        /* Логика Auto-Genre остается превосходной. Не трогаем, лишь вызываем State Morph. */
        when {
            !hasRhythmicDrive || subToMidRatio < 1.05f || (m > 12f && b < 22f) -> { activeRoute = melodicRoute; detectedGenreName = "MELODIC / CHILL" }
            subToMidRatio in 1.05f..1.35f && growlIntensity < 1.25f -> { activeRoute = houseRoute; detectedGenreName = "BASS HOUSE / GROOVE" }
            else -> { activeRoute = dubstepRoute; detectedGenreName = if (growlIntensity > 1.45f) "TEAROUT / DEATHSTEP" else "DUBSTEP / RIDDIM" }
        }

        // Система Паттернов: Проверка на морфинг. Задержку опускаем до 5 секунд для бОльшей динамики!
        if (tension > 0.55f && now - patternCooldown > 5000L && !isManualPatternOverride && morphProgress >= 1.0f) {
            if (nextPatternQueued < 0) {
                routeStep = (routeStep + 1) % activeRoute.size
                nextPatternQueued = activeRoute[routeStep]
            }
            if (isRealKick && nextPatternQueued >= 0) {
                prevDropPattern = currentDropPattern; currentDropPattern = nextPatternQueued
                nextPatternQueued = -1; startMorph(); patternCooldown = now
            }
        }
    }

    private fun processPhysicsAndRender() {
        val now = SystemClock.elapsedRealtime()

        if ((audioManager?.isMusicActive == true) && (now - lastFftTime > 1200L)) {
            lastFftTime = now; initAudioVisualizer()
        }

        if (isMusicSilent) {
            renderBlank()
            liveFrameListener?.invoke(frameBuffer, "PAUSED // STANDBY", 0f, 0f, 0f, 0f, "[ AWAITING AUDIO... ]")
            return
        }

        if (now - calmPatternTimer > 18000L) {
            calmPatternTimer = now; calmPatternType = (calmPatternType + 1) % 3
        }

        if (morphProgress < 1.0f) {
            val elapsed = now - morphStartTime
            morphProgress = (elapsed.toFloat() / morphDurationMs).coerceIn(0.0f, 1.0f)
        }

        var isTimeFrozen = false
        var strobeDim = 1.0

        if (isStrobeGating) {
            strobeTick++
            if ((strobeTick % 6) < 3) { isTimeFrozen = true; strobeDim = 0.50 }
        } else {
            strobeTick = 0
        }

        if (!isTimeFrozen && !isPreDropVoid) {
            val bassError = targetBass - smoothBass
            bassVel += bassError * springForce
            bassVel *= friction
            smoothBass += bassVel
            if (smoothBass < 0f) smoothBass = 0f

            smoothHigh = if (targetHigh > smoothHigh) targetHigh else smoothHigh * 0.60f + targetHigh * 0.40f
            smoothMid = smoothMid * 0.70f + targetMid * 0.30f

            if (glitchFrames > 0) glitchFrames--

            val baseSpeed = when {
                tension <= 0.05f -> 0.0015
                tension <= 0.30f -> 0.004
                tension <= 0.75f -> 0.012 + ((tension - 0.30) / 0.45) * 0.035
                else -> 0.045
            }

            kineticVelocityBoost *= 0.80
            rhythmicBrake = rhythmicBrake * 0.85 + 1.0 * 0.15

            if ((tension < 0.25f || hitDensity < 0.15f) && testModeOverride != 2) {
                kineticVelocityBoost = 0.0; shakeImpulse = 0.0; rhythmicBrake = 1.0
            }

            // Мягко дотягиваем реальный Спин к Целевому. Выглядит как старт/стоп/бэкскретч мотора!
            actualSpinDirection = actualSpinDirection * 0.90 + targetSpinDirection * 0.10

            val totalSpeed = (baseSpeed + kineticVelocityBoost) * rhythmicBrake

            // Накопительная шкала абсолютна, она растет! А направление дает переменная actualSpinDirection в блоках Паттернов!
            absContinuousAngle += totalSpeed

            timeSec += if (tension > 0.15f) 0.025 else 0.006
        }

        if (shakeImpulse > 0.04) {
            shakeX = (Random.nextDouble() - 0.5) * shakeImpulse
            shakeY = (Random.nextDouble() - 0.5) * shakeImpulse
            shakeImpulse *= 0.65
        } else {
            shakeX = 0.0; shakeY = 0.0; shakeImpulse = 0.0
        }

        kickFlash *= 0.6f
        buildupHitStrobe *= 0.75f

        // Мягкий эластичный Fly-Out (Камера тянется на пружинке обратно)
        val flyForce = -flyOutTrigger * 0.4
        flyOutTrigger += flyForce
        if (abs(flyOutTrigger) < 0.01) flyOutTrigger = 0.0

        val renderPoly3D = absContinuousAngle * actualSpinDirection * 0.9
        precalculate3DOctahedron(renderPoly3D, 6.2 + (smoothBass / dynamicPeak) * 2.0 + kickFlash * 1.8)

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
            projOctX[i] = x1 * scale; projOctY[i] = y2 * scale; projOctZ[i] = z2
        }
    }

    private fun renderMasterVisual(t: Float, strobeDim: Double) {
        val centerX = 12.0 + shakeX
        val centerY = 12.0 + shakeY
        val bNorm = (smoothBass / dynamicPeak).coerceIn(0.1f, 3.0f).toDouble()
        val mNorm = (smoothMid / 16.0).coerceIn(0.1, 2.5)
        val hNorm = (smoothHigh / 14.0).coerceIn(0.0, 2.5)

        val wCalm: Double; val wBld: Double; val wDrp: Double

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

                val currentFlyX = flyOutPanX * flyOutTrigger
                val currentFlyY = flyOutPanY * flyOutTrigger

                dx -= currentFlyX
                dy -= currentFlyY

                if (glitchFrames > 0) {
                    val rageScale = (growlIntensity - 0.8f).coerceAtLeast(0.1f)
                    val glitchStripe = ((y + timeSec * 28.0) / 2.0).toInt() % 3
                    val tearOffset = floor(rageScale * 8.5)

                    if (glitchStripe == 0) {
                        dx += tearOffset
                    } else if (glitchStripe == 2 && rageScale > 0.4) {
                        dx -= floor(tearOffset * 0.6)
                        if (y % 2 == 0) dy += floor(rageScale * 3.0)
                    }
                }

                var r = hypot(dx, dy)
                var theta = atan2(dy, dx)

                var singularityDot = 0.0
                if (isPreDropVoid) {
                    val elapsedVoid = (SystemClock.elapsedRealtime() - voidTimer) / 1000.0
                    r += (elapsedVoid * elapsedVoid) * 20.0
                    theta += (elapsedVoid * 12.0) / (r + 1.0)
                    dx = r * cos(theta)
                    dy = r * sin(theta)
                    singularityDot = exp(-hypot(x - centerX, y - centerY) * 3.5) * 0.8
                }

                if (r > 12.5 && singularityDot == 0.0) {
                    frameBuffer[idx] = 0
                    continue
                }

                val vCalm = when (calmPatternType) {
                    0 -> {
                        val zoom = (ln(r + 0.1) * 1.8 - timeSec * 0.4)
                        val recursiveRing = abs(sin(zoom * PI)).pow3()
                        (recursiveRing * exp(-r * 0.12) * (0.4 + bNorm * 0.3)).coerceIn(0.0, 1.0)
                    }
                    1 -> {
                        val baseHalo = 6.5 + bNorm * 1.5
                        val organicWarp = sin(theta * 3.0 + timeSec * 1.4) * (1.0 + mNorm * 1.5) + cos(theta * 5.0 - timeSec * 0.9) * (hNorm * 1.2)
                        val deformedR = baseHalo + organicWarp
                        val plasmaRing = exp(-abs(r - deformedR) * 1.5) * (0.5 + mNorm * 0.4)
                        val innerGlow = exp(-r * 1.1) * (0.25 + hNorm * 0.25)
                        max(plasmaRing, innerGlow).coerceIn(0.0, 1.0)
                    }
                    else -> {
                        val irisRot = theta + timeSec * 0.35
                        val irisBlades = abs(sin(irisRot * 6.0 + r * 0.3)).pow2() * 1.15
                        val ring1 = exp(-abs(r - 5.0) * 1.5) * irisBlades * 1.2
                        val ring2 = exp(-abs(r - 8.8) * 1.6) * (sin(irisRot * 12.0) * 0.4 + 0.6) * 1.1
                        val centerLens = exp(-r * 1.4) * 0.9
                        max(max(ring1, ring2), centerLens) * (0.6 + bNorm * 0.4)
                    }
                }

                val inSpeed = timeSec * (6.0 + t * 14.0)
                val inWaves = abs(sin((r + inSpeed) * 0.9)).pow4()
                val cardinalAxes = min(abs(dx), abs(dy))
                val railBeams = exp(-cardinalAxes * 1.8) * (1.0 - r / 12.5)
                val centerCoreCharge = exp(-r * (1.5 - t * 0.8)) * (0.4 + t * 0.6)
                val hitPulse = exp(-r * 0.6) * (buildupHitStrobe * 0.8)
                val vBuildup = (railBeams * inWaves * 1.2 + centerCoreCharge + hitPulse).coerceIn(0.0, 1.0)

                val p = morphProgress
                val vMeat: Double

                if (p >= 1.0f) {
                    vMeat = evaluateDynamicDropVisual(currentDropPattern, dx, dy, r, theta, bNorm, mNorm, hNorm, t)
                } else {
                    val rOld = r * (1.0 + p * 0.8); val tOld = theta + p * PI * 0.4
                    val dxOld = rOld * cos(tOld); val dyOld = rOld * sin(tOld)
                    val valOld = evaluateDynamicDropVisual(prevDropPattern, dxOld, dyOld, rOld, tOld, bNorm, mNorm, hNorm, t)

                    val invP = 1.0f - p
                    val rNew = r * (1.0 + invP * 0.8); val tNew = theta - invP * PI * 0.4
                    val dxNew = rNew * cos(tNew); val dyNew = rNew * sin(tNew)
                    val valNew = evaluateDynamicDropVisual(currentDropPattern, dxNew, dyNew, rNew, tNew, bNorm, mNorm, hNorm, t)

                    val wOld = cos(p * PI * 0.5)
                    val wNew = sin(p * PI * 0.5)
                    vMeat = (valOld * wOld) + (valNew * wNew)
                }

                val flash = if (kickFlash > 0.1f) exp(-r * 0.45) * kickFlash.toDouble() * 1.2 else 0.0
                val vDropFull = max(vMeat, flash).coerceIn(0.0, 1.0)

                var blended = (vCalm * (wCalm / sumW) + vBuildup * (wBld / sumW) + vDropFull * (wDrp / sumW)) * strobeDim
                blended = max(blended, singularityDot)

                val gamma = 2.0 + t * 1.1
                val bright = (blended.pow(gamma) * 255.0).toInt().coerceIn(0, 255)

                frameBuffer[idx] = bright
            }
        }

        try {
            glyphManager?.setAppMatrixFrame(frameBuffer)

            val activeName = when {
                testModeOverride == 1 -> "[TEST CALM] " + (if (calmPatternType == 0) "Zen-Tunnel" else if (calmPatternType == 1) "Lunar Halo" else "Cyber-Shutter")
                testModeOverride == 2 -> "[TEST DROP] " + patternNames.getOrElse(currentDropPattern) { "Drop Pattern" }
                isManualPatternOverride -> "[MANUAL] " + patternNames.getOrElse(currentDropPattern) { "Pattern" }
                t < 0.35f -> "CALM [$detectedGenreName]: " + (if (calmPatternType == 0) "Zen-Tunnel" else if (calmPatternType == 1) "Lunar Halo" else "Cyber-Shutter")
                t < 0.75f -> "BUILDUP: Charging Railgun"
                else -> "DROP [$detectedGenreName]: " + patternNames.getOrElse(currentDropPattern) { "Drop Pattern" }
            }

            val telemetryStr = String.format(
                "PK:%04.1f | FL:%03.1f | K-DLT:%04.1f\n" +
                        "FLUX:%04.1f | H-DENS:%03.1f | S-DLT:%04.1f\n" +
                        "G-INT:%03.1f | S/M RATIO:%04.1f\n" +
                        "FLY:%03.1f | MORPH:%03.1f",
                dynamicPeak, dynamicFloor, lastKickDelta,
                rhythmFlux, hitDensity, lastSnareDelta,
                growlIntensity, subToMidRatio,
                flyOutTrigger, morphProgress
            )

            liveFrameListener?.invoke(frameBuffer, activeName, t, targetBass, targetMid, targetHigh, telemetryStr)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun evaluateDynamicDropVisual(
        patternId: Int, dx: Double, dy: Double, r: Double, theta: Double, bNorm: Double, mNorm: Double, hNorm: Double, t: Float
    ): Double {

        val tVal = t.toDouble()
        // ФАЗА chill-режима: От 1.0 (Мелодика/Спокойствие) до 0.0 (Tearout)
        val calmPhase = (1.0 - tVal).coerceIn(0.0, 1.0)

        // ------------------ SYNC & ASYNC DRIVERS -------------------
        // [БАЗОВЫЙ ДРАЙВЕР] Синхронно основной силе ритма (Вращение +).
        val bRot = absContinuousAngle * actualSpinDirection

        // [MID ДРАЙВЕР] Мягкая рассинхронизация. Движется с другой скоростью, с пульсациями (mNorm влияет на фазу).
        val mRot = bRot * 1.3 - sin(timeSec * 0.45 + mNorm) * 1.5

        // [HIGH ДРАЙВЕР] Острый реверсивный (Отзеркаленный асинхронный). Приостанавливается в Ambient.
        val hRot = -bRot * 1.5 + (timeSec * 0.8 * hNorm)
        val kinSpin = bRot

        return when (patternId) {
            0 -> {
                // [0] SHURIKEN: Добавлена морфация формы в фигуру/крест, Внешнее Орбитальное кольцо!
                // При calmPhase лезвия расширяются до "Цветка", при мясе сужаются в "Металлическую иглу" (3.0 -> 0.6)
                val bladeBend = calmPhase * 0.5 * r * actualSpinDirection
                val starPoints = theta + bRot - bladeBend
                val bladeShapeX = (abs(cos(starPoints * 2.0)) + 0.001).pow(1.8 - calmPhase * 1.2)

                val centerShuriken = sharpGlow(bladeShapeX * r - (0.8 + mNorm), 4.0 - bNorm.coerceAtMost(1.0)) * (1.1 + mNorm)
                val voidCore = if (r < (1.2 + kickFlash * 1.2)) 0.8 else 0.0

                // НОВЫЙ АСИНК-ЭЛЕМЕНТ: Орбитальный барабан (звенья/кольцо). Крутится отдельно по "High/Snare".
                // Асинхронное отдаление или смещение при drop
                val ringRad = 9.5 - (bNorm * 0.5) + calmPhase
                val baseOuterOrbit = sharpGlow(r - ringRad, 2.5)
                val fragments = abs(sin((theta + hRot) * 10.0)).pow3() // Точечные кубы внешнего кольца
                val outBand = baseOuterOrbit * fragments * (0.8 + hNorm * 1.4)

                max(centerShuriken, max(voidCore, outBand)).coerceIn(0.0, 1.0)
            }
            1 -> {
                // [1] HEXA-SHIELD: Многослойный, сложные детали щита (Гексы вращаются асинхронно + рамки меняют размер)
                val hTheta1 = theta + bRot
                val hx1 = dx * cos(bRot) + dy * sin(bRot)
                val hy1 = -dx * sin(bRot) + dy * cos(bRot)
                val sdfHex1 = max(abs(hy1), abs(hx1) * 0.866025 + abs(hy1) * 0.5)

                val radius1 = 4.2 + (calmPhase * 2.5) + bNorm * 0.6
                val gapPulsator = abs(sin(hTheta1 * 3.0)).pow3() // Сквозные "вентили" корпуса
                val plateMain = sharpGlow(sdfHex1 - radius1, 3.8 - calmPhase*1.5) * (0.7 + gapPulsator * 0.8) * (1.0 + mNorm)

                // Окантовка (Shield Border): смещена во внешний радиус и крутится Реверсом
                val hexRot2 = hRot * 0.5
                val hx2 = dx * cos(hexRot2) + dy * sin(hexRot2)
                val hy2 = -dx * sin(hexRot2) + dy * cos(hexRot2)
                val sdfHex2 = max(abs(hy2), abs(hx2) * 0.866025 + abs(hy2) * 0.5)

                val extFrames = sharpGlow(sdfHex2 - (radius1 + 3.8 + hNorm), 8.0) * abs(cos((theta+hRot)*6.0)).pow2() * (hNorm * 1.5)
                val corePulse = sharpGlow(sdfHex1 - 1.5, 4.0) * (0.9 + kickFlash)

                max(plateMain, max(extFrames, corePulse)).coerceIn(0.0, 1.0)
            }
            2 -> {
                // [2] ACOUSTIC PULSAR: Сильный сдвиг после слеша, расхождение частей!
                // Разделяем сферу напополам и физически "отбрасываем" половины в стороны под удар Бочки.
                val rotAxis = bRot * 0.15 + (PI/2.0)
                val nX = -sin(rotAxis); val nY = cos(rotAxis)
                val dCutPlane = dx * nX + dy * nY
                val sliceDirection = if (dCutPlane > 0) 1.0 else -1.0

                // Физическое раскалывание и отдаление долей (+база +динамика bNorm)
                val cutDistSplit = (2.2 + calmPhase*1.0) + (kickFlash * 3.0) + bNorm
                val rxShifted = dx - (nX * sliceDirection * cutDistSplit)
                val ryShifted = dy - (nY * sliceDirection * cutDistSplit)
                val radOfSphere = hypot(rxShifted, ryShifted)

                // Внутренняя асинхронная текстура "пульсирующей лавы" внутри сферы
                val lavaTexture = (0.5 + 0.5 * sin(radOfSphere * 2.8 - mRot * 3.0))

                // Запекание сфер
                val twoHalves = if (radOfSphere <= 5.6 + (calmPhase*1.5)) lavaTexture * (0.6 + mNorm * 0.8) else 0.0

                val centerBladeLine = sharpGlow(dCutPlane, 12.0) * (if (r < 11.0) kickFlash*2.0 else 0.0)

                max(twoHalves, centerBladeLine).coerceIn(0.0, 1.0)
            }
            3 -> {
                // [3] CYBER-REACTOR: Все ТРИ слоя. Меньше грязи - больше цветов (красочность - гармоники)
                val centralPulsar = sharpGlow(r - (1.0 + bNorm * 1.5), 1.8) + (if (r < 0.6 + kickFlash) 0.8 else 0.0)

                // 1st Row: Inner Solid Hoop (Тяжелая тяга бочки) - Synced
                val rRing1 = 3.6 + (calmPhase * 1.5)
                val dashRing1 = sharpGlow(r - rRing1, 3.5) * abs(sin((theta + bRot) * 4.0)).pow2() * (0.8 + bNorm)

                // 2nd Row: Спутники (Идеально геометрически). Asynced Midrange
                val rRing2 = rRing1 + 3.0 + (mNorm * 0.4)
                val nodesPhz = theta - mRot
                val diffM = abs((nodesPhz % (PI / 4.0)) - (PI / 8.0)) // 8 Satellites
                val satDistanceY = abs(r - rRing2)
                val satDistanceX = (diffM * rRing2)
                val row2Sats = exp(-(satDistanceY.pow2() + satDistanceX.pow2() * 1.5)) * (0.6 + mNorm * 1.5)

                // 3rd Row: Outermost Orbit. Проявляется по ВЫСОКИМ ЧАСТОТАМ, как искры реактора
                val rRing3 = 10.5
                val highDustMask = sharpGlow(r - rRing3, 6.0) * abs(sin((theta + hRot) * 12.0)).pow5()
                // Рендерим 3 слой только когда hNorm > 0.4 ИЛИ тишина-эмбиент
                val displayT3 = (calmPhase * 0.4 + hNorm.coerceIn(0.0, 1.5))
                val row3Out = highDustMask * displayT3

                max(max(centralPulsar, dashRing1), max(row2Sats, row3Out)).coerceIn(0.0, 1.0)
            }
            4 -> {
                // [4] SACRED LOTUS: По лепестку на слой (+Асинхрон)
                val thetaL = theta + mRot * 0.35

                // Tier 1 (Central). Было 6 -> стало 6. Новые градиенты спокойствия
                val rootRadius = 2.4 + (calmPhase * 2.8)
                val rTier1 = rootRadius + cos((thetaL) * 6.0) * (0.6 + bNorm * 1.3)
                val petalCenter1 = sharpGlow(r - rTier1, 2.5) * (0.8 + bNorm)

                // Tier 2 (Middle) Лепестки: Стало 7, Синхронное контр-вращение с первой волной.
                val rTier2 = (rootRadius + 3.4) + sin((-theta + bRot) * 7.0) * (1.2 + calmPhase * 1.2)
                val petalMid2 = sharpGlow(r - rTier2, 2.8 - mNorm.coerceAtMost(1.0)) * (0.8 + mNorm * 0.9)

                // Tier 3 (Outer): Раскрываются, только когда есть высокие 8 штук
                val rTier3 = (rootRadius + 6.6) + cos((theta + hRot*0.8) * 8.0) * (hNorm * 1.2 + calmPhase)
                val petalOut3 = sharpGlow(r - rTier3, 3.2) * (hNorm + calmPhase * 0.3)

                val centerDiamond = if(r < 1.4) 1.2 else 0.0

                max(petalCenter1, max(petalMid2, max(petalOut3, centerDiamond))).coerceIn(0.0, 1.0)
            }
            5 -> {
                // [5] VOLUMETRIC OCTAHEDRON: Сохранена магия 3D, НО добавлены всплески
                // из ядер проекции асинхронными осколками, парящими рядом. Не сбивает каркас.
                var framePolyGlow = 0.0

                // Чистый каркас:
                for (i in 0 until octEdgesFlat.size step 2) {
                    val ix = octEdgesFlat[i]; val iy = octEdgesFlat[i+1]
                    val pxDist = distToSegmentSq(dx, dy, projOctX[ix], projOctY[ix], projOctX[iy], projOctY[iy])
                    // Откалиброван размер толщины от напряженности.
                    val wEdge = sharpGlow(pxDist.pow(0.5), 1.2 + calmPhase * 1.5) * (if (projOctZ[ix] > -0.5) 1.0 else 0.2)
                    if (wEdge > framePolyGlow) framePolyGlow = wEdge
                }

                var verticesFire = 0.0
                // Добавляем эффект парящей фрагментации углов (разорванная сетка):
                val asynchNodeShift = mNorm * 1.8
                for (i in 0 until 6) {
                    // Вершины растягиваются наружу пропорционально средним (как глич):
                    val asynxPx = projOctX[i] * (1.0 + asynchNodeShift)
                    val asynyPy = projOctY[i] * (1.0 + asynchNodeShift)

                    val pxToVertex = (dx - asynxPx).pow2() + (dy - asynyPy).pow2()
                    val burstLight = exp(-pxToVertex * 1.2) * (1.1 + mNorm + kickFlash)
                    if (burstLight > verticesFire) verticesFire = burstLight
                }

                val calmGalaxyScale = calmPhase * (0.35 + hNorm)
                val calmCoreHaze = sharpGlow(r - 5.0, 0.4) * calmGalaxyScale * abs(cos(theta * 6.0 + hRot)).pow2()

                max(framePolyGlow, max(verticesFire, calmCoreHaze)).coerceIn(0.0, 1.0)
            }
            6 -> {
                // [6] NEBULA-SPIN: Абсолютный отход от лотоса (Многослойный Космический Свирл, Без четких лепестков).
                val organicRotBase = theta + bRot
                val organicRotM = -theta + mRot

                // Формируем облако синусов на переплетениях
                val baseBodySize = 4.0 + (calmPhase * 3.0) + (bNorm * 1.5)
                val rippleWaveX = sin(organicRotBase * 6.0) * (2.2 - calmPhase)
                val rippleWaveY = cos(organicRotM * 9.0) * (1.4 + mNorm * 1.0)

                val combinedOrganicRadius = baseBodySize + rippleWaveX + rippleWaveY
                // Точечное размазывание "Свечения Туманности", пульсирующая граница!
                val cosmicPlume = exp(-abs(r - combinedOrganicRadius).pow2() * (1.0 + kickFlash * 1.5)) * (0.8 + mNorm)

                // Газовые хвосты внутри Туманности: асинхронные!
                val starDustSwirl = exp(-abs(r - (2.5 + hRot % 2.5)).pow2() * 3.5) * (0.6 + hNorm)

                val holeGravity = if (r < 1.0 + bNorm*0.5) (1.2 + bNorm) else 0.0

                max(cosmicPlume, max(starDustSwirl, holeGravity)).coerceIn(0.0, 1.0)
            }
            7 -> {
                // [7] 3D-KALEIDOSCOPE: Замедлен, отцентрован. Глобальная трансформация!
                val morphFreq = 4.0 + calmPhase * 3.0 // В Ambient становится более гладким круглым цветком (от 4 до 7 долей)

                val fWv = (kinSpin * (0.4 + bNorm*0.1)) // Значительно замедлен в chill. Только drop крутит калейд быстро.
                val backPlateX = (kinSpin * -0.6)

                // Front layer (Главный кристалл)
                val mxFront = dx * cos(fWv) + dy * sin(fWv); val myFront = -dx * sin(fWv) + dy * cos(fWv)
                val geoForm = abs(mxFront * myFront) / (abs(sin(r * 0.3 * morphFreq)) + 1.2)

                val solidCrystalBorder = sharpGlow(geoForm - (0.55 + bNorm*0.6 + calmPhase*1.5), 2.5) * (1.1 + mNorm)

                // Мистический Задний Асинхронный "Скретченный" Крест
                val mCrossX = dx * cos(backPlateX) + dy * sin(backPlateX); val mCrossY = -dx * sin(backPlateX) + dy * cos(backPlateX)
                val logicCrusader = abs(mCrossX * mCrossY) / (cos(r * 0.2).coerceAtLeast(0.4) + 1.0)
                val darkBackgroundCrus = sharpGlow(logicCrusader - (1.2 + calmPhase*0.5), 1.8) * (0.5 + hNorm * 0.5)

                val pulseHeart = if(r < 1.8 + bNorm) (0.9 + kickFlash) else 0.0

                max(solidCrystalBorder, max(darkBackgroundCrus, pulseHeart)).coerceIn(0.0, 1.0)
            }
            8 -> {
                // [8] TEAROUT-SAW: Зубастая пила + Радиальная фактурная рисовка. Кольцо осколков от малого(Chill) к большому(Deathstep)
                val motorV = theta + bRot
                val grindSnaresV = theta - hRot * 1.5

                // Выпиливание канавок прямо в лезвии с помощью Modulo "рельеф".  Это и есть текстура диска!
                val texGrinder = (r % 2.0).coerceIn(0.0, 1.0)

                // Модуляция зубьев "Акульего Плавника" с переменным кол-вом (24 зубца)
                val sharkSawEdge = (abs(sin(motorV * 12.0 + (r * 0.25))) + sin(motorV * 12.0 + (r * 0.25))) * 0.5
                val staticRimSize = 6.0 + (calmPhase * 1.2) + mNorm * 0.5
                val teethLimit = staticRimSize + (sharkSawEdge.pow2() * (2.0 + bNorm*1.8))

                val metalSolidCore = if(r in 4.0..teethLimit) (0.8 + mNorm * texGrinder * 0.6) else 0.0

                val hubLockCenter = sharpGlow(r - 2.8, 6.0) * (0.8 + bNorm*0.4) + (if(r < 1.0) 1.2 else 0.0)

                // Орбитальные осколки механической Пилы (Замешаны на Снейре, Асинхрон).
                val fragmentOuterRingOffset = teethLimit + 2.5 + hNorm
                val outerLinksMask = sharpGlow(r - fragmentOuterRingOffset, 4.0)
                // Анимация разбросанных зубьев против кручения самой Пилы
                val mechanicNodes = abs(sin(grindSnaresV * 10.0)).pow5()
                val flyingDebrisOrbit = outerLinksMask * mechanicNodes * (hNorm * 1.5 + calmPhase * 0.5)

                max(metalSolidCore, max(hubLockCenter, flyingDebrisOrbit)).coerceIn(0.0, 1.0)
            }
            else -> {
                // [9] TRUE STAR->POLY MORPH. Точный Morph Modulo Полигона между точечной Звездой и шестигранником
                // Morph parameter: calm tracks/chill = star of 4 sides.
                // Rage tension tracks = heavy heavy 6-edged Bolt
                val morphPolys = 4.0 + (t * 2.0)  // Меняется плавно между 4.0 (Ambient) -> 6.0 (Deathstep)
                val phStar = theta + mRot // Общая фаза

                val foldCutArc = (PI * 2.0) / morphPolys
                val localizedP = abs((phStar % foldCutArc) - (foldCutArc / 2.0))

                val baseLineCutSdf = r * cos(localizedP).coerceAtLeast(0.15) // Безупречная дистанционная обводка

                // Синхронный толстый корпус Модуло. Меняет цветность
                val massBouncerOffset = 3.8 + (calmPhase * 3.5)
                val heavyFramePolygon = sharpGlow(baseLineCutSdf - (massBouncerOffset + bNorm), 2.2) * (1.1 + mNorm)

                val internalRinger = sharpGlow(baseLineCutSdf - 2.5, 4.0) * (0.8 + bNorm)
                val heartPolyg = if (r < 1.0) 1.0 else 0.0

                // Плавные Лучи-Свечения - независимый второй объект. Привязаны к High-Frequency-Rotations.
                // Лучи исчезают на бочке(Стресс), подчеркивают ВХОД бита. АСИНХРОН.
                val rayTracesRot = theta + hRot * 1.5
                val morphRayAmt = morphPolys * 2.0 // Лучей ровно x2
                val shootingCosmRayLight = abs(sin(rayTracesRot * morphRayAmt * 0.5)).pow4() * exp(-abs(r - 11.5) * 0.8)
                val beamEmission = shootingCosmRayLight * (hNorm * 2.5 + calmPhase * 0.6)

                max(heavyFramePolygon, max(internalRinger, max(heartPolyg, beamEmission))).coerceIn(0.0, 1.0)
            }
        }
    }

    // Безаллокационное измерение расстояния к отрезку: квадраты
    private fun distToSegmentSq(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val vx = x2 - x1; val vy = y2 - y1
        val lenSq = vx.pow2() + vy.pow2()
        if (lenSq < 1e-8) return (px - x1).pow2() + (py - y1).pow2()
        val param = ((px - x1) * vx + (py - y1) * vy) / lenSq
        val dotL = param.coerceIn(0.0, 1.0)
        return (px - (x1 + dotL * vx)).pow2() + (py - (y1 + dotL * vy)).pow2()
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
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}