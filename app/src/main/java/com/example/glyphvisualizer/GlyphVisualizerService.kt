package com.example.glyphvisualizer

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
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import kotlin.math.*

interface LiveFrameListener {
    fun onFrame(frame: IntArray, tension: Float, bass: Float, mid: Float, high: Float, pattern: Int)
    fun onAdvancedTelemetry(profCtx: Int, tension: Float, currentMomentName: String, pCur: Int,
        mProg: Float, dwellTime: Float, bSalience: Float, mSalience: Float, hSalience: Float, glitchImpactLvl: Float)
    fun onPatternSelected(pattern:Int) { }
    fun onEngineDiagnostics(message: String) { }
    fun onModeState(forceMode: Int, manualPattern: Boolean) { }
}

/** Android-адаптер. Всё изменяемое состояние движка принадлежит engineHandler. */
class GlyphVisualizerService : Service() {
    companion object {
        @Volatile var liveFrameListener: LiveFrameListener? = null
        @Volatile var isRunning = false; private set
        @Volatile var lastError = ""; private set
    }
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var glyph: GlyphMatrixManager? = null
    private var visualizer: Visualizer? = null
    private lateinit var audio: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val engine = VjEngine()
    private val renderer = SceneRenderer()
    private val analyzer = SpectrumAnalyzer()
    private var magnitudes = DoubleArray(512)
    private val measurement = Visualizer.MeasurementPeakRms()
    private var measurementAvailable = false
    private lateinit var cacheSource:CachedPlaybackSource
    private var usingCache=false
    private var cacheFrames=0
    private var volumeAudible=true
    private var lastVolumeCheck=0L
    private var allowedApps = emptySet<String>()
    private var wasAllowed = false
    private var captureEnabled=false
    private var idleSince=0L
    private var idleBlankShown=false
    private val hardwareFrame=IntArray(625)
    private var hasHardwareFrame=false
    private var lastFrame = 0L
    private var lastCapture = 0L
    private var lastRetry = 0L
    private var lastPreview = 0L
    private var lastTelemetry = 0L
    private var captureFrames = 0
    private var renderFrames = 0
    private var diagnosticsStart = 0L
    private var renderMs = 0.0
    private var sdkMs = 0.0
    private var maximumCaptureGapMs = 0.0
    private val peakPulse = DoubleArray(3)
    @Volatile private var closing = false
    private var hardwareReady = false
    private var routeGeneration = 0
    private var lastHardwareError = 0L
    private var hardwareRetryAt = 0L
    private var hardwareRetryDelay = 100L
    private val main = Handler(Looper.getMainLooper())
    private val previewLock = Any()
    private val previewFrame = IntArray(625)
    private var previewQueued = false
    private var previewT = 0f; private var previewB = 0f; private var previewM = 0f; private var previewH = 0f
    private var previewPattern = 0
    private val previewDispatch = Runnable {
        synchronized(previewLock) {
            if (!closing) liveFrameListener?.onFrame(previewFrame,previewT,previewB,previewM,previewH,previewPattern)
            previewQueued = false
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { scheduleAudioRestart() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { scheduleAudioRestart() }
    }
    private val restartAudio = Runnable { if (!closing) initAudio() }
    private fun scheduleAudioRestart() {
        handler.removeCallbacks(restartAudio); handler.postDelayed(restartAudio,400)
    }
    private fun report(message: String, error: Exception? = null) {
        lastError = message
        Log.w("GlyphVJ",message,error)
    }
    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cacheSource=CachedPlaybackSource(applicationContext)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel("vfx","Glyph VJ",NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this,"vfx").setContentTitle("Glyph VJ работает")
            .setContentText("Анализ системного аудио · 10 сцен").setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(101,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(101,notification)
        } catch (error: Exception) { report("Не удалось запустить foreground service: ${error.message}",error); stopSelf(); return }
        worker = HandlerThread("GlyphVJ",Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        handler = Handler(worker.looper)
        isRunning = true; closing = false; lastError = ""
        audio.registerAudioDeviceCallback(devices,handler)
        handler.post {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Glyph::VJ").apply { setReferenceCounted(false) }
            reloadSettings()
            try {
                glyph = GlyphMatrixManager.getInstance(applicationContext)
                glyph?.init(object : GlyphMatrixManager.Callback {
                    override fun onServiceConnected(name: ComponentName?) {
                        handler.post {
                            if (!closing) try { glyph?.register(Glyph.DEVICE_23112); hardwareReady = true; hasHardwareFrame=false; hardwareRetryAt=0L; hardwareRetryDelay=100L }
                            catch (error: Exception) { report("Glyph register: ${error.message}",error) }
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName?) { handler.post { hardwareReady = false; hasHardwareFrame=false } }
                })
            } catch (error: Exception) { report("Glyph SDK: ${error.message}",error) }
            initAudio(); handler.post(tick)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::handler.isInitialized) return START_NOT_STICKY
        val action = intent?.getStringExtra("ACTION")
        handler.post {
            if (!closing) when (action) {
                "PREV_PATTERN" -> {engine.stepPattern(-1);notifyPattern()}
                "NEXT_PATTERN" -> {engine.stepPattern(1);notifyPattern()}
                "MODE_AUTO" -> { engine.forceMode=0; engine.manualPattern=false }
                "MODE_FORCE_CALM" -> { engine.forceMode=1; engine.manualPattern=false }
                "MODE_FORCE_DROP" -> { engine.forceMode=2; engine.manualPattern=false }
                "RELOAD_PROFILE", "RELOAD_APPS" -> reloadSettings()
            }
        }
        // Не пытаемся самовольно рестартовать microphone FGS из фона.
        return START_NOT_STICKY
    }
    private fun notifyPattern() {
        val selected=engine.pattern
        val forced=engine.forceMode
        main.post {if(!closing) {liveFrameListener?.onPatternSelected(selected);liveFrameListener?.onModeState(forced,true)}}
        lastTelemetry=0L
    }
    private fun reloadSettings() {
        val p = getSharedPreferences("glyph_profile",Context.MODE_PRIVATE)
        allowedApps = p.getStringSet("whitelisted_apps",emptySet())?.toSet() ?: emptySet()
        // Калибровка flux — без жанровых labels. Старые четыре несовместимых признака не читаются.
        engine.onsetSensitivity = p.getFloat("vj2_sensitivity",1f).toDouble().coerceIn(0.65,1.5)
    }
    private fun permitted(): Boolean = audio.isMusicActive &&
        (allowedApps.isEmpty() || allowedApps.contains(MediaNotificationListener.activeMediaAppPackage))

    private fun initAudio() {
        routeGeneration++
        if(::cacheSource.isInitialized)cacheSource.resetCursor()
        val generation = routeGeneration
        try { visualizer?.release() } catch (_: Exception) { }
        visualizer=null; captureEnabled=false; analyzer.reset(); engine.resetAudio(); lastCapture=0; measurementAvailable=false
        try {
            val v=Visualizer(0)
            visualizer=v
            v.captureSize=Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            magnitudes=DoubleArray(v.captureSize/2)
            v.scalingMode=Visualizer.SCALING_MODE_NORMALIZED
            measurementAvailable = v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)==Visualizer.SUCCESS
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, bytes: ByteArray?, sampleRate: Int) { }
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, sampleRate: Int) {
                    if (closing || usingCache || fft==null || generation!=routeGeneration) return
                    // Visualizer создаётся на worker Looper: callback и физика сериализованы.
                    if (!permitted()) return
                    val now=SystemClock.elapsedRealtimeNanos()
                    val dt=if(lastCapture==0L) 0.02 else (now-lastCapture)*1e-9
                    if(lastCapture!=0L) maximumCaptureGapMs=max(maximumCaptureGapMs,dt*1000.0)
                    captureFrames++
                    lastCapture=now
                    for(k in 1 until magnitudes.size) magnitudes[k]=hypot(fft[2*k].toDouble(),fft[2*k+1].toDouble())
                    val f=analyzer.analyze(magnitudes,sampleRate/1000,fft.size)
                    if(measurementAvailable && v!=null) {
                        try {
                            if(v.getMeasurementPeakRms(measurement)==Visualizer.SUCCESS) {
                                // RMS в mB, не в dB: сохраняет провалы при normalized FFT.
                                f.level=if(measurement.mRms<=-9000) 0.0 else 10.0.pow(measurement.mRms/2000.0)
                            } else {
                                measurementAvailable=false; engine.resetAudio()
                            }
                        } catch(error: Exception) {
                            measurementAvailable=false; engine.resetAudio()
                            report("RMS недоступен; используется FFT: ${error.message}",error)
                        }
                    }
                    engine.acceptAudio(f,dt)
                }
            }, min(50000,Visualizer.getMaxCaptureRate()),false,true)
            captureEnabled=permitted();v.enabled=captureEnabled
        } catch(error: Exception) {
            report("Аудиозахват недоступен: ${error.message}",error)
            try { visualizer?.release() } catch (_: Exception) { }; visualizer=null
        }
        lastRetry=SystemClock.elapsedRealtime()
    }
    private fun setCaptureEnabled(enabled:Boolean) {
        if(captureEnabled==enabled || visualizer==null) return
        try {
            visualizer?.enabled=enabled
            captureEnabled=enabled
        } catch(error:Exception) {
            report("Переключение аудиозахвата: ${error.message}",error)
            try {visualizer?.release()} catch(_:Exception) { }
            visualizer=null;captureEnabled=false
        }
    }
    private fun sendHardwareFrame(frame:IntArray) {
        if(!hardwareReady || (hasHardwareFrame && frame.contentEquals(hardwareFrame))) return
        val now=SystemClock.elapsedRealtime()
        if(now<hardwareRetryAt) return
        try {
            val target=glyph ?: return
            target.setAppMatrixFrame(frame)
            frame.copyInto(hardwareFrame);hasHardwareFrame=true
            hardwareRetryAt=0L;hardwareRetryDelay=100L
        } catch(error:Exception) {
            val ms=SystemClock.elapsedRealtime()
            hardwareRetryAt=ms+hardwareRetryDelay
            hardwareRetryDelay=(hardwareRetryDelay*2).coerceAtMost(4000L)
            if(ms-lastHardwareError>5000) {report("Вывод Glyph: ${error.message}",error);lastHardwareError=ms}
        }
    }
    private val tick=object : Runnable {
        override fun run() {
            if(closing) return
            val begin=SystemClock.elapsedRealtimeNanos()
            val dt=if(lastFrame==0L) 0.02 else ((begin-lastFrame)*1e-9).coerceIn(0.001,0.1)
            lastFrame=begin
            val media=MediaNotificationListener.playback
            cacheSource.request(media)
            engine.context(cacheSource.contextModel)
            val nowMs=SystemClock.elapsedRealtime()
            val cached=cacheSource.ready(media,nowMs)
            if(cached!=usingCache) {
                usingCache=cached;analyzer.reset();engine.resetAudio();lastCapture=0
                cacheSource.resetCursor()
                if(cached) {
                    try{visualizer?.release()}catch(_:Exception){}
                    visualizer=null;captureEnabled=false
                }else if(permitted() && volumeAudible) initAudio()
                else lastRetry=0L
            }
            if(nowMs-lastVolumeCheck>=250) {
                lastVolumeCheck=nowMs
                volumeAudible=try{audio.getStreamVolume(AudioManager.STREAM_MUSIC)>0 && !audio.isStreamMute(AudioManager.STREAM_MUSIC)}catch(_:Exception){false}
            }
            val allowed=permitted() && volumeAudible
            if(allowed!=wasAllowed) {
                analyzer.reset();engine.resetAudio();lastCapture=0
                idleSince=if(allowed) 0L else begin
                idleBlankShown=false;cacheSource.resetCursor()
            }
            if(!allowed && idleSince==0L) idleSince=begin
            wasAllowed=allowed
            setCaptureEnabled(allowed && !usingCache)
            if(allowed) {
                if(wakeLock?.isHeld==false) wakeLock?.acquire(4*60*60*1000L)
                if(!usingCache && SystemClock.elapsedRealtime()-lastRetry>4000 &&
                    (visualizer==null || lastCapture==0L || begin-lastCapture>2_000_000_000L)) initAudio()
            } else if(begin-idleSince>1_000_000_000L) {
                if(wakeLock?.isHeld==true) wakeLock?.release()
                renderer.frame.fill(0);sendHardwareFrame(renderer.frame)
                if(!idleBlankShown) {
                    synchronized(previewLock) {
                        previewFrame.fill(0);previewT=0f;previewB=0f;previewM=0f;previewH=0f;previewPattern=engine.pattern
                        if(liveFrameListener!=null && !previewQueued) {previewQueued=true;main.post(previewDispatch)}
                    }
                    idleBlankShown=true
                    captureFrames=0;cacheFrames=0;renderFrames=0;renderMs=0.0;sdkMs=0.0;maximumCaptureGapMs=0.0;diagnosticsStart=0
                }
                handler.postDelayed(this,250L)
                return
            }
            if(allowed && usingCache && media!=null) {
                val feed=cacheSource.feed(media,nowMs,{engine.resetAudio()}){features,seconds,warm ->
                    engine.response(cacheSource.currentResponse)
                    engine.acceptAudio(features,seconds,warmReplay=warm)
                    if(warm)engine.advance(seconds)
                }
                cacheFrames+=feed.frames
            }
            engine.advance(dt)
            for(b in 0..2) peakPulse[b]=max(peakPulse[b],abs(engine.layers[b].displacement))
            val renderStart=SystemClock.elapsedRealtimeNanos()
            val frame=renderer.render(engine)
            renderMs += (SystemClock.elapsedRealtimeNanos()-renderStart)*1e-6
            val sdkStart=SystemClock.elapsedRealtimeNanos()
            sendHardwareFrame(frame)
            sdkMs += (SystemClock.elapsedRealtimeNanos()-sdkStart)*1e-6
            renderFrames++
            if(diagnosticsStart==0L) diagnosticsStart=begin
            if(begin-diagnosticsStart>=1_000_000_000L) {
                val seconds=(begin-diagnosticsStart)*1e-9
                if(liveFrameListener!=null) {
                    val message=String.format(java.util.Locale.US,
                        "VJ3.25 %s · %.1fHz | %.1fFPS\ndraw %.2fms sdk %.2fms gap %.0fms\nHits %d/%d/%d | Cut %d FX %d\nTurn %d/%d/%d | Rew %d/%d/%d\nE %.2f | Parts %d hit %d dip %d | %.0fms",
                        if(usingCache)(if(engine.learningExplicit)"CACHE+EXAMPLE" else if(engine.learningActive)"CACHE+MODEL" else "CACHE") else "LIVE FFT",(if(usingCache)cacheFrames else captureFrames)/seconds,renderFrames/seconds,
                        renderMs/max(1,renderFrames),sdkMs/max(1,renderFrames),maximumCaptureGapMs,
                        engine.layers[0].onsetCount,engine.layers[1].onsetCount,engine.layers[2].onsetCount,engine.slashCount,engine.effectCount,
                        engine.layers[0].turnCount,engine.layers[1].turnCount,engine.layers[2].turnCount,
                        engine.layers[0].rewindCount,engine.layers[1].rewindCount,engine.layers[2].rewindCount,
                        engine.motionEnergy,engine.activeElementCount,engine.elementOnsetCount,
                        engine.elementDipCount,engine.transitionSeconds*1000)
                    main.post { if(!closing) liveFrameListener?.onEngineDiagnostics(message) }
                }
                captureFrames=0; cacheFrames=0; renderFrames=0; renderMs=0.0; sdkMs=0.0; maximumCaptureGapMs=0.0; peakPulse.fill(0.0); diagnosticsStart=begin
            }
            if(liveFrameListener!=null && begin-lastPreview>33_000_000L) {
                lastPreview=begin
                synchronized(previewLock) {
                    frame.copyInto(previewFrame)
                    previewT=engine.tension.toFloat(); previewB=engine.layers[0].salience.toFloat()
                    previewM=engine.layers[1].salience.toFloat(); previewH=engine.layers[2].salience.toFloat(); previewPattern=engine.pattern
                    if(!previewQueued) { previewQueued=true; main.post(previewDispatch) }
                }
            }
            if(liveFrameListener!=null && begin-lastTelemetry>250_000_000L) {
                lastTelemetry=begin
                val tension=engine.tension.toFloat(); val moment=engine.sectionLabel
                val pattern=engine.pattern; val morph=engine.morph.toFloat(); val age=engine.momentAge.toFloat()
                val b=engine.layers[0].salience.toFloat(); val m=engine.layers[1].salience.toFloat(); val h=engine.layers[2].salience.toFloat()
                val glitch=engine.glitch.toFloat()
                val forceMode=engine.forceMode; val manual=engine.manualPattern
                main.post { if(!closing) {
                    liveFrameListener?.onModeState(forceMode,manual)
                    liveFrameListener?.onAdvancedTelemetry(-1,tension,moment,pattern,morph,age,b,m,h,glitch)
                } }
            }
            val spent=(SystemClock.elapsedRealtimeNanos()-begin)/1_000_000L
            handler.postDelayed(this,max(1L,20L-spent))
        }
    }
    private fun motionLabel(layer: VjEngine.Layer): String =
        if (layer.rewinding) "REW" else if (layer.velocity + layer.angularKick >= 0.0) "+" else "-"

    override fun onDestroy() {
        closing=true; isRunning=false
        if(!::handler.isInitialized && ::cacheSource.isInitialized)cacheSource.close()
        if(::handler.isInitialized) {
            try { audio.unregisterAudioDeviceCallback(devices) } catch (error: Exception) {
                report("Audio callback unregister: ${error.message}",error)
            }
            handler.removeCallbacksAndMessages(null)
            handler.post {
                cacheSource.close()
                routeGeneration++
                // Сбой одного вызова не должен оставлять остальные системные ресурсы.
                try { visualizer?.release() } catch (_: Exception) { }
                visualizer=null;captureEnabled=false
                renderer.frame.fill(0)
                val target=glyph
                try { target?.setAppMatrixFrame(renderer.frame) } catch (_: Exception) { }
                try { target?.closeAppMatrix() } catch (_: Exception) { }
                try { target?.unInit() } catch (_: Exception) { }
                glyph=null;hardwareReady=false;hasHardwareFrame=false
                try { if(wakeLock?.isHeld==true) wakeLock?.release() } finally { worker.quitSafely() }
            }
        }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder?=null
}



