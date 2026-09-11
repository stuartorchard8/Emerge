package org.emerge.demo.outofspace.audio

import org.emerge.audio.synth.AudioSource
import org.emerge.audio.synth.PinkNoise
import org.emerge.audio.synth.Response
import org.emerge.audio.synth.Smoothed
import org.emerge.audio.synth.StateVariableFilter
import org.emerge.audio.synth.Oscillator
import org.emerge.audio.synth.Wave
import org.emerge.audio.synth.WhiteNoise
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Plume
import org.emerge.demo.outofspace.world.VesselState
import kotlin.math.sqrt

/**
 * **What a rocket motor sounds like**, built out of [org.emerge.audio.synth]'s primitives and driven
 * by what actually left the nozzle — see [Plume].
 *
 * ### The layers, and what each is for
 *
 * A jet is broadband noise with a shape, and the shape is where the information is. Four layers,
 * each answering a different question a player might have about the engine they built:
 *
 *  - **Rumble** — pink noise, low-passed. *How much* is coming out. This is the mass flow, and it is
 *    what tells a big engine from a small one.
 *  - **Roar** — white noise through a resonant band-pass. *What it is made of.* The band moves with
 *    the speed of sound in the exhaust, which goes as `1/√M`: hydrogen sits an octave and a half
 *    above water, which is exactly the difference that makes hydrogen the good propellant.
 *  - **Hiss** — white noise, high-passed. *How hot it is.* A cold gas thruster is a sigh; a lit
 *    chamber is a tearing sound.
 *  - **Tone** — two quiet oscillators a fifth apart, at the same `1/√M` pitch. A jet is not *only*
 *    noise, and without something periodic in it the sound has no pitch to follow when the propellant
 *    changes. Quiet on purpose: turned up it stops being an engine and becomes a synthesiser.
 *
 * ⚠️ **Every constant below is a look, not a fact.** They are gathered in [Tuning] so they can be
 * found and moved. What is *not* arbitrary is which sim quantity drives which layer, and that is
 * what the comments above are for.
 *
 * ### One voice per engine, claimed and held
 *
 * A voice keeps filter state, noise state and six smoothed parameters. Handing engine A's voice to
 * engine B mid-burn means B inherits A's ramp and A's filter ringing, which is audible as a swoop.
 * So [ExhaustAudioSystem] claims a voice per nozzle and holds it for as long as that nozzle burns.
 */
class ExhaustVoice(private val sampleRate: Int, seed: Long) : AudioSource {

    // ── What the game says, written from the frame thread ─────────────────────
    //
    // ⚠️ Plain volatile floats, and every one of them goes through a [Smoothed] before anything
    // hears it. A sim tick is 16 ms and a sample is 0.02 ms, so an unsmoothed parameter is a step
    // function 64 times a second — which is not a change in the sound, it is a buzz *added* to it.

    /** How loud this jet should be here, 0..1: throttle, mass flow and distance already folded in. */
    private val loudness = Smoothed(sampleRate, Tuning.ATTACK_SECONDS, Tuning.RELEASE_SECONDS)

    /** Metres per second at the nozzle. */
    private val speed = Smoothed(sampleRate, Tuning.SWEEP_SECONDS, Tuning.SWEEP_SECONDS)

    /** Kelvin in the chamber. */
    private val heat = Smoothed(sampleRate, Tuning.SWEEP_SECONDS, Tuning.SWEEP_SECONDS)

    /** Kilograms a tick out of the nozzle. */
    private val flow = Smoothed(sampleRate, Tuning.SWEEP_SECONDS, Tuning.SWEEP_SECONDS)

    /** Grams per mole of the exhaust — the pitch of the whole thing. */
    private val molar = Smoothed(sampleRate, Tuning.SWEEP_SECONDS, Tuning.SWEEP_SECONDS, Tuning.REFERENCE_MOLAR)

    /** 1 when the jet is firing into a wall, 0 when it reaches open space. */
    private val boxed = Smoothed(sampleRate, Tuning.SWEEP_SECONDS, Tuning.SWEEP_SECONDS)

    private val rumbleSource = PinkNoise(seed)
    private val roarSource = WhiteNoise(seed * 31 + 7)
    private val hissSource = WhiteNoise(seed * 131 + 17)
    private val rumble = StateVariableFilter(sampleRate, Response.LowPass)
    private val roar = StateVariableFilter(sampleRate, Response.BandPass)
    private val hiss = StateVariableFilter(sampleRate, Response.HighPass)
    private val fundamental = Oscillator(sampleRate, Wave.Triangle)
    private val fifth = Oscillator(sampleRate, Wave.Sine)

    /** True while this voice is worth rendering at all — see [ExhaustAudioSystem]'s claims. */
    val isSilent: Boolean get() = loudness.value < Tuning.INAUDIBLE && loudness.target < Tuning.INAUDIBLE

    /**
     * Hands this voice a burn to describe. Called from the frame thread, once per frame per engine.
     *
     * @param gain what this jet is worth *at the listener* — see [ExhaustAudioSystem.onFrame], which
     *   is where the distance falloff lives, because how loud a thing is from over there is a fact
     *   about the camera and not about the engine.
     */
    fun set(gain: Float, metresPerSecond: Float, kelvin: Float, kilogramsPerTick: Float, gramsPerMole: Float, blocked: Boolean) {
        loudness.target = gain.coerceIn(0f, 1f)
        speed.target = metresPerSecond
        heat.target = kelvin
        flow.target = kilogramsPerTick
        // ⛔ Never zero: the pitch divides by its square root, and an engine with nothing in it is
        // one that has just shut — it should fall silent at the pitch it had, not shriek on the way.
        molar.target = if (gramsPerMole > 0f) gramsPerMole else Tuning.REFERENCE_MOLAR
        boxed.target = if (blocked) 1f else 0f
    }

    /** Shuts this voice down over its release time. Its dials are left where they were. */
    fun release() {
        loudness.target = 0f
    }

    override fun render(out: FloatArray, from: Int, to: Int) {
        if (isSilent) {
            // Still advanced, so a voice coming back does not jump from wherever it stopped.
            loudness.advance(to - from)
            return
        }

        // ── Once a block, not once a sample ──────────────────────────────────
        //
        // The filters' tunings are a function of parameters that move at a sim tick's pace, and a
        // block is a millisecond or two. Retuning per sample would cost three `tan` calls a sample
        // for a difference nothing can hear — see [StateVariableFilter.prepare], which is public for
        // exactly this.
        val count = to - from
        val blockSpeed = speed.advance(count).coerceAtLeast(0f)
        val blockHeat = heat.advance(count).coerceAtLeast(0f)
        val blockFlow = flow.advance(count).coerceAtLeast(0f)
        val blockMolar = molar.advance(count).coerceAtLeast(1f)
        val blockBoxed = boxed.advance(count)

        // The speed of sound in the exhaust goes as 1/√M, and every formant in a gas goes with it.
        // This one ratio is what makes hydrogen sound like hydrogen.
        val pitch = sqrt(Tuning.REFERENCE_MOLAR / blockMolar)
        val energy = (blockSpeed / Tuning.FAST_METRES_PER_SECOND).coerceIn(0f, 2f)
        val warmth = (blockHeat / Tuning.HOT_KELVIN).coerceIn(0f, 2f)
        val thickness = (blockFlow / Tuning.HEAVY_KILOGRAMS_PER_TICK).coerceIn(0f, 1.5f)

        rumble.cutoff = Tuning.RUMBLE_HZ * pitch * (0.7f + 0.6f * energy)
        rumble.q = 0.8f
        // Boxed in, the band tightens and drops: a jet with a wall in front of it is a boom in a
        // room rather than a hiss into space, and the resonance is most of what says so.
        roar.cutoff = Tuning.ROAR_HZ * pitch * (0.6f + 0.9f * energy) * (1f - 0.45f * blockBoxed)
        roar.q = Tuning.ROAR_Q + Tuning.BOXED_Q * blockBoxed
        hiss.cutoff = Tuning.HISS_HZ * pitch * (0.5f + 0.9f * warmth)
        hiss.q = 0.7f
        fundamental.frequency = Tuning.TONE_HZ * pitch * (0.8f + 0.5f * energy)
        fifth.frequency = fundamental.frequency * 1.5f
        rumble.prepare()
        roar.prepare()
        hiss.prepare()

        val rumbleGain = Tuning.TRIM * Tuning.RUMBLE_GAIN * (0.35f + 0.9f * thickness) * (1f + 0.5f * blockBoxed)
        val roarGain = Tuning.TRIM * Tuning.ROAR_GAIN * (0.4f + 0.8f * energy)
        val hissGain = Tuning.TRIM * Tuning.HISS_GAIN * warmth * (1f - 0.6f * blockBoxed)
        val toneGain = Tuning.TRIM * Tuning.TONE_GAIN * (0.3f + 0.7f * energy)

        for (i in from until to) {
            val level = loudness.next()
            var sample = rumble.processPrepared(rumbleSource.next()) * rumbleGain
            sample += roar.processPrepared(roarSource.next()) * roarGain
            sample += hiss.processPrepared(hissSource.next()) * hissGain
            sample += fundamental.next() * toneGain
            sample += fifth.next() * toneGain * 0.4f
            out[i] += sample * level
        }
    }

    /**
     * **Every number that is a matter of taste**, in one place.
     *
     * ⚠️ Separated from the code above on purpose: what layer is driven by what quantity is a design
     * decision worth arguing about, and how loud each one is at what frequency is a thing to sit and
     * fiddle with. Only the second kind lives here.
     */
    object Tuning {
        /** Fast enough to feel simultaneous with the key. */
        const val ATTACK_SECONDS = 0.02f

        /** A jet has gas in it still; cutting it dead sounds like a cut tape. */
        const val RELEASE_SECONDS = 0.12f

        /** How fast a *timbre* change follows the sim — slow, so a throttle move is a swell. */
        const val SWEEP_SECONDS = 0.06f

        /** Water, 18 g/mol: the propellant everything else is pitched against. */
        const val REFERENCE_MOLAR = 18f

        /** Exhaust velocity at which the sound is "full" — hot hydrogen goes half again past this. */
        const val FAST_METRES_PER_SECOND = 4_000f

        /** Chamber temperature at which the hiss is fully open. */
        const val HOT_KELVIN = 2_500f

        /** Mass flow at which the rumble is fully open. About what a thruster throws at full tilt. */
        const val HEAVY_KILOGRAMS_PER_TICK = 0.5f

        const val RUMBLE_HZ = 180f
        const val ROAR_HZ = 900f
        const val HISS_HZ = 3_000f
        const val TONE_HZ = 70f

        /**
         * **What the four layers are multiplied by together**, so their balance can be tuned without
         * anybody having to keep the total in their head.
         *
         * ⚠️ **It is set from the loudest burn the game can produce, not from a typical one.** Hot
         * hydrogen at full tilt opens every layer at once — the gains below sum to about 2.3 there —
         * and a voice that leaves full scale is one the mixer's limiter is bending for the whole
         * burn, which is distortion rather than safety. At 0.4 that worst case lands just inside
         * unity and an ordinary water thruster sits around a quarter of it, which is where a sound
         * effect wants to be: audible, with room for seven more of them.
         */
        const val TRIM = 0.4f

        const val RUMBLE_GAIN = 0.55f
        const val ROAR_GAIN = 0.40f
        const val HISS_GAIN = 0.22f
        const val TONE_GAIN = 0.07f

        /** Wide enough to be a resonance rather than a whistle. */
        const val ROAR_Q = 1.4f

        /** What a wall in front of the nozzle adds to it. */
        const val BOXED_Q = 2.5f

        /** Below this a voice costs a millisecond of arithmetic and buys nothing. */
        const val INAUDIBLE = 0.001f
    }
}

/**
 * Turns [VesselState.plumes] into a fixed pool of [ExhaustVoice]s, once a frame.
 *
 * The twin of [ImpactAudioSystem], and the same division of labour: this is in `core` because how
 * loud an engine is from the far end of the ship is a judgement about *the game* and must be the
 * same on every platform. What a host supplies is somewhere to put the samples — it hands [voices]
 * to an `org.emerge.audio.synth.Mixer` and pumps that.
 *
 * ⚠️ **Called once per rendered frame, not once per tick** — see [ImpactAudioSystem], which says
 * the same. A burn is a continuous thing and a frame is a good enough sampling of it.
 *
 * ### Claims
 *
 * A nozzle that is burning holds one voice for as long as it burns, keyed by its tile. That is what
 * keeps a jet's timbre continuous: hand it a different voice next frame and it inherits a stranger's
 * filter state, its ramp and its noise, which is audible as a swoop on every frame boundary. When a
 * motor shuts, its voice is released — it fades out over [ExhaustVoice.Tuning.RELEASE_SECONDS] — and
 * only then can another nozzle take it.
 *
 * ⛔ **More motors than voices is a real case and it is handled by loudness, not by luck.** Plumes
 * are considered nearest-first, so the pool goes to the engines a player can actually hear; a
 * distant sixteenth motor is dropped rather than stealing the voice of one in front of the camera.
 */
class ExhaustAudioSystem(
    sampleRate: Int,
    maxVoices: Int = DEFAULT_MAX_VOICES,
    /** Beyond this many tiles from the camera, a jet is not heard. Impact audio's number. */
    private val maxAudibleTiles: Float = 48f,
) {
    /** Hand these to a mixer, in this order, once. The pool never changes size. */
    val voices: List<ExhaustVoice> =
        List(maxVoices.coerceAtLeast(1)) { ExhaustVoice(sampleRate, seed = 1L + it * 2_654_435_761L) }

    /** Which nozzle holds which voice, by tile index — see the class note on claims. */
    private val claims = HashMap<Int, ExhaustVoice>()

    /** The frame's burns, loudest first. Rebuilt each frame; there are never many. */
    private val audible = ArrayList<Audible>()
    private val loudestFirst = Comparator<Audible> { a, b -> b.gain.compareTo(a.gain) }
    private val claimedThisFrame = HashSet<Int>()

    /**
     * @param camX the camera centre in **grid tiles**, as [ImpactAudioSystem.onFrame] takes it and
     *   for the same reason: the listener is the view and not the ship.
     */
    fun onFrame(state: VesselState, camX: Float, camY: Float) {
        audible.clear()
        for (plume in state.plumes) {
            val gain = gainOf(plume, state, camX, camY)
            if (gain <= ExhaustVoice.Tuning.INAUDIBLE) continue
            audible.add(Audible(plume, gain))
        }
        // Loudest first, so the pool is spent on what can be heard — see the class note.
        audible.sortWith(loudestFirst)

        claimedThisFrame.clear()
        for (entry in audible) {
            val key = entry.plume.bell.index
            val voice = claims[key] ?: freeVoice() ?: continue
            claims[key] = voice
            claimedThisFrame.add(key)
            val plume = entry.plume
            voice.set(
                gain = entry.gain,
                metresPerSecond = plume.metresPerSecond.toFloat(),
                kelvin = plume.kelvin.toFloat(),
                kilogramsPerTick = plume.mass.toFloat() / Budget.KILOGRAM,
                gramsPerMole = plume.gramsPerMole.toFloat(),
                blocked = !plume.clear,
            )
        }

        // Everything that was burning last frame and is not now. ⚠️ The claim is dropped only once
        // the voice has actually finished fading, or a motor pulsing on and off every other frame
        // would hand its half-faded voice to a neighbour.
        val iterator = claims.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in claimedThisFrame) continue
            entry.value.release()
            if (entry.value.isSilent) iterator.remove()
        }
    }

    /** Everything shut, for a host tearing down or a world being reloaded. */
    fun release() {
        for (voice in voices) voice.release()
        claims.clear()
    }

    private fun freeVoice(): ExhaustVoice? {
        for (voice in voices) {
            if (claims.values.none { it === voice }) return voice
        }
        return null
    }

    /**
     * How loud this jet is at the camera: what it is worth at the nozzle, then the distance.
     *
     * ⚠️ **The throttle and the mass flow are both in it, and they are not the same thing.** A motor
     * at full throttle with a nearly-dry store makes a thin sound; one at a tenth throttle with a
     * full one makes a quiet one. Multiplying them is what gets both.
     */
    private fun gainOf(plume: Plume, state: VesselState, camX: Float, camY: Float): Float {
        val throttle = (plume.firing.toFloat() / 1000f).coerceIn(0f, 1f)
        if (throttle <= 0f) return 0f
        val thickness = (plume.mass.toFloat() / (Budget.KILOGRAM * ExhaustVoice.Tuning.HEAVY_KILOGRAMS_PER_TICK))
            .coerceIn(0f, 1f)
        val grid = state.grid
        val dx = grid.xOf(plume.bell) + 0.5f - camX
        val dy = grid.yOf(plume.bell) + 0.5f - camY
        val tiles = sqrt(dx * dx + dy * dy)
        return throttle * (0.3f + 0.7f * thickness) * attenuationAt(tiles)
    }

    /** Impact audio's curve: cubed, so the far half of the radius is nearly all of the silence. */
    private fun attenuationAt(tiles: Float): Float {
        if (maxAudibleTiles <= 0f) return 0f
        val linear = (1f - tiles / maxAudibleTiles).coerceIn(0f, 1f)
        return linear * linear * linear
    }

    private class Audible(val plume: Plume, val gain: Float)

    companion object {
        /**
         * Voices in the pool. Eight is more engines than a vessel has ever had firing at once —
         * flight control throttles the ones that would fight each other — and past about four
         * simultaneous jets a listener hears a single wall of noise anyway.
         */
        const val DEFAULT_MAX_VOICES = 8
    }
}
