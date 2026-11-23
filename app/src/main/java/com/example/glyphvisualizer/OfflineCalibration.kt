package com.example.glyphvisualizer

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import java.io.*
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.*

/** Калибровка статистики признаков: три участка вместо полного декодирования альбома. */
class OfflineCalibration(private val context: Context) {
    data class Result(val frames: Int, val fluxMean: Double, val fluxStd: Double)
    open class Paused(val reason: String) : Exception(reason)

    companion object {
        // Для коротких файлов — целиком, для длинных — начало, середина и конец.
        internal fun sampleStarts(durationUs: Long): LongArray =
            if (durationUs <= 60_000_000L) longArrayOf(0L)
            else doubleArrayOf(.10, .50, .85).map {
                min((durationUs * it).toLong(), durationUs - 18_000_000L)
            }.distinct().toLongArray()
    }

    fun analyze(uri: Uri, cancelled: () -> Boolean = { false },
                guard: () -> Unit = {}, paced: Boolean = true): Result {
        fun checkRunning() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw Paused("Приостановлено")
            guard()
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            checkRunning()
            extractor.setDataSource(context, uri, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            require(track >= 0) { "В файле нет аудиодорожки" }
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val duration = if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else 0L
            val starts = sampleStarts(duration)
            val windowUs = if (starts.size == 1) 60_000_000L else 18_000_000L
            val decoder = MediaCodec.createDecoderByType(requireNotNull(input.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(input, null, null, 0); decoder.start()
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val pcm = DoubleArray(1024)
            val fft = PcmSpectrum(1024)
            val analyzer = SpectrumAnalyzer()
            val info = MediaCodec.BufferInfo()
            var count = 0; var mean = 0.0; var m2 = 0.0
            val trackStart = System.nanoTime()
            var quantumStart = trackStart
            for ((segment, startUs) in starts.withIndex()) {
                checkRunning()
                if (segment > 0) decoder.flush()
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                analyzer.reset()
                var filled = 0
                var inputDone = false; var outputDone = false
                var lastProgress = System.nanoTime()
                val endUs = startUs + windowUs
                while (!outputDone) {
                    checkRunning()
                    check(System.nanoTime() - trackStart < 180_000_000_000L) { "Превышено время обработки трека" }
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(5000)
                        if (index >= 0) {
                            val buffer = requireNotNull(decoder.getInputBuffer(index)); buffer.clear()
                            val pts = extractor.sampleTime
                            val size = if (pts < 0 || pts >= endUs) -1 else extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, max(0L, pts), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else { decoder.queueInputBuffer(index, 0, size, pts, 0); extractor.advance() }
                        }
                    }
                    val out = decoder.dequeueOutputBuffer(info, 5000)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val format = decoder.outputFormat
                        rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(rate > 0 && channels in 1..32) { "Некорректный формат PCM" }
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Неподдерживаемый PCM: $encoding" }
                        filled = 0; analyzer.reset()
                    } else if (out >= 0) {
                        try {
                            val buffer = decoder.getOutputBuffer(out)
                            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.order(ByteOrder.LITTLE_ENDIAN)
                                val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                require(channels in 1..32 && rate > 0)
                                val stride = channels * bytesPerSample
                                // seek может попасть раньше цели; не включаем preroll в статистику.
                                val skip = (((startUs - info.presentationTimeUs).coerceAtLeast(0L) * rate + 999_999L) / 1_000_000L)
                                    .coerceAtMost((buffer.remaining() / stride).toLong()).toInt()
                                buffer.position(buffer.position() + skip * stride)
                                var sampleIndex = skip.toLong()
                                while (buffer.remaining() >= stride && info.presentationTimeUs + sampleIndex * 1_000_000L / rate < endUs) {
                                    if (sampleIndex % 1024L == 0L) checkRunning()
                                    var sample = 0.0
                                    for (ch in 0 until channels) sample += if (bytesPerSample == 4) buffer.float.toDouble() else buffer.short / 32768.0
                                    require(sample.isFinite()) { "Некорректные значения PCM" }
                                    pcm[filled++] = (sample / channels).coerceIn(-1.0, 1.0); sampleIndex++
                                    if (filled == 1024) {
                                        val features = analyzer.analyze(fft.transform(pcm), rate, 1024)
                                        if (features.level > 1e-4) {
                                            val flux = features.flux.average()
                                            count++; val delta = flux - mean; mean += delta / count; m2 += delta * (flux - mean)
                                        }
                                        val hop = (rate * .020).roundToInt().coerceIn(1, 1024)
                                        pcm.copyInto(pcm, 0, hop, 1024); filled = 1024 - hop
                                    }
                                }
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs >= endUs
                        } finally { decoder.releaseOutputBuffer(out, false) }
                        lastProgress = System.nanoTime()
                    }
                    check(System.nanoTime() - lastProgress < 10_000_000_000L) { "Декодер не выдаёт аудио более 10 секунд" }
                    // Пауза после освобождения output buffer, без удержания ресурса кодека.
                    val busy = System.nanoTime() - quantumStart
                    if (paced && busy >= 20_000_000L) {
                        try { Thread.sleep((busy / 1_000_000L * 2).coerceIn(20L, 100L)) }
                        catch (_: InterruptedException) { throw Paused("Приостановлено") }
                        quantumStart = System.nanoTime()
                    }
                }
            }
            checkRunning()
            require(count > 10) { "Слишком мало аудио для калибровки" }
            return Result(count, mean, sqrt(max(0.0, m2 / (count - 1))))
        } finally {
            // Ошибка stop/release одного ресурса не мешает освободить другой.
            try { codec?.stop() } catch (error: Exception) { Log.w("GlyphCalibration", "codec.stop", error) }
            try { codec?.release() } catch (error: Exception) { Log.w("GlyphCalibration", "codec.release", error) }
            try { extractor.release() } catch (error: Exception) { Log.w("GlyphCalibration", "extractor.release", error) }
        }
    }
}

/** Формат состояния не зависит от Activity и проверяется JVM-тестами. */
internal data class CalibrationCheckpoint(
    val queueId: String, val total: Int, val next: Int = 0, val successes: Int = 0,
    val failures: Int = 0, val meanSum: Double = 0.0, val stdSum: Double = 0.0,
    val lastFailure: String = "", val reason: String = "Готово к продолжению", val analysisVersion:Int=2
) {
    val complete: Boolean get() = next >= total
    val sensitivity: Float get() = (0.85 + meanSum / max(1, successes) * .6 + stdSum / max(1, successes) * .3).coerceIn(.8, 1.2).toFloat()
    fun record(result: OfflineCalibration.Result?, error: String = ""): CalibrationCheckpoint {
        check(!complete)
        require(result == null || (result.fluxMean.isFinite() && result.fluxStd.isFinite() && result.frames > 10))
        return copy(next = next + 1, successes = successes + if (result != null) 1 else 0,
            failures = failures + if (result == null) 1 else 0,
            meanSum = meanSum + (result?.fluxMean ?: 0.0), stdSum = stdSum + (result?.fluxStd ?: 0.0),
            lastFailure = if (error.isNotEmpty()) error.take(500) else lastFailure, reason = "Сохранено")
    }
    fun write(out: DataOutputStream) {
        out.writeInt(2); out.writeUTF(queueId); out.writeInt(total); out.writeInt(next)
        out.writeInt(successes); out.writeInt(failures); out.writeDouble(meanSum); out.writeDouble(stdSum)
        out.writeUTF(lastFailure); out.writeUTF(reason);out.writeInt(analysisVersion)
    }
    companion object {
        fun read(input: DataInputStream): CalibrationCheckpoint {
            val version=input.readInt();require(version in 1..2) { "Неизвестный формат калибровки" }
            val s = CalibrationCheckpoint(input.readUTF(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readDouble(), input.readDouble(), input.readUTF(), input.readUTF(),if(version==2)input.readInt() else 1)
            require(s.queueId.matches(Regex("[a-f0-9-]{36}")) && s.total in 1..100_000 && s.next in 0..s.total &&
                s.successes in 0..s.next && s.failures == s.next - s.successes && s.meanSum.isFinite() && s.stdSum.isFinite() &&
                s.meanSum >= 0 && s.stdSum >= 0 && s.analysisVersion in 1..2) { "Повреждённое состояние калибровки" }
            return s
        }
    }
}

/** Один worker на процесс. Без wake lock и скрытого продолжения после ухода с экрана. */
internal object CalibrationJobs {
    @Volatile private var worker: Thread? = null
    @Volatile private var cancelled = false
    @Volatile private var pauseReason = "Приостановлено"
    @Volatile var scanning = false
        private set
    @Volatile var status = ""
        private set
    val isRunning: Boolean get() = worker != null
    val isBusy: Boolean get() = isRunning || scanning
    val mayResumeOnReturn: Boolean get() = isRunning && !cancelled
    private var observer: ((String) -> Unit)? = null
    private var serviceObserver:((String)->Unit)?=null

    @Synchronized fun observe(callback: ((String) -> Unit)?) { observer = callback }
    @Synchronized fun observeService(callback:((String)->Unit)?) {serviceObserver=callback}
    fun notice(text:String){publish(text)}
    private fun publish(text: String) {
        status = text
        val callbacks=synchronized(this){listOfNotNull(observer,serviceObserver)}
        for(callback in callbacks)try{callback(text)}catch(error:Exception){Log.w("GlyphAnalysis","Status observer",error)}
    }
    @Synchronized fun beginScan(): Boolean {
        if (isBusy) return false
        scanning = true; cancelled = false
        return true
    }
    @Synchronized fun endScan() { scanning = false }
    fun scanCancelled(): Boolean = cancelled || Thread.currentThread().isInterrupted
    @Synchronized fun pause(reason: String = "Пауза: приложение закрыто") {
        pauseReason = reason; cancelled = true
        worker?.interrupt()
    }
    private fun stateFile(context: Context) = AtomicFile(File(context.filesDir, "calibration-state.bin"))
    private fun queueFile(context: Context, id: String) = AtomicFile(File(context.filesDir, "calibration-queue-$id.bin"))
    private fun writeAtomic(file: AtomicFile, block: (DataOutputStream) -> Unit) {
        val stream = file.startWrite()
        try { val out = DataOutputStream(stream); block(out); out.flush(); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
    @Synchronized fun checkpoint(context: Context): CalibrationCheckpoint? {
        val file = stateFile(context)
        val stream = try { file.openRead() } catch (_: FileNotFoundException) { return null }
        return stream.use { CalibrationCheckpoint.read(DataInputStream(it)) }
    }
    @Synchronized fun clear(context: Context) {
        check(!isBusy) { "Сначала приостановите калибровку" }
        stateFile(context).delete()
        // Сброс работает и при повреждённом заголовке, не затрагивая другие файлы.
        val queueName = Regex("calibration-queue-[a-f0-9-]{36}\\.bin(?:\\.new|\\.bak)?")
        context.filesDir.listFiles()?.filter { queueName.matches(it.name) }?.forEach { it.delete() }
        TrackAnalysisCache(context).clear()
        LearningStore(context).clear()
        status = ""
    }
    @Synchronized private fun save(context: Context, state: CalibrationCheckpoint) {
        writeAtomic(stateFile(context)) { state.write(it) }
    }
    private fun applyProfile(context: Context, state: CalibrationCheckpoint) {
        if (state.successes > 0) check(context.getSharedPreferences("glyph_profile", Context.MODE_PRIVATE).edit()
            .putInt("vj2_tracks", state.successes).putFloat("vj2_sensitivity", state.sensitivity).commit()) { "Не удалось сохранить профиль" }
    }
    @Synchronized fun start(context: Context, uris: List<Uri>? = null, launchWorker:Boolean=true,learnOnly:Boolean=false): Boolean {
        if (isBusy) return false
        val app = context.applicationContext
        var state = checkpoint(app)
        if (uris != null) {
            require(uris.isNotEmpty() && uris.size <= 100_000)
            val id = UUID.randomUUID().toString()
            writeAtomic(queueFile(app, id)) { out ->
                out.writeInt(1); out.writeInt(uris.size)
                for (uri in uris) out.writeUTF(uri.toString())
            }
            val old = state
            state = CalibrationCheckpoint(id, uris.size)
            save(app, state)
            if (old != null) queueFile(app, old.queueId).delete()
        }
        // Old sensitivity-only results are not complete analysis maps. Rebuild the same durable queue.
        if(state!=null && state.analysisVersion<2) {
            state=CalibrationCheckpoint(state.queueId,state.total,reason="Полный анализ: очередь обновлена")
            save(app,state)
        }
        val initial = state ?: return false
        if (initial.complete && !learnOnly && !LearningStore(app).needsTraining()) return false
        LearningStore(app).markPending()
        if(!launchWorker)return true
        cancelled = false; pauseReason = "Приостановлено"
        worker = Thread({ run(app, initial,learnOnly) }, "GlyphAnalysis-3.17").also { it.start() }
        return true
    }
    private fun progress(s: CalibrationCheckpoint, reason: String): String =
        "$reason · ${s.next}/${s.total}; готово: ${s.successes}; пропущено: ${s.failures}" +
            if (s.lastFailure.isNotEmpty()) "\nПоследняя ошибка: ${s.lastFailure}" else ""

    private fun run(app: Context, initial: CalibrationCheckpoint,learnOnly:Boolean) {
        var state = initial
        var sliceStarted = System.nanoTime()
        var tracksThisRun = 0
        var stopReason = "Приостановлено"
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            applyProfile(app, state)
            val policy = CalibrationThermalGuard(app)
            val analyzer = OfflineTrackAnalysis(app)
            if(!learnOnly) queueFile(app, state.queueId).openRead().use { stream ->
                val input = DataInputStream(BufferedInputStream(stream))
                require(input.readInt() == 1 && input.readInt() == state.total) { "Повреждён список треков" }
                repeat(state.next) { input.readUTF() }
                while (!state.complete) {
                    if (cancelled) throw OfflineCalibration.Paused(pauseReason)
                    val uri = Uri.parse(input.readUTF())
                    publish(progress(state, "Полный анализ трека ${state.next + 1}"))
                    var result: OfflineCalibration.Result? = null; var failure = ""
                    try {
                        while(result==null) {
                            try {
                                policy.check()
                                result=analyzer.analyze(uri,{cancelled},{policy.check()},{position ->
                                    publish(progress(state,"Анализ трека ${state.next+1} · ${position/1000} с"))
                                })
                            }catch(heat:CalibrationThermalGuard.Hot) {
                                val waitStarted=android.os.SystemClock.elapsedRealtime()
                                while(true) {
                                    if(cancelled)throw OfflineCalibration.Paused(pauseReason)
                                    publish(progress(state,"Остывание · декодер освобождён"))
                                    if(android.os.SystemClock.elapsedRealtime()-waitStarted>900000L)throw OfflineCalibration.Paused("Пауза: телефон не остыл за 15 минут")
                                    try{Thread.sleep(1000)}catch(_:InterruptedException){throw OfflineCalibration.Paused(pauseReason)}
                                    try{policy.check();publish(progress(state,"Продолжаем анализ трека ${state.next+1}"));break}catch(_:CalibrationThermalGuard.Hot){}
                                }
                            }
                        }
                    } catch (pause: OfflineCalibration.Paused) { throw pause }
                    catch (disk:IOException) {throw OfflineCalibration.Paused("Пауза: ошибка записи/чтения анализа: ${disk.message}")}
                    catch (_: SecurityException) { throw OfflineCalibration.Paused("Пауза: нет доступа к файлам. Повторно предоставьте доступ к папке") }
                    catch (error: Exception) {
                        failure = error.message ?: error.javaClass.simpleName
                        Log.w("GlyphCalibration", "Track ${state.next + 1} failed", error)
                    }
                    // Если остановили текущий трек, его не помечаем пропущенным.
                    if (cancelled) throw OfflineCalibration.Paused(pauseReason)
                    val updated = state.record(result, failure)
                    save(app, updated); state = updated
                    applyProfile(app, state); tracksThisRun++
                    publish(progress(state, "Сохранено"))
                    if (!state.complete) {
                        try { Thread.sleep(25) } catch (_: InterruptedException) { throw OfflineCalibration.Paused(pauseReason) }
                    }
                }
            }
            val learned=LearningStore(app).train({
                if(cancelled || Thread.currentThread().isInterrupted)throw OfflineCalibration.Paused(pauseReason)
                policy.check()
            }){message->publish(progress(state,message))}
            stopReason = (if(state.complete) "Готово · " else "Пауза очереди · ")+learned
        } catch (pause: OfflineCalibration.Paused) { stopReason = if (cancelled) pauseReason else pause.reason }
        catch (error: Exception) {
            stopReason = "Пауза: ${error.message ?: error.javaClass.simpleName}"
            Log.e("GlyphCalibration", "Calibration paused", error)
        } finally {
            // Сброс interrupt нужен для надёжного сохранения причины остановки.
            Thread.interrupted()
            try { state = state.copy(reason = stopReason.take(500)); save(app, state) }
            catch (error: Exception) { Log.e("GlyphCalibration", "Cannot save stop reason", error) }
            synchronized(this) { worker = null }
            publish(progress(state, stopReason))
        }
    }
}

internal class CalibrationThermalGuard(private val context: Context) {
    class Hot(reason:String):OfflineCalibration.Paused(reason)
    private var checkedAt = 0L
    var restMillis=25L;private set
    fun check() {
        val now = System.nanoTime()
        if (checkedAt != 0L && now - checkedAt < 1_000_000_000L) return
        checkedAt = now
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (Build.VERSION.SDK_INT >= 29 && power != null && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE)
            throw Hot("Пауза: телефон нагрелся. Продолжите после остывания")
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        restMillis=when {temperature<=0 -> 25L;temperature<370 -> 5L;temperature<385 -> 25L;else -> 75L}
        if (temperature >= 400) throw Hot("Пауза: температура батареи ≥40 °C")
    }
}
