package com.example.glyphvisualizer

import kotlin.math.*

/** Независимое от Android ядро: аудио обрабатывается ровно один раз, физика — отдельно. */
class VjEngine {
    enum class Moment { AMBIENT, MELODIC, GROOVE, BUILDUP, DROP, BREAKDOWN, VOID }
    class Layer {
        var phase = 0.0
        var orbitPhase = 0.0
        var velocity = 0.0
        var displacement = 0.0
        var springVelocity = 0.0
        var hit = 0.0
        var presence = 1.0
        var salience = 1.0 / 3.0
        var direction = 1.0
        // displacement теперь измеряется в пикселях Matrix, а не в произвольных долях.
        var drive = 0.0
        var shapePhase = 0.0
        var shapeDirection = 1.0
        var burst = 0.0
        internal var shapeAccents = 0
        internal var shapeTravel = 0.0
        internal var historySaved = -10.0
        internal var dipStart = -100.0
        internal var dipDuration = .6
        internal var dipArmed = true
        internal var recoveredFor = 0.0
        var dipCount = 0; internal set
        internal var shapeVelocity = 0.0
        var onsetCount = 0
        var lastStrength = 0.0
        var threshold = 0.0
        var turnCount = 0; internal set
        var rewindCount = 0; internal set
        var rewinding = false; internal set
        internal var accentsSinceTurn = 0
        internal var lastTurn = -10.0
        internal var rewindStart = 0.0
        internal var rewindDuration = 0.0
        internal var rewindDistance = 0.0
        // Кольцевой буфер формы: перемотка не меняет независимое вращение.
        // Радиальные импульсы остаются живой реакцией на текущую музыку.
        internal val historyTime = DoubleArray(256)
        internal val historyShape = DoubleArray(256)
        internal var historyHead = 0
        internal var historyCount = 0
        internal var angularKick = 0.0
        internal var levelFast = 0.0
        internal var levelSlow = 0.0
        internal var absentFor = 0.0
        internal var everPresent = false
        internal var presenceReference = 0.0
    }
    /** One complete visible component, with its own rhythm, spin and contrast. */
    class Element {
        var phase=0.0; internal set
        var shapePhase=0.0; internal set
        var velocity=0.0; internal set
        // A finite sound-led turn settles into a pose; sustained melody has a
        // separate continuous carrier. Neither depends on a track name/clock.
        internal val phraseTurn=PhraseTurn()
        internal val elasticAccent=ElasticAccent()
        var attackEdge=0.0; internal set
        var melodicFlow=0.0; internal set
        var shapeVelocity=0.0; internal set
        var direction=1.0; internal set
        var shapeDirection=1.0; internal set
        var hit=0.0; internal set
        var scale=1.0; internal set
        var presence=1.0; internal set
        var turnCount=0; internal set
        var dipCount=0; internal set
        var onsetCount=0; internal set
        internal var fast=0.0; internal var slow=0.0; internal var peak=0.0
        internal var previousShare=0.0; internal var missingFor=0.0
        internal var recoveredFor=0.0; internal var dipUntil=-1.0
        internal var armed=true; internal var accents=0; internal var shapeAccents=0
        internal var shapeTravel=0.0
        var completedCycle=false; internal set
        internal val shapeTurn=PhraseTurn()
        internal var lastTurn=-10.0; internal var lastShapeTurn=-10.0
        internal var lastOnset=-10.0
        internal var fluxFloor=0.0;internal var previousFlux=0.0;internal var fluxArmed=true
        internal var angularKick=0.0;internal var flashUntil=-10.0
        // Local musical contour and a damped radial gesture. Units are scale fractions.
        var contour=0.0; internal set
        var activity=0.0; internal set
        var radial=0.0; internal set
        internal var radialVelocity=0.0
        internal var radialTarget=0.0
        internal var radialHold=-1.0
        var gestureSource=0; internal set // 0: none, 1: attack, 2: timbre
        internal var registerMotion=0.0
        var light=1.0; internal set
        var brake=1.0; internal set
        var blinkCount=0; internal set
        var pauseCount=0; internal set
        internal var pauseUntil=-10.0
        internal var lastPause=-10.0
        internal var pauseArmed=false
        internal var flashFloor=.12
        internal var rawLevel=0.0
        internal var blinkPendingAt=-10.0
        internal var blinkFromShare=0.0
        internal var eventSamples=0
        internal var eventShare=0.0
        internal var eventRegister=0.0
        internal var eventTexture=0.0
        internal var lastContraction=-10.0
        internal var turnReadyUntil=-10.0
        internal var hitRelease=.19
        internal val shapeTapeTime=DoubleArray(160)
        internal val shapeTapeValue=DoubleArray(160)
        internal var tapeHead=0; internal var tapeCount=0
        internal var tapeSaved=-10.0
        var rewinding=false; internal set
        var rewindCount=0; internal set
        internal var rewindStart=0.0; internal var rewindDuration=.5
        internal var rewindDistance=.8
        internal var shapeMin=0.0; internal var shapeMax=0.0
    }
    // Entity order follows SceneRenderer.entityFor(); channels are FFT subbands.
    private val sceneChannels=arrayOf(
        intArrayOf(1,5,3), intArrayOf(0,2,5,3), intArrayOf(1,2,5),
        intArrayOf(0,3,5), intArrayOf(2,3,5), intArrayOf(2,5),
        intArrayOf(0,2,4,1,5), intArrayOf(1,3,5),
        intArrayOf(0,2,3,1,5), intArrayOf(0,3,5))
    val elements=Array(5) { Element().apply { direction=if(it%2==0) 1.0 else -1.0 } }
    val shuriken=ShurikenMotion(elements)
    private val sceneVoice=BassCycleTracker()
    private var previousRegister=0.0
    private var lastElementFlash=-10.0
    private var lastReactorContraction=-10.0
    private var hiddenElement=-1
    private var lastElementDip=-10.0
    var contractionCount=0; private set
    val activeElementCount get()=sceneChannels[pattern].size
    val elementDipCount get()=elements.sumOf { it.dipCount }
    val elementTurnCount get()=elements.sumOf { it.turnCount }
    val elementOnsetCount get()=elements.sumOf { it.onsetCount }
    val layers = Array(3) { Layer().apply { direction = if (it == 1) -1.0 else 1.0 } }
    var moment = Moment.AMBIENT; private set
    var tension = 0.0; private set
    var motionEnergy = 0.0; private set
    var bodyTexture = 0.0; private set
    var accent = 0.0; private set
    private var targetMotion = 0.0
    private var modulation = 0.0
    private var cutMean = .42
    private var cutVariance = .04
    private var noveltyMean = .12
    private var noveltyVariance = .015
    var time = 0.0; private set
    var momentAge = 0.0; private set
    var sceneAge = 0.0; private set
    var pattern = 0; private set
    var previousPattern = 0; private set
    var morph = 1.0; private set
    var glitch = 0.0; private set
    var cameraX = 0.0; private set
    var cameraY = 0.0; private set
    var slashAge = 10.0; private set
    var slashDuration = 0.85; private set
    var slashAngle = 0.0; private set
    var slowSplit = 0.0; private set
    var splitStrength = .5; private set
    var splitSlide = false; private set
    var melodicWeight = 0.0; private set
    var calmWeight = 0.0; private set
    var buildWeight = 0.0; private set
    var releaseBloom = 0.0; private set
    var shockAge = 10.0; private set
    var shockStrength = 0.0; private set
    var shockHardness = 0.0; private set
    var shockCount = 0; private set
    private var lastShock = -10.0
    private var risingFor = 0.0
    private var densityFast=0.0
    private var densitySlow=0.0
    private val rhythmHistory=DoubleArray(6)
    private var rhythmWindow=0.0
    private var rhythmAttacks=0.0
    private var rhythmWindows=0
    private var entranceStarted=-10.0
    private var entranceLevel=0.0
    private var entranceHits=0
    private var entranceUntil=-10.0
    private var melodicSection = false
    private var buildExitAge=0.0
    private var timbralAge=0.0
    private var timbralLeader=-1
    private var decisionAge=0.0
    private var decidedMoment:Moment?=null
    var sceneDecisionReason="initial"; private set
    val sectionLabel: String get() = when(moment) {
        Moment.BUILDUP -> if(melodicSection) "МЕЛОДИЧНЫЙ РАЗГОН" else "МОЩНЫЙ РАЗГОН"
        Moment.DROP -> if(melodicSection) "МЕЛОДИЧНЫЙ ДРОП" else "ДРОП"
        Moment.MELODIC -> "МЕЛОДИЯ"
        Moment.AMBIENT -> "СПОКОЙНО"
        Moment.BREAKDOWN -> "ПЕРЕДЫШКА"
        Moment.VOID -> "ТИШИНА"
        else -> "ГРУВ"
    }
    private var slowSplitUntil = -10.0
    private var slowSplitCooldown = -10.0
    var bladeTeeth = 0.0; private set
    var melodyPhase = 0.0; private set
    var audioGain = 0.0; private set
    var manualPattern = false
    var forceMode = 0 // 0:auto, 1:calm, 2:heavy. Принудительный режим полностью управляет tension.
    var onsetCount = 0; private set
    var slashCount = 0; private set
    var audioCadence = 0.02; private set
    var impactCount = 0; private set
    var onsetSensitivity = 1.0
    var brightness = 1.0
    // Seconds internally; public bounds are milliseconds for unambiguous tuning.
    var transitionMinMs = 80.0
    // Captured at the musical entrance; style cannot flutter during the morph.
    var transitionStyle = 0; private set // dissolve geometry, legato, impact, rhythmic
    var transitionMaxMs = 1800.0
    var transitionSeconds = 1.4; private set
    var sceneMinSeconds = 8.0; private set
    var sceneMaxSeconds = 32.0; private set
    var texture = 0.0; private set
    var tremor = 0.0; private set
    var zoom = 1.0; private set
    var glitchStyle = 0; private set
    var effectCount = 0; private set
    private var lastAccent = 0.0
    private var lastVfx = -10.0
    private var changeCount = 0
    private var effectAge = 10.0
    private var cameraVx = 0.0; private var cameraVy = 0.0
    private var audioClock = 0.0
    private var lastAudioTime = -10.0
    private var audioPresent = false
    private var initialized = false
    private var warmup = 0.0
    private var shortLevel = 0.0; private var longLevel = 0.0
    private var priorFast = 0.0; private var rise = 0.0
    private var quietTime = 0.0
    private var activity = 0.0; private var fluxSmooth = 0.0; private var flatness = 0.0
    private var targetTension = 0.0
    private var candidate = Moment.AMBIENT; private var candidateAge = 0.0
    private var lastImpact = -10.0
    private var lastKick = -10.0
    private var beatPeriod = 0.5; private var beatConfidence = 0.0; private var beatCount = 0
    private var leader = 0
    private val fluxMean = DoubleArray(3) { 0.08 }
    private val fluxVar = DoubleArray(3) { 0.02 }
    private val bandSlow = DoubleArray(3) { 0.1 }
    private val bandFast = DoubleArray(3)
    private val refractory = DoubleArray(3) { -10.0 }
    private val armed = BooleanArray(3) { true }
    private val pending = DoubleArray(3)
    private val fluxValley = DoubleArray(3)
    private val lastFlux = DoubleArray(3)
    private val onsetDensity = DoubleArray(3)
    private val emphasis = DoubleArray(3)
    private val moments = Moment.values()
    private val votes = DoubleArray(moments.size)
    private val shapeSlow = DoubleArray(3)
    private val visited = DoubleArray(10) { -1000.0 }
    private var transitionPending = false
    private var transitionPendingUntil=-10.0
    private val sceneSignature=DoubleArray(3)
    private val sceneAnchor=DoubleArray(3)
    var transitionSerial=0; private set
    var scenePulseAge=10.0; private set
    var sceneBloom=0.0; private set
    private fun markSceneBoundary() {transitionPending=true;transitionPendingUntil=time+2.0}
    private fun spinResponse(i:Int):Double=when(pattern) {
        1 -> if(i==1) .11 else .18
        2 -> if(i==2) .13 else .23
        3 -> .22+i*.05
        4 -> .38
        5 -> if(i==0) .38 else .18
        6 -> .12
        7 -> .42
        8 -> if(i==3) .10 else .22
        9 -> .34
        else -> .20+i*.035
    }
    private fun styledSpin(i:Int,base:Double):Double=when(pattern) {
        1 -> base*(if(i==1) 1.15 else .85)+elements[i].hit*.55
        2 -> base*(if(i==2) 1.3 else .8)
        3 -> base*(if(i==0) .85 else if(i==1) .7 else 1.15)
        4 -> base*.64
        5 -> base.coerceAtMost(if(i==0) 2.0 else 4.5)
        6 -> base*.90+elements[0].hit*.55
        7 -> base*.55
        8 -> base+elements[i].hit*(if(i==3) 2.2 else .5)
        9 -> base*(if(i==0) .72 else if(i==1) .90 else .60)
        else -> base
    }

    /** Для остановки/смены плеера: не оставляет «замороженный последний FFT». */
    fun resetAudio() {
        response(null);liveContext.reset();activeControl=null;sceneVoice.reset();previousRegister=0.0;lastElementFlash=-10.0;lastReactorContraction=-10.0
        if(pattern==0) shuriken.reset(time)
        initialized = false; warmup = 0.0; audioPresent = false
        shortLevel = 0.0; longLevel = 0.0; quietTime = 0.0
        activity = 0.0; targetMotion=0.0; modulation=0.0; bodyTexture=0.0; cutMean=.42; cutVariance=.04; hiddenElement=-1; lastElementDip=time; fluxSmooth = 0.0; rise = 0.0; targetTension = 0.0
        noveltyMean=.12; noveltyVariance=.015
        texture=0.0; tremor=0.0; glitch=0.0; zoom=1.0; bladeTeeth=0.0
        beatConfidence = 0.0; lastKick = -10.0; beatCount = 0
        pending.fill(0.0); armed.fill(true); fluxMean.fill(0.08); fluxVar.fill(0.02)
        fluxValley.fill(0.0); lastFlux.fill(0.0); onsetDensity.fill(0.0); votes.fill(0.0)
        bandSlow.fill(0.1); bandFast.fill(0.0)
        candidate = Moment.AMBIENT; candidateAge = 0.0
        for (l in layers) {
            l.absentFor = 0.0; l.everPresent = false; l.presence=1.0; l.dipArmed=true; l.dipStart=-100.0; l.recoveredFor=0.0; l.historySaved=-10.0; l.burst=0.0; l.shapeAccents=0; l.shapeTravel=0.0; l.presenceReference=0.0
            l.levelFast = 0.0; l.levelSlow = 0.0; l.drive = 0.0
            l.angularKick = 0.0; l.hit = 0.0; l.springVelocity = 0.0; l.shapeVelocity = 0.0
            l.rewinding = false; l.historyCount = 0; l.historyHead = 0
            l.accentsSinceTurn = 0; l.lastTurn = time
        }
        transitionPending=false;transitionPendingUntil=-10.0;sceneSignature.fill(0.0);sceneAnchor.fill(0.0);scenePulseAge=10.0;sceneBloom=0.0
        slowSplit=0.0;slowSplitUntil=-10.0;slowSplitCooldown=time
        splitStrength=.5;splitSlide=false
        melodicWeight=0.0;calmWeight=0.0;buildWeight=0.0;releaseBloom=0.0
        risingFor=0.0;melodicSection=false
        densityFast=0.0;densitySlow=0.0
        rhythmHistory.fill(0.0);rhythmWindow=0.0;rhythmAttacks=0.0;rhythmWindows=0
        entranceStarted=-10.0;entranceLevel=0.0;entranceHits=0;entranceUntil=-10.0
        buildExitAge=0.0;timbralAge=0.0;timbralLeader=-1;decisionAge=0.0
        decidedMoment=null
        controlHeavy=0.0;controlMotion=0.0;controlMelody=0.0;controlWeight=0.0
        shockAge=10.0;shockStrength=0.0;shockHardness=0.0;lastShock=time;shockCount=0
        for (el in elements) {
            resetGesture(el)
            el.fast=0.0;el.slow=0.0;el.peak=0.0;el.missingFor=0.0
            el.recoveredFor=0.0;el.dipUntil=-1.0;el.armed=true
            el.hit=0.0;el.presence=1.0;el.scale=1.0
            el.accents=0;el.shapeAccents=0;el.shapeTravel=0.0;el.completedCycle=false
            el.fluxFloor=0.0;el.previousFlux=0.0;el.fluxArmed=true;el.angularKick=0.0;el.flashUntil=-10.0
        }
    }

    private val liveContext=LiveContext()
    internal fun context(model:MusicalContext?){liveContext.model(model)}
    private var learningControl:ResponseControl?=null
    private var activeControl:ResponseControl?=null
    private var controlHeavy=0.0
    private var controlMotion=0.0
    private var controlMelody=0.0
    private var controlWeight=0.0
    var learningActive=false;private set
    val learningExplicit:Boolean get()=learningActive && learningControl?.explicit==true
    internal fun response(control:ResponseControl?) {learningControl=control;learningActive=control!=null && control.confidence>0 && forceMode==0}

    fun acceptAudio(f: AudioFeatures, seconds: Double, audible: Boolean = true, warmReplay:Boolean=false) {
        val dt = seconds.coerceIn(0.005, 0.2)
        audioCadence = follow(audioCadence, dt, dt, 1.0)
        audioClock += dt; lastAudioTime = time
        audioPresent = audible && f.level > 1e-7
        if (!audioPresent) {
            risingFor=0.0
            quietTime += dt; targetTension = 0.0
            if (quietTime > 0.8 && moment != Moment.VOID) { moment = Moment.VOID; momentAge = 0.0; markSceneBoundary() }
            return
        }
        liveContext.observe(f,dt)
        activeControl=learningControl ?: liveContext.response
        warmup += dt
        if (!initialized) {
            shortLevel = f.level; longLevel = f.level; priorFast = f.level
            for (b in 0..2) { shapeSlow[b] = f.energy[b] }
            initialized = true
        }
        shortLevel = follow(shortLevel, f.level, dt, 0.22)
        longLevel = follow(longLevel, f.level, dt, 7.0)
        val ratio = shortLevel / max(longLevel, 1e-8)
        quietTime = if (ratio < 0.20) quietTime + dt else 0.0
        val currentRise=(shortLevel-priorFast)/max(longLevel,1e-8)/dt
        rise = follow(rise, currentRise, dt, 1.2)
        priorFast = shortLevel
        var events = 0; var strongest = 0.0; var shapeChange = 0.0
        var musicalAccent = 0.0
        var cuttingAccent = 0.0
        var bodyRise = 0.0
        var bodyOnset = false
        for (b in 0..2) {
            val l = layers[b]
            val share = f.energy[b]
            bandFast[b] = follow(bandFast[b], share, dt, 0.12)
            if(l.absentFor<0.15) bandSlow[b] = follow(bandSlow[b], share, dt, 5.0)
            val level = share * f.level
            val attackRise=unit((level-l.levelFast)/max(l.levelSlow*.8,1e-7))
            if(b<2) bodyRise=max(bodyRise,attackRise)
            if (l.levelSlow <= 1e-10) { l.levelSlow = level; l.levelFast = level }
            l.levelFast = follow(l.levelFast, level, dt, if (level > l.levelFast) 0.035 else 0.14)
            l.levelSlow = follow(l.levelSlow, level, dt, 2.5)
            // Непрерывная деформация для басовых/вокальных фраз между дискретными атаками.
            val contour = ((l.levelFast / max(1e-7, l.levelSlow) - 1.0) * 0.65).coerceIn(-0.5, 1.0)
            l.drive = follow(l.drive, if (share > 0.015) contour else 0.0, dt, 0.07)
            val flux = f.flux[b]
            // Порог считается по прошлой статистике, а не уже включившей текущий удар.
            val deviation = sqrt(max(0.0004, fluxVar[b]))
            val z = (flux - fluxMean[b]) / deviation
            // Обычные музыкальные атаки не обязаны быть статистическими выбросами.
            // Потолок согласован с диапазоном flux [0, 1], отдельный z оставлен для impact.
            val threshold = (max(0.12, fluxMean[b] + 0.70 * deviation) *
                onsetSensitivity.coerceIn(0.8, 1.2)).coerceAtMost(0.48)
            l.threshold = threshold
            fluxValley[b] = min(fluxValley[b], flux)
            if (flux < max(0.065, threshold * 0.72)) armed[b] = true
            val rising = flux - fluxValley[b] > max(0.09, deviation * 0.4) && flux > lastFlux[b] + 0.025
            val separation = if (b == 2) 0.09 else 0.14
            if (warmup > 0.7 && (armed[b] || rising) && audioClock - refractory[b] > separation &&
                flux > threshold && share > 0.018 && ratio > 0.22) {
                val strength = unit(.15 + flux*.55 + attackRise*.40) // Stable weight, independent of adaptive detection threshold.
                pending[b] = max(pending[b], strength)
                l.hit = max(l.hit, strength)
                l.lastStrength = strength; l.onsetCount++
                onsetDensity[b] = min(1.0, onsetDensity[b] + strength * 0.17)
                refractory[b] = audioClock; armed[b] = false
                fluxValley[b] = flux
                onsetCount++; events++; strongest = max(strongest, z)
                if (b < 2) {
                    musicalAccent = max(musicalAccent, strength)
                    bodyOnset = true
                    // Either a physical envelope attack or a strong changing mid/bass timbre.
                    cuttingAccent=max(cuttingAccent,unit(flux*.68+attackRise*.45))
                }
                l.burst=max(l.burst,strength*ease((strength-.35)/.60))
                l.shapeAccents++
                if(l.shapeAccents >= 7+b*2 && l.shapeTravel>7.2+b*.3 && !l.rewinding && f.novelty>.065) {
                    l.shapeDirection = -l.shapeDirection; l.shapeAccents=0; l.shapeTravel=0.0
                    if(forceMode!=1 && strength>.66 && l.historyCount>12) {
                        val oldest=(l.historyHead-l.historyCount+256)%256
                        val available=time-l.historyTime[oldest]
                        if(available>.65) {
                            l.rewinding=true; l.rewindCount++; l.rewindStart=time
                            l.rewindDuration=.48+strength*.35
                            l.rewindDistance=min(available-.04,.65+strength*.80)
                        }
                    }
                }
                if (b == 0) {
                    val gap = audioClock - lastKick
                    if (gap in 0.14..1.05) {
                        val agreement = unit(1.0 - abs(gap - beatPeriod) / (beatPeriod * 0.3))
                        beatConfidence = follow(beatConfidence, agreement, gap, 2.0)
                        beatPeriod = mix(beatPeriod, gap, 0.18)
                    } else beatConfidence *= 0.8
                    lastKick = audioClock; beatCount++
                }
                // Разные музыкальные фразы у трёх слоёв, без случайного таймера и z > 3.
                l.accentsSinceTurn++
                // Фразы 4/8/6 атак не запирают движение в одном повторяющемся угловом секторе.
                val phraseExtra = when (l.turnCount % 3) { 1 -> 4; 2 -> 2; else -> 0 }
                val phraseLength = (if (forceMode == 1) 8 else 4) + b * 2 + phraseExtra
                val turnHold = if (forceMode == 1) 3.5 else 1.6 + b * 0.35
                val turnCue=pattern==0 || (f.novelty>.12 && abs(share-bandSlow[b])>.06) || ratio<.70
                if (turnCue && !l.rewinding && l.accentsSinceTurn >= phraseLength && time - l.lastTurn > turnHold) {
                    l.direction = -l.direction
                    l.turnCount++; l.lastTurn = time; l.accentsSinceTurn = 0
                    // Старый импульс не должен подавлять новое направление.
                    l.angularKick = 0.0

                }
            }
            lastFlux[b] = flux
            onsetDensity[b] *= exp(-dt / 1.25)
            val delta = flux - fluxMean[b]
            fluxMean[b] = follow(fluxMean[b], flux, dt, 2.8)
            fluxVar[b] = follow(fluxVar[b], delta * delta, dt, 2.8)
            // Broad-band absences must never erase arbitrary strokes of a shape.
            l.presence=1.0
            emphasis[b] = sqrt(share) * (0.5 + min(1.5, bandFast[b] / max(0.025, bandSlow[b]))) + onsetDensity[b] * 0.7
            shapeChange += abs(share - shapeSlow[b])
            shapeSlow[b] = follow(shapeSlow[b], share, dt, 2.5)
        }
        var sum = 0.0
        for (value in emphasis) sum += value
        // Сначала нормализуется цель, затем сглаживается состояние — без обратной связи масштабов.
        for (b in 0..2) layers[b].salience = follow(layers[b].salience, emphasis[b] / max(1e-6, sum), dt, 0.35)
        var nextLeader = leader
        for (b in 0..2) if (layers[b].salience > layers[nextLeader].salience + 0.12) nextLeader = b
        // A spectral leader is a voice inside a phrase, not a section boundary.
        leader = nextLeader
        activity = unit(onsetDensity[0] * 0.50 + onsetDensity[1] * 0.35 + onsetDensity[2] * 0.15)
        fluxSmooth = follow(fluxSmooth, f.flux.average(), dt, 0.8)
        flatness = follow(flatness, f.flatness, dt, 0.8)
        // Motion and heaviness are separate: fast clean percussion can energize a
        // melodic scene without forcing the distorted/drop geometry.
        bodyTexture=follow(bodyTexture,f.bodyTexture,dt,.85)
        texture=bodyTexture
        modulation=follow(modulation,max(bodyRise,f.novelty*2.2),dt,.65)
        val rhythmic=unit(onsetDensity[0]*.55+onsetDensity[1]*.38+onsetDensity[2]*.07)
        densityFast=follow(densityFast,rhythmic,dt,.65)
        densitySlow=follow(densitySlow,rhythmic,dt,5.0)
        rhythmWindow+=dt
        if(bodyOnset)rhythmAttacks+=1.0
        if(rhythmWindow>=1.0) {
            for(i in 0..4)rhythmHistory[i]=rhythmHistory[i+1]
            rhythmHistory[5]=rhythmAttacks/rhythmWindow
            rhythmWindow=0.0;rhythmAttacks=0.0;rhythmWindows++
        }
        val bodyFlux=max(f.flux[0],f.flux[1])
        val flow=follow(fluxSmooth,bodyFlux,dt,.6)
        targetMotion=ease((rhythmic*.70+flow*.26+modulation*.28-.10)/.70)
        val denseBody=bodyTexture*sqrt(f.bodyShare.coerceAtLeast(0.0))
        var heaviness=ease((rhythmic*(.46+denseBody*.27)+denseBody*.15+
            modulation*.20+flow*.12-.15)/.65)
        // Separate a confirmed entrance from gradual accumulation. Require a
        // large real envelope jump followed by more than one low/mid attack.
        if(time<entranceUntil) {
            if(f.level<entranceLevel*.60)entranceUntil=time
            else if(bodyOnset && (f.bodyTexture>.45 ||
                (f.tonalContrast>.45 && f.spectralContinuity>.75)))
                entranceUntil=max(entranceUntil,time+.80)
        }
        if(entranceHits>0 && (time-entranceStarted>.95 || f.level<entranceLevel*.60))entranceHits=0
        if(bodyOnset && warmup>3 && forceMode!=1) {
            if(entranceHits>0) {
                entranceHits++
                val tonalEntrance=f.tonalContrast>.45 && f.spectralContinuity>.75
                if(entranceHits>=(if(tonalEntrance)3 else 2) &&
                    (f.bodyTexture>.45 || tonalEntrance) && ratio>1.15) {
                    entranceUntil=time+1.8;entranceHits=0
                }
            } else if(time>entranceUntil && bodyRise>.65 && currentRise>.8 &&
                f.level>longLevel*1.7 &&
                (calmWeight>.45 || moment==Moment.BUILDUP || moment==Moment.BREAKDOWN || moment==Moment.VOID)) {
                entranceStarted=time;entranceLevel=f.level;entranceHits=1
            }
        }
        val recentEntrance=time<entranceUntil && forceMode!=1
        val entranceSettling=recentEntrance && time-entranceStarted<2.5
        lastAccent=musicalAccent
        accent=max(accent,musicalAccent)
        val teaching=activeControl
        // Cache bins and live clusters may disagree at their boundaries. Blend
        // their correction continuously; never let a prediction add an impulse.
        val weightAim=if(forceMode!=0 || teaching==null) 0.0 else
            if(teaching.explicit) 1.0 else teaching.confidence*.35
        controlWeight=follow(controlWeight,weightAim,dt,.9)
        val controlTau=if(teaching?.explicit==true) .45 else 1.5
        controlHeavy=follow(controlHeavy,teaching?.heavy?.takeIf{it>=0} ?: heaviness,dt,controlTau)
        controlMotion=follow(controlMotion,teaching?.motion?.takeIf{it>=0} ?: targetMotion,dt,controlTau)
        controlMelody=follow(controlMelody,teaching?.melodic?.takeIf{it>=0} ?: melodicWeight,dt,controlTau)
        if(forceMode==0) {
            heaviness=mix(heaviness,controlHeavy,controlWeight)
            targetMotion=mix(targetMotion,controlMotion,controlWeight)
        }
        if(recentEntrance && teaching?.explicit!=true) {
            targetMotion=max(targetMotion,.62)
            if(f.bodyTexture>.45 && f.tonalContrast<.45)heaviness=max(heaviness,.66)
        }
        targetTension=heaviness
        // Tonality and pressure are independent: a clean melodic drop is still a drop.
        val melodicAim=ease((f.tonalContrast-.20)/.32)*ease((f.spectralContinuity-.40)/.42)
        melodicWeight=follow(melodicWeight,melodicAim,dt,.8)
        if(melodicWeight>.65) melodicSection=true
        else if(melodicWeight<.40) melodicSection=false
        // A single entrance raises density too. Demand repeated increases across
        // several windows, so a sudden drop is not misread as a long buildup.
        val earlyRhythm=(rhythmHistory[0]+rhythmHistory[1])*.5
        val middleRhythm=(rhythmHistory[2]+rhythmHistory[3])*.5
        val lateRhythm=(rhythmHistory[4]+rhythmHistory[5])*.5
        val firstGrowth=middleRhythm-earlyRhythm;val lastGrowth=lateRhythm-middleRhythm
        val rhythmicBuild=rhythmWindows>=6 && firstGrowth>-.1 && lastGrowth>-.1 && firstGrowth+lastGrowth>.9 &&
            firstGrowth<1.5 && lastGrowth<1.5 && densityFast-densitySlow>.065 && ratio>.75
        val energyBuild=!entranceSettling && rise>.10 && currentRise>.035 && ratio>1.08 && fluxSmooth>.10
        risingFor=if(!entranceSettling && (energyBuild || rhythmicBuild)) min(4.0,risingFor+dt)
            else max(0.0,risingFor-dt*2.5)
        buildExitAge=if(moment==Moment.BUILDUP && !(energyBuild || rhythmicBuild)) buildExitAge+dt else 0.0
        val quietAim=(1-ease((targetMotion-.12)/.38))*(1-ease((heaviness-.12)/.35))
        calmWeight=follow(calmWeight,if(forceMode==1) 1.0 else if(forceMode==2) 0.0 else quietAim,dt,
            if(quietAim>calmWeight) .65 else .20)
        processElements(f,dt,ratio)
        // A sustained energy rise may open the sphere without a hard cut.
        val acceleration=targetMotion-motionEnergy
        if(pattern==2 && ratio>.94 && bodyFlux>.34 &&
            (rise>.12 || (acceleration>.19 && f.novelty>.045) ||
             (moment==Moment.BUILDUP && acceleration>.12)) &&
            time-slowSplitCooldown>4.0 && warmup>1.5 && slashAge>.30) {
            slowSplitUntil=time+(beatPeriod*4.0).coerceIn(1.3,2.6)
            slowSplitCooldown=time
            if(slowSplit<.08 && slashAge>slashDuration) {
                slashAngle=PI/2;splitSlide=f.flux[1]>f.flux[0]
            }
            splitStrength=unit(.25+bodyFlux*.45+acceleration*.6)
        }
        val proposed = when {
            quietTime > 0.8 -> Moment.BREAKDOWN
            ratio < 0.50 -> Moment.BREAKDOWN
            entranceSettling && teaching?.explicit!=true -> Moment.DROP
            moment==Moment.BUILDUP && buildExitAge<.85 -> Moment.BUILDUP
            risingFor > 1.4 -> Moment.BUILDUP
            recentEntrance && teaching?.explicit!=true -> Moment.DROP
            moment==Moment.DROP && rhythmic>.24 &&
                (heaviness>.42 || (melodicWeight>.55 && targetMotion>.35)) -> Moment.DROP
            heaviness > 0.62 || (rhythmic>.36 && targetMotion>.53 && melodicWeight>.55) -> Moment.DROP
            heaviness > 0.18 -> Moment.GROOVE
            f.energy[1] > 0.4 && flatness < 0.20 -> Moment.MELODIC
            else -> Moment.AMBIENT
        }
        // Соседние кадры GROOVE/BUILDUP больше не сбрасывают всё подтверждение секции.
        for (i in votes.indices) votes[i] = follow(votes[i], if (i == proposed.ordinal) 1.0 else 0.0, dt, 0.38)
        var voteWinner = proposed.ordinal
        for (i in votes.indices) if (votes[i] > votes[voteWinner]) voteWinner = i
        val voted = moments[voteWinner]
        if (candidate != voted) { candidate = voted; candidateAge = 0.0 } else candidateAge += dt
        val hold = if (candidate == Moment.DROP) 0.18 else if (candidate == Moment.VOID) 0.1 else 0.65
        val sectionHold=if(candidate==Moment.BREAKDOWN || candidate==Moment.VOID) .7 else 2.0
        if (candidateAge >= hold && moment != candidate && momentAge > sectionHold) {
            val was = moment
            moment = candidate; momentAge = 0.0
            markSceneBoundary()
            if (moment == Moment.DROP && warmup > 2.0) {
                releaseBloom=1.0
                if(was != Moment.GROOVE && melodicWeight<.55) impact(0.85)
            }
            // Граница секции не сбрасывает направление: оно принадлежит фразе каждого слоя.
        }
        val unusual=bodyOnset && cuttingAccent>.90 &&
            (f.novelty>max(.24,noveltyMean+1.65*sqrt(noveltyVariance)) || (bodyRise>.90 && ratio>1.65))
        val noveltyDelta=f.novelty-noveltyMean
        noveltyMean=follow(noveltyMean,f.novelty,dt,3.0)
        noveltyVariance=follow(noveltyVariance,noveltyDelta*noveltyDelta,dt,3.0)
        // Brief visible VFX are driven by accents; the slow mood estimate is not a veto.
        if(forceMode!=1 && calmWeight<.45 && melodicWeight<.65 && warmup>2 && unusual && time-lastVfx>max(1.25,beatPeriod*3.0)) {
            lastVfx=time; effectAge=0.0; effectCount++
            glitchStyle=(effectCount+leader)%3
            glitch=max(glitch,.72+cuttingAccent*.25)
            tremor=max(tremor,.20+cuttingAccent*.20)
            if(bodyRise>.70 && ratio>1.4 && time-lastImpact>3.0) impact(cuttingAccent)
        }
        // Adaptive strength selection with a real recovery interval. No novelty
        // requirement: repeated similar strong kicks must remain eligible.
        val cutThreshold=(cutMean+.35*sqrt(cutVariance)).coerceIn(.60,.83)
        // A separate travelling impact is reserved for strong body events.
        // Hats, a held tone, and elapsed time alone cannot launch it.
        if(forceMode!=1 && warmup>1.2 && bodyOnset && cuttingAccent>.72 &&
            ratio>.7 && (calmWeight<.5 || bodyRise>.65) && time-lastShock>max(.60,beatPeriod*1.4)) {
            shockAge=0.0;shockStrength=unit(cuttingAccent*(.5+.5*unit(bodyRise)))
            shockHardness=unit(bodyTexture*(1-.65*melodicWeight))
            lastShock=time;shockCount++
        }
        val cutGap=if(cuttingAccent>.96 && bodyRise>.65) .23 else (beatPeriod*.70).coerceIn(.32,.62)
        if(bodyOnset && cuttingAccent>cutThreshold && (bodyRise>.16 || cuttingAccent>.86) &&
            ratio>.35 && slashAge>cutGap) {
            slashDuration=(cutGap*1.05).coerceIn(.30,.65)
            if(slashAge>slashDuration && slowSplit<.08) {
                slashAngle=PI/2;splitSlide=f.flux[1]>f.flux[0]
            }
            splitStrength=unit(cuttingAccent*(.4+.6*unit(ratio)))
            slashAge=0.0; slashCount++
        }
        if(bodyOnset) {
            val delta=cuttingAccent-cutMean
            cutMean=follow(cutMean,cuttingAccent,.05,.8)
            cutVariance=follow(cutVariance,delta*delta,.05,.8)
        }
        if(forceMode!=1 && unusual && denseBody>.40 && bladeTeeth<.12) bladeTeeth=1.0
        var sectionContrast=0.0
        for(b in 0..2) {
            sceneSignature[b]=follow(sceneSignature[b],f.energy[b],dt,.80)
            if(warmup<.12) {sceneSignature[b]=f.energy[b];sceneAnchor[b]=f.energy[b]}
            sectionContrast+=abs(sceneSignature[b]-sceneAnchor[b])
        }
        val minDwell=(beatPeriod*16.0).coerceIn(7.0,14.0)*(1.0-tension*.10)
        val accumulating=risingFor>.25 || moment==Moment.BUILDUP || candidate==Moment.BUILDUP
        if(leader!=timbralLeader){timbralLeader=leader;timbralAge=0.0}
        timbralAge=if(sectionContrast>.38 && !accumulating) timbralAge+dt else max(0.0,timbralAge-dt*3)
        val freshBoundary=transitionPending && moment!=Moment.VOID && momentAge>1.6 && candidate==moment
        val timbralBoundary=timbralAge>2.2 && momentAge>1.6 && candidate==moment
        val quietEntrance=freshBoundary && decidedMoment!=null && momentAge>2.4 && calmWeight>.55
        decisionAge+=dt
        // Let the current scene articulate its phrase. Changes require sustained
        // section evidence and an audible entrance, never a lone leader/attack.
        if(!warmReplay && warmup>4 && !accumulating && !manualPattern && morph>=1.0 && sceneAge>minDwell && decisionAge>3 &&
            (pattern!=7 || elements[0].completedCycle || (freshBoundary && momentAge>2.5)) &&
            (freshBoundary || timbralBoundary) && (events>0 || bodyOnset || quietEntrance)) {
            val newPhrase=(freshBoundary && decidedMoment!=null && decidedMoment!=moment) ||
                (timbralBoundary && sectionContrast>.60)
            selectScene(newPhrase);decidedMoment=moment
            transitionPending=false;timbralAge=0.0;decisionAge=0.0
            sceneSignature.copyInto(sceneAnchor)
        }
        // Keep the latest confirmed section pending through minimum scene dwell.
    }

    private fun processElements(f: AudioFeatures, dt: Double, globalRatio: Double) {
        if(pattern==0) {
            shuriken.learnedFlow=controlMelody
            shuriken.learnedConfidence=if(forceMode==0)controlWeight else 0.0
            shuriken.observe(f,dt,time,forceMode==1,
                if(forceMode==2) 1.0 else targetTension,
                if(forceMode==2) 1.0 else targetMotion)
            return
        }
        val channels=sceneChannels[pattern]
        var blinkCandidate=-1; var blinkScore=0.0
        var collapseCandidate=-1; var collapseScore=0.0; var collapseTimbral=false
        val voiceAccent=sceneVoice.observe(f,dt,time)
        val tonalAccent=previousRegister>0 && f.tonalContrast>.25 && f.spectralContinuity>.45 &&
            abs(f.leadRegister-previousRegister)>.08
        val registerDelta=if(previousRegister>0 && f.leadRegister>0)
            ((f.leadRegister-previousRegister)/max(.01,dt)).coerceIn(-3.0,3.0) else 0.0
        previousRegister=f.leadRegister
        // One leading timbral event owns one component. The old broadcast to
        // every low/mid component made unrelated parts say the same syllable.
        var voiceOwner=-1; var voiceScore=-1.0
        for(i in channels.indices) {
            val ch=channels[i]
            if(ch !in 1..3 || (pattern==6 && i in 1..2)) continue
            val score=f.subEnergy[ch]+max(0.0,f.subEnergy[ch]-elements[i].previousShare)*2.0
            if(score>voiceScore){voiceScore=score;voiceOwner=i}
        }
        val tonalFlow=ease((f.tonalContrast-.25)/.12)*ease((f.spectralContinuity-.65)/.20)
        sceneBloom=follow(sceneBloom,unit(f.energy[1]*1.6+f.flux[1]*.35),dt,.38)
        for (i in channels.indices) {
            val el=elements[i]
            val ch=channels[i]
            // Outer details represent the treble voice, not only >6 kHz air.
            // That narrow band is often nearly absent in headphone/encoded audio.
            val share=unit(f.subEnergy[ch]+if(ch==5) f.subEnergy[4]*.50 else 0.0)
            val level=f.level*sqrt(share)
            // A blink follows a confirmed local release, not every strong attack.
            val fallingShare=el.previousShare>.08 && share<el.previousShare*.48 &&
                el.rawLevel>1e-7 && level<el.rawLevel*.65
            val recentAttack=audioClock-el.lastOnset in .035.. .65
            if(fallingShare && recentAttack){el.blinkPendingAt=time;el.blinkFromShare=el.previousShare}
            if(share>el.blinkFromShare*.72 || time-el.blinkPendingAt>.14)el.blinkPendingAt=-10.0
            val endedVoice=time-el.blinkPendingAt in .039.. .14 && share<el.blinkFromShare*.55
            if(endedVoice && recentAttack && globalRatio>.45 && forceMode!=1 &&
                warmup>1 && hiddenElement<0 && time-lastElementDip>1 &&
                time-lastElementFlash>max(1.0,beatPeriod*2.25) &&
                !(pattern==7 && i==0) && !(pattern==2 && i==1)) {
                val score=(el.blinkFromShare-share)*(1+el.hit)
                if(score>blinkScore){blinkScore=score;blinkCandidate=i}
            }
            el.rawLevel=level
            if(el.peak<1e-9) {el.fast=level;el.slow=level;el.peak=level}
            el.fast=follow(el.fast,level,dt,if(level>el.fast) .08 else .16)
            el.slow=follow(el.slow,level,dt,1.6)
            el.peak=follow(el.peak,max(el.fast,level),dt,1.3)
            val band=if(ch<2) 0 else if(ch<4) 1 else 2
            // Kaleidoscope's main motif is a melodic carrier even though its
            // impact channel is bass. Shape rewind must not stop its rotation.
            val melodicRole=ch in 1..3 || (pattern==9 && i==0)
            val flowTarget=if(melodicRole && share>.025) tonalFlow else 0.0
            el.melodicFlow=follow(el.melodicFlow,flowTarget,dt,if(flowTarget>el.melodicFlow) .65 else .32)
            val contrast=el.fast/max(el.slow,1e-8)
            el.contour=follow(el.contour,((contrast-1.0)*.85).coerceIn(-.65,1.0),dt,.12)
            el.activity=follow(el.activity,unit(share*3.0+f.flux[band]*.35),dt,.24)
            el.registerMotion=follow(el.registerMotion,
                if(ch in 1..3 && f.tonalContrast>.20) registerDelta/3.0 else 0.0,dt,.20)
            val flux=f.flux[band]
            el.fluxFloor=follow(el.fluxFloor,flux,dt,.8)
            if(flux<max(.10,el.fluxFloor*.75))el.fluxArmed=true
            val spectralAttack=el.fluxArmed && flux>max(.22,el.fluxFloor*1.35) && flux-el.previousFlux>.035
            val timbralAttack=i==voiceOwner && (voiceAccent || (tonalAccent && ch in 2..3))
            val eventDifference=abs(share-el.eventShare)+abs(f.bodyTexture-el.eventTexture)*.28+
                (if(el.eventRegister>0 && f.leadRegister>0)abs(f.leadRegister-el.eventRegister)*.22 else 0.0)
            val distinctive=el.eventSamples>=2 && eventDifference>.12 &&
                (f.novelty>.12 || timbralAttack) && (f.bodyTexture>.28 || f.tonalContrast>.32)
            val localEnd=contrast<.66 && recentAttack && share>.018
            if(el.pauseArmed && localEnd && time-el.lastPause>1.1 && forceMode!=1 &&
                (pattern!=6 || i==0) && !(pattern==5 && i==0)) {
                el.pauseUntil=time+(beatPeriod*.20).coerceIn(.065,.13)
                el.lastPause=time;el.pauseArmed=false;el.pauseCount++;el.turnReadyUntil=time+1.1
            }
            val onset=(spectralAttack || timbralAttack || (flux>.25 && contrast>1.20)) && share>(if(ch>=4).004 else .018) &&
                audioClock-el.lastOnset>(if(ch>=4) .12 else .16)
            el.previousFlux=flux
            // The three iris blade pairs share one motion controller. Their
            // own subbands still track falling edges for pairwise disappearance.
            if(onset && warmup>.7 && (pattern!=6 || i==0)) {
                val strength=unit(max(if(timbralAttack) .38+sceneVoice.strength*.4 else 0.0,
                    (flux-.12)*1.15+max(0.0,contrast-1.0)*.35+abs(share-el.previousShare)*.8))
                el.fluxArmed=false
                val calmScale=if(forceMode==1) .25 else 1.0-.72*calmWeight
                // A recognizable arc with an end, rather than another speed
                // spike on top of perpetual spinning. Protect melodic carriers.
                val arc=when(pattern){1->.46;2->.32;3->.42;4->.26;5->.30;6->.35;7->.25;8->.62;else->.32}
                val role=if(ch>=4) .65 else if(timbralAttack) 1.45 else .75
                el.phraseTurn.trigger(el.direction*arc*(.35+.65*strength)*role*calmScale*(1-.82*el.melodicFlow),
                    if(timbralAttack) .055 else .038)
                el.angularKick=0.0
                el.hit=max(el.hit,strength)
                // A shape gesture has a destination and a finite tail, like a turn.
                // It does not make the pose timeline run forever after an attack.
                val shapeArc=when(pattern){7->.46;9->.26;4->.18;else->.15}
                el.shapeTurn.trigger(el.shapeDirection*shapeArc*strength*calmScale*
                    (if(ch>=4) .22 else 1.0)*(1-.75*el.melodicFlow),
                    if(timbralAttack) .10 else .065)
                el.attackEdge=unit(strength*(.30+.70*f.bodyTexture)*(1-.7*el.melodicFlow))
                // Low/mid attacks deform one coherent part; high hats only light it.
                if(ch<4 && pattern!=2 && pattern!=7)
                    el.elasticAccent.trigger(strength*(.025+.12*el.attackEdge)*calmScale)
                if(i==0 && strength>.50) scenePulseAge=0.0
                el.onsetCount++;el.lastOnset=audioClock
                el.pauseArmed=true
                el.hitRelease=if(timbralAttack) .26 else if(pattern==4 || pattern==9) .23 else if(ch>=4) .11 else .18
                if(distinctive)el.turnReadyUntil=time+.8
                el.eventSamples++;el.eventShare=share;el.eventRegister=f.leadRegister;el.eventTexture=f.bodyTexture
                // Same event -> same part and gesture; no fixed outer-row collapse.
                // Brief inward loading, then spring release. Timbre motion is slower.
                if(pattern==3) {
                    val voiced=timbralAttack && sceneVoice.strength>.78 && eventDifference>.16 && f.novelty>.08 && ch in 0..3
                    val eligible=((distinctive && eventDifference>.22 && f.novelty>.18) || voiced) &&
                        time-el.lastContraction>4.0 && time-lastReactorContraction>max(2.8,beatPeriod*6) && forceMode!=1
                    val score=strength*(.4+share)+(if(timbralAttack) .24 else 0.0)
                    if(eligible && score>collapseScore){collapseScore=score;collapseCandidate=i;collapseTimbral=timbralAttack}
                } else if(pattern!=7 && !(pattern==2 && i==1)) {
                    val depth=when(pattern){3->.23;4->if(i==0) .30 else .12;9->.15;else->.12}
                    el.radialTarget=-depth*strength*calmScale
                    el.radialHold=time+(if(timbralAttack) (beatPeriod*.32).coerceIn(.09,.20) else .055)
                    el.gestureSource=if(timbralAttack) 2 else 1
                    contractionCount++
                }
                el.accents++;el.shapeAccents++
                val phrase=3+(i*2+ch)%4+(el.turnCount%3)
                if(el.accents>=phrase && time<el.turnReadyUntil && time-el.lastTurn>(if(pattern==4 || pattern==7 || pattern==9) 7.0 else 4.5+ch*.20)) {
                    el.direction=-el.direction;el.turnCount++;el.lastTurn=time;el.accents=0;el.angularKick=0.0
                }
                val shapePhrase=if(pattern==7) 3 else 5+i%3
                val fullCycle=pattern!=7 || el.shapeTravel>=8.0
                if(fullCycle && time<el.turnReadyUntil && !el.rewinding && el.shapeAccents>=shapePhrase &&
                    time-el.lastShapeTurn>(if(pattern==7) 5.0 else 2.0)) {
                    el.shapeDirection=-el.shapeDirection
                    el.lastShapeTurn=time;el.shapeAccents=0;el.shapeTravel=0.0
                    if(((pattern==7 && i==0) || (pattern==9 && i<2)) && distinctive && el.tapeCount>16 && forceMode!=1) {
                        val oldest=(el.tapeHead-el.tapeCount+160)%160
                        val available=time-el.shapeTapeTime[oldest]
                        if(available>.7) {
                            el.shapeTapeTime[el.tapeHead]=time;el.shapeTapeValue[el.tapeHead]=el.shapePhase
                            el.tapeHead=(el.tapeHead+1)%160;el.tapeCount=min(160,el.tapeCount+1)
                            el.rewinding=true;el.rewindCount++;el.rewindStart=time
                            el.rewindDuration=(beatPeriod*.9).coerceIn(.35,.7)
                            el.rewindDistance=min(available-.03,.65+strength*.5)
                        }
                    }
                }
            }
            el.previousShare=share
            // Falling edge, recent peak and audible other music are all needed.
            // The same prolonged absence cannot re-trigger a second dip.
            val missing=el.peak>max(1e-6,f.level*.045) && el.fast<el.peak*.30 &&
                globalRatio>.45 && f.level>1e-7
            el.missingFor=if(missing) el.missingFor+dt else 0.0
            el.recoveredFor=if(!missing && el.fast>el.peak*.62) el.recoveredFor+dt else 0.0
            if(el.recoveredFor>.75 && time>el.dipUntil+1.0) el.armed=true
            if(!(pattern==7 && i==0) && !(pattern==2 && i==1) && hiddenElement<0 &&
                el.armed && warmup>2.0 && el.missingFor>.20 &&
                el.missingFor<.55 && time-lastElementDip>max(2.8,beatPeriod*5)) {
                hiddenElement=i;lastElementDip=time
                el.dipUntil=time+(beatPeriod*1.2).coerceIn(.42,.85)
                el.armed=false;el.dipCount++
            }
        }
        if(blinkCandidate>=0) {
            val el=elements[blinkCandidate]
            el.flashUntil=time+(beatPeriod*.16).coerceIn(.05,.10)
            el.flashFloor=if(pattern==4 || pattern==9) .28 else .12
            el.blinkCount++;el.blinkPendingAt=-10.0;lastElementFlash=time;lastElementDip=time
        }
        if(collapseCandidate>=0) {
            val el=elements[collapseCandidate];val strength=unit(collapseScore)
            el.radialTarget=-(.17+.13*strength)
            el.radialHold=time+(beatPeriod*(if(collapseTimbral) .38 else .24)).coerceIn(.09,.22)
            el.gestureSource=if(collapseTimbral) 2 else 1
            el.lastContraction=time;lastReactorContraction=time;contractionCount++
        }
    }

    private fun impact(strength: Double) {
        if (time - lastImpact < 2.8) return
        lastImpact = time; impactCount++
        effectCount++
        glitch = strength
        effectAge=0.0; tremor=max(tremor,strength*0.9)
        val angle = layers[leader].phase + leader * 2.1
        cameraVx += cos(angle) * strength * 24.0
        cameraVy += sin(angle) * strength * 24.0
        // Global impacts do not consume or trigger the independently classified slash.
    }

    private fun selectScene(newPhrase:Boolean) {
        // Suitability is a constraint. Recency only breaks close ties; retaining
        // a fitting current scene is a valid decision, including at a boundary.
        val gentle=forceMode==1 || (forceMode!=2 && (calmWeight>.55 || moment==Moment.BREAKDOWN))
        val melodic=forceMode!=2 && melodicWeight>.55
        val heavy=forceMode==2 || (tension>.40 && !melodic)
        fun suitable(p:Int)=when {
            gentle || melodic -> p==4 || p==5 || p==7 || p==9
            heavy -> p==0 || p==2 || p==3 || p==6 || p==8
            else -> p==1 || p==3 || p==5 || p==6
        }
        var best=pattern;var bestScore=-1e9;var currentScore=-1e9
        for (p in 0..9) {
            if(!suitable(p))continue
            val affinity = when (sceneSignature.indices.maxBy { sceneSignature[it] }) {
                0 -> if (p == 0 || p == 3 || p == 8) 2.0 else 0.0
                1 -> if (p == 4 || p == 5 || p == 7 || p == 9) 2.0 else 0.0
                else -> if (p == 1 || p == 2 || p == 6) 2.0 else 0.0
            }
            val score=affinity+unit((time-visited[p])/80.0)*.35+
                (if(p==pattern) (if(newPhrase) -.75 else .70) else 0.0)
            if(p==pattern)currentScore=score
            if (score > bestScore) { bestScore = score; best = p }
        }
        if(best!=pattern && bestScore>currentScore+(if(newPhrase) .10 else .65)) {
            sceneDecisionReason=if(melodic) "confirmed melodic section" else if(gentle) "confirmed quiet section"
                else if(heavy) "confirmed heavy section" else "sustained spectral section"
            changePattern(best)
        } else sceneDecisionReason="retain compatible scene"
    }
    fun stepPattern(delta:Int) {
        manualPattern=true
        changePattern(((pattern+delta)%10+10)%10,true)
    }
    fun changePattern(next:Int,interrupt:Boolean=false) {
        if(next.coerceIn(0,9)==pattern || (!interrupt && morph<1.0)) return
        transitionSerial++;scenePulseAge=10.0;sceneSignature.copyInto(sceneAnchor)
        previousPattern = pattern; visited[pattern] = time
        pattern = next.coerceIn(0, 9); morph = 0.0; sceneAge = 0.0
        hiddenElement=-1
        for(el in elements) {
            resetGesture(el)
            el.fast=0.0;el.slow=0.0;el.peak=0.0;el.missingFor=0.0
            el.recoveredFor=0.0;el.dipUntil=-1.0;el.armed=true
            el.hit=0.0;el.presence=1.0;el.scale=1.0;el.accents=0;el.shapeAccents=0
            el.shapeTravel=0.0;el.completedCycle=false;el.fluxFloor=0.0;el.previousFlux=0.0;el.fluxArmed=true;el.angularKick=0.0;el.flashUntil=-10.0
        }
        sceneVoice.reset();previousRegister=0.0
        if(pattern==0) shuriken.reset(time)
        changeCount++
        val low=transitionMinMs.coerceIn(40.0,3000.0)/1000.0
        val high=max(low,transitionMaxMs.coerceIn(40.0,5000.0)/1000.0)
        transitionStyle=when {
            interrupt || forceMode==1 || calmWeight>.55 || moment==Moment.BREAKDOWN -> 0
            forceMode==2 || (tension>.48 && melodicWeight<.55) -> 2
            melodicWeight>.55 -> 1
            else -> 3
        }
        val pulse=if(beatConfidence>.35)beatPeriod.coerceIn(.25,.85) else .5
        val musicalSeconds=when(transitionStyle) {
            2 -> pulse*mix(.85,.40,unit(lastAccent*texture))
            1 -> pulse*mix(2.8,1.5,motionEnergy)
            3 -> pulse*mix(1.5,.8,motionEnergy)
            else -> pulse*mix(3.2,2.0,motionEnergy)
        }
        transitionSeconds=musicalSeconds.coerceIn(low,high)
        if(interrupt) transitionSeconds=.24
        sceneMinSeconds=mix(5.5,12.0,1.0-max(motionEnergy,tension))
        sceneMaxSeconds=sceneMinSeconds+mix(8.0,19.0,1.0-max(motionEnergy,tension))
    }

    fun advance(seconds: Double) {
        var left = seconds.coerceIn(0.0, 0.1)
        // Фиксированный верхний шаг для устойчивых пружин при просадке FPS.
        while (left > 1e-8) { val dt = min(left, 1.0 / 120.0); stepPhysics(dt); left -= dt }
    }
    private fun stepPhysics(dt: Double) {
        scenePulseAge+=dt
        time += dt; momentAge += dt; sceneAge += dt; slashAge += dt
        val fresh = audioPresent && time - lastAudioTime < 0.35
        val target = if (fresh) { if (forceMode == 1) 0.0 else if (forceMode == 2) 1.0 else targetTension } else 0.0
        tension = follow(tension, target, dt, if (target > tension) .38 else .85)
        val motionTarget=if(!fresh) 0.0 else if(forceMode==1) .08 else if(forceMode==2) 1.0 else targetMotion
        motionEnergy=follow(motionEnergy,motionTarget,dt,if(motionTarget>motionEnergy) .18 else .65)
        accent*=exp(-dt/.22)
        audioGain = follow(audioGain, if (fresh) {if(quietTime < 1.5) 1.0 else .65} else 0.0, dt, if (fresh) 0.15 else 0.28)
        if (!fresh && moment != Moment.VOID && time - lastAudioTime > 0.8) { moment = Moment.VOID; momentAge = 0.0 }
        morph = min(1.0, morph + dt / transitionSeconds)
        effectAge+=dt
        shockAge+=dt
        if(!fresh || forceMode==1)shockStrength=follow(shockStrength,0.0,dt,.035)
        buildWeight=follow(buildWeight,if(fresh && moment==Moment.BUILDUP && forceMode!=1) 1.0 else 0.0,dt,.65)
        releaseBloom*=exp(-dt/.48)
        glitch *= exp(-dt / .16)
        bladeTeeth *= exp(-dt / .43)
        tremor *= exp(-dt / 0.16)
        zoom=1.0+glitch*0.15+tremor*0.05
        slowSplit=follow(slowSplit,if(fresh && time<slowSplitUntil) 1.0 else 0.0,dt,
            if(time<slowSplitUntil) .55 else .75)
        cameraVx += (-95.0 * cameraX - 16.0 * cameraVx) * dt
        cameraVy += (-95.0 * cameraY - 16.0 * cameraVy) * dt
        cameraX = (cameraX + cameraVx * dt).coerceIn(-2.2, 2.2)
        cameraY = (cameraY + cameraVy * dt).coerceIn(-2.2, 2.2)
        for (b in 0..2) {
            val l = layers[b]
            if (pending[b] > 0) {
                val kick = pending[b]
                // Ощутимый ход на 25x25 даже до того, как медленная оценка tension успела вырасти.
                val power = if (forceMode == 1) .24 else .22 + motionEnergy*.70 + tension*.22
                l.springVelocity = (l.springVelocity + kick * 43.0 * power).coerceAtMost(58.0)
                l.angularKick = (l.angularKick + l.direction * kick * (1.8 + motionEnergy * 6.5) * power).coerceIn(-8.0, 8.0)
                pending[b] = 0.0
            }
            l.springVelocity += (-185.0 * l.displacement - 20.0 * l.springVelocity) * dt
            l.displacement = (l.displacement + l.springVelocity * dt).coerceIn(-0.65, 2.4)
            l.hit *= exp(-dt / (if (b == 2) 0.11 else 0.19))
            val direction = l.direction
            val carrier=if(pattern==5) .12+.88*elements[0].melodicFlow else 1.0
            val speed = direction * (.08 + motionEnergy*(.45+l.salience*1.6) + tension*.45 + max(0.0,l.drive)*motionEnergy*.7)*carrier
            l.velocity = follow(l.velocity, speed, dt, 0.18)
            // Spin never reads the shape tape. Scratching only reverses morph time.
            l.phase += (l.velocity+l.angularKick)*dt*audioGain
            l.orbitPhase+=(l.velocity+l.direction*l.burst*(2.0+motionEnergy*13.0))*dt*audioGain
            if(l.rewinding && fresh && forceMode!=1) {
                val progress=unit((time-l.rewindStart)/l.rewindDuration)
                restoreMotion(l,l.rewindStart-ease(progress)*l.rewindDistance)
                if(progress>=1.0) {
                    l.rewinding=false; l.historyCount=0; l.historyHead=0
                    l.shapeVelocity=0.0; l.historySaved=-10.0
                }
            } else {
                if(l.rewinding) { l.rewinding=false; l.historyCount=0; l.historyHead=0 }
                val shapeSpeed=l.shapeDirection*(.15+motionEnergy*.75+l.hit*.55+max(0.0,l.drive)*.30)
                l.shapeVelocity=follow(l.shapeVelocity,shapeSpeed,dt,.22)
                l.shapePhase+=l.shapeVelocity*dt*audioGain
                l.shapeTravel+=abs(l.shapeVelocity)*dt*audioGain
                if(time-l.historySaved>=.04) {
                    val i=l.historyHead
                    l.historyTime[i]=time; l.historyShape[i]=l.shapePhase
                    l.historyHead=(i+1)%256; l.historyCount=min(256,l.historyCount+1); l.historySaved=time
                }
            }
            l.burst*=exp(-dt/(.18+motionEnergy*.35))
            l.angularKick *= exp(-dt / 0.12)
        }
        for (i in elements.indices) {
            if(pattern==0 && i<3) continue
            val el=elements[i]
            el.elasticAccent.advance(dt,fresh && forceMode!=1)
            el.attackEdge*=exp(-dt/.22)
            val active=i<sceneChannels[pattern].size
            val lightAim=(.74+el.activity*.24+el.contour*.18+el.hit*.24).coerceIn(.58,1.20)
            el.light=follow(el.light,lightAim,dt,if(lightAim>el.light) .035 else .19)
            el.brake=follow(el.brake,if(fresh && time<el.pauseUntil) .025 else 1.0,dt,if(time<el.pauseUntil) .016 else .11)
            val bladeFollower=pattern==6 && (i==1 || i==2)
            val source=if(active) sceneChannels[pattern][i] else 5
            if(bladeFollower) {
                val master=elements[0]
                el.phase=master.phase;el.velocity=master.velocity;el.direction=master.direction
                el.shapePhase=master.shapePhase;el.shapeVelocity=master.shapeVelocity
                el.shapeDirection=master.shapeDirection;el.hit=master.hit
            } else {
                val orbitBurst=if(pattern==5 && i==1) el.hit*(3.0+motionEnergy*9.0) else 0.0
                val intent=if(active && fresh) (.12+motionEnergy*(.30+el.activity)+
                    max(0.0,el.contour)*.55+el.hit*.6+orbitBurst+
                    el.melodicFlow*(.28+.50*unit((previousRegister-5.8)/2.0))) else 0.0
                val weight=if(source<2) 1.10 else if(source<4) .85 else 1.35
                val carrier=(if(forceMode==1) .22 else .06+.94*el.melodicFlow)*(1-.50*calmWeight)
                el.velocity=follow(el.velocity,el.direction*styledSpin(i,intent*weight)*carrier,dt,spinResponse(i))
                val turn=el.phraseTurn.advance(dt,fresh)
                el.phase+=((el.velocity+el.angularKick)*dt*el.brake+turn)*audioGain
                el.angularKick*=exp(-dt/.13)
                val shapePace=when(pattern){4->.65;7->.85+motionEnergy*1.35*(1-.70*melodicWeight)*(1-.8*calmWeight);9->.85;6->1.15;else->1.0}
                val shapeIntent=if(active && fresh) (.025+el.melodicFlow*(.14+motionEnergy*(.35+el.activity))+
                    abs(el.registerMotion)*.55+abs(el.contour)*.22)*shapePace else 0.0
                el.shapeVelocity=follow(el.shapeVelocity,el.shapeDirection*shapeIntent*(1-.60*calmWeight),dt,.25)
                val shapeStep=el.shapeTurn.advance(dt,fresh)
                if(el.rewinding && fresh && forceMode!=1) {
                    val u=ease((time-el.rewindStart)/el.rewindDuration)
                    restoreElementShape(el,el.rewindStart-u*el.rewindDistance)
                    if(time-el.rewindStart>=el.rewindDuration) {
                        el.rewinding=false;el.tapeCount=0;el.tapeHead=0;el.tapeSaved=-10.0
                    }
                } else {
                    if(el.rewinding){el.rewinding=false;el.tapeCount=0;el.tapeHead=0}
                    el.shapePhase+=(el.shapeVelocity*dt+shapeStep)*audioGain*el.brake
                    if(active && fresh) {
                        el.shapeTravel+=(abs(el.shapeVelocity)*dt+abs(shapeStep))*audioGain*el.brake
                        el.shapeMin=min(el.shapeMin,el.shapePhase);el.shapeMax=max(el.shapeMax,el.shapePhase)
                        if(pattern==7 && i==0 && el.shapeMax-el.shapeMin>=8.0)el.completedCycle=true
                        if(time-el.tapeSaved>=.04) {
                            el.shapeTapeTime[el.tapeHead]=time;el.shapeTapeValue[el.tapeHead]=el.shapePhase
                            el.tapeHead=(el.tapeHead+1)%160;el.tapeCount=min(160,el.tapeCount+1);el.tapeSaved=time
                        }
                    }
                }
                el.hit*=exp(-dt/el.hitRelease)
            }
            val dip=i==hiddenElement && time<el.dipUntil
            val flashing=time<el.flashUntil
            el.presence=follow(el.presence,if(flashing) el.flashFloor else if(dip) .08 else 1.0,dt,
                if(flashing) .012 else if(time<el.flashUntil+.15) .035 else if(dip) .13 else .23)
            val contracting=active && pattern!=7 && fresh && time<el.radialHold
            val radialAim=if(contracting) el.radialTarget else 0.0
            val stiffness=if(el.gestureSource==2) 105.0 else 240.0
            el.radialVelocity+=(stiffness*(radialAim-el.radial)-12.0*el.radialVelocity)*dt
            el.radial=(el.radial+el.radialVelocity*dt).coerceIn(-.32,.22)
            val radiusTarget=when {
                dip -> .70
                else -> {
                    val softness=if(forceMode==1) .28 else 1.0-.72*calmWeight
                    1.0+el.radial+softness*(el.contour*.10+el.registerMotion*.08)
                }
            }
            el.scale=if(bladeFollower) elements[0].scale
                else follow(el.scale,radiusTarget,dt,.045)
            if(i==hiddenElement && time>el.dipUntil && el.presence>.97) hiddenElement=-1
        }
        if(pattern==0) shuriken.advance(dt,time,fresh,audioGain,forceMode==1)
        // Метаморфоза теперь тоже обратима; отдельный вечный ход вперёд больше не маскирует rewind.
        melodyPhase = layers[1].shapePhase * 0.55
    }

    private fun restoreElementShape(el:Element,target:Double) {
        if(el.tapeCount==0)return
        var previous=(el.tapeHead-el.tapeCount+160)%160
        for(n in 1 until el.tapeCount) {
            val current=(previous+1)%160
            if(el.shapeTapeTime[current]>=target) {
                val f=unit((target-el.shapeTapeTime[previous])/max(1e-6,el.shapeTapeTime[current]-el.shapeTapeTime[previous]))
                el.shapePhase=mix(el.shapeTapeValue[previous],el.shapeTapeValue[current],f);return
            }
            previous=current
        }
        el.shapePhase=el.shapeTapeValue[previous]
    }

    private fun resetGesture(el:Element) {
        el.shapeTurn.advance(.02,false)
        el.elasticAccent.reset();el.attackEdge=0.0
        el.phraseTurn.reset();el.melodicFlow=0.0
        el.light=1.0;el.brake=1.0;el.pauseUntil=-10.0;el.lastPause=-10.0;el.pauseArmed=false
        el.flashFloor=.12;el.rawLevel=0.0;el.blinkPendingAt=-10.0;el.blinkFromShare=0.0
        el.eventSamples=0;el.eventShare=0.0;el.eventRegister=0.0;el.eventTexture=0.0
        el.turnReadyUntil=-10.0;el.lastContraction=-10.0;el.hitRelease=.19
        el.rewinding=false;el.tapeCount=0;el.tapeHead=0;el.tapeSaved=-10.0
        el.shapeMin=el.shapePhase;el.shapeMax=el.shapePhase
        el.contour=0.0;el.activity=0.0;el.radial=0.0;el.radialVelocity=0.0
        el.radialTarget=0.0;el.radialHold=-1.0;el.gestureSource=0;el.registerMotion=0.0
    }

    private fun restoreMotion(l: Layer, target: Double) {
        val oldest = (l.historyHead - l.historyCount + 256) % 256
        var previous = oldest
        for (n in 1 until l.historyCount) {
            val current = (oldest + n) % 256
            if (l.historyTime[current] >= target) {
                val fraction = unit((target - l.historyTime[previous]) /
                    max(1e-8, l.historyTime[current] - l.historyTime[previous]))
                l.shapePhase = mix(l.historyShape[previous], l.historyShape[current], fraction)
                return
            }
            previous = current
        }
        l.shapePhase = l.historyShape[previous]
    }
}

/** Scene 0: distinct musical envelopes for body, orbit and centre.
 * The phrase gesture is an inference from reference editing, not a beat clock.
 */
/** Tracks rises after real troughs in the low/mid voice, including constant-RMS filter sweeps.
 * No BPM clock: holding a note or losing audio cannot synthesize extra cycles. */
/** Critically damped finite gesture. Retriggers retain velocity, cap outstanding
 * travel and cannot accumulate a backlog during rolls. The settled pose holds. */
/** Bounded audio impulse: squash, elastic release, settle. Exact integration
 * keeps the same response at different renderer cadences. */
internal class ElasticAccent {
    var value=0.0; private set
    var velocity=0.0; private set
    fun reset(){value=0.0;velocity=0.0}
    fun trigger(strength:Double){velocity=(velocity-strength.coerceIn(0.0,.2)*32).coerceIn(-5.0,5.0)}
    fun advance(dt:Double,audible:Boolean) {
        if(!audible){reset();return}
        val damping=9.0;val frequency=sqrt(24.0*24.0-damping*damping)
        val a=value;val b=(velocity+damping*a)/frequency
        val c=cos(frequency*dt);val s=sin(frequency*dt);val decay=exp(-damping*dt)
        value=decay*(a*c+b*s)
        velocity=decay*((b*frequency-damping*a)*c-(a*frequency+damping*b)*s)
        if(abs(value)>.18) {value=value.coerceIn(-.18,.18);velocity=0.0}
        if(abs(value)<1e-6 && abs(velocity)<1e-5)reset()
    }
}

internal class PhraseTurn {
    var remaining=0.0; private set
    var velocity=0.0; private set
    var count=0; private set
    private var tau=.045
    fun reset(){remaining=0.0;velocity=0.0;count=0}
    fun trigger(angle:Double,seconds:Double){
        remaining=(remaining+angle).coerceIn(-1.25,1.25)
        tau=seconds.coerceIn(.025,.09);count++
    }
    fun advance(dt:Double,audible:Boolean):Double {
        if(!audible){remaining=0.0;velocity=0.0;return 0.0}
        val old=remaining;val omega=1.0/tau
        val c=-velocity+omega*old;val decay=exp(-omega*dt)
        remaining=(old+c*dt)*decay
        velocity=(velocity+omega*c*dt)*decay
        if(abs(remaining)<1e-5 && abs(velocity)<1e-4){remaining=0.0;velocity=0.0}
        return old-remaining
    }
}

class BassCycleTracker {
    var count=0; private set
    var strength=0.0; private set
    var period=.45; private set
    var confidence=0.0; private set
    var shape=0.0; private set
    private var mean=0.0
    private var smooth=0.0
    private var trough=0.0
    private var peak=0.0
    private var last=-10.0
    private var age=0.0
    private var armed=true
    private var ready=false
    fun reset() {
        mean=0.0;smooth=0.0;trough=0.0;peak=0.0;last=-10.0;age=0.0
        armed=true;ready=false;strength=0.0;confidence=0.0;period=.45
    }
    fun observe(f:AudioFeatures,dt:Double,time:Double):Boolean {
        age+=dt
        val voice=f.subEnergy[1]+f.subEnergy[2]+f.subEnergy[3]
        val level=f.level*sqrt(voice.coerceAtLeast(0.0))
        shape=(.15*f.subEnergy[1]+.50*f.subEnergy[2]+f.subEnergy[3])/max(voice,1e-9)
        if(!ready) {mean=level;ready=true}
        mean=follow(mean,level,dt,.8)
        val drive=shape+.28*ln((level+1e-9)/(mean+1e-9)).coerceIn(-2.0,2.0)
        if(age<=dt*1.5) {smooth=drive;trough=drive;peak=drive}
        val previousSmooth=smooth
        smooth=follow(smooth,drive,dt,.025)
        if(time-last>max(.65,period*2.4)) confidence=follow(confidence,0.0,dt,.25)
        if(!armed) {
            peak=max(peak,smooth)
            if(peak-smooth>.075) {armed=true;trough=smooth}
        }
        if(armed) trough=min(trough,smooth)
        val rise=smooth-trough
        val bodyPresent=voice>.13 && f.bodyShare>.30 && f.level>1e-7
        if(age>.35 && bodyPresent && armed && rise>.105 && time-last>.16) {
            val interval=time-last
            if(interval in .17..1.5) {
                val consistent=confidence<.05 || abs(interval-period)<max(.10,period*.55)
                confidence=if(consistent) unit(confidence+.28) else confidence*.35
                period=if(confidence<.30) interval else mix(period,interval,.35)
            } else confidence=0.0
            // More than a threshold crossing: include current rise speed for sharp syllables.
            strength=unit(.35+4.0*max(0.0,smooth-previousSmooth)*(.05/dt.coerceIn(.005,.2)))
            count++;last=time;armed=false;peak=smooth
            return true
        }
        return false
    }
}

class ShurikenMotion(private val elements:Array<VjEngine.Element>) {
    internal var learnedFlow=-1.0
    internal var learnedConfidence=0.0
    var opening=.25; private set
    var cross=0.0; private set
    var crossCount=0; private set
    val offsetX=DoubleArray(3)
    val offsetY=DoubleArray(3)
    var glitchAmount=0.0; private set
    var glitchMode=0; private set
    var gestureCount=0; private set
    var glitchCount=0; private set
    var travelWeight=0.0; private set
    var bodyZoom=1.0; private set
    var orbitRate=0.0; private set
    var specialCount=0; private set
    var mainMirror=1.0; private set
    var centerMirror=1.0; private set
    var mirrorCount=0; private set
    var drumCount=0; private set
    var drumPeriod=.20; private set
    var rollConfidence=0.0; private set
    var flashAmount=0.0; private set
    var flashEntity=2; private set
    var expressiveness=0.0; private set
    val bassCycle=BassCycleTracker()
    val rotationDirection get()=elements[0].direction
    val wubCount get()=bassCycle.count
    val wubConfidence get()=bassCycle.confidence
    val wubPeriod get()=bassCycle.period
    var wubAngle=0.0; private set
    var spiralAmount=0.0; private set
    var spiralCount=0; private set
    var travelCount=0; private set
    var clockWeight=0.0; private set
    var clockSteps=0; private set
    var balancedTurns=0; private set
    private var clockTexture=0.0
    private var clockReady=false
    private var clockActive=false
    private var clockBodyActive=false
    private var clockNovelty=0.0
    private var clockUntil=-10.0
    private var clockStart=-10.0
    private val clockFrom=DoubleArray(2)
    private val clockTo=DoubleArray(2)
    private var lastBalancedTurn=-10.0
    var melodicWeight=0.0; private set
    var melodicSpeed=0.0; private set
    var tremorAmount=0.0; private set
    var tremorCount=0; private set
    var melodicPhraseCount=0; private set
    val ringScale get()=elements[1].scale
    val centerScale get()=elements[2].scale
    private var toneSmooth=0.0; private var continuitySmooth=0.0
    private var registerFast=0.0; private var registerSlow=0.0
    private var melodicLevel=0.0; private var melodicLevelSlow=0.0
    private var melodicAccent=0.0; private var phraseArmed=true
    private var lastMelodicPhrase=-10.0
    private var tremorUntil=-10.0
    private var lastWub=-10.0
    private var wubUntil=-10.0
    private var spiralStart=-10.0
    private var spiralDuration=.7
    private var spiralPower=0.0
    private var lastSpiral=-10.0
    private var lastTravel=-10.0
    private var priorWubPower=0.0
    private var priorWubShape=0.0
    private var ringEnvelope=0.0
    private var voiceRichness=0.0
    private var rhythmicMotion=0.0
    private var lastMirror=-10.0
    private var mirrorAccents=0
    private val drumFast=DoubleArray(4)
    private val drumSlow=DoubleArray(4)
    private var lastDrum=-10.0
    private var drumArmed=true
    private var previousDrumScore=0.0
    private var fastIntervals=0
    private var flashUntil=-10.0
    private var flashPower=0.0
    private var specialArmed=true
    private var lastSpecial=-10.0
    private var highSustain=0.0
    private val goalPhase=DoubleArray(3)
    private val moveX=DoubleArray(3)
    private val moveY=DoubleArray(3)
    private val moveUntil=DoubleArray(3)
    private var roughness=0.0
    private var novelty=0.0
    private var burstStart=-10.0
    private var burstPower=0.0
    private var lastBurst=-10.0
    private var burstArmed=true
    private var pulseTime=-10.0
    private var pulsePeriod=.45
    private val activity=DoubleArray(3)
    private val kick=DoubleArray(3)
    private val mean=DoubleArray(3) { .2 }
    private val variance=DoubleArray(3) { .02 }
    private val holdUntil=DoubleArray(3)
    private val gap=DoubleArray(3)
    private val seenGap=BooleanArray(3)
    private val onsetArmed=BooleanArray(3) { true }
    private var age=0.0
    private var phraseFast=0.0; private var phraseSlow=0.0
    private var phraseRise=0.0; private var phraseRecovery=0.0
    private var crossArmed=true; private var crossUntil=-1.0
    private var lastCross=0.0; private var lastDip=-10.0
    private var hidden=-1; private var openingTarget=.25

    fun reset(time:Double) {
        offsetX.fill(0.0);offsetY.fill(0.0);moveX.fill(0.0);moveY.fill(0.0);moveUntil.fill(time)
        for(i in 0..2) goalPhase[i]=elements[i].phase
        roughness=0.0;novelty=0.0;travelWeight=0.0;glitchAmount=0.0;bodyZoom=1.0;orbitRate=0.0;highSustain=0.0;specialArmed=true;lastSpecial=time
        burstStart=-10.0;lastBurst=time;burstArmed=true;pulseTime=time;pulsePeriod=.45
        activity.fill(0.0);kick.fill(0.0);mean.fill(.2);variance.fill(.02)
        holdUntil.fill(time);gap.fill(0.0);seenGap.fill(false);onsetArmed.fill(true)
        age=0.0;phraseFast=0.0;phraseSlow=0.0;phraseRise=0.0;phraseRecovery=0.0
        crossArmed=true;crossUntil=time;lastCross=time;lastDip=time;hidden=-1
        opening=.25;openingTarget=.25;cross=0.0
        mainMirror=1.0;centerMirror=1.0;lastMirror=time;mirrorAccents=0
        drumFast.fill(0.0);drumSlow.fill(0.0);lastDrum=-10.0;drumArmed=true
        previousDrumScore=0.0;fastIntervals=0;drumPeriod=.20
        rollConfidence=0.0;flashAmount=0.0;flashUntil=time;flashPower=0.0
        expressiveness=0.0;rhythmicMotion=0.0
        clockWeight=0.0;clockTexture=0.0;clockReady=false;clockActive=false;clockBodyActive=false;clockNovelty=0.0;clockUntil=time;clockStart=time;lastBalancedTurn=time
        for(i in 0..1) {clockFrom[i]=elements[i].phase;clockTo[i]=elements[i].phase}
        melodicWeight=0.0;melodicSpeed=0.0;tremorAmount=0.0;tremorUntil=time
        toneSmooth=0.0;continuitySmooth=0.0;registerFast=0.0;registerSlow=0.0
        melodicLevel=0.0;melodicLevelSlow=0.0;melodicAccent=0.0
        phraseArmed=true;lastMelodicPhrase=time
        bassCycle.reset();lastWub=time-10;wubUntil=time;wubAngle=0.0
        spiralStart=time-10;lastSpiral=time;spiralAmount=0.0;spiralPower=0.0
        lastTravel=time;priorWubPower=0.0;priorWubShape=0.0;ringEnvelope=0.0;voiceRichness=0.0
    }
    fun observe(f:AudioFeatures,dt:Double,time:Double,calm:Boolean,heaviness:Double,motion:Double) {
        age+=dt
        roughness=follow(roughness,f.bodyTexture,dt,.65)
        val expressionTarget=if(calm) 0.0 else ease((heaviness-.12)/.60)
        expressiveness=follow(expressiveness,expressionTarget,dt,if(expressionTarget>expressiveness) .28 else .75)
        rhythmicMotion=follow(rhythmicMotion,if(calm) .08 else motion,dt,.22)
        observeMelodic(f,dt,time,calm)
        observeDrums(f,dt,time,calm)
        voiceRichness=follow(voiceRichness,(f.subEnergy[1]+f.subEnergy[2]+f.subEnergy[3])/max(f.bodyShare,1e-9),dt,.45)
        if(!clockReady) {clockTexture=f.bodyTexture;clockReady=true}
        clockTexture=follow(clockTexture,f.bodyTexture,dt,2.0)
        clockNovelty=follow(clockNovelty,f.novelty,dt,2.0)
        val clockTarget=if(calm) 0.0 else ease((.64-clockTexture)/.16)*ease((bassCycle.confidence-.25)/.40)*ease((.85-expressiveness)/.30)
        clockWeight=follow(clockWeight,clockTarget,dt,if(clockTarget>clockWeight) .35 else .70)
        if(clockWeight>.55 && !clockActive) {
            clockActive=true
            for(i in 0..1) {clockFrom[i]=elements[i].phase;clockTo[i]=elements[i].phase}
            clockStart=time;clockUntil=time
        } else if(clockWeight<.25) clockActive=false
        if(clockActive && clockNovelty>.14 && !clockBodyActive) {
            clockBodyActive=true;clockFrom[0]=elements[0].phase;clockTo[0]=elements[0].phase
        } else if(!clockActive || clockNovelty<.13) clockBodyActive=false
        observeBassVoice(f,dt,time,calm)
        ringEnvelope=follow(ringEnvelope,unit(.65*f.flux[2]+.35*f.flux[1]),dt,.065)
        val noveltyRate=f.novelty*sqrt(.02/dt.coerceIn(.005,.2))
        novelty=follow(novelty,noveltyRate,dt,.65)
        travelWeight=unit((roughness-.30)/.55)*(1.0-unit((novelty-.08)*5.0))
        val spectralEdge=max(f.flux[1],f.flux[2])
        highSustain=follow(highSustain,unit(f.energy[2]+.30*f.energy[1])*(if(f.level>1e-5) 1.0 else 0.0),dt,.22)
        val special=noveltyRate>.21 && f.flux[1]>.65 && f.flux[2]>.55
        if(noveltyRate<.065 && f.flux[1]<.30) specialArmed=true
        if(special && specialArmed && time-lastSpecial>.40 && !calm) {
            specialArmed=false;lastSpecial=time;specialCount++
            val power=unit(.35+f.flux[1]*.55+noveltyRate)
            if(!clockActive) goalPhase[1]+=elements[1].direction*(.22+.35*power)
            elements[1].lastOnset=time
            elements[1].hit=max(elements[1].hit,power*.85)
            // A timbral accent can also move the blades, even without a bass hit.
            if(!clockBodyActive && time-elements[0].lastOnset>.16) {
                goalPhase[0]+=elements[0].direction*(.10+.15*power)
                elements[0].lastOnset=time
            }
            mirrorAccents++
            if(expressiveness>.28 && mirrorAccents>=2 && time-lastMirror>max(.42,drumPeriod*2.0)) mirrorMain(time)
        }
        if(spectralEdge<.28 || noveltyRate<.05) burstArmed=true
        // Clear gaps are mandatory even through continuously noisy material.
        if(!calm && age>.5 && burstArmed && time-lastBurst>.48 &&
            novelty>.09 && spectralEdge>.44 && noveltyRate>.13 && f.energy[2]>.10 &&
            ((roughness>.48 && expressiveness>.35) || expressiveness>.90)) {
            burstArmed=false;lastBurst=time;burstStart=time
            burstPower=unit(.52+.65*spectralEdge+1.0*noveltyRate)
            goalPhase[0]+=elements[0].direction*(.10+.16*burstPower)
            elements[0].lastOnset=time
            glitchMode=glitchCount%5;glitchCount++
        }
        for(i in 0..2) {
            val el=elements[i]
            val share=unit(when(i) {
                0 -> .55*f.subEnergy[0]+f.subEnergy[1]+.75*f.subEnergy[2]
                1 -> .55*f.subEnergy[4]+f.subEnergy[5]
                else -> .40*f.subEnergy[2]+f.subEnergy[3]
            })
            val level=f.level*sqrt(share)
            if(el.peak<1e-9) {el.fast=level;el.slow=level;el.peak=level}
            el.fast=follow(el.fast,level,dt,if(level>el.fast) .04 else .13)
            el.slow=follow(el.slow,level,dt,2.0)
            el.peak=follow(el.peak,max(level,el.fast),dt,1.4)
            val flux=when(i) {0 -> max(f.flux[0],f.flux[1]*.8);1 -> f.flux[2];else -> f.flux[1]}
            val attack=unit((level-el.slow)/max(el.slow,1e-8))
            val threshold=(mean[i]+sqrt(variance[i])*.55).coerceIn(.20,.65)
            val delta=flux-mean[i]
            mean[i]=follow(mean[i],flux,dt,1.5)
            variance[i]=follow(variance[i],delta*delta,dt,1.5)
            if(flux<threshold*.60 || attack<.035) onsetArmed[i]=true
            if(age>.35 && share>.003 && attack>.07 && flux>threshold && onsetArmed[i] &&
                time-el.lastOnset>(if(i==1) .12 else .18)) {
                onsetArmed[i]=false
                val strength=unit(.65*flux+.55*attack)
                val power=if(calm) .35 else .55+.45*expressiveness
                if((i!=0 || melodicWeight<.45) && time-el.lastTurn>(if(i==1) 10.0 else 4.0) && seenGap[i] && el.accents>=(if(i==1) 8 else 3)) {
                    if(i!=0) {el.direction=-el.direction;el.lastTurn=time;el.turnCount++}
                    else requestBalancedTurn(time)
                    el.accents=0;kick[i]=0.0
                }
                seenGap[i]=false;el.lastOnset=time;el.onsetCount++;el.accents++
                el.hit=max(el.hit,strength*power)
                activity[i]=unit(activity[i]+.40*strength)*power.coerceAtLeast(.65)
                kick[i]=el.direction*strength*(if(i==1) 3.1 else 1.35)*power
                holdUntil[i]=time+(.055+.085*strength)*(if(calm) 1.5 else 1.0)
                // Musical angular destination: fast travel, then a real dwell.
                if((i==0 && !clockBodyActive) || (i==1 && !clockActive) || i==2) goalPhase[i]+=el.direction*(if(i==1) .10+.35*strength else .055+.11*strength)*power
                if(i==0) {
                    val interval=time-pulseTime
                    if(interval in .18..1.2) pulsePeriod=follow(pulsePeriod,interval,.1,.25)
                    pulseTime=time;gestureCount++
                    // Translation is scheduled by a phrase contrast in observeBassVoice,
                    // not by cycling directions on every ordinary bass attack.
                    // Strong body accents can mirror a sustained bass phrase.
                    if(!calm && expressiveness>.48 && strength>.70) {
                        mirrorAccents++
                        if(mirrorAccents>=3 && time-lastMirror>max(.55,pulsePeriod*1.5)) mirrorMain(time)
                    }
                }
            }
            val missing=el.peak>max(1e-7,f.level*.025) && el.fast<el.peak*.25
            gap[i]=if(missing) gap[i]+dt else 0.0
            if(gap[i]>.18) seenGap[i]=true
            if((i!=0 || melodicWeight<.45) && gap[i]>.22 && hidden<0 && el.armed && age>2 && time-lastDip>2.8) {
                hidden=i;lastDip=time;el.dipUntil=time+.35+.25*unit(gap[i]);el.dipCount++;el.armed=false
            }
            if(!missing && el.fast>el.peak*.60) el.recoveredFor+=dt else el.recoveredFor=0.0
            if(el.recoveredFor>.45) el.armed=true
        }
        val body=f.level*sqrt(unit(f.subEnergy[0]+f.subEnergy[1]+f.subEnergy[2]))
        if(phraseSlow<1e-9) {phraseSlow=body;phraseFast=body}
        phraseFast=follow(phraseFast,body,dt,.28);phraseSlow=follow(phraseSlow,body,dt,4.0)
        val contrast=phraseFast/max(phraseSlow,1e-8)
        phraseRise=if(contrast>1.30) phraseRise+dt else 0.0
        phraseRecovery=if(contrast<1.05) phraseRecovery+dt else 0.0
        if(phraseRecovery>.55) crossArmed=true
        if(phraseRise>.18 && activity[0]>.20 && time-lastCross>2.0 && age>1.5 && crossArmed) {
            crossUntil=time+.30+.55*unit(contrast-1)+.30*activity[0]
            lastCross=time;crossCount++;crossArmed=false
        }
        openingTarget=(.20+(.22*unit(contrast-.7)+.45*activity[0]+.35*elements[0].hit)*
            (if(calm) .70 else .60+.40*expressiveness))
            .coerceIn(.20,if(calm) .48 else 1.0)
    }
    private fun observeMelodic(f:AudioFeatures,dt:Double,time:Double,calm:Boolean) {
        toneSmooth=follow(toneSmooth,f.tonalContrast,dt,.8)
        continuitySmooth=follow(continuitySmooth,f.spectralContinuity,dt,.8)
        val evidence=ease((toneSmooth-.25)/.09)*ease((continuitySmooth-.69)/.08)
        var target=if(f.level>1e-7) ease((evidence-.30)/.35) else 0.0
        if(f.level>1e-7 && learnedFlow>=0 && !calm)target=mix(target,learnedFlow,learnedConfidence)
        melodicWeight=follow(melodicWeight,target,dt,if(target>melodicWeight) .65 else 1.4)
        if(registerSlow==0.0 && f.leadRegister>0.0) {registerFast=f.leadRegister;registerSlow=f.leadRegister}
        if(f.leadRegister>0.0) {
            registerFast=follow(registerFast,f.leadRegister,dt,.24)
            registerSlow=follow(registerSlow,f.leadRegister,dt,2.5)
        }
        if(melodicLevelSlow<1e-9) {melodicLevel=f.level;melodicLevelSlow=f.level}
        melodicLevel=follow(melodicLevel,f.level,dt,.20)
        melodicLevelSlow=follow(melodicLevelSlow,f.level,dt,3.0)
        val lift=unit((registerFast-registerSlow)/.32)
        val swell=unit((melodicLevel/max(1e-9,melodicLevelSlow)-1.0)/.65)
        melodicAccent=follow(melodicAccent,max(lift,swell),dt,.20)
        // A continuous carrier follows register/phrase energy, never a fixed timer.
        melodicSpeed=(.55+.95*unit((registerFast-6.3)/1.6)+1.2*melodicAccent)*(if(calm) .55 else 1.0)
        if(melodicAccent<.22) phraseArmed=true
        if(melodicWeight>.60 && !clockBodyActive && melodicAccent>.55 && phraseArmed && time-lastMelodicPhrase>6.0) {
            if(requestBalancedTurn(time)) {
                phraseArmed=false;lastMelodicPhrase=time;melodicPhraseCount++
                elements[1].direction=-elements[1].direction
                centerMirror=-centerMirror
            }
        }
    }
    private fun requestBalancedTurn(time:Double):Boolean {
        if(time-lastBalancedTurn<8.0 || time-lastMirror<8.0 || spiralAmount>.05) return false
        val el=elements[0]
        el.direction=-el.direction;el.lastTurn=time;el.turnCount++
        goalPhase[0]=el.phase
        lastBalancedTurn=time;balancedTurns++
        // Do not mirror here: changing direction and handedness together cancels
        // the apparent reversal on screen. A clock phrase keeps its step timing.
        return true
    }

    private fun observeBassVoice(f:AudioFeatures,dt:Double,time:Double,calm:Boolean) {
        if(!bassCycle.observe(f,dt,time)) return
        val power=bassCycle.strength
        val confident=bassCycle.confidence
        val character=ease((expressiveness-.10)/.80)*(.20+.80*ease((voiceRichness-.18)/.40))
        val shapeContrast=abs(bassCycle.shape-priorWubShape)
        val powerContrast=abs(power-priorWubPower)
        val waited=time-lastBalancedTurn
        val phraseAccent=shapeContrast>(if(waited>12.0) .07 else .14) || powerContrast>(if(waited>12.0) .12 else .24)
        if(!calm && confident>.30 && power>.55 && phraseAccent) requestBalancedTurn(time)
        val angle=(24.0+156.0*power*character*(.55+.45*confident))*PI/180.0
        wubAngle=if(clockBodyActive) (if(power>.8 && expressiveness>.45) 45.0 else 30.0)*PI/180.0
            else (if(calm) min(angle,.30) else angle)*(1.0-melodicWeight)
        if(clockActive) {clockSteps++;clockStart=time;clockUntil=time+(bassCycle.period*.34).coerceIn(.12,.18)}
        // Replace unfinished intent rather than adding a backlog of full turns.
        goalPhase[0]=elements[0].phase+elements[0].direction*wubAngle
        lastWub=time;wubUntil=time+min(.58,max(.32,bassCycle.period*.90))
        goalPhase[1]=elements[1].phase+elements[1].direction*(if(clockActive) PI/6.0 else (.18+.60*power)*(if(calm) .4 else 1.0))
        elements[1].lastOnset=time
        if(clockActive) for(i in 0..1) {clockFrom[i]=elements[i].phase;clockTo[i]=goalPhase[i]}
        if(melodicWeight>.35) {
            elements[1].hit=max(elements[1].hit,power*melodicWeight)
            elements[2].hit=max(elements[2].hit,power*melodicWeight)
            elements[2].lastOnset=time
            goalPhase[2]+=elements[2].direction*power*melodicWeight*.9
            moveX[2]=(if(bassCycle.shape>priorWubShape) 1.0 else -1.0)*.45*power*melodicWeight
            moveY[2]=0.0;moveUntil[2]=time+.09
        }
        val contrast=abs(power-priorWubPower)>.20 || abs(bassCycle.shape-priorWubShape)>.14
        val textured=f.bodyTexture>.30 && character>.38
        // A change within an established voice can curl inward, then release.
        if(!calm && melodicWeight<.35 && expressiveness<.85 && confident>.30 && contrast && power>.45 && textured && time-lastSpiral>4.0) {
            spiralStart=time;lastSpiral=time;spiralDuration=(bassCycle.period*1.5).coerceIn(.48,1.0)
            spiralPower=unit(.35+.65*power);spiralCount++
        }
        // One stable axis chosen by the actual spectral shift, followed by a return.
        // Repetitive equal syllables remain centered; no four-direction carousel.
        if(!calm && melodicWeight<.35 && expressiveness<.85 && contrast && confident>.20 && textured && time-lastTravel>1.6 &&
            time-lastSpiral>1.1 && power>priorWubPower+.18 && power>.60) {
            val distance=(.5+1.4*power)*travelWeight
            if(distance>.25) {
                val direction=if(bassCycle.shape>=priorWubShape) 1.0 else -1.0
                moveX[0]=direction*distance;moveY[0]=0.0
                moveX[2]=moveX[0]*.75;moveY[2]=0.0
                moveX[1]=-moveX[0]*.16;moveY[1]=0.0
                for(j in 0..2) moveUntil[j]=time+min(.28,bassCycle.period*.45)
                lastTravel=time;travelCount++
            }
        }
        priorWubPower=power;priorWubShape=bassCycle.shape
    }
    private fun mirrorMain(time:Double) {
        // Let an angular accent finish before changing handedness.
        if(clockBodyActive || time-lastBalancedTurn<8.0 || time-lastMirror<8.0 || melodicWeight>.40 || expressiveness>.85 || spiralAmount>.02 || (time-lastWub<.10 && wubAngle>1.0) || time-lastMirror<1.20-.65*expressiveness) return
        mainMirror=-mainMirror
        mirrorCount++;lastMirror=time;mirrorAccents=0
        // The central silhouette has its own phrase, not every body flip.
        // Use local pose parity; lifetime diagnostic counters survive reset().
        if(mainMirror>0.0) centerMirror=-centerMirror
    }
    private fun observeDrums(f:AudioFeatures,dt:Double,time:Double,calm:Boolean) {
        var score=0.0
        for(i in 0..3) {
            val share=when(i) {
                0 -> f.subEnergy[0]+f.subEnergy[1]
                1 -> f.subEnergy[2]+f.subEnergy[3]
                2 -> f.subEnergy[4]+f.subEnergy[5]
                else -> 1.0
            }
            val level=f.level*sqrt(unit(share))
            if(drumSlow[i]<1e-9) {drumFast[i]=level;drumSlow[i]=level}
            val rise=unit((level-drumFast[i])/max(1e-9,max(drumSlow[i]*.35,drumFast[i]*.65)))
            // Envelope attacks distinguish drums from sustained spectral wobble.
            // RMS envelope remains useful when Android normalizes/quantizes
            // each FFT independently and suppresses amplitude-related flux.
            val flux=if(i==3) max(f.flux[0],max(f.flux[1],f.flux[2])) else f.flux[i]
            val edge=if(i==3) .85+.15*flux else .25+.75*flux
            if(share>.025) score=max(score,rise*edge)
            drumFast[i]=follow(drumFast[i],level,dt,if(level>drumFast[i]) .022 else .060)
            drumSlow[i]=follow(drumSlow[i],level,dt,.70)
        }
        if(score<.10) drumArmed=true
        val newEdge=drumArmed || score>previousDrumScore+.18
        // Coarse 20 Hz capture quantizes an 86 ms interval into 50/100 ms.
        // A fixed 55 ms refractory would discard half the real attacks.
        val separation=max(.035,.055-dt*.35)
        if(age>.35 && !calm && score>.30 && newEdge && time-lastDrum>separation) {
            drumArmed=false;drumCount++
            val interval=time-lastDrum
            if(interval in max(.035,.060-dt*.5).. .31) {
                val consistent=fastIntervals==0 || abs(interval-drumPeriod)<max(drumPeriod*.55,dt*1.25)
                fastIntervals=if(consistent) fastIntervals+1 else 1
                drumPeriod=if(fastIntervals==1) interval else mix(drumPeriod,interval,.32)
            } else fastIntervals=0
            lastDrum=time
            val trebleAttack=f.subEnergy[4]+f.subEnergy[5]>.07 && f.flux[2]>.50
            if(fastIntervals>=2 && rhythmicMotion>.18 && (expressiveness>.20 || trebleAttack)) {
                rollConfidence=unit((fastIntervals-1)/3.0)
                // A bright hi-hat in a gentle phrase should pulse, not black out
                // the whole silhouette. Full contrast builds with a confirmed roll.
                flashEntity=if(trebleAttack) 1 else if(melodicWeight>.35) 2 else 0
                flashPower=unit(.70+.30*score)*(.32+.68*expressiveness)*(.65+.35*rollConfidence)
                flashUntil=time+(drumPeriod*.34).coerceIn(.025,.065)
                // Confirm a repeated drum train first, then one bounded displacement
                // per detected attack. Alternating axes have zero long-term drift.
                val amount=(.45+.55*score)*rollConfidence*(1.0-.75*melodicWeight)
                val axis=fastIntervals%4
                moveX[0]=when(axis){0->amount;2->-amount;else->0.0}
                moveY[0]=when(axis){1->amount;3->-amount;else->0.0}
                moveUntil[0]=time+(drumPeriod*.32).coerceIn(.025,.060)
                tremorUntil=time+drumPeriod*1.5;tremorAmount=amount;tremorCount++
                if(!clockBodyActive) goalPhase[0]+=elements[0].direction*(.035+.045*score)
                if(fastIntervals%4==0 && time-lastMirror>max(.70,drumPeriod*4.0)) mirrorMain(time)
            }
        }
        previousDrumScore=score
    }
    fun advance(dt:Double,time:Double,fresh:Boolean,gain:Double,calm:Boolean) {
        if(!fresh || calm || time>tremorUntil) tremorAmount=follow(tremorAmount,0.0,dt,.08)
        val spiralAge=(time-spiralStart)/spiralDuration
        if(!fresh || calm) spiralStart=time-10.0
        spiralAmount=if(fresh && !calm && spiralAge in 0.0..1.0) sin(PI*spiralAge)*spiralPower else 0.0
        for(i in 0..2) {
            val el=elements[i]
            activity[i]*=exp(-dt/.85);kick[i]*=exp(-dt/(if(i==1) .17 else .13))
            el.hit*=exp(-dt/(if(i==1) .15 else .24))
            val distance=goalPhase[i]-el.phase
            val active=fresh && (time-el.lastOnset<max(.26,pulsePeriod*.70) ||
                (i==0 && time<wubUntil) ||
                (i==0 && time-lastDrum<.16 && rollConfidence>.10) || (i==1 && highSustain>.08))
            // Sustained treble keeps a small drift; attacks carry most of the orbit.
            val drift=if(i==1 && fresh && !clockActive) elements[i].direction*highSustain*
                (if(calm) .08 else .08+1.4*ringEnvelope) else 0.0
            val speedLimit=if(i==0 && time<wubUntil && !calm) 9.0 else 8.0
            val unwinding=i==0 && spiralAge>.5 && spiralAge<1.0 && spiralAmount>0.0
            val ticking=fresh && ((i==0 && clockBodyActive) || (i==1 && clockActive))
            val angularTarget=if(ticking && time>=clockUntil) 0.0 else if(active && !unwinding) (distance/(if(calm) .13 else .045)+drift).coerceIn(-speedLimit,speedLimit) else 0.0
            val flow=if(i==0 && fresh && !clockBodyActive) ease((melodicWeight-.20)/.55) else 0.0
            val target=mix(angularTarget,el.direction*melodicSpeed,flow)
            el.velocity=if(ticking && time>=clockUntil) 0.0 else follow(el.velocity,target,dt,if(flow>.5) .28 else if(abs(target)>abs(el.velocity)) .040 else .055)
            if(ticking && time>=clockUntil) goalPhase[i]=el.phase
            if(i==0 && spiralAmount>0.0) el.velocity=el.velocity.coerceIn(-12.0,12.0)
            val beforePhase=el.phase
            val delta=el.velocity*dt*gain
            if(i!=1 && flow<.01 && abs(delta)>abs(distance) && delta*distance>0) {el.phase=goalPhase[i];el.velocity=0.0}
            else el.phase+=delta
            if(flow>.01) goalPhase[i]=el.phase
            if(ticking) {
                val previous=beforePhase
                val progress=unit((time-clockStart)/max(.001,clockUntil-clockStart))
                el.phase=mix(clockFrom[i],clockTo[i],ease(progress))
                el.velocity=if(progress>=1.0) 0.0 else (el.phase-previous)/dt
                goalPhase[i]=el.phase
            }
            if(i==1) {goalPhase[i]+=drift*dt*gain;orbitRate=el.velocity}
            val holding=fresh && time<moveUntil[i]
            offsetX[i]=follow(offsetX[i],if(holding) moveX[i] else 0.0,dt,if(i==0 && tremorAmount>.05) {if(holding) .012 else .040} else if(holding) .045 else .24)
            offsetY[i]=follow(offsetY[i],if(holding) moveY[i] else 0.0,dt,if(i==0 && tremorAmount>.05) {if(holding) .012 else .040} else if(holding) .045 else .24)
            val dip=i==hidden && time<el.dipUntil && (i!=0 || melodicWeight<.45)
            el.presence=follow(el.presence,if(dip) .03 else 1.0,dt,if(dip) .09 else .22)
            val radius=1.0+el.hit*(if(i==0) .13*(1.0-melodicWeight) else if(i==1) .10-.25*melodicWeight else .10+.23*melodicWeight)-(if(dip) .12 else 0.0) - (if(i==0) hypot(offsetX[i],offsetY[i])*.055 else 0.0)
            el.scale=follow(el.scale,radius,dt,if(radius>el.scale) .045 else .23)
            if(i==hidden && time>=el.dipUntil && el.presence>.97) hidden=-1
        }
        val burstAge=time-burstStart
        val burstWindow=when {
            burstAge in 0.0..0.055 -> 1.0
            burstAge in .095..0.155 -> .90
            burstAge in .205..0.285 -> .70
            burstAge in .335..0.385 -> .55
            else -> 0.0
        }
        glitchAmount=if(fresh && !calm) burstPower*burstWindow else 0.0
        if(!fresh || calm || time-lastDrum>max(.38,drumPeriod*2.4)) {
            rollConfidence=follow(rollConfidence,0.0,dt,.12)
            fastIntervals=0
            flashUntil=time
        }
        // Keep the torn/duplicated shape visible when a glitch and drum coincide.
        flashAmount=if(fresh && !calm && time<flashUntil) flashPower*(1.0-.65*glitchAmount) else 0.0
        val main=elements[0]
        val fall=if(main.peak>1e-7) unit((.55-main.fast/main.peak)/.40) else 0.0
        val targetZoom=if(fresh) (1.0+travelWeight*(.23*main.hit-.19*fall)-.28*spiralAmount).let {mix(it,1.0,melodicWeight)}.coerceIn(.70,1.20) else 1.0
        bodyZoom=follow(bodyZoom,targetZoom,dt,if(targetZoom>bodyZoom) .075 else .22)
        val crossTarget=if(fresh && time<crossUntil) 1.0-melodicWeight else 0.0
        cross=follow(cross,crossTarget,dt,if(crossTarget>cross) .12 else .30)
        val target=if(fresh) mix(openingTarget,.30,melodicWeight) else .25
        opening=follow(opening,target,dt,if(target>opening) .06 else .28)
    }
}






