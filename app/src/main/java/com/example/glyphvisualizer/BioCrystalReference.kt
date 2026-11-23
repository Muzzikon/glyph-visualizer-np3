package com.example.glyphvisualizer

import kotlin.math.*

/** Reference-derived LED pose atlas. Music controls pose, rotation and deformation;
 * the phone does not decode or play a video. No allocations in render(). */
class BioCrystalReference {
    val pixels = DoubleArray(625)
    private val poseGain = DoubleArray(BioCrystalPoses.COUNT) { f ->
        var peak=0
        for(v in BioCrystalPoses.pixels[f]) peak=max(peak,v.toInt() and 255)
        255.0/max(100,peak)
    }

    // Filter the source poses once, not the live output: musical attacks stay immediate.
    // Filter both masks with the same kernel so component reclassification cannot pop.
    private val softPoses = Array(2) { part -> Array(BioCrystalPoses.COUNT) { pose ->
        DoubleArray(625) { index ->
            var sum = 0.0
            for (offset in -2..2) {
                val f = (pose + offset).coerceIn(0, BioCrystalPoses.COUNT - 1)
                sum += intensity(f, index, part + 1) * poseGain[f] * (3 - abs(offset))
            }
            sum / (9.0 * 255.0)
        }
    } }

    internal fun developmentPose(phase: Double): Double =
        (BioCrystalPoses.COUNT - 1) * (0.5 - 0.5 * cos(phase * 0.55))

    private fun softSample(pose: Double, x: Double, y: Double, part: Int): Double {
        if (x < 0 || x > 24 || y < 0 || y > 24) return 0.0
        val a = floor(pose).toInt().coerceIn(0, BioCrystalPoses.COUNT - 1)
        val b = min(a + 1, BioCrystalPoses.COUNT - 1)
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val jx = min(ix + 1, 24); val jy = min(iy + 1, 24)
        val pa = softPoses[part][a]; val pb = softPoses[part][b]
        val va = mix(mix(pa[iy*25+ix], pa[iy*25+jx], x-ix),
            mix(pa[jy*25+ix], pa[jy*25+jx], x-ix), y-iy)
        val vb = mix(mix(pb[iy*25+ix], pb[iy*25+jx], x-ix),
            mix(pb[jy*25+ix], pb[jy*25+jx], x-ix), y-iy)
        return mix(va, vb, pose-a)
    }

    internal fun samplePose(position: Double, x: Double, y: Double, part: Int = 0, normalize: Boolean = false): Double {
        // Reflect at the ends: no jump from the final pose to the first.
        val end = (BioCrystalPoses.COUNT - 1).toDouble()
        val cycle = ((position % (2 * end)) + 2 * end) % (2 * end)
        val pose = if (cycle <= end) cycle else 2 * end - cycle
        val a = floor(pose).toInt(); val b = min(a + 1, BioCrystalPoses.COUNT - 1)
        return mix(spatial(a,x,y,part)*(if(normalize)poseGain[a] else 1.0),
            spatial(b,x,y,part)*(if(normalize)poseGain[b] else 1.0),pose-a)
    }

    private fun intensity(pose: Int, index: Int, part: Int): Double {
        val value=(BioCrystalPoses.pixels[pose][index].toInt() and 255).toDouble()
        if(part==0)return value
        val body=(BioCrystalPoses.bodyMasks[pose][index/8].toInt() ushr (index%8)) and 1
        return if((part==1 && body==1)||(part==2 && body==0)) value else 0.0
    }
    private fun spatial(pose: Int, x: Double, y: Double, part: Int): Double {
        if (x < 0 || x > 24 || y < 0 || y > 24) return 0.0
        val ix=floor(x).toInt(); val iy=floor(y).toInt()
        val jx=min(24,ix+1); val jy=min(24,iy+1)
        val top=mix(intensity(pose,iy*25+ix,part),intensity(pose,iy*25+jx,part),x-ix)
        val bottom=mix(intensity(pose,jy*25+ix,part),intensity(pose,jy*25+jx,part),x-ix)
        return mix(top,bottom,y-iy)/255.0
    }

    fun render(e: VjEngine): DoubleArray {
        val b=e.layers[0];val m=e.layers[1];val h=e.layers[2]
        // shapePhase belongs to the recorded motion history, so rewind also retraces these poses.
        // Like scene 9, form development follows a sinusoid: slow at the extremes,
        // alive in the middle. A pure function of recorded phase preserves rewind.
        val pose=developmentPose(m.shapePhase+b.shapePhase*0.15)
        val bodyAngle=b.phase*0.12
        val outerAngle=bodyAngle+sin((h.phase-b.phase)*0.35)*0.18
        val cb=cos(bodyAngle);val sb=sin(bodyAngle)
        val ch=cos(outerAngle);val sh=sin(outerAngle)
        val bp=(b.displacement+b.drive*0.5).coerceIn(-0.6,2.1)
        val mp=(m.displacement+m.drive*0.55).coerceIn(-0.6,2.1)
        val hp=(h.displacement+h.drive*0.3).coerceIn(-0.5,1.6)
        for(y in 0..24) for(x in 0..24) {
            val dx=x-12.0-e.cameraX;val dy=y-12.0-e.cameraY
            val radius=hypot(dx,dy)
            val branch=ease((radius-4.0)/4.0)
            // Core and branches share coordinates: the independent mid response does not tear roots apart.
            val scale=(1.0+bp*0.065+mp*branch*0.045).coerceIn(0.91,1.20)
            val sx=(dx*cb+dy*sb)/scale;val sy=(-dx*sb+dy*cb)/scale
            val body=softSample(pose,sx+12,sy+12,0)
            // Detached components are measured from the source, not cut off by a circular mask.
            val outerScale=(1.0-hp*0.025).coerceIn(0.94,1.02)
            val ox=(dx*ch+dy*sh)/outerScale;val oy=(-dx*sh+dy*ch)/outerScale
            val outer=softSample(pose,ox+12,oy+12,1)
            val presence=mix(b.presence,m.presence,branch*0.65)
            val light=body*presence*(1.0+b.hit*0.12+m.hit*branch*0.10)
            val spark=outer*h.presence*(1.0+h.hit*0.22)
            // Undo output gamma, so reference intensity isn't darkened a second time.
            // Contrast recovery after temporal + spatial interpolation; keep dim facets, sharpen edges.
            val value=unit((light+spark-0.025)/0.91)
            pixels[y*25+x]=value.pow(0.92/1.15)
        }
        return pixels
    }
}
