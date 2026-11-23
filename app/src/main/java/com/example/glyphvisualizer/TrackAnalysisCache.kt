package com.example.glyphvisualizer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.*
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.*

internal data class TrackIdentity(val uri:String,val title:String,val artist:String,val album:String,
    val durationMs:Long,val size:Long,val modified:Long) {
    val id:String get()=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    fun write(out:DataOutputStream) {for(s in arrayOf(uri,title,artist,album))out.writeUTF(s.take(8000));out.writeLong(durationMs);out.writeLong(size);out.writeLong(modified)}
    companion object {
        fun read(input:DataInputStream)=TrackIdentity(input.readUTF(),input.readUTF(),input.readUTF(),input.readUTF(),input.readLong(),input.readLong(),input.readLong())
        fun normalized(text:String)=Normalizer.normalize(text,Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim().replace(Regex("\\s+")," ")
        fun sourceKey(raw:String):String = try {
            if(raw.startsWith("content:")) {
                val uri=java.net.URI(raw)
                if(uri.authority=="com.android.externalstorage.documents" && uri.path.contains("/document/"))
                    "local:"+uri.path.substringAfterLast("/document/") else raw
            }else {
                val path=if(raw.startsWith("file:"))java.net.URI(raw).path else raw
                when {
                    path.matches(Regex("/storage/emulated/\\d+/.*"))->"local:primary:"+path.replaceFirst(Regex("/storage/emulated/\\d+/"),"")
                    path.startsWith("/storage/") && path.count{it=='/'}>=3 ->"local:"+path.removePrefix("/storage/").replaceFirst("/",":")
                    else->raw
                }
            }
        }catch(_:Exception){raw}
    }
}
internal data class PlaybackTrack(val app:String,val session:Int,val uri:String,val title:String,val artist:String,val album:String,
    val durationMs:Long,val positionMs:Long,val updateMs:Long,val speed:Float,val playing:Boolean) {
    val key:String ="$session|$uri|$title|$artist|$album|$durationMs"
    fun position(nowMs:Long):Long? {
        if(!playing || positionMs<0 || updateMs<=0 || updateMs>nowMs+100 || !speed.isFinite() || speed !in .5f..2f) return null
        return (positionMs+(nowMs-updateMs).coerceAtLeast(0)*speed.toDouble()).toLong().coerceAtLeast(0)
    }
}

/** 24 bytes/20 ms, ratio precision 1/255, log amplitude and pitch proxies 16-bit. */
internal object FeaturePacking {
    const val BYTES=24
    fun put(f:AudioFeatures,out:DataOutputStream) {
        fun ratio(x:Double)=out.writeByte((unit(x)*255).roundToInt())
        for(x in f.energy)ratio(x);for(x in f.flux)ratio(x)
        val db=if(f.level<=1e-7) -120.0 else 20*log10(f.level)
        out.writeShort(((db.coerceIn(-120.0,12.0)+120)/132*65535).roundToInt())
        ratio(f.flatness);out.writeShort((f.centroid.coerceIn(0.0,24000.0)/24000*65535).roundToInt());ratio(f.novelty)
        ratio(f.bodyTexture);ratio(f.bodyShare);ratio(f.tonalContrast);ratio(f.spectralContinuity)
        out.writeShort((f.leadRegister.coerceIn(0.0,10.0)/10*65535).roundToInt())
        for(x in f.subEnergy)ratio(x)
    }
    fun get(bytes:ByteArray,index:Int,f:AudioFeatures) {
        var at=index*BYTES
        fun ratio()=(bytes[at++].toInt() and 255)/255.0
        fun short():Int {val hi=bytes[at++].toInt() and 255;return (hi shl 8)+(bytes[at++].toInt() and 255)}
        for(i in 0..2)f.energy[i]=ratio();for(i in 0..2)f.flux[i]=ratio()
        val q=short();f.level=if(q==0)0.0 else 10.0.pow((-120+q/65535.0*132)/20)
        f.flatness=ratio();f.centroid=short()/65535.0*24000;f.novelty=ratio()
        f.bodyTexture=ratio();f.bodyShare=ratio();f.tonalContrast=ratio();f.spectralContinuity=ratio();f.leadRegister=short()/65535.0*10
        for(i in 0..5)f.subEnergy[i]=ratio()
        val sum=f.energy.sum();if(sum>0)for(i in 0..2)f.energy[i]/=sum
        val sub=f.subEnergy.sum();if(sub>0)for(i in 0..5)f.subEnergy[i]/=sub
    }
}
internal data class CachedTrack(val identity:TrackIdentity,val rate:Int,val hop:Int,val firstUs:Long,val data:ByteArray) {
    val frames:Int get()=data.size/FeaturePacking.BYTES
    fun index(positionMs:Long):Int=((positionMs*1000-firstUs).coerceAtLeast(0)*rate/(hop*1_000_000L)).toInt().coerceIn(0,max(0,frames-1))
    fun timeMs(index:Int):Long=(firstUs+index*hop*1_000_000L/rate)/1000
    fun read(index:Int,out:AudioFeatures)=FeaturePacking.get(data,index,out)
}

/** Atomic complete-track files, CRC provided by gzip; incomplete files are never indexed. */
internal class TrackAnalysisCache(private val context:Context) {
    companion object {
        const val VERSION=1
        const val MAGIC=0x47564a43
        const val MAX_FRAMES=1_080_000 // Six hours at 50 Hz, <=26 MB decoded RAM per active track.
        const val MAX_DISK=3L*1024*1024*1024
        @Volatile var revision=0L;private set
        private val catalogLock=Any()
    }
    private val dir=File(context.filesDir,"track-analysis-v1").also{it.mkdirs()}
    private val catalog=File(dir,"catalog.bin")
    private fun file(id:String)=File(dir,"$id.gvj")
    private var catalogRevision=-1L
    private var memoryEntries=linkedMapOf<String,TrackIdentity>()
    private var validCatalogBytes=0L
    private var diskBytes:Long?=null
    fun entries():List<TrackIdentity> = synchronized(catalogLock) {
        if(catalogRevision==revision)return@synchronized memoryEntries.values.toList()
        validCatalogBytes=0L
        val entries=linkedMapOf<String,TrackIdentity>()
        if(catalog.exists())DataInputStream(BufferedInputStream(FileInputStream(catalog))).use { input ->
            try {
                while(true) {
                    val len=input.readInt();require(len in 1..40000)
                    val bytes=ByteArray(len);input.readFully(bytes);val crc=input.readLong()
                    require(java.util.zip.CRC32().also{it.update(bytes)}.value==crc)
                    val identity=TrackIdentity.read(DataInputStream(ByteArrayInputStream(bytes)))
                    if(identity.durationMs in 1..21_600_000)entries[identity.id]=identity
                    require(entries.size<=100_000)
                    validCatalogBytes+=4+len+8
                }
            }catch(_:EOFException){}catch(_:IllegalArgumentException){} // Torn tail ignored; complete preceding records survive.
        }
        memoryEntries=entries;catalogRevision=revision
        entries.values.toList()
    }
    fun match(track:PlaybackTrack,entries:List<TrackIdentity>):TrackIdentity? {
        if(track.durationMs<=0 || track.uri.startsWith("http:") || track.uri.startsWith("https:"))return null
        // Same local source is unambiguous even when the player's duration estimate differs.
        if(track.uri.isNotBlank())entries.firstOrNull{
            TrackIdentity.sourceKey(track.uri)==TrackIdentity.sourceKey(it.uri)
        }?.let{return it}
        val candidates=entries.filter { abs(it.durationMs-track.durationMs)<=250 &&
            ((track.uri.isNotBlank() && TrackIdentity.sourceKey(track.uri)==TrackIdentity.sourceKey(it.uri)) ||
            (TrackIdentity.normalized(track.title).isNotBlank() && TrackIdentity.normalized(track.artist).isNotBlank() &&
             TrackIdentity.normalized(track.title)==TrackIdentity.normalized(it.title) && TrackIdentity.normalized(track.artist)==TrackIdentity.normalized(it.artist) &&
             (track.album.isBlank() || TrackIdentity.normalized(track.album)==TrackIdentity.normalized(it.album)))) }
        val exact=candidates.filter{track.uri.isNotBlank() && TrackIdentity.sourceKey(it.uri)==TrackIdentity.sourceKey(track.uri)}
        return if(exact.isNotEmpty())exact.first() else candidates.singleOrNull()
    }
    fun fresh(identity:TrackIdentity):Boolean {
        val stamp=sourceStamp(context,Uri.parse(identity.uri))
        // Without stable provider revision metadata, use live FFT rather than a possibly changed file.
        return identity.size>=0 && identity.modified>0 && stamp.first==identity.size && stamp.second==identity.modified
    }
    fun load(identity:TrackIdentity):CachedTrack? = try {
        if(!fresh(identity))null else loadFile(file(identity.id),identity)
    }catch(_:Exception){null}
    fun reusable(identity:TrackIdentity):CachedTrack? {
        val old=entries().firstOrNull{it.uri==identity.uri && it==identity} ?: return null
        // Integrity checked before skipping a previously analysed track.
        return load(old)
    }
    private fun loadFile(file:File,identity:TrackIdentity):CachedTrack {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt()==MAGIC && input.readInt()==VERSION)
            val actual=TrackIdentity.read(input);require(actual==identity)
            val rate=input.readInt();val hop=input.readInt();val first=input.readLong();val frames=input.readInt()
            require(rate in 8000..192000 && hop in 1..3840 && first in 0..1_000_000 && frames in 11..MAX_FRAMES)
            val data=ByteArray(Math.multiplyExact(frames,FeaturePacking.BYTES))
            GZIPInputStream(input).use { zip -> DataInputStream(zip).readFully(data);require(zip.read()==-1) }
            return CachedTrack(actual,rate,hop,first,data)
        }
    }
    inner class Writer(val identity:TrackIdentity,val rate:Int,val hop:Int,val firstUs:Long):Closeable {
        private val temp=File(dir,"${identity.id}.tmp")
        private val raw=FileOutputStream(temp)
        private val header=DataOutputStream(raw)
        private val countOffset:Long
        private val body:DataOutputStream
        var count=0;private set
        private var done=false
        init {
            header.writeInt(MAGIC);header.writeInt(VERSION);identity.write(header)
            header.writeInt(rate);header.writeInt(hop);header.writeLong(firstUs);header.flush()
            countOffset=raw.channel.position();header.writeInt(0);header.flush()
            body=DataOutputStream(BufferedOutputStream(GZIPOutputStream(raw),32768))
        }
        fun append(features:AudioFeatures) {require(count<MAX_FRAMES){"Трек длиннее 6 часов"};FeaturePacking.put(features,body);count++}
        fun finish() {
            require(count>10)
            body.close()
            RandomAccessFile(temp,"rw").use{it.seek(countOffset);it.writeInt(count);it.fd.sync()}
            val used=diskBytes ?: (dir.listFiles()?.filter{it.extension=="gvj"}?.sumOf{it.length()} ?: 0L).also{diskBytes=it}
            if(used-file(identity.id).length()+temp.length()>MAX_DISK || dir.usableSpace<=temp.length()+64L*1024*1024)throw IOException("Недостаточно места для анализа (лимит 3 ГБ)")
            synchronized(catalogLock) {
                entries()
                // Discard a torn journal tail before adding records, so later entries remain readable.
                if(catalog.exists() && catalog.length()!=validCatalogBytes)RandomAccessFile(catalog,"rw").use{it.setLength(validCatalogBytes)}
                val oldSize=file(identity.id).length()
                val newSize=temp.length()
                // On Android/Linux rename atomically replaces the previous complete file.
                check(temp.renameTo(file(identity.id))){"Не удалось сохранить анализ трека"}
                val bytes=ByteArrayOutputStream().also{identity.write(DataOutputStream(it))}.toByteArray()
                FileOutputStream(catalog,true).use { stream ->
                    val out=DataOutputStream(stream);out.writeInt(bytes.size);out.write(bytes)
                    out.writeLong(java.util.zip.CRC32().also{it.update(bytes)}.value);out.flush();stream.fd.sync()
                }
                validCatalogBytes+=4+bytes.size+8
                diskBytes=used-oldSize+newSize
                memoryEntries[identity.id]=identity
                revision++;catalogRevision=revision;done=true
            }
        }
        override fun close() {try{body.close()}finally{if(!done)temp.delete()}}
    }
    fun clear() = synchronized(catalogLock) {
        dir.listFiles()?.filter{it.name=="catalog.bin" || it.name.matches(Regex("[a-f0-9]{64}\\.(gvj|tmp)"))}?.forEach{it.delete()}
        memoryEntries.clear();diskBytes=0L;validCatalogBytes=0;revision++;catalogRevision=revision
    }
    fun count():Int=entries().size
}

internal fun sourceStamp(context:Context,uri:Uri):Pair<Long,Long> {
    if(uri.toString().startsWith("file:"))return try{File(java.net.URI(uri.toString())).let{it.length() to it.lastModified()}}catch(_:Exception){-1L to -1L}
    return try {
        context.contentResolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null)?.use { c ->
            if(!c.moveToFirst()) -1L to -1L else c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)) to c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
        } ?: (-1L to -1L)
    }catch(_:Exception){-1L to -1L}
}
