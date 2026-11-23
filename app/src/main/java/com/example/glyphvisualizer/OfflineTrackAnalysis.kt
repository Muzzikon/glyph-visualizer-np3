package com.example.glyphvisualizer

import android.content.Context
import android.media.*
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.*

/** Full-track PCM analysis. Streaming buffers and compressed durable features, no retained PCM. */
internal class OfflineTrackAnalysis(private val context:Context) {
    private val cache=TrackAnalysisCache(context)
    private val thermal=CalibrationThermalGuard(context)
    fun analyze(uri:Uri,cancelled:()->Boolean,guard:()->Unit,progress:(Long)->Unit={},paced:Boolean=true):OfflineCalibration.Result {
        fun running(){if(cancelled() || Thread.currentThread().isInterrupted)throw OfflineCalibration.Paused("Приостановлено");guard();if(paced)thermal.check()}
        running()
        val retriever=MediaMetadataRetriever()
        var title="";var artist="";var album="";var durationMs=0L
        try {
            retriever.setDataSource(context,uri)
            title=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            artist=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            album=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            durationMs=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }catch(_:Exception){/* Tags are optional; the selected audio stream is authoritative. */}finally{try{retriever.release()}catch(_:Exception){}}
        running()
        val stamp=sourceStamp(context,uri)
        val extractor=MediaExtractor();var codec:MediaCodec?=null;var writer:TrackAnalysisCache.Writer?=null
        try {
            extractor.setDataSource(context,uri,null)
            val track=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}
                ?: error("В файле нет аудиодорожки")
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            if(durationMs<=0 && format.containsKey(MediaFormat.KEY_DURATION))durationMs=format.getLong(MediaFormat.KEY_DURATION)/1000
            require(durationMs in 1..21_600_000){"Неизвестная длительность или трек длиннее 6 часов"}
            val identity=TrackIdentity(uri.toString(),title,artist,album,durationMs,stamp.first,stamp.second)
            cache.reusable(identity)?.let { old ->
                var n=0;var mean=0.0;var m2=0.0;val f=AudioFeatures()
                repeat(old.frames){i ->
                    if(i%100==0)running()
                    old.read(i,f)
                    if(f.level>1e-7){val flux=f.flux.average();n++;val delta=flux-mean;mean+=delta/n;m2+=delta*(flux-mean)}
                }
                return OfflineCalibration.Result(max(11,n),mean,if(n>1)sqrt(max(0.0,m2/(n-1)))else 0.0)
            }
            val decoder=MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)));codec=decoder
            decoder.configure(format,null,null,0);decoder.start()
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=AudioFormat.ENCODING_PCM_16BIT
            var builder:TimelineBuilder?=null
            val info=MediaCodec.BufferInfo();var inputDone=false;var outputDone=false
            var count=0;var mean=0.0;var m2=0.0
            var lastProgress=System.nanoTime();var quantum=lastProgress;var lastStatus=lastProgress
            var basePts:Long?=null
            var firstInputPts:Long?=null;var lastInputPts=0L;var packetSpan=0L
            fun createBuilder() {
                require(rate in 8000..192000 && channels in 1..32 && encoding in arrayOf(AudioFormat.ENCODING_PCM_16BIT,AudioFormat.ENCODING_PCM_FLOAT))
                val hop=(rate*.020).roundToInt();var size=256;while(size<hop)size*=2
                val first=size/2*1_000_000L/rate
                val sink=cache.Writer(identity,rate,hop,first);writer=sink
                builder=TimelineBuilder(rate,size,hop){f ->
                    sink.append(f)
                    if(f.level>1e-7){val flux=f.flux.average();count++;val delta=flux-mean;mean+=delta/count;m2+=delta*(flux-mean)}
                }
            }
            while(!outputDone) {
                running()
                var fed=false
                if(!inputDone) {
                    val i=decoder.dequeueInputBuffer(0)
                    if(i>=0) {
                        fed=true
                        val b=requireNotNull(decoder.getInputBuffer(i));b.clear()
                        val pts=extractor.sampleTime;val n=if(pts<0)-1 else extractor.readSampleData(b,0)
                        if(n<0){decoder.queueInputBuffer(i,0,0,max(0L,pts),MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                        else{
                            if(firstInputPts==null)firstInputPts=pts else {
                                require(pts>=lastInputPts){"Немонотонные метки времени аудио"}
                                packetSpan=max(packetSpan,pts-lastInputPts)
                            }
                            lastInputPts=pts
                            decoder.queueInputBuffer(i,0,n,pts,0);extractor.advance()
                        }
                    }
                }
                val out=decoder.dequeueOutputBuffer(info,if(fed)0 else 2000)
                if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual=decoder.outputFormat
                    val nextRate=actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);val nextChannels=actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    require(builder==null || (nextRate==rate && nextChannels==channels)){"Формат аудио изменился внутри трека"}
                    rate=nextRate;channels=nextChannels
                    encoding=if(actual.containsKey(MediaFormat.KEY_PCM_ENCODING))actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                }else if(out>=0) {
                    try {
                        val b=decoder.getOutputBuffer(out)
                        if(b!=null && info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                            if(builder==null)createBuilder()
                            val timeline=requireNotNull(builder)
                            val initial=basePts
                            if(initial==null) {
                                require(abs(info.presentationTimeUs)<=50_000){"Нестандартное начало временной шкалы аудио"}
                                basePts=info.presentationTimeUs
                            }else require(abs(info.presentationTimeUs-initial-timeline.samples*1_000_000L/rate)<=50_000){"Разрыв временной шкалы аудио"}
                            b.position(info.offset);b.limit(info.offset+info.size);b.order(ByteOrder.LITTLE_ENDIAN)
                            val stride=channels*(if(encoding==AudioFormat.ENCODING_PCM_FLOAT)4 else 2)
                            require(info.size%stride==0){"Неполный PCM-кадр"}
                            var n=0
                            while(b.remaining()>=stride) {
                                if(n++%1024==0)running()
                                var sample=0.0
                                repeat(channels){sample+=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)b.float.toDouble() else b.short/32768.0}
                                require(sample.isFinite()){ "Некорректный PCM" }
                                timeline.accept((sample/channels).coerceIn(-1.0,1.0))
                            }
                            if(System.nanoTime()-lastStatus>500_000_000){progress(timeline.samples*1000/rate);lastStatus=System.nanoTime()}
                        }
                        outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    }finally{decoder.releaseOutputBuffer(out,false)}
                    lastProgress=System.nanoTime()
                }
                require(System.nanoTime()-lastProgress<10_000_000_000L){"Декодер не выдаёт аудио более 10 секунд"}
                val busy=System.nanoTime()-quantum
                if(paced && busy>=50_000_000L) {
                    try{Thread.sleep(thermal.restMillis)}catch(_:InterruptedException){throw OfflineCalibration.Paused("Приостановлено")}
                    quantum=System.nanoTime()
                }
            }
            running()
            val timeline=requireNotNull(builder){"Нет декодированных данных"}
            // Metadata can be a VBR/container estimate. Verify actual packet coverage instead.
            val decodedUs=timeline.samples*1_000_000L/rate
            require(decodedUs<=21_600_000_000L){"Трек длиннее 6 часов"}
            require(inputDone && outputDone && firstInputPts!=null){"Декодирование не завершено"}
            val packetEnd=lastInputPts+packetSpan
            require(abs((basePts ?: 0L)+decodedUs-packetEnd)<=max(500_000L,packetSpan*3)){
                "Декодер потерял часть аудио: PCM ${decodedUs/1000} мс, конец пакетов ${packetEnd/1000} мс"
            }
            if(abs(decodedUs/1000-durationMs)>max(500L,durationMs/1000))
                android.util.Log.w("GlyphAnalysis","Duration metadata differs: declared=$durationMs decoded=${decodedUs/1000}; complete packet timeline verified")
            val sink=requireNotNull(writer);require(sink.count>10)
            require(sourceStamp(context,uri)==stamp){"Файл изменился во время анализа"}
            sink.finish()
            return OfflineCalibration.Result(max(11,count),mean,if(count>1)sqrt(max(0.0,m2/(count-1)))else 0.0)
        }finally {
            try{writer?.close()}catch(_:Exception){}
            try{codec?.stop()}catch(_:Exception){};try{codec?.release()}catch(_:Exception){};try{extractor.release()}catch(_:Exception){}
        }
    }
}

internal class TimelineBuilder(val rate:Int,val size:Int,val hop:Int,private val sink:(AudioFeatures)->Unit) {
    private val pcm=DoubleArray(size);private val fft=PcmSpectrum(size);private val analyzer=SpectrumAnalyzer()
    private var filled=0;var samples=0L;private set
    fun accept(sample:Double) {
        pcm[filled++]=sample;samples++
        if(filled==size) {
            val f=analyzer.analyze(fft.transform(pcm),rate,size)
            var square=0.0;for(v in pcm)square+=v*v
            f.level=sqrt(square/size) // Real PCM RMS, unaffected by normalized Android capture.
            sink(f);pcm.copyInto(pcm,0,hop,size);filled=size-hop
        }
    }
}
