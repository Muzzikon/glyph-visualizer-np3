package com.example.glyphvisualizer

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Disk/provider/model work is off the render Looper. One immutable loaded bundle is published. */
internal class CachedPlaybackSource(context:Context) {
    private val cache=TrackAnalysisCache(context.applicationContext)
    private val learning=LearningStore(context.applicationContext)
    private val io=Executors.newSingleThreadExecutor{task -> Thread(task,"GlyphTrackCache").apply{isDaemon=true}}
    private val generation=AtomicLong()
    private data class Loaded(val key:String,val map:CachedTrack,val responses:Array<ResponseControl?>)
    @Volatile var contextModel:MusicalContext?=null;private set
    @Volatile private var requested=""
    @Volatile private var loaded:Loaded?=null
    @Volatile var status="LIVE · нет карты";private set
    private var revision=-1L;private var learningRevision=-1L
    private var lastIndex=-1;private var lastPosition=-1L;private var validatedAt=0L
    private val decoded=AudioFeatures()
    var currentResponse:ResponseControl?=null;private set
    data class Feed(val frames:Int,val reset:Boolean)
    fun request(track:PlaybackTrack?) {
        val key=track?.key ?: "";val rev=TrackAnalysisCache.revision;val learnRev=LearningStore.revision
        if(key==requested && rev==revision && learnRev==learningRevision)return
        val previous=loaded;val sameMap=key==requested && rev==revision
        requested=key;revision=rev;learningRevision=learnRev;loaded=null
        lastIndex=-1;lastPosition=-1;validatedAt=0L;currentResponse=null
        val ticket=generation.incrementAndGet()
        status=if(track==null)"LIVE · нет позиции плеера" else "LIVE · поиск карты"
        io.execute {
            if(generation.get()!=ticket)return@execute
            try {
                val context=learning.contextModel()
                if(generation.get()==ticket)contextModel=context
                if(track==null)return@execute
                val candidate=if(sameMap && previous!=null && cache.fresh(previous.map.identity))previous.map else {
                    val identity=cache.match(track,cache.entries());identity?.let{cache.load(it)}
                }
                val responses=if(candidate!=null)try{
                    val checkCurrent={check(generation.get()==ticket && !Thread.currentThread().isInterrupted){"Cache request cancelled"}}
                    learning.playbackControls(candidate,checkCurrent)
                }catch(_:Exception){emptyArray<ResponseControl?>()} else emptyArray()
                if(generation.get()==ticket) {
                    loaded=candidate?.let{Loaded(key,it,responses)}
                    status=if(candidate!=null)"CACHE · полный анализ" else "LIVE · совпадение не подтверждено"
                }
            }catch(_:Exception){if(generation.get()==ticket){loaded=null;status="LIVE · карта недоступна"}}
        }
    }
    fun ready(track:PlaybackTrack?,now:Long):Boolean {
        val bundle=loaded ?: return false;val current=bundle.map
        val position=track?.position(now) ?: return false
        if(now-validatedAt>=30000) {
            validatedAt=now;val ticket=generation.get()
            io.execute {if(generation.get()==ticket && !cache.fresh(current.identity)) {loaded=null;status="LIVE · исходный файл изменён"}}
        }
        return bundle.key==track.key && position <= current.timeMs(current.frames-1)+250 && current.frames>10
    }
    fun feed(track:PlaybackTrack,now:Long,reset:()->Unit,accept:(AudioFeatures,Double,Boolean)->Unit):Feed {
        val bundle=loaded ?: return Feed(0,false)
        if(bundle.key!=track.key) return Feed(0,false)
        val current=bundle.map
        val position=track.position(now) ?: return Feed(0,false)
        val target=current.index(position)
        val discontinuity=lastIndex<0 || target<lastIndex || target-lastIndex>12 || (lastPosition>=0 && position-lastPosition>250)
        var frames=0
        if(discontinuity){reset();lastIndex=(target-150).coerceAtLeast(-1)}
        val dt=current.hop.toDouble()/current.rate/track.speed
        while(lastIndex<target) {
            lastIndex++;current.read(lastIndex,decoded)
            // Models were learned at normal speed; preserve the existing rules on speed-shifted playback.
            currentResponse=if(kotlin.math.abs(track.speed-1f)<=.03f)bundle.responses.getOrNull((current.timeMs(lastIndex)/LearningFeatures.BIN_MS).toInt()) else null
            currentResponse=currentResponse?.let { control ->
                val within=current.timeMs(lastIndex)%LearningFeatures.BIN_MS
                control.copy(preparation=control.preparation*unit((within-3500)/500.0))
            }
            accept(decoded,dt,discontinuity && lastIndex<target);frames++
        }
        lastPosition=position
        return Feed(frames,discontinuity)
    }
    fun resetCursor(){lastIndex=-1;lastPosition=-1;currentResponse=null}
    fun close(){generation.incrementAndGet();loaded=null;currentResponse=null;io.shutdownNow()}
}
