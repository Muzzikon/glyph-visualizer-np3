package com.example.glyphvisualizer

import kotlin.math.*

/** Ridge residual regression; model selection and final evaluation never split one track across folds. */
internal class LearningScale(val center:DoubleArray,val width:DoubleArray) {
    fun transform(x:DoubleArray):DoubleArray {
        val z=DoubleArray(16);z[0]=1.0
        for(i in 0 until LearningFeatures.DIM)z[i+1]=((x[i]-center[i])/width[i]).coerceIn(-3.0,3.0)
        z[13]=x[5]*x[9];z[14]=x[6]*x[7];z[15]=max(x[2],x[3])*x[5]
        return z
    }
    companion object {
        fun fit(rows:List<LearningRow>):LearningScale {
            val hist=Array(LearningFeatures.DIM){DoubleArray(64)}
            // Each track contributes equal total weight; a long song or many labels cannot dominate.
            val counts=rows.groupingBy{it.group}.eachCount()
            for(row in rows)for(i in hist.indices)hist[i][(unit(row.x[i])*63).roundToInt()]+=1.0/counts.getValue(row.group)
            fun quantile(h:DoubleArray,p:Double):Double {val total=h.sum();if(total==0.0)return .5;var sum=0.0;for(i in h.indices){sum+=h[i];if(sum+max(1e-12,total*1e-12)>=total*p)return i/63.0};return 1.0}
            return LearningScale(DoubleArray(hist.size){quantile(hist[it],.5)},DoubleArray(hist.size){max(.08,(quantile(hist[it],.9)-quantile(hist[it],.1))*.5)})
        }
    }
}
internal class ResponseModel(val scale:LearningScale,val weights:Array<DoubleArray>,val enabled:BooleanArray,
    val support:List<DoubleArray>,val signature:String,val validation:DoubleArray,val baseline:DoubleArray,val labelCount:Int,val trackCount:Int,val trainingGroups:Set<String>) {
    fun predict(x:DoubleArray):ResponseControl? {
        if(!enabled.any{it} || support.isEmpty())return null
        val z=scale.transform(x);var nearest=Double.POSITIVE_INFINITY
        for(row in support){var d=0.0;for(i in 1..12)d+=(z[i]-row[i]).pow(2);nearest=min(nearest,sqrt(d/12))}
        // Domain support, not a claimed calibrated probability. Out-of-domain examples fall back.
        val confidence=unit((1.25-nearest)/.75)
        if(confidence<=0.0)return null
        val y=LearningFeatures.prior(x)
        for(head in 0..2)if(enabled[head]){var residual=0.0;for(i in z.indices)residual+=weights[head][i]*z[i];y[head]=unit(y[head]+residual)}
        // A disabled output must not overwrite that part of the established renderer rules.
        return ResponseControl(if(enabled[0])y[0] else -1.0,if(enabled[1])y[1] else -1.0,if(enabled[2])y[2] else -1.0,confidence)
    }
}
internal data class LearningFit(val model:ResponseModel?,val scale:LearningScale,val message:String,val holdout:Set<String>)
internal object ResponseLearning {
    private val penalties=doubleArrayOf(.25,1.0,4.0,16.0)
    fun train(rows:List<LearningRow>,labels:List<TeachingExample>,signature:String,check:()->Unit={}):LearningFit {
        val scale=LearningScale.fit(rows+labels.map{LearningRow(it.group,it.x)})
        val groups=labels.map{it.group}.distinct().sorted()
        if(labels.size<16 || groups.size<8)return LearningFit(null,scale,"Для общей модели нужно 16 оценок минимум на 8 разных треках",emptySet())
        val holdout=groups.filterIndexed{i,_->i%5==0}.toSet()
        val dev=labels.filter{it.group !in holdout};val test=labels.filter{it.group in holdout}
        if(dev.map{it.group}.distinct().size<5 || holdout.size<2)return LearningFit(null,scale,"Недостаточно независимых треков для проверки",holdout)
        val devGroups=dev.map{it.group}.distinct().sorted()
        var bestPenalty=penalties.last();var bestLoss=Double.POSITIVE_INFINITY
        // Alpha chosen on development tracks only. The final holdout is never used to tune alpha.
        for(alpha in penalties) {
            var loss=0.0;var total=0
            for(fold in 0 until 4) {
                check();val excluded=devGroups.filterIndexed{i,_->i%4==fold}.toSet()
                val train=dev.filter{it.group !in excluded}
                val foldScale=LearningScale.fit(rows.filter{it.group !in holdout && it.group !in excluded}+train.map{LearningRow(it.group,it.x)})
                val weights=solve(train,foldScale,alpha)
                for(g in excluded){val examples=dev.filter{it.group==g};var groupLoss=0.0
                    for(e in examples){val y=predict(e.x,foldScale,weights);groupLoss+=(0..2).sumOf{(y[it]-e.target[it]).pow(2)}}
                    loss+=groupLoss/max(1,examples.size);total++}
            }
            loss/=max(1,total);if(loss<bestLoss){bestLoss=loss;bestPenalty=alpha}
        }
        check()
        val devScale=LearningScale.fit(rows.filter{it.group !in holdout}+dev.map{LearningRow(it.group,it.x)})
        val fitted=solve(dev,devScale,bestPenalty)
        val error=DoubleArray(3);val priorError=DoubleArray(3);val meanError=DoubleArray(3)
        val groupCounts=dev.groupingBy{it.group}.eachCount();val totalGroups=devGroups.size
        val mean=DoubleArray(3){h->dev.sumOf{it.target[h]/groupCounts.getValue(it.group)}/totalGroups}
        // Equal holdout track weighting, even when the user supplied unequal numbers of fragments.
        val testCounts=test.groupingBy{it.group}.eachCount()
        val perTrack=HashMap<String,Array<DoubleArray>>()
        for(e in test){check();val y=predict(e.x,devScale,fitted);val prior=LearningFeatures.prior(e.x);val weight=1.0/testCounts.getValue(e.group)/holdout.size
            for(h in 0..2){val a=(y[h]-e.target[h]).pow(2);val b=(prior[h]-e.target[h]).pow(2);val c=(mean[h]-e.target[h]).pow(2)
                error[h]+=a*weight;priorError[h]+=b*weight;meanError[h]+=c*weight
                val track=perTrack.getOrPut(e.group){Array(3){DoubleArray(3)}}
                track[0][h]+=a/testCounts.getValue(e.group);track[1][h]+=b/testCounts.getValue(e.group);track[2][h]+=c/testCounts.getValue(e.group)}}
        val baseline=DoubleArray(3){min(priorError[it],meanError[it])}
        val enabled=BooleanArray(3){h->
            val diversity=test.maxOf{it.target[h]}-test.minOf{it.target[h]}
            val comparator=if(priorError[h]<=meanError[h])1 else 2
            diversity>=.15 && baseline[h]>.004 && error[h]<=baseline[h]*.90 && error[h]<.10 &&
                perTrack.values.all{it[0][h]<=it[comparator][h]+.01}
        }
        if(!enabled.any{it})return LearningFit(null,scale,"Модель не прошла проверку на других треках; сохранена обычная автоматика",holdout)
        check()
        // Keep the final holdout out of deployed coefficients as well as hyperparameter selection.
        val candidates=dev.sortedWith(compareBy<TeachingExample>{it.group}.thenBy{it.bin}).map{devScale.transform(it.x)}
        val support=ArrayList<DoubleArray>();val distances=DoubleArray(candidates.size){Double.POSITIVE_INFINITY}
        var next=0
        while(support.size<min(64,candidates.size)) {
            check();val anchor=candidates[next];support.add(anchor)
            for(i in candidates.indices){var distance=0.0;for(j in 1..12)distance+=(candidates[i][j]-anchor[j]).pow(2);distances[i]=min(distances[i],distance)}
            next=distances.indices.maxByOrNull{distances[it]} ?: 0
            if(distances[next]<1e-12)break
        }
        return LearningFit(ResponseModel(devScale,fitted,enabled,support,signature,error,baseline,dev.size,devGroups.size,devGroups.toSet()),scale,
            "Модель принята: ${labels.size} оценок, ${groups.size} треков, ${enabled.count{it}}/3 выходов",holdout)
    }
    private fun predict(x:DoubleArray,scale:LearningScale,weights:Array<DoubleArray>):DoubleArray {
        val z=scale.transform(x);val y=LearningFeatures.prior(x)
        for(h in 0..2){var sum=0.0;for(i in z.indices)sum+=z[i]*weights[h][i];y[h]=unit(y[h]+sum)};return y
    }
    private fun solve(labels:List<TeachingExample>,scale:LearningScale,alpha:Double):Array<DoubleArray> {
        val n=16;val gram=Array(n){DoubleArray(n)};val rhs=Array(3){DoubleArray(n)}
        val counts=labels.groupingBy{it.group}.eachCount()
        for(e in labels){val x=scale.transform(e.x);val prior=LearningFeatures.prior(e.x);val w=1.0/counts.getValue(e.group)
            for(i in 0 until n){for(j in 0..i)gram[i][j]+=x[i]*x[j]*w;for(h in 0..2)rhs[h][i]+=x[i]*(e.target[h]-prior[h])*w}}
        for(i in 0 until n){gram[i][i]+=if(i==0)alpha*.1 else alpha;for(j in 0 until i)gram[j][i]=gram[i][j]}
        val lower=Array(n){DoubleArray(n)}
        for(i in 0 until n)for(j in 0..i){var v=gram[i][j];for(k in 0 until j)v-=lower[i][k]*lower[j][k];lower[i][j]=if(i==j)sqrt(max(v,1e-12)) else v/lower[j][j]}
        return Array(3){h->val x=DoubleArray(n);for(i in 0 until n){var v=rhs[h][i];for(k in 0 until i)v-=lower[i][k]*x[k];x[i]=v/lower[i][i]}
            for(i in n-1 downTo 0){var v=x[i];for(k in i+1 until n)v-=lower[k][i]*x[k];x[i]=v/lower[i][i]};x}
    }
}
