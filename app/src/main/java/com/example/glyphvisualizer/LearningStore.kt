package com.example.glyphvisualizer

import android.content.Context
import android.util.AtomicFile
import java.io.*
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.*

internal data class LearningTimeline(val group:String,val data:ByteArray) {
    val bins:Int get()=data.size/LearningFeatures.STRIDE
    fun vector(bin:Int,out:DoubleArray)=LearningFeatures.read(data,bin,out)
}
/** Private atomic records; maps stay compatible with 3.15. No label is inferred from a button toggle. */
internal class LearningStore(context:Context) {
    private val app=context.applicationContext
    private val dir=File(app.filesDir,"response-learning-v1").also{check(it.mkdirs() || it.isDirectory)}
    companion object {
        @Volatile var revision=0L;private set
        private val lock=Any()
        private const val MAGIC=0x47564c31
        private val groupPattern=Regex("[a-f0-9]{64}")
        fun fingerprint(map:CachedTrack)=hash(map.data)
        private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    }
    private fun write(name:String,body:(DataOutputStream)->Unit) {
        val bytes=ByteArrayOutputStream().also{b->DataOutputStream(b).use(body)}.toByteArray()
        require(bytes.size<=512*1024)
        val file=AtomicFile(File(dir,name));val stream=file.startWrite()
        try {val out=DataOutputStream(stream);out.writeInt(MAGIC);out.writeInt(1);out.writeInt(bytes.size)
            out.writeLong(CRC32().also{it.update(bytes)}.value);out.write(bytes);out.flush();stream.fd.sync();file.finishWrite(stream)}
        catch(error:Exception){file.failWrite(stream);throw error}
    }
    private fun <T> read(name:String,body:(DataInputStream)->T):T? {
        synchronized(lock) {
        try{return AtomicFile(File(dir,name)).openRead().use{stream->val input=DataInputStream(stream)
            require(input.readInt()==MAGIC && input.readInt()==1);val size=input.readInt();require(size in 0..512*1024)
            val crc=input.readLong();val bytes=ByteArray(size);input.readFully(bytes);require(input.read()==-1)
            require(CRC32().also{it.update(bytes)}.value==crc)
            DataInputStream(ByteArrayInputStream(bytes)).use{data->body(data).also{require(data.read()==-1)}}}}
        catch(_:Exception){return null}
        }
    }
    private fun writeArray(out:DataOutputStream,x:DoubleArray){for(v in x){require(v.isFinite());out.writeDouble(v)}}
    private fun readArray(input:DataInputStream,size:Int,min:Double=-1e5,max:Double=1e5)=DoubleArray(size){input.readDouble().also{require(it.isFinite() && it in min..max)}}
    private fun readScale(input:DataInputStream)=LearningScale(readArray(input,12,0.0,1.0),readArray(input,12,.079,1.0))
    private fun writeScale(out:DataOutputStream,scale:LearningScale){writeArray(out,scale.center);writeArray(out,scale.width)}
    fun loadTimeline(identity:TrackIdentity):LearningTimeline?=read("${identity.id}.windows") {input ->
        require(input.readInt()==TrackAnalysisCache.VERSION)
        require(input.readLong()==identity.size && input.readLong()==identity.modified)
        val group=input.readUTF();require(groupPattern.matches(group));val size=input.readInt();require(size in 13..70213 && size%13==0)
        LearningTimeline(group,ByteArray(size).also{input.readFully(it)})
    }
    fun timeline(map:CachedTrack,check:()->Unit={},paced:Boolean=false):LearningTimeline {
        loadTimeline(map.identity)?.let{return it}
        val out=ByteArrayOutputStream();val window=LearningFeatures.Window();val f=AudioFeatures();var bin=0
        var quantum=System.nanoTime()
        for(i in 0 until map.frames) {
            if(i%200==0){check();if(paced && System.nanoTime()-quantum>50_000_000){Thread.sleep(5);quantum=System.nanoTime()}}
            val next=(map.timeMs(i)/LearningFeatures.BIN_MS).toInt()
            while(bin<next){window.finish(out);bin++}
            map.read(i,f);window.add(f)
        }
        window.finish(out);check()
        val result=LearningTimeline(fingerprint(map),out.toByteArray())
        synchronized(lock){write("${map.identity.id}.windows"){o->o.writeInt(TrackAnalysisCache.VERSION);o.writeLong(map.identity.size);o.writeLong(map.identity.modified);o.writeUTF(result.group);o.writeInt(result.data.size);o.write(result.data)}}
        return result
    }
    fun labels():List<TeachingExample> = synchronized(lock) {
        val result=read("labels.bin"){input ->
            val count=input.readInt();require(count in 0..512)
            List(count){val group=input.readUTF();require(groupPattern.matches(group));val bin=input.readInt();require(bin in 0..5400)
                TeachingExample(group,bin,readArray(input,12,0.0,1.0),readArray(input,3,0.0,1.0))}
        }
        if(result==null && (File(dir,"labels.bin").exists() || File(dir,"labels.bin.bak").exists()))throw IOException("Повреждены оценки обучения; файл сохранён для восстановления")
        result ?: emptyList()
    }
    fun signature(labels:List<TeachingExample>):String {
        val data=ByteArrayOutputStream();DataOutputStream(data).use{out->
            for(e in labels.sortedWith(compareBy<TeachingExample>{it.group}.thenBy{it.bin})){out.writeUTF(e.group);out.writeInt(e.bin);writeArray(out,e.x);writeArray(out,e.target)}}
        return hash(data.toByteArray())
    }
    fun teach(track:PlaybackTrack,position:Long,target:DoubleArray?):String {
        val cache=TrackAnalysisCache(app);val identity=cache.match(track,cache.entries()) ?: error("Не удалось однозначно найти обработанный локальный трек")
        require(cache.fresh(identity)){"Исходный файл изменился; повторите полный анализ"}
        require(position in 0..identity.durationMs)
        val timeline=loadTimeline(identity) ?: cache.load(identity)?.let{timeline(it)} ?: error("Нет пригодной карты этого файла; сначала выполните полный анализ")
        val bin=(position/LearningFeatures.BIN_MS).toInt();val x=DoubleArray(12)
        require(timeline.vector(bin,x)){"В этом фрагменте недостаточно звучащей музыки для обучения"}
        require(target==null || (target.size==3 && target.all{it.isFinite() && it in 0.0..1.0}))
        synchronized(lock) {
            markPending()
            val updated=labels().filterNot{it.group==timeline.group && it.bin==bin}.toMutableList()
            if(target!=null){require(updated.size<512){"Лимит 512 оценок; сначала удалите ненужные примеры"};updated.add(TeachingExample(timeline.group,bin,x,target.copyOf()))}
            write("labels.bin"){o->o.writeInt(updated.size);for(e in updated){o.writeUTF(e.group);o.writeInt(e.bin);writeArray(o,e.x);writeArray(o,e.target)}}
            // Retracted/edited examples cannot keep influencing an old global model.
            AtomicFile(File(dir,"model.bin")).delete();revision++
        }
        return if(target==null)"Оценка удалена" else "Сохранён пример ${bin*4}–${bin*4+4} с; общую модель обновите в меню обучения"
    }
    fun model(labels:List<TeachingExample>):ResponseModel?=read("model.bin") {input ->
        val sig=input.readUTF();require(sig==signature(labels));val scale=readScale(input)
        val enabled=BooleanArray(3){input.readBoolean()};val weights=Array(3){readArray(input,16)}
        val count=input.readInt();require(count in 0..64);val support=List(count){readArray(input,16,-3.1,3.1)}
        val validation=readArray(input,3,0.0,10.0);val baseline=readArray(input,3,0.0,10.0)
        val labelCount=input.readInt();require(labelCount in 0..512)
        val tracks=input.readInt();require(tracks in 0..512)
        val groupCount=input.readInt();require(groupCount in 0..512)
        val groups=List(groupCount){input.readUTF().also{require(groupPattern.matches(it))}}.toSet()
        require(groups.size==tracks)
        ResponseModel(scale,weights,enabled,support,sig,validation,baseline,labelCount,tracks,groups)
    }
    private fun saveModel(model:ResponseModel) {
        write("model.bin"){o->o.writeUTF(model.signature);writeScale(o,model.scale);for(v in model.enabled)o.writeBoolean(v)
            for(w in model.weights)writeArray(o,w);o.writeInt(model.support.size);for(x in model.support)writeArray(o,x)
            writeArray(o,model.validation);writeArray(o,model.baseline);o.writeInt(model.labelCount);o.writeInt(model.trackCount)
            o.writeInt(model.trainingGroups.size);for(g in model.trainingGroups.sorted())o.writeUTF(g)}
    }
    fun contextModel():MusicalContext?=read("context.bin") {input ->
        require(input.readInt()==1)
        val k=input.readInt();require(k in 1..12)
        val centers=Array(k){readArray(input,12,0.0,1.0)}
        val next=Array(k){readArray(input,12,0.0,1.0)}
        val counts=IntArray(k){input.readInt().also{require(it in 0..12288)}}
        val forecast=input.readBoolean();val error=input.readDouble();val baseline=input.readDouble();val groups=input.readInt()
        require(error.isFinite() && error in 0.0..1.0 && baseline.isFinite() && baseline in 0.0..1.0 && groups in 2..512)
        MusicalContext(centers,next,counts,forecast,error,baseline,groups)
    }
    private fun saveContext(model:MusicalContext) {
        write("context.bin"){o->o.writeInt(1);o.writeInt(model.centers.size)
            for(x in model.centers)writeArray(o,x);for(x in model.next)writeArray(o,x)
            for(n in model.counts)o.writeInt(n)
            o.writeBoolean(model.forecast);o.writeDouble(model.error);o.writeDouble(model.baseline);o.writeInt(model.groups)}
    }
    fun status():String {
        val previous=read("report.bin"){input ->input.readUTF().also{input.readUTF();input.readInt()}} ?: "Статистика ещё не обучена"
        return if(File(dir,"pending.bin").exists() || File(dir,"pending.bin.bak").exists()) "$previous\nОбновление обучения не завершено" else previous
    }
    fun markPending(){synchronized(lock){write("pending.bin"){it.writeBoolean(true)}}}
    fun needsTraining():Boolean {
        if(File(dir,"pending.bin").exists() || File(dir,"pending.bin.bak").exists())return true
        if(!File(dir,"context-stage.bin").exists())return true
        val sig=read("report.bin"){input->input.readUTF();input.readUTF().also{input.readInt()}} ?: return true
        return try{sig!=signature(labels())}catch(_:Exception){false}
    }
    fun playbackControls(map:CachedTrack,check:()->Unit):Array<ResponseControl?> {
        val examples=try{labels()}catch(_:Exception){emptyList()};val model=model(examples)
        val context=contextModel()
        if(model==null && context==null && examples.isEmpty())return emptyArray()
        val saved=loadTimeline(map.identity)
        if(model==null && context==null) {
            val group=saved?.group ?: fingerprint(map)
            if(examples.none{it.group==group})return emptyArray()
        }
        val timeline=saved ?: timeline(map,check)
        return buildControls(timeline,examples,model,check,context)
    }
    fun controls(timeline:LearningTimeline,check:()->Unit={}):Array<ResponseControl?> {
        val examples=try{labels()}catch(_:Exception){emptyList()}
        return buildControls(timeline,examples,model(examples),check)
    }
    private fun buildControls(timeline:LearningTimeline,examples:List<TeachingExample>,model:ResponseModel?,check:()->Unit,context:MusicalContext?=contextModel()):Array<ResponseControl?> {
        val exact=examples.filter{it.group==timeline.group}.associateBy{it.bin};val x=DoubleArray(12)
        return Array(timeline.bins){bin->
            if(bin%32==0)check()
            if(!timeline.vector(bin,x))null
            else {
                val automatic=context?.response(x)
                val taught=exact[bin]?.let{ResponseControl(it.target[0],it.target[1],it.target[2],1.0,true)} ?: model?.predict(x)
                val selected=taught ?: automatic
                val nextX=DoubleArray(12)
                if(selected==null || selected.explicit || !timeline.vector(bin+1,nextX))selected else {
                    val upcoming=LearningFeatures.prior(nextX)[2]-LearningFeatures.prior(x)[2]
                    selected.copy(preparation=upcoming.coerceIn(-.15,.15))
                }
            }}
    }
    fun train(check:()->Unit,progress:(String)->Unit):String {
        markPending()
        val cache=TrackAnalysisCache(app);val entries=cache.entries().sortedBy{it.id}
        val representative=HashMap<String,List<LearningRow>>()
        val transitions=HashMap<String,List<ContextPair>>()
        var good=0
        for((index,id) in entries.withIndex()) {
            check();progress("Обучение статистики · ${index+1}/${entries.size}")
            if(!cache.fresh(id))continue
            val timeline=loadTimeline(id) ?: cache.load(id)?.let{timeline(it,check,true)} ?: continue
            good++
            if(representative.containsKey(timeline.group))continue // identical packed audio copies share a validation group
            if(representative.size>=512 && timeline.group>=representative.keys.maxOrNull()!!)continue
            val x=DoubleArray(12);val valid=(0 until timeline.bins).filter{timeline.vector(it,x)}
            if(valid.isEmpty())continue
            val count=min(24,valid.size)
            val sample=List(count){n->val bin=valid[if(count==1)0 else n*(valid.size-1)/(count-1)];timeline.vector(bin,x);LearningRow(timeline.group,x.copyOf())}
            representative[timeline.group]=sample
            transitions[timeline.group]=List(count){n->
                val bin=valid[if(count==1)0 else n*(valid.size-1)/(count-1)]
                val now=DoubleArray(12);val next=DoubleArray(12);timeline.vector(bin,now)
                if(timeline.vector(bin+1,next))ContextPair(timeline.group,now,next) else null
            }.filterNotNull()
            if(representative.size>512){val key=representative.keys.maxOrNull();representative.remove(key);transitions.remove(key)}
        }
        check();progress("Проверка модели на независимых треках")
        val labels=labels();val sig=signature(labels)
        var quantum=System.nanoTime()
        val fit=ResponseLearning.train(representative.values.flatten(),labels,sig){check();if(System.nanoTime()-quantum>50_000_000){Thread.sleep(5);quantum=System.nanoTime()}}
        check();progress("Обучение звуковых состояний и проверка прогноза")
        val contextModel=MusicalContext.train(representative.values.flatten(),transitions.values.flatten()){
            check();if(System.nanoTime()-quantum>50_000_000){Thread.sleep(5);quantum=System.nanoTime()}
        }
        check()
        synchronized(lock) {
            require(signature(labels())==sig){"Оценки изменились во время обучения; повторите обновление модели"}
            write("statistics.bin"){o->writeScale(o,fit.scale);o.writeInt(good)}
            if(fit.model!=null)saveModel(fit.model)
            if(contextModel!=null)saveContext(contextModel) else AtomicFile(File(dir,"context.bin")).delete()
            val contextText=if(contextModel==null)"для звуковых состояний нужно больше разных треков" else
                "${contextModel.centers.size} звуковых состояний; прогноз: ${if(contextModel.forecast) "проверен" else "проверку не прошёл, выключен"}"
            val message="Статистика: $good треков · $contextText · ${fit.message}"
            write("report.bin"){o->o.writeUTF(message.take(1000));o.writeUTF(sig);o.writeInt(good)}
            write("validation.bin"){o->o.writeUTF(fit.message);o.writeInt(fit.holdout.size);for(g in fit.holdout.sorted())o.writeUTF(g)
                val model=fit.model;o.writeBoolean(model!=null);if(model!=null){writeArray(o,model.validation);writeArray(o,model.baseline)}}
            write("context-stage.bin"){it.writeInt(1)}
            AtomicFile(File(dir,"pending.bin")).delete()
            revision++
            return message
        }
    }
    fun clear(){synchronized(lock){dir.deleteRecursively();revision++}}
}
