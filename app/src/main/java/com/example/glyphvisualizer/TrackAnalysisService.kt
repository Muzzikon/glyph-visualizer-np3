package com.example.glyphvisualizer

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat

/** Visible, user-started local analysis. No hidden restart; checkpoints survive OS time limits. */
class TrackAnalysisService:Service() {
    companion object {
        @Volatile var isRunning=false;private set
        @Volatile private var active:TrackAnalysisService?=null
        internal fun wakeForCooling(){active?.main?.post{active?.let{service->service.wakeAlarmSet=false;if(!service.closing)service.acquireLock(30000L)}}}
    }
    private val main=Handler(Looper.getMainLooper())
    private var lock:PowerManager.WakeLock?=null
    private var closing=false
    private var updatedAt=0L
    private var lockUntil=0L
    private var wakeAlarmSet=false
    private val wakeIntent by lazy{PendingIntent.getBroadcast(this,103,Intent(this,AnalysisWakeReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
    private val pauseIntent by lazy{PendingIntent.getService(this,0,Intent(this,TrackAnalysisService::class.java).putExtra("ACTION","PAUSE"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
    private lateinit var notifications:NotificationManager
    override fun onCreate() {
        super.onCreate()
        notifications=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26)notifications.createNotificationChannel(NotificationChannel("analysis","Анализ треков",NotificationManager.IMPORTANCE_LOW))
        try {
            if(Build.VERSION.SDK_INT>=29)startForeground(102,notification("Подготовка сохранённой очереди"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(102,notification("Подготовка сохранённой очереди"))
        }catch(error:Exception){GlyphAnalysisStatus.error="Не удалось запустить анализ: ${error.message}";CalibrationJobs.notice(GlyphAnalysisStatus.error);stopSelf();return}
        lock=(getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Glyph::Analysis").apply{setReferenceCounted(false)}
        isRunning=true;active=this;GlyphAnalysisStatus.error=""
        CalibrationJobs.observeService {status ->
            main.post {
                if(!closing) try {
                    val working=CalibrationJobs.isRunning && !status.startsWith("Остывание") && !status.startsWith("Перерыв") && !status.startsWith("Пауза")
                    if(working)acquireLock(10*60*1000L) else {
                        if(CalibrationJobs.isRunning && (status.startsWith("Остывание") || status.startsWith("Перерыв")) && !wakeAlarmSet) {
                            wakeAlarmSet=true
                            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+60000L,wakeIntent)
                        }
                        releaseLock()
                    }
                    val now=SystemClock.elapsedRealtime()
                    if(now-updatedAt>=2000){updatedAt=now;notifications.notify(102,notification(status.take(160)))}
                    if(!CalibrationJobs.isBusy)stopSelf()
                }catch(error:Exception){GlyphAnalysisStatus.error="Ошибка службы анализа: ${error.message}";CalibrationJobs.pause("Пауза: ошибка службы анализа");stopSelf()}
            }
        }
    }
    private fun notification(text:String):Notification {
        return NotificationCompat.Builder(this,"analysis").setContentTitle("GlyphMusic · анализ треков")
            .setContentText(text).setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause,"Пауза",pauseIntent).build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!isRunning)return START_NOT_STICKY
        if(intent?.getStringExtra("ACTION")=="PAUSE") {
            CalibrationJobs.pause("Пауза по запросу");releaseLock();stopSelf()
        }else try {
            if(!CalibrationJobs.isBusy && !CalibrationJobs.start(applicationContext,learnOnly=intent?.getStringExtra("ACTION")=="LEARN"))stopSelf()
        }catch(error:Exception){GlyphAnalysisStatus.error="Не удалось продолжить: ${error.message}";CalibrationJobs.notice(GlyphAnalysisStatus.error);stopSelf()}
        return START_NOT_STICKY
    }
    // compileSdk >= 35. This avoids a foreground-service timeout crash on Android 15+.
    override fun onTimeout(startId:Int,fgsType:Int) {
        CalibrationJobs.pause("Пауза: Android ограничил время фоновой обработки; продолжите из приложения")
        releaseLock();stopSelf()
    }
    private fun releaseLock(){lockUntil=0L;try{if(lock?.isHeld==true)lock?.release()}catch(_:Exception){}}
    private fun acquireLock(ms:Long){
        val now=SystemClock.elapsedRealtime()
        if(lock?.isHeld==true && lockUntil-now>=ms/2)return
        try{lock?.acquire(ms);lockUntil=now+ms}catch(error:Exception){GlyphAnalysisStatus.error="Блокировка сна недоступна: ${error.message}";CalibrationJobs.pause("Пауза: блокировка сна недоступна");stopSelf()}
    }
    override fun onDestroy() {
        closing=true;isRunning=false;active=null
        try{(getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(wakeIntent)}catch(_:Exception){}
        CalibrationJobs.observeService(null)
        if(CalibrationJobs.mayResumeOnReturn)CalibrationJobs.pause("Пауза: служба анализа остановлена")
        main.removeCallbacksAndMessages(null);releaseLock();stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
internal object GlyphAnalysisStatus {@Volatile var error=""}

/** A cooling alarm only wakes an existing service; it never restarts a killed job. */
class AnalysisWakeReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){TrackAnalysisService.wakeForCooling()}}
