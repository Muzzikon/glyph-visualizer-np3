package com.example.glyphvisualizer

import kotlin.math.*

/** Векторные штрихи с постоянной топологией. При переходе движутся вершины,
 * а не смешиваются две растровые картинки. Буферы выделяются один раз. */
class SceneRenderer {
    companion object { const val PATHS = 16; const val POINTS = 13; const val TOTAL = 3 * PATHS * POINTS }
    class Geometry {
        val x = DoubleArray(TOTAL); val y = DoubleArray(TOTAL)
        val alpha = DoubleArray(3 * PATHS); val width = DoubleArray(3 * PATHS)
        val entity = IntArray(3 * PATHS)
        fun copyInto(other:Geometry) {x.copyInto(other.x);y.copyInto(other.y);alpha.copyInto(other.alpha);width.copyInto(other.width);entity.copyInto(other.entity)}
        fun clear() { x.fill(0.0); y.fill(0.0); alpha.fill(0.0); width.fill(0.30); entity.fill(-1) }
    }
    private val source = Geometry(); private val destination = Geometry()
    private val displayed=Geometry()
    private var haveDisplayed=false
    private var seenTransition=-1
    private val pixels = DoubleArray(625)
    private val lastPixels = DoubleArray(625)
    val frame = IntArray(625)
    private val vx = DoubleArray(6); private val vy = DoubleArray(6); private val vz = DoubleArray(6)
    private val vertices = arrayOf(doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0),
        doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0))
    private val edges = intArrayOf(0,2,0,3,0,4,0,5,1,2,1,3,1,4,1,5,2,3,3,4,4,5,5,2)
    private val crystalX = DoubleArray(5); private val crystalY = DoubleArray(5)
    private val shapeX=DoubleArray(8); private val shapeY=DoubleArray(8)
    // Cyclic, differentiable pose interpolation; shape and rotation have separate clocks.
    // Outer tip, shoulder, inner fold, twist, reach, detach, roundness, skew.
    // One connected panel topology travels through eight substantially different silhouettes.
    private val crystalPoses=arrayOf(
        doubleArrayOf(10.6,2.2,.9,0.0,.8,0.0,.0,0.0),
        doubleArrayOf(8.7,4.4,4.8,PI/4,.2,0.0,.0,0.0),
        doubleArrayOf(9.2,2.8,2.0,.48,.6,1.2,.0,1.6),
        doubleArrayOf(10.2,3.2,.8,0.0,.3,0.0,.85,0.0),
        doubleArrayOf(8.4,3.8,4.3,PI/4,.8,1.0,.25,-1.0),
        doubleArrayOf(10.8,.85,2.4,-.28,.2,0.0,.0,1.8),
        doubleArrayOf(9.0,4.1,1.4,0.0,.7,.5,.0,-1.2),
        doubleArrayOf(9.7,2.0,5.2,.35,.4,.6,1.0,0.0))
    private val pose=DoubleArray(8)
    // Radius, length, width, motif, tilt, separation of paired motifs.
    private val kaleidoPoses=arrayOf(
        doubleArrayOf(3.8,1.45,.78,0.0,0.0,.05),
        doubleArrayOf(2.9,1.95,.70,1.0,.12,.35),
        doubleArrayOf(4.2,1.05,1.20,2.0,.34,1.0),
        doubleArrayOf(3.6,1.80,.43,3.0,.67,.62),
        doubleArrayOf(3.0,1.85,.98,1.0,-.26,.05),
        doubleArrayOf(4.3,.95,1.20,2.0,-.32,.90),
        doubleArrayOf(3.5,1.60,.72,0.0,.42,.50),
        doubleArrayOf(3.1,1.80,.48,3.0,.10,.18))
    private val kaleidoPose=Array(2){DoubleArray(6)}
    private fun poseAt(keys:Array<DoubleArray>,phase:Double,out:DoubleArray) {
        val whole=floor(phase);val k=((whole.toInt()%keys.size)+keys.size)%keys.size
        val t=phase-whole;val t2=t*t;val t3=t2*t
        for(j in out.indices) {
            val a=keys[(k+keys.size-1)%keys.size][j];val b=keys[k][j]
            val c=keys[(k+1)%keys.size][j];val d=keys[(k+2)%keys.size][j]
            out[j]=.5*(2*b+(-a+c)*t+(2*a-5*b+4*c-d)*t2+(-a+3*b-3*c+d)*t3)
        }
    }
    private fun motifRadius(kind:Int,q:Double):Double=when(kind) {
        0 -> 1.0/(abs(cos(q))+abs(sin(q)))
        1 -> .76+.24*abs(cos(q))
        2 -> .5/cos(((q+PI/3)%(2*PI/3)+2*PI/3)%(2*PI/3)-PI/3)
        else -> .78/max(abs(cos(q)),abs(sin(q)))
    }
    private val entityScale=DoubleArray(5)

    private var g = destination
    private lateinit var e: VjEngine
    private var visualStyle = 0.0
    // Scene entity indices are the actual parts listed in the specification.
    // One path can never be only half-hidden by an unrelated layer/id modulus.
    internal fun entityFor(scene: Int, layer: Int, id: Int): Int = when(scene) {
        0 -> if(layer==0) 0 else if(layer==2) 1 else 2
        1 -> if(layer==0) 0 else if(layer==2) 2 else if(id==0) 1 else 3
        2 -> if(layer==1) 1 else if(layer==2) {if(id==0 || id>=9) 1 else 2} else 0
        3,4,9 -> if(scene==9 && layer==2 && id>=6) 0 else layer
        5 -> if(layer==2) 1 else 0
        6 -> if(layer==0) 3 else if(layer==2) 4 else id%3
        7 -> layer
        8 -> if(layer==0) {if(id<12) 3 else 0}
             else if(layer==2) 4 else if(id==0) 1 else 2
        else -> 0
    }
    private fun path(layer: Int, id: Int, brightness: Double = 1.0, width: Double = 0.28): Int {
        val p = layer * PATHS + id
        val entity=entityFor(activePattern,layer,id)
        val voice=e.elements[entity]
        g.entity[p]=entity
        g.alpha[p]=brightness*voice.presence*(.87+voice.hit*.24)
        if(activePattern==0 && entity==e.shuriken.flashEntity)
            g.alpha[p]*=1.0-e.shuriken.flashAmount*(if(entity==0) .62*(1.0-e.shuriken.melodicWeight) else .90)
        if(activePattern!=0)g.alpha[p]*=voice.light
        g.width[p]=width+min(.025,voice.hit*.025)
        return p * POINTS
    }
    private var activePattern=0
    private fun transformEntities(scene:Int) {
        val scales=entityScale
        for(i in scales.indices) scales[i]=e.elements[i].scale
        if(scene==2)scales[1]=scales[0] // Texture follows the shell, with its own musical articulation.
        // Teaser-inspired anticipation: retain the core, load the surrounding
        // structure inward; release an outer halo at a confirmed section arrival.
        if(scene!=0 && scene!=7) for(i in 0 until e.activeElementCount) {
            val outer=i>0
            scales[i]*=1.0-e.buildWeight*(if(outer) .12 else .035)*(1-.55*e.melodicWeight)+
                e.releaseBloom*(if(outer) .10 else .025)
        }
        if(scene==0) {
            scales[0]*=e.shuriken.bodyZoom
            scales[2]*=e.shuriken.bodyZoom
            if(e.shuriken.cross<.15) scales[0]=min(scales[0],(10.7-hypot(e.shuriken.offsetX[0],e.shuriken.offsetY[0]))/9.1)
            if(e.shuriken.cross<.15) scales[0]=min(scales[0],(11.2*scales[1]+.15)/9.1)
            scales[2]=min(scales[2],(1.80*scales[0]-.18)/1.17).coerceAtLeast(.45)
        }
        if(scene==3 || scene==9) {
            // Bound only the requested row. Crossings must not drag its neighbours.
            scales[0]=scales[0].coerceIn(.68,1.20)
            scales[1]=scales[1].coerceIn(.76,if(scene==3) 1.16 else 1.05)
            scales[2]=scales[2].coerceIn(.81,1.02)
        }
        for(p in g.alpha.indices) {
            if(g.alpha[p]<.005) continue
            val entity=g.entity[p]
            if(entity<0 || scene==7) continue // A single rigid crystal never zooms.
            val layer=p/PATHS
            val reference=when(scene) {
                2 -> if(entity==1) 0.0 else if(entity==0) 0.0 else e.layers[layer].phase
                4 -> if(layer==1) e.layers[1].phase*.72 else if(layer==0) e.layers[0].phase*.88 else e.layers[2].phase
                5 -> if(layer==2) e.layers[2].orbitPhase else e.layers[layer].phase
                else -> e.layers[layer].phase
            }
            val spin=when {
                scene==2 && entity==1 -> e.elements[0].phase
                scene==5 && entity==0 -> 0.0 // All body rotation already happens in 3D.
                scene==1 && entity==3 -> .09*sin(e.elements[entity].phase)
                scene==6 && layer==1 -> .26*sin(e.elements[entity].phase)
                else -> e.elements[entity].phase-reference
            }
            val curl=if(scene==0 && (entity==0 || entity==2))
                e.elements[entity].direction*e.shuriken.spiralAmount*(if(entity==0) .85 else .35) else 0.0
            val c=cos(spin+curl);val s=sin(spin+curl);val scale=scales[entity]
            val mirror=if(scene==0) when(entity) {
                0 -> e.shuriken.mainMirror
                2 -> e.shuriken.centerMirror
                else -> 1.0
            } else 1.0
            val base=p*POINTS
            // Area-preserving elastic articulation. Split halves and the rigid
            // crystal retain their geometric contracts; iris pairs share a master.
            val elastic=if(scene==0 || scene==2 || scene==7) 0.0 else
                e.elements[if(scene==6 && layer==1) 0 else entity].elasticAccent.value.coerceIn(-.18,.18)
            val stretch=1.0+elastic
            // Reactor nodes keep their size hierarchy while their centres travel.
            val nodeCount=when(layer){0->4;1->8;else->12}
            val node=scene==3 && p%PATHS<nodeCount
            var cx=0.0;var cy=0.0
            if(node) {
                for(k in base until base+POINTS-1){cx+=g.x[k];cy+=g.y[k]}
                cx/=(POINTS-1);cy/=(POINTS-1)
            }
            for(k in base until base+POINTS) {
                val x=if(node) g.x[k]+cx*(scale*stretch-1.0) else g.x[k]*scale*stretch
                val y=if(node) g.y[k]+cy*(scale/stretch-1.0) else g.y[k]*scale/stretch
                g.x[k]=(x*c-y*s)*mirror;g.y[k]=x*s+y*c
                if(scene==0 && entity<3) {
                    g.x[k]+=e.shuriken.offsetX[entity];g.y[k]+=e.shuriken.offsetY[entity]
                }
            }
        }
        if(scene==1) {
            // Connect the actual transformed core and shield vertices.
            // The fourth voice bends/brightens the links without tearing endpoints away.
            for(n in 0..5) {
                val core=n*2;val shield=PATHS*POINTS+n*2
                val base=(PATHS+n+1)*POINTS
                val x0=g.x[core];val y0=g.y[core];val x1=g.x[shield];val y1=g.y[shield]
                val dx=x1-x0;val dy=y1-y0;val length=max(.1,hypot(dx,dy))
                val bend=e.elements[3].contour*.40+e.elements[3].hit*.30
                for(i in 0 until POINTS) {
                    val t=i.toDouble()/(POINTS-1);val bow=sin(PI*t)*bend
                    g.x[base+i]=mix(x0,x1,t)-dy/length*bow
                    g.y[base+i]=mix(y0,y1,t)+dx/length*bow
                }
            }
        }
    }
    private fun point(base: Int, i: Int, x: Double, y: Double) { g.x[base+i] = x; g.y[base+i] = y }
    private fun polar(base: Int, i: Int, r: Double, a: Double) = point(base, i, r * cos(a), r * sin(a))
    private fun arc(layer: Int, id: Int, r: Double, start: Double, sweep: Double,
                    brightness: Double = 0.85, width: Double = 0.28, cx: Double = 0.0, cy: Double = 0.0) {
        val base = path(layer, id, brightness, width)
        for (i in 0 until POINTS) {
            val a = start + sweep * i / (POINTS - 1)
            point(base, i, cx + r*cos(a), cy + r*sin(a))
        }
    }
    private fun line(layer: Int, id: Int, x1: Double, y1: Double, x2: Double, y2: Double,
                     brightness: Double = 0.85, width: Double = 0.25) {
        val base = path(layer,id,brightness,width)
        for (i in 0 until POINTS) { val t = i.toDouble()/(POINTS-1); point(base,i,mix(x1,x2,t),mix(y1,y2,t)) }
    }
    private fun ringNodes(layer: Int, radius: Double, count: Int, size: Double, phase: Double, brightness: Double = 0.8) {
        for (n in 0 until count) {
            val a = phase + 2*PI*n/count
            arc(layer,n,size,0.0,2*PI,brightness,0.22,radius*cos(a),radius*sin(a))
        }
    }
    private fun polygon(layer: Int, id: Int, sides: Int, radius: Double, phase: Double, brightness: Double = 0.85, roundness: Double = 0.0) {
        val base = path(layer,id,brightness)
        for (i in 0 until POINTS) {
            val u = i.toDouble()/(POINTS-1)*sides
            val k = floor(u); val t = u-k
            val a = phase + k*2*PI/sides; val b = a+2*PI/sides
            point(base,i,radius*mix(mix(cos(a),cos(b),t),cos(a+t*2*PI/sides),roundness),
                radius*mix(mix(sin(a),sin(b),t),sin(a+t*2*PI/sides),roundness))
        }
    }
    // Blade thickness belongs to the geometry. Keeping the stroke narrow makes
    // even a fully opened blade read as an outlined shape on a 25x25 matrix.
    internal fun bladeWidthEnvelope(development: Double): Double = .18+2.65*unit(development)
    private fun crystal(layer:Int,id:Int,cx:Double,cy:Double,length:Double,width:Double,
                        angle:Double,bevel:Double,brightness:Double) {
        val base=path(layer,id,brightness,.23);val c=cos(angle);val s=sin(angle)
        fun xx(v:Int):Double=when(v%6){0->length;1,5->length*bevel;2,4->-length*bevel;else->-length}
        fun yy(v:Int):Double=when(v%6){1,2->width;4,5->-width;else->0.0}
        for(i in 0 until POINTS){val u=i.toDouble()/(POINTS-1)*6;val k=min(5,floor(u).toInt());val f=u-k
            val x=mix(xx(k),xx(k+1),f);val y=mix(yy(k),yy(k+1),f)
            point(base,i,cx+x*c-y*s,cy+x*s+y*c)
        }
    }
    private fun lotusPetal(layer:Int,id:Int,root:Double,tip:Double,spread:Double,angle:Double,light:Double) {
        val base=path(layer,id,light,.19)
        val c=cos(angle); val s=sin(angle)
        for(i in 0 until POINTS){
            val u=i.toDouble()/(POINTS-1)
            val side=if(u<.5) 1.0 else -1.0
            val q=if(u<.5) u*2 else (1-u)*2
            val r=mix(root,tip,q)
            val voice=e.elements[layer]
            val bow=spread*voice.melodicFlow*(voice.registerMotion*.85+voice.contour*.50)*sin(PI*q)
            val w=side*spread*sin(PI*q).coerceAtLeast(0.0).pow(.72)+bow
            point(base,i,r*c-w*s,r*s+w*c)
        }
    }
    // Original Bio-Crystal's implicit fold, bound to the reversible music timeline.
    // The code's contour is kept; beat-driven progress replaces its wall clock.
    private fun bioCrystal(x:Double,y:Double):Double {
        val spin=e.elements[0].phase*.70
        val rx=x*cos(spin)-y*sin(spin);val ry=x*sin(spin)+y*cos(spin)
        val r=hypot(x,y)
        val t=e.elements[0].shapePhase
        val z=sin(r*.45+t*1.35)*mix(1.45,2.8,visualStyle)
        val fold=abs(rx*ry)/(z*z+1)
        // Retain the old fold as a quiet secondary texture. The connected facets
        // carry the main silhouette all the way to the extensions.
        val envelope=1-ease((r-7.0)/3.5)
        return exp(-abs(fold-(.55+sin(t*2.2)*.08))*4.0)*.19*envelope
    }
    fun render(engine: VjEngine): IntArray {
        e = engine
        visualStyle=ease(e.tension)*(1-.65*e.melodicWeight)*(1-.75*e.calmWeight)
        if(seenTransition!=e.transitionSerial) {
            if(haveDisplayed) displayed.copyInto(source) else build(e.previousPattern,source)
            seenTransition=e.transitionSerial
        }
        build(e.pattern,destination)
        val t = ease(e.morph)
        for(p in 0 until 3*PATHS) {
            val progress=when(e.transitionStyle) {
                2 -> ease(unit((e.morph-.12)/.76))
                3 -> ease(unit((e.morph-(p/PATHS)*.10)/.80))
                else -> t
            }
            val fold=if(e.transitionStyle==2) 1.0-.65*sin(PI*progress).pow(2) else 1.0
            val arc=if(e.transitionStyle==1) .22*sin(PI*progress) else 0.0
            displayed.alpha[p]=mix(source.alpha[p],destination.alpha[p],progress)
            displayed.width[p]=mix(source.width[p],destination.width[p],progress)
            for(i in 0 until POINTS) {
                val k=p*POINTS+i
                val x=mix(source.x[k],destination.x[k],progress)*fold
                val y=mix(source.y[k],destination.y[k],progress)*fold
                displayed.x[k]=x*cos(arc)-y*sin(arc)
                displayed.y[k]=x*sin(arc)+y*cos(arc)
            }
        }
        haveDisplayed=true
        pixels.fill(0.0)
        for (p in 0 until 3*PATHS) {
            val a = displayed.alpha[p]
            if (a < 0.005) continue
            val w = displayed.width[p]
            val base = p*POINTS
            for (i in 0 until POINTS-1) {
                val k = base+i
                val x1 = displayed.x[k]
                val y1 = displayed.y[k]
                val x2 = displayed.x[k+1]
                val y2 = displayed.y[k+1]
                val bridge=p in PATHS+8 until PATHS+16 &&
                    (e.pattern==3 || (e.morph<1.0 && e.previousPattern==3))
                val localWidth=if(bridge) w*(.58+.42*abs(cos(PI*(i+.5)/(POINTS-1)))) else w
                val entity=destination.entity[p]
                val voice=if(entity>=0)e.elements[entity] else null
                // A moving specular crest on a sustained tone gives depth
                // without turning every sustained note into repeated flashes.
                val legato=if(e.pattern==0 || e.pattern==2) 0.0 else (voice?.melodicFlow ?: 0.0)*e.melodicWeight
                val angle=atan2(y1+y2,x1+x2)
                val crest=.5+.5*cos(angle-(voice?.phase ?: 0.0)-(voice?.shapePhase ?: 0.0)*.65)
                val shading=1.0-legato*.38*(1-crest*crest)
                val edge=voice?.attackEdge ?: 0.0
                rasterLine(x1+12+e.cameraX,y1+12+e.cameraY,
                    x2+12+e.cameraX,y2+12+e.cameraY,localWidth+edge*.055,a*shading)
            }
        }
        val bioWeight=if(e.pattern==7) t else if(e.morph<1.0 && e.previousPattern==7) 1.0-t else 0.0
        if(bioWeight>0.005) for(y in 0..24) for(x in 0..24){
            val k=y*25+x
            pixels[k]=max(pixels[k],bioCrystal(x-12.0,y-12.0)*bioWeight)
        }
        val sceneGesture=e.pattern==0
        renderImpactWave()
        val glitchPower=if(sceneGesture) e.shuriken.glitchAmount else e.glitch
        val glitchMode=if(sceneGesture) e.shuriken.glitchMode else e.glitchStyle
        val active=glitchPower>0.10
        val shakeX=sin(e.time*83.0)*e.tremor*1.5
        val shakeY=cos(e.time*107.0)*e.tremor*1.2
        val tearTime=floor(e.time*25)
        val echo=e.layers[2].hit*e.tension*0.14
        val ink=e.tension*(1-e.melodicWeight)*(1-e.calmWeight)*(.4+.6*e.accent)
        for (y in 0..24) {
            val band = (y/3+glitchMode)%3==0
            val tear=if(active && band) (sin(y*1.7+tearTime)*glitchPower*(if(sceneGesture) 8.0 else 4.5)).roundToInt() else 0
            for (x in 0..24) {
            val index = y*25+x
            if ((x-12)*(x-12)+(y-12)*(y-12) > 156.25) { frame[index]=0; continue }
            // Короткий детерминированный сдвиг полос только после impact.
            var sampleX=(x-12)/e.zoom+12+shakeX+tear
            var sampleY=(y-12)/e.zoom+12+shakeY
            if(active && glitchMode==1 && band) { sampleX=floor(sampleX/2)*2; sampleY=floor(sampleY/2)*2 }
            if(sceneGesture && active && glitchMode==1 && band) sampleY=12+(sampleY-12)*.45
            var value=samplePixels(sampleX,sampleY)
            if(sceneGesture && active && glitchMode==3 && band) {
                value=max(value,glitchPower*.83)
            }
            if(sceneGesture && active && glitchMode==4) {
                value=if(band) value*.12 else max(value,samplePixels(sampleX-3*glitchPower,sampleY)*.8)
            }
            if(sceneGesture && active && glitchMode==2 && band) {
                value=max(value,samplePixels(sampleX+4*glitchPower,sampleY)*.80)
            }
            if(active && glitchMode==2) value=max(value,lastPixels[index]*glitchPower*0.65)
            // Short local echo on accents; no permanent glowing fog.
            value=max(value,lastPixels[index]*echo)
            // Hard sections separate bright edges from dark space; legato
            // retains grey depth instead of turning into the same strobe.
            frame[index] = (unit(value*e.audioGain*e.brightness*(1+.40*ink)).pow(1.15+.60*ink)*255).roundToInt()
        }
        }
        pixels.copyInto(lastPixels)
        return frame
    }
    private fun samplePixels(x: Double, y: Double): Double {
        if(x<0 || x>24 || y<0 || y>24) return 0.0
        val ix=floor(x).toInt(); val iy=floor(y).toInt()
        val jx=min(24,ix+1); val jy=min(24,iy+1)
        return mix(mix(pixels[iy*25+ix],pixels[iy*25+jx],x-ix),
            mix(pixels[jy*25+ix],pixels[jy*25+jx],x-ix),y-iy)
    }
    private fun renderImpactWave() {
        if((e.pattern!=1 && e.pattern!=3 && e.pattern!=4 && e.pattern!=8 && e.pattern!=9) ||
            e.shockAge>=.58 || e.shockStrength<.01 || e.forceMode==1)return
        val u=unit(e.shockAge/.58)
        val radius=2.0+11.0*ease(u)
        val alpha=e.shockStrength*.85*sin(PI*u)*(1-u)*(1-.60*e.calmWeight)
        val hard=e.shockHardness
        val sides=if(e.pattern==1) 6 else if(e.pattern==8) 8 else 48
        val phase=e.elements[0].phase
        val flatten=if(e.pattern==4 || e.pattern==9) 1.0-.22*e.melodicWeight else 1.0
        for(j in 0 until sides) {
            val a=phase+2*PI*j/sides;val b=phase+2*PI*(j+1)/sides
            if(e.pattern==8 && hard>.4) {
                rasterLine(12+(radius-.65)*cos(a),12+(radius-.65)*sin(a),
                    12+(radius+.65)*cos(a),12+(radius+.65)*sin(a),.20,alpha)
            } else rasterLine(12+radius*cos(a),12+radius*sin(a)*flatten,
                12+radius*cos(b),12+radius*sin(b)*flatten,.16+hard*.10,alpha)
        }
    }
    private fun rasterLine(x1: Double,y1: Double,x2: Double,y2: Double,width: Double,alpha: Double) {
        val loX = floor(min(x1,x2)-width-0.7).toInt().coerceIn(0,24)
        val hiX = ceil(max(x1,x2)+width+0.7).toInt().coerceIn(0,24)
        val loY = floor(min(y1,y2)-width-0.7).toInt().coerceIn(0,24)
        val hiY = ceil(max(y1,y2)+width+0.7).toInt().coerceIn(0,24)
        val dx=x2-x1; val dy=y2-y1; val length=dx*dx+dy*dy
        val reach=width+0.65; val reachSquare=reach*reach
        for (y in loY..hiY) for (x in loX..hiX) {
            val t=if(length<1e-10) 0.0 else unit(((x-x1)*dx+(y-y1)*dy)/length)
            val offsetX=x-x1-t*dx; val offsetY=y-y1-t*dy
            if(offsetX*offsetX+offsetY*offsetY>=reachSquare) continue
            val distance=hypot(offsetX,offsetY)
            val a=unit((width+0.65-distance)/0.85)*alpha
            val k=y*25+x
            pixels[k]=max(pixels[k],a)
        }
    }
    private fun build(pattern: Int, geometry: Geometry) {
        g=geometry; g.clear(); activePattern=pattern
        val b=e.layers[0]; val m=e.layers[1]; val h=e.layers[2]
        val morph=e.melodyPhase + m.shapePhase * 0.50
        // The live music in the reference recording lives mostly at 0.25..0.60;
        // the previous 0.40..0.85 mapping hid almost the whole structural range.
        val style=visualStyle
        val breath=sin(morph*1.3)
        // Пружина имеет единицы «пиксель Matrix». Огибающая добавляет движение между ударами.
        val bp = (b.displacement + b.drive * 0.5).coerceIn(-0.6, 2.1)
        val mp = (m.displacement + m.drive * 0.55).coerceIn(-0.6, 2.1)
        val hp = (h.displacement + h.drive * 0.3).coerceIn(-0.5, 1.6)
        val rb=1.0+bp/8.0
        val rm=1.0+mp/8.0
        when(pattern) {
            0 -> { // Shuriken: четыре кривых лезвия распрямляются в крест.
                val development=e.shuriken.opening
                val curve=.20+.80*development
                val cross=e.shuriken.cross
                for(blade in 0..3) {
                    val base=path(0,blade,.94,.18)
                    val angle=b.phase+blade*PI/2
                    val scale=1.0
                    // Two different cubic edges: a swept cutting edge and a
                    // concave return meet at a sharp hooked tip, never a petal.
                    val tipX=8.4; val tipY=.5+curve*2.1
                    for(i in 0 until POINTS) {
                        val outer=i<=6
                        val t=if(outer) i/6.0 else (12-i)/6.0
                        val u=1-t
                        val cx1=if(outer) 3.8 else 3.1
                        val cy1=if(outer) -1.1-curve*2.0 else .15
                        val cx2=if(outer) 8.3 else 5.7
                        val cy2=if(outer) -1.0-curve*2.6 else -.05+curve*.40
                        val x=(u*u*u*1.4+3*u*u*t*cx1+3*u*t*t*cx2+t*t*t*tipX)*scale
                        val y=(u*u*u*(-.8)+3*u*u*t*cy1+3*u*t*t*cy2+t*t*t*tipY)*scale
                        // A flared outline exits the circular display; both
                        // sides stay thin even in the widest cross pose.
                        val ct=if(i<=6) i/6.0 else if(i==7) 1.0 else (12-i)/5.0
                        val crossX=1.45+12.5*ct
                        val crossY=(if(i<=6) -1 else 1)*(.38+2.25*ct)
                        val xx=mix(x,crossX,cross);val yy=mix(y,crossY,cross)
                        point(base,i,xx*cos(angle)-yy*sin(angle),xx*sin(angle)+yy*cos(angle))
                    }
                    if(e.bladeTeeth>.09 && cross<.75) for(tooth in 0..1){
                        val a=b.phase+blade*PI/2+curve*.85+(tooth-.5)*.23
                        val r=5.2+tooth*1.9
                        line(0,8+blade*2+tooth,r*cos(a),r*sin(a),
                            (r+e.bladeTeeth*1.85)*cos(a+.22),
                            (r+e.bladeTeeth*1.85)*sin(a+.22),e.bladeTeeth*.90*(1-cross),.19)
                    }
                }
                arc(1,0,1.05+e.elements[2].hit*.12,0.0,2*PI,.70,.18)
                // An asymmetric mark makes a mirrored circular center readable.
                val central=e.layers[1].phase
                val mark=path(1,1,.90,.17)
                for(i in 0 until POINTS) {
                    val t=i.toDouble()/(POINTS-1)*2
                    val x=if(t<=1) mix(-.55,.55,t) else mix(.55,-.25,t-1)
                    val y=if(t<=1) mix(-.48,-.05,t) else mix(-.05,.48,t-1)
                    point(mark,i,x*cos(central)-y*sin(central),x*sin(central)+y*cos(central))
                }
                ringNodes(2,11.2,12,.28+e.elements[1].hit*.16,h.phase,.65)
                // Three brighter satellites make the dotted orbit's angle readable.
                // They belong to the same outer component, including its short dips.
                for(n in 0..2) {
                    val a=h.phase+n*2*PI/3+.17
                    arc(2,12+n,.53+e.elements[1].hit*.12,0.0,2*PI,.88,.18,
                        10.9*cos(a),10.9*sin(a))
                }
            }
            1 -> { // Hexa Shield: ядро, щит, отдельные угловые скобы.
                polygon(0,0,6,2.5+bp*0.8,b.phase,0.9)
                polygon(1,0,6,(6.8+breath*0.25)*rm,m.phase,0.88)
                // Шесть рёбер связывают сердцевину с углами щита.
                for(n in 0..5) {
                    val a=m.phase+n*PI/3
                    val r=(6.8+breath*0.25)*rm
                    line(1,n+1,3.15*cos(a),3.15*sin(a),r*cos(a),r*sin(a),.58,.18)
                }
                for(n in 0..5) {
                    val a=h.phase*.75+n*PI/3
                    val r=10.0+hp*.45
                    val x0=r*cos(a);val y0=r*sin(a)
                    val x1=r*cos(a+PI/3);val y1=r*sin(a+PI/3)
                    val gap=.045+style*.12+h.hit*.08
                    line(2,n,mix(x0,x1,gap),mix(y0,y1,gap),
                        mix(x0,x1,1-gap),mix(y0,y1,1-gap),.78,.20)
                    arc(2,n+6,.22,0.0,2*PI,.40,.17,x0,y0)
                }
                for(n in 0..2) { val a=b.phase+n*2*PI/3
                    line(0,n+1,1*cos(a),1*sin(a),(2.4+bp*0.8)*cos(a),(2.4+bp*0.8)*sin(a),0.6) }
            }
            2 -> { // Whole -> slash -> séparation -> recovery. Направление фиксируется в момент удара.
                val age=e.slashAge/max(.25,e.slashDuration)
                val cutSeparation=when { age<0.04 -> 0.0; age<0.17 -> ease((age-0.04)/0.13)*2.8
                    age<0.30 -> 2.8; age<0.85 -> 2.8*(1-ease((age-0.30)/0.55)); else -> 0.0 }
                val separation=max(cutSeparation,e.slowSplit*2.25)*(.22+.78*e.splitStrength)*(1-.65*e.calmWeight)
                val a=e.slashAngle
                val nx=-sin(a); val ny=cos(a)
                val shell = (7.0+bp*0.65).coerceAtMost(8.2)
                for(half in 0..1) {
                    val sign=if(half==0) 1.0 else -1.0
                    val ox=(if(e.splitSlide) cos(a) else nx)*sign*separation
                    val oy=(if(e.splitSlide) sin(a) else ny)*sign*separation
                    val halfAngle=a // Both cut edges share exactly one orientation.
                    val shellPath=path(0,half,.92,.30)
                    for(i in 0 until POINTS) {
                        val q=i.toDouble()/(POINTS-1)*PI
                        val angle=halfAngle+half*PI+q
                        val r=shell*mix(1.0,1.0/(abs(cos(q))+abs(sin(q))),style*.8)
                        point(shellPath,i,ox+r*cos(angle),oy+r*sin(angle))
                    }
                    // Две внутренние дуги сохраняют читаемую текстуру цельного объекта.
                    // Текстура движется от mid, но её концы остаются внутри своей половины.
                    val base=path(1,half,0.65,0.22)
                    for(i in 0 until POINTS) {
                        val u=i.toDouble()/(POINTS-1)
                        val angle=halfAngle+half*PI+u*PI
                        val radius=4.8+mp*0.65+(0.25+style*.85)*sin(u*PI)*sin(4*angle+e.elements[1].shapePhase*1.8)
                        point(base,i,ox+radius*cos(angle),oy+radius*sin(angle))
                    }
                    arc(1,half+2,2.6+mp*0.45,halfAngle+half*PI,PI,0.75,0.25,ox,oy)
                    if(separation>0.03) line(0,half+2,ox-shell*cos(halfAngle),oy-shell*sin(halfAngle),
                        ox+shell*cos(halfAngle),oy+shell*sin(halfAngle),0.6)
                }
                if(age<0.16) line(2,0,-10*cos(a),-10*sin(a),10*cos(a),10*sin(a),(1-age/0.16),0.4)
                if(age<.52) {
                    val fade=1-ease(age/.52)
                    for(j in 0..1) {
                        val base=path(2,9+j,fade*(if(j==0) 1.0 else .60),.38)
                        for(i in 0 until POINTS){
                            val u=i.toDouble()/(POINTS-1)
                            val xx=(u-.5)*21
                            val yy=sin(PI*u)*(1.2+j*.7)*(1-age/.52)
                            point(base,i,xx*cos(a)-yy*sin(a),xx*sin(a)+yy*cos(a))
                        }
                    }
                }
                for(n in 0..3) {
                    val q=h.phase+n*PI/2
                    arc(2,n+1,11.0-hp*0.6,q,0.18+h.hit*0.12,0.5,0.2)
                }
            }
            3 -> { // Local radial physics owns each row; no second global pulse.
                val inner=3.15
                val middle=6.85
                val outer=10.85
                ringNodes(0,inner,4,.70+b.hit*.07,b.phase,.86)
                ringNodes(1,middle,8,1.02+style*.08+m.hit*.06,m.phase,.84)
                ringNodes(2,outer,12,.32+h.hit*.05,h.phase,.74)
                // Eight curved bridges; the line thickens near each node.
                for(n in 0..7) arc(1,n+8,middle,m.phase+n*PI/4,PI/4,
                    .39+style*.12,.20)
            }
            4 -> { // Independent fast/slow rows, sharp petals and a restrained halo.
                val innerVoice=e.elements[0];val outerVoice=e.elements[1]
                val opening=unit(.16+innerVoice.activity*.50+innerVoice.contour*.32+
                    innerVoice.registerMotion*.22+innerVoice.radial*.85+innerVoice.hit*.32)
                val back=m.phase*.72
                for(n in 0..7) {
                    val a=back+n*PI/4
                    val pulse=outerVoice.contour*.75+outerVoice.hit*.55
                    val flare=style*(outerVoice.registerMotion*.30+outerVoice.hit*.32)
                    lotusPetal(1,n,1.8,8.3+style*.50+pulse*.75+e.elements[1].hit*.50,
                        1.75+style*.16+flare,a+.06*flare,.78)
                }
                for(n in 0..5) {
                    val a=b.phase*.88+n*PI/3+PI/8
                    lotusPetal(0,n,.3,.55+5.7*opening,
                        (.35+1.2*opening),a,.88)
                }
                arc(0,7,.45+opening*.18,0.0,2*PI,.65,.16)
                for(n in 0..5) {
                    val a=h.phase+n*PI/3
                    arc(2,n,10.8-hp*.30,a,.16+h.hit*.18,.42,.15)
                }
            }
            5 -> { // Orthographic 3D, 12 edges; independent audio axes, depth contrast.
                val yaw=b.phase*.72+0.5+b.hit*0.12
                val pitch=m.phase*.47+0.65+m.drive*.16
                val roll=h.phase*.28+0.2+h.hit*.08
                for(n in 0..5) {
                    val vertex=vertices[n]
                    val vyLocal=vertex[1]
                    val v=vertex
                    val x=v[0]*cos(yaw)+v[2]*sin(yaw); val z=-v[0]*sin(yaw)+v[2]*cos(yaw)
                    val y=vyLocal*cos(pitch)-z*sin(pitch); val zz=vyLocal*sin(pitch)+z*cos(pitch)
                    val scale=7.1*(1.0+bp*.08) // Orthographic: opposite vertices remain symmetric.
                    vx[n]=(x*cos(roll)-y*sin(roll))*scale; vy[n]=(x*sin(roll)+y*cos(roll))*scale; vz[n]=zz
                }
                for(n in 0..11) {
                    val i=edges[2*n]; val j=edges[2*n+1]
                    line(0,n,vx[i],vy[i],vx[j],vy[j],0.65-(vz[i]+vz[j])*0.18,0.17)
                }
                for(n in 0..7) {
                    val a=h.orbitPhase+n*PI/4
                    val orbit=10.5-hp*.22
                    val tilt=.65+.22*sin(m.shapePhase*.45)
                    val x=orbit*cos(a); val y=orbit*sin(a)*tilt
                    val z=sin(a)*sqrt(1-tilt*tilt)
                    arc(2,n,.23+.12*(1-z),0.0,2*PI,.65-z*.20,.16,x,y)
                }
                arc(1,0,1.0+mp*0.5,0.0,2*PI,0.4,0.18)
                for(axis in 0..2) {
                    val n=when(axis){0->0;1->2;else->3}
                    line(1,13+axis,vx[n]*.20,vy[n]*.20,
                        -vx[n]*.20,-vy[n]*.20,.24,.13)
                }
            }
            6 -> { // Mechanical Iris: шесть отдельных створок, центральное отверстие и крепёж.
                val aperture=(4.5-style*2.4+breath*.6-bp*.9+m.drive*.65-e.elements[0].hit*.45).coerceIn(.8,5.0)
                polygon(0,0,6,aperture,b.phase,0.92)
                for(n in 0..5) {
                    val a=m.phase+n*PI/3
                    val base=path(1,n,0.8,0.28)
                    val xs=crystalX; val ys=crystalY
                    val inner=aperture+1.0
                    xs[0]=inner*cos(a); ys[0]=inner*sin(a)
                    val edge=8.2+mp*0.8
                    val twist=0.05+style*.28+m.drive*0.16
                    xs[1]=edge*cos(a+twist); ys[1]=edge*sin(a+twist)
                    xs[2]=(edge-0.5)*cos(a+0.62); ys[2]=(edge-0.5)*sin(a+0.62)
                    xs[3]=inner*cos(a+0.30); ys[3]=inner*sin(a+0.30)
                    xs[4]=xs[0]; ys[4]=ys[0]
                    for(i in 0 until POINTS) {
                        val u=i.toDouble()/(POINTS-1)*4
                        val k=min(3,floor(u).toInt()); val f=u-k
                        point(base,i,mix(xs[k],xs[k+1],f),mix(ys[k],ys[k+1],f))
                    }
                }
                ringNodes(2,11.3-hp*0.65,6,0.40,h.phase,0.62)
            }
            7 -> { // Four folding panels: cross, frame, pinwheel, petals, separated facets and iris.
                val voice=e.elements[0];val ribs=e.elements[1];val tips=e.elements[2]
                poseAt(crystalPoses,voice.shapePhase,pose)
                val force=(1-.80*e.calmWeight)*(1-.55*e.melodicWeight)
                val turn=voice.phase
                val outer=(pose[0]+voice.contour*.45*force).coerceIn(7.0,11.1)
                val inner=pose[2].coerceIn(.7,5.5)
                val width=pose[1].coerceIn(.65,4.5)
                val detach=pose[5].coerceIn(0.0,1.5)*(.35+.65*force)
                val rounded=unit(pose[6]);val skew=pose[7]*(.45+.55*force)
                for(n in 0..3) {
                    val axis=turn-PI/2+n*PI/2+pose[3]
                    val c=cos(axis);val sn=sin(axis)
                    val middle=(inner+outer)*.5
                    val xs=crystalX;val ys=crystalY
                    xs[0]=inner;ys[0]=0.0
                    xs[1]=middle+skew;ys[1]=width
                    xs[2]=outer;ys[2]=0.0
                    xs[3]=middle-skew;ys[3]=-width
                    xs[4]=xs[0];ys[4]=0.0
                    val base=path(0,n,.90,.18)
                    for(i in 0 until POINTS) {
                        val u=i.toDouble()/(POINTS-1);val edge=min(3,(u*4).toInt());val f=u*4-edge
                        val q=PI-u*2*PI
                        val x=mix(mix(xs[edge],xs[edge+1],f),middle+(outer-inner)*.5*cos(q),rounded)+detach
                        val y=mix(mix(ys[edge],ys[edge+1],f),width*sin(q),rounded)
                        val limit=min(1.0,11.5/max(1e-6,hypot(x,y)))
                        point(base,i,(x*c-y*sn)*limit,(x*sn+y*c)*limit)
                    }
                    val crease=middle+(ribs.contour*.65+ribs.registerMotion*.4)*(.35+.65*force)
                    val ix=(inner+detach)*c;val iy=(inner+detach)*sn
                    val tx=(outer+detach).coerceAtMost(11.5)*c;val ty=(outer+detach).coerceAtMost(11.5)*sn
                    line(1,n,ix,iy,tx,ty,.28+ribs.hit*.20,.13)
                    for(side in 0..1) {
                        val sign=if(side==0)1.0 else -1.0
                        val x=crease+detach;val y=width*.72*sign
                        line(1,4+n*2+side,ix,iy,x*c-y*sn,x*sn+y*c,.25+ribs.hit*.12,.12)
                    }
                    val reach=(outer+detach+.35+unit(pose[4])*.65+tips.hit*.3*force).coerceAtMost(11.8)
                    arc(2,n,.25+tips.activity*.32,axis-PI/2,PI,.55,.15,reach*c,reach*sn)
                    arc(2,n+4,11.4,axis+PI/4,.10+tips.hit*.20,.28,.13)
                }
            }
            8 -> { // Saw: 12 настоящих зубьев, отдельные канавки и внешний крепёж.
                val toothVoice=e.elements[3]
                val teeth=(.20+style*2.50+e.bladeTeeth*.45+toothVoice.hit*1.35+
                    max(0.0,toothVoice.contour)*.65).coerceIn(.20,3.65)
                for(n in 0..11) {
                    val base=path(0,n,0.92,0.28)
                    for(i in 0 until POINTS) {
                        val u=i.toDouble()/(POINTS-1)
                        val tooth=if(u<0.72) u/0.72 else (1-u)/0.28
                        val r=6.45+bp*0.25+teeth*tooth
                        polar(base,i,r,b.phase+(n+u)*2*PI/12)
                    }
                }
                arc(1,0,2.8+mp*0.65,0.0,2*PI,0.7,0.24)
                for(n in 0..5) arc(1,n+1,4.7+mp*0.65,m.phase+n*PI/3,0.45,0.6,0.22)
                for(n in 0..2) {
                    val a=m.phase+n*2*PI/3
                    line(1,n+7,(1.1+mp*0.2)*cos(a),(1.1+mp*0.2)*sin(a),
                        (2.3+mp*0.5)*cos(a),(2.3+mp*0.5)*sin(a),0.40,0.17)
                }
                ringNodes(2,11.4-hp*0.6,12,0.30+h.hit*0.12,h.phase,0.68)
            }
            9 -> { // Independent paired motifs: diamond, lens, triangle and tile.
                for(layer in 0..1) {
                    val voice=e.elements[layer];val kp=kaleidoPose[layer]
                    poseAt(kaleidoPoses,voice.shapePhase*.62+layer*2,kp)
                    val separation=unit(kp[5])*(.55+.45*style)
                    val kindPhase=kp[3].coerceIn(0.0,3.0)
                    val kind=floor(kindPhase).toInt();val blend=ease(kindPhase-kind)
                    val radius=if(layer==0)kp[0]+voice.contour*.38 else 8.2+(kp[0]-3.6)*.60+voice.contour*.40
                    val length=kp[1]*(if(layer==0)1.35 else 1.04)
                    val width=kp[2]*(if(layer==0)1.20 else .98)
                    val count=if(layer==0)6 else 12
                    for(n in 0 until count) {
                        val pair=n/2;val side=if(n%2==0)-1.0 else 1.0
                        val localSeparation=if(layer==0).30+.70*separation else separation
                        val a=e.layers[layer].phase+pair*(if(layer==0)2*PI/3 else PI/3)+
                            side*localSeparation*(if(layer==0)PI/6 else PI/12)
                        val tilt=a+kp[4]+side*separation*.32
                        val cx=radius*cos(a);val cy=radius*sin(a)
                        val base=path(layer,n,if(layer==0).92 else .86,.14)
                        for(i in 0 until POINTS) {
                            val q=i.toDouble()/(POINTS-1)*2*PI
                            val r=mix(motifRadius(kind,q),motifRadius(min(3,kind+1),q),blend)
                            val xx=length*r*cos(q);val yy=width*r*sin(q)
                            point(base,i,cx+xx*cos(tilt)-yy*sin(tilt),cy+xx*sin(tilt)+yy*cos(tilt))
                        }
                    }
                }
                for(n in 0..5)arc(2,n,11.4,h.phase+n*PI/3,.11+e.elements[2].activity*.12,.48,.16)
            }
        }
        // Scene-specific second-level construction; reserved path slots, no new per-frame buffers.
        val detail=0.10+0.42*style
        when(pattern) {
            0 -> for(n in 0..3) {
                val a=b.phase+n*PI/2
                val closed=1-unit((.5+.5*sin(morph)-.30)/.55)
                line(0,n+4,2.8*cos(a),2.8*sin(a),(6.3+bp*.6)*cos(a+.17),(6.3+bp*.6)*sin(a+.17),
                    detail*(.12+.88*closed),.17)
            }
            1 -> for(n in 0..5) { val a=m.phase+n*PI/3+PI/6
                line(1,n+7,4.8*cos(a),4.8*sin(a),
                    (6.2+mp*.25)*cos(a),(6.2+mp*.25)*sin(a),detail,.16) }
            2 -> for(n in 0..3) arc(2,n+5,9.1-hp*.3,h.phase+n*PI/2,.25+h.hit*.3,detail,.18)
            3 -> {
                for(n in 0..3) { val a=b.phase+n*PI/2
                    line(0,n+5,1.7*cos(a),1.7*sin(a),(2.5+bp*.65)*cos(a),(2.5+bp*.65)*sin(a),detail,.17) }
                for(n in 0..2) arc(2,n+12,10.15+h.drive*.15,h.phase+n*2*PI/3,.16,detail*.45,.14)
            }
            5 -> for(n in 0..11) {
                val i=edges[n*2];val j=edges[n*2+1]
                line(1,n+1,vx[i]*.40,vy[i]*.40,vx[j]*.40,vy[j]*.40,detail*.70,.12)
            }
            6 -> {
                // Mechanical cam ring + split outer housing + shutter ribs.
                val aperture=(4.5-style*2.4+breath*.6-bp*.9+m.drive*.65-e.elements[0].hit*.45).coerceIn(.8,5.0)
                for(n in 0..5) {
                    val a=m.phase+n*PI/3
                    line(1,n+6,(aperture+1.4)*cos(a+.16),(aperture+1.4)*sin(a+.16),
                        (7.6+mp*.55)*cos(a+.35),(7.6+mp*.55)*sin(a+.35),detail,.17)
                    arc(2,n+6,10.0-hp*.35,h.phase+n*PI/3+.13,.58,.42,.23)
                }
                arc(0,1,aperture*.55,b.phase,PI*.72,.60,.19)
                arc(0,2,aperture*.55,b.phase+PI,PI*.72,.60,.19)
            }
            8 -> for(n in 0..3) arc(0,n+12,6.05+bp*.35,b.phase+n*PI/2,PI/2,
                .56+detail*.25,.18)
            9 -> polygon(0,12,6,.70+e.elements[0].contour*.12,b.phase,.42,0.0)
        }
        // The same musical phase has different geometry, not just a different playback speed.
        // Smooth mode interpolation avoids a pop at the calm/heavy boundary.
        for(p in 0 until 3*PATHS) {
            val layer=p/PATHS
            if(layer==2) g.alpha[p]*=mix(.55,1.0,style)
        }
        transformEntities(pattern)
    }
}
