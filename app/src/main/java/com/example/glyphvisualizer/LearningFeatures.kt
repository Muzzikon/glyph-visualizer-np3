package com.example.glyphvisualizer

import java.io.ByteArrayOutputStream
import kotlin.math.*

/** Stable, gain-independent four-second descriptors. Silence is a separate validity byte. */
internal object LearningFeatures {
    const val DIM=12
    const val BIN_MS=4000L
    const val STRIDE=DIM+1
    fun prior(x:DoubleArray):DoubleArray {
        val body=sqrt(x[9])*x[5]
        val activity=max(x[2],x[3])
        return doubleArrayOf(unit(body*.55+activity*.7+x[10]*.3-.10),
            ease((x[6]-.22)/.18)*ease((x[7]-.60)/.25),unit(activity*.75+x[11]*.45+.15))
    }
    class Window {
        private val sums=DoubleArray(DIM)
        private var squareTexture=0.0;private var squareFlux=0.0
        private var count=0;private var audible=0
        fun add(f:AudioFeatures) {
            count++;if(f.level<=1e-7)return
            audible++
            sums[0]+=f.energy[0];sums[1]+=f.energy[2]
            for(i in 0..2)sums[2+i]+=f.flux[i]
            sums[5]+=f.bodyTexture;sums[6]+=f.tonalContrast;sums[7]+=f.spectralContinuity
            sums[8]+=f.novelty;sums[9]+=f.bodyShare
            squareTexture+=f.bodyTexture*f.bodyTexture
            val flux=max(f.flux[0],f.flux[1]);sums[11]+=flux;squareFlux+=flux*flux
        }
        fun finish(out:ByteArrayOutputStream) {
            val x=DoubleArray(DIM);val n=max(1,audible)
            for(i in 0..9)x[i]=sums[i]/n
            x[10]=sqrt(max(0.0,squareTexture/n-x[5]*x[5]))*3
            val flux=sums[11]/n;x[11]=sqrt(max(0.0,squareFlux/n-flux*flux))*3
            for(v in x)out.write((unit(v)*255).roundToInt())
            out.write(if(count>=25 && audible>=max(25,count/2))1 else 0)
            sums.fill(0.0);squareTexture=0.0;squareFlux=0.0;count=0;audible=0
        }
    }
    fun read(data:ByteArray,bin:Int,out:DoubleArray):Boolean {
        if(bin<0 || bin>=data.size/STRIDE)return false
        val offset=bin*STRIDE
        for(i in 0 until DIM)out[i]=(data[offset+i].toInt() and 255)/255.0
        return data[offset+DIM].toInt()==1
    }
}
internal data class LearningRow(val group:String,val x:DoubleArray)
internal data class TeachingExample(val group:String,val bin:Int,val x:DoubleArray,val target:DoubleArray)
internal data class ResponseControl(val heavy:Double,val melodic:Double,val motion:Double,val confidence:Double,val explicit:Boolean=false,val preparation:Double=0.0)

/** Audio-only vocabulary and next-window regression. Visual semantics remain explicit rules. */
internal data class ContextPair(val group:String,val x:DoubleArray,val next:DoubleArray)
internal class MusicalContext(val centers:Array<DoubleArray>,val next:Array<DoubleArray>,val counts:IntArray,
    val forecast:Boolean,val error:Double,val baseline:Double,val groups:Int) {
    fun nearest(x:DoubleArray):Int=centers.indices.minByOrNull{distance(x,centers[it])} ?: 0
    fun response(x:DoubleArray):ResponseControl? {
        val k=nearest(x);val d=sqrt(distance(x,centers[k]))
        if(d>.24)return null // unsupported sound stays with local audio rules
        val confidence=unit(1-d/.24)
        val descriptor=DoubleArray(12){mix(x[it],centers[k][it],.25*confidence)}
        val p=LearningFeatures.prior(descriptor)
        val future=if(forecast && counts[k]>=8)LearningFeatures.prior(next[k]) else p
        // Only prepare broad movement; future predictions never create attacks or blank elements.
        return ResponseControl(p[0],p[1],p[2],confidence*.65,false,
            if(forecast) (future[2]-p[2]).coerceIn(-.15,.15)*confidence else 0.0)
    }
    companion object {
        fun distance(a:DoubleArray,b:DoubleArray):Double {var sum=0.0;for(i in 0..11){val d=a[i]-b[i];sum+=d*d};return sum/12}
        fun train(rows:List<LearningRow>,pairs:List<ContextPair>,check:()->Unit):MusicalContext? {
            val groups=rows.map{it.group}.distinct().sorted()
            if(groups.size<2 || rows.size<8)return null
            val hold=if(groups.size>=8)groups.filterIndexed{i,_->i%5==0}.toSet() else emptySet()
            val dev=rows.filter{it.group !in hold};if(dev.isEmpty())return null
            val centers=ArrayList<DoubleArray>();centers.add(dev.first().x.copyOf())
            while(centers.size<min(12,dev.size)) {
                check()
                val far=dev.maxByOrNull{r->centers.minOf{distance(r.x,it)}}!!.x
                if(centers.minOf{distance(far,it)}<1e-6)break
                centers.add(far.copyOf())
            }
            val groupCounts=dev.groupingBy{it.group}.eachCount()
            repeat(12) {
                check();val sums=Array(centers.size){DoubleArray(12)};val weight=DoubleArray(centers.size)
                for((i,row) in dev.withIndex()) {
                    if(i%256==0)check()
                    val k=centers.indices.minByOrNull{distance(row.x,centers[it])}!!
                    val w=1.0/groupCounts.getValue(row.group);weight[k]+=w
                    for(j in 0..11)sums[k][j]+=row.x[j]*w
                }
                for(k in centers.indices)if(weight[k]>0)for(j in 0..11)centers[k][j]=sums[k][j]/weight[k]
            }
            val next=Array(centers.size){centers[it].copyOf()};val sums=Array(centers.size){DoubleArray(12)}
            val counts=IntArray(centers.size);val weights=DoubleArray(centers.size)
            val train=pairs.filter{it.group !in hold};val pairCounts=train.groupingBy{it.group}.eachCount()
            val global=DoubleArray(12);var globalWeight=0.0
            for((i,p) in train.withIndex()) {
                if(i%256==0)check()
                val k=centers.indices.minByOrNull{distance(p.x,centers[it])}!!
                val w=1.0/pairCounts.getValue(p.group);weights[k]+=w;counts[k]++;globalWeight+=w
                for(j in 0..11){sums[k][j]+=p.next[j]*w;global[j]+=p.next[j]*w}
            }
            if(globalWeight>0)for(j in 0..11)global[j]/=globalWeight
            for(k in next.indices)if(weights[k]>0)for(j in 0..11)next[k][j]=sums[k][j]/weights[k]
            val scores=ArrayList<Triple<Double,Double,Double>>()
            for((_,ps) in pairs.filter{it.group in hold}.groupBy{it.group}) {
                check();var err=0.0;var stay=0.0;var average=0.0
                for(p in ps) {
                    val k=centers.indices.minByOrNull{distance(p.x,centers[it])}!!
                    val predicted=if(counts[k]>=8 && sqrt(distance(p.x,centers[k]))<=.24)next[k] else p.x
                    err+=distance(predicted,p.next);stay+=distance(p.x,p.next);average+=distance(global,p.next)
                }
                scores.add(Triple(err/ps.size,stay/ps.size,average/ps.size))
            }
            val err=if(scores.isEmpty())0.0 else scores.map{it.first}.average()
            val stay=if(scores.isEmpty())0.0 else scores.map{it.second}.average()
            val avg=if(scores.isEmpty())0.0 else scores.map{it.third}.average()
            val baseline=min(stay,avg)
            val enabled=scores.size>=2 && baseline>1e-5 && err<baseline*.90 &&
                scores.all{it.first<= (if(stay<=avg)it.second else it.third)+.005}
            return MusicalContext(centers.toTypedArray(),next,counts,enabled,err,baseline,groups.size)
        }
    }
}

/** Four-second live context costs no disk IO and at most twelve prototype comparisons per window. */
internal class LiveContext {
    private var model:MusicalContext?=null
    private var window=LearningFeatures.Window();private var elapsed=0.0
    var response:ResponseControl?=null;private set
    fun model(value:MusicalContext?) {if(model!==value){model=value;reset()}}
    fun reset(){window=LearningFeatures.Window();elapsed=0.0;response=null}
    fun observe(f:AudioFeatures,dt:Double) {
        val m=model ?: return
        window.add(f);elapsed+=dt
        if(elapsed>=4.0) {
            val bytes=ByteArrayOutputStream(13);window.finish(bytes);val x=DoubleArray(12)
            response=if(LearningFeatures.read(bytes.toByteArray(),0,x))m.response(x) else null
            elapsed=0.0
        }
    }
}
