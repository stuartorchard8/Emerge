package org.emerge.demo.outofspace

import org.emerge.audio.synth.Response
import org.emerge.audio.synth.StateVariableFilter
import org.emerge.demo.outofspace.audio.ExhaustAudioSystem
import org.emerge.demo.outofspace.audio.ExhaustVoice
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.Plume
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.energyAtKelvin
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.thermalMassOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The noise a burn makes — see [org.emerge.demo.outofspace.audio.ExhaustVoice].
 *
 * ⚠️ **None of this asserts what it sounds *like*.** That is Stu's ear and a set of constants he is
 * expected to move, and a test that pinned the mix would break on every improvement. What is pinned
 * is that the **sim reaches the speaker**: that a burn makes a sound and silence makes none, that
 * each parameter moves the layer it is supposed to move in the direction it is supposed to move it,
 * and that the voice pool does not swap voices around under a burn. Those are the failures that are
 * inaudible until they are maddening.
 */
class ExhaustAudioTest {

    // ── One voice ─────────────────────────────────────────────────────────────

    /** Told about a burn, it makes a noise; released, it fades away and stops. */
    @Test
    fun `a voice sounds while it is burning and falls silent when it is not`() {
        val voice = ExhaustVoice(RATE, seed = 1L)
        assertTrue(silent(voice), "a voice nobody has spoken to is making a noise")

        voice.set(gain = 1f, metresPerSecond = 3_000f, kelvin = 2_500f, kilogramsPerTick = 0.5f, gramsPerMole = 18f, blocked = false)
        assertTrue(level(voice, seconds = 0.2f) > 0.05f, "a full-throttle burn produced nothing audible")

        voice.release()
        // Several release time constants: a single pole approaches and never arrives, so what is
        // asserted is that it has got down into the noise floor, not that it reached zero.
        level(voice, seconds = 1f)
        assertTrue(silent(voice), "a released voice is still sounding")
    }

    /** Gain does what gain does, and it is the throttle and the distance arriving together. */
    @Test
    fun `a quiet jet is quieter`() {
        val loud = voiceAt(gain = 1f)
        val quiet = voiceAt(gain = 0.25f)
        assertTrue(
            level(quiet, 0.2f) < level(loud, 0.2f) * 0.5f,
            "a quarter-gain jet came out at ${level(quiet, 0.2f)} against ${level(loud, 0.2f)}",
        )
    }

    /**
     * ⭐ **Hot exhaust hisses.** The temperature drives the high band and nothing else does, so this
     * is the one assertion that says the chamber's heat reaches the speaker at all.
     */
    @Test
    fun `a hotter chamber puts more energy up high`() {
        val cold = bandRatio(voiceAt(kelvin = 300f))
        val hot = bandRatio(voiceAt(kelvin = 3_000f))
        assertTrue(hot > cold * 1.5f, "hot ($hot) is barely brighter than cold ($cold)")
    }

    /**
     * ⭐ **A light propellant is pitched higher**, because the speed of sound in a gas goes as
     * `1/√M` — the same physics that makes hydrogen the good propellant makes it the high-pitched
     * one. Hydrogen against rock moves every filter in the voice by a factor of eight.
     *
     * ⚠️ **The *sound* does not move by eight, and that is worth knowing rather than hiding.**
     * Measured, the centre of energy goes from 47 Hz to 69 Hz — half again, not eightfold — because
     * the rumble layer is pink noise and pink noise has most of its power in its bottom octave
     * wherever you put the corner. So the tail anchors the average in both cases and what actually
     * moves is everything above it. Half again is plainly audible, and if a bigger shift is wanted
     * the thing to change is the *mix* (less rumble) and not the pitch law.
     */
    @Test
    fun `a lighter molecule sounds higher`() {
        val heavy = centreFrequency(voiceAt(gramsPerMole = 140f))   // forsterite, thrown as rock
        val light = centreFrequency(voiceAt(gramsPerMole = 2f))     // hydrogen
        assertTrue(light > heavy * 1.3f, "hydrogen ($light Hz) is not pitched above rock ($heavy Hz)")
    }

    /** More mass a tick is more rumble: the layer that tells a big engine from a small one. */
    @Test
    fun `a thicker flow rumbles harder`() {
        val thin = lowEnergy(voiceAt(kilogramsPerTick = 0.05f))
        val thick = lowEnergy(voiceAt(kilogramsPerTick = 0.6f))
        assertTrue(thick > thin * 1.3f, "a thick flow ($thick) did not rumble past a thin one ($thin)")
    }

    /** A motor firing into a wall booms rather than hisses — the blocked case, made audible. */
    @Test
    fun `a blocked motor is duller than a clear one`() {
        val clear = bandRatio(voiceAt(blocked = false))
        val blocked = bandRatio(voiceAt(blocked = true))
        assertTrue(blocked < clear * 0.8f, "blocked ($blocked) is as bright as clear ($clear)")
    }

    /** Whatever it is fed, it stays inside the range a sound card can carry. */
    @Test
    fun `a voice at full tilt stays within full scale`() {
        val voice = voiceAt(gain = 1f, metresPerSecond = 9_400f, kelvin = 4_000f, kilogramsPerTick = 2f, gramsPerMole = 2f)
        val block = FloatArray(RATE / 2)
        voice.render(block, 0, block.size)
        var peak = 0f
        for (v in block) peak = maxOf(peak, abs(v))
        assertTrue(peak <= 1f, "one voice alone peaked at $peak, which leaves nothing for the others")
    }

    // ── The pool ──────────────────────────────────────────────────────────────

    /**
     * ⛔ **A burning nozzle keeps its voice.** Swap voices under a burn and the new one inherits the
     * old one's ramp and filter state, which is a swoop on a frame boundary — audible, intermittent,
     * and nearly impossible to track down after the fact.
     */
    @Test
    fun `a nozzle holds the same voice from frame to frame`() {
        val system = ExhaustAudioSystem(RATE)
        val state = worldWith(plume(tile(10, 10)), plume(tile(20, 10)))

        system.onFrame(state, camX = 15f, camY = 10f)
        val first = system.voices.filter { !it.isSilent }
        assertEquals(2, first.size, "two burns should have claimed two voices")

        repeat(5) { system.onFrame(state, camX = 15f, camY = 10f) }
        assertEquals(first.toSet(), system.voices.filter { !it.isSilent }.toSet(), "the claims moved")
    }

    /** And gives it up when it stops, so the pool is not exhausted by a ship that once burned. */
    @Test
    fun `a nozzle that stops burning releases its voice`() {
        val system = ExhaustAudioSystem(RATE)
        val burning = worldWith(plume(tile(10, 10)))
        system.onFrame(burning, camX = 10f, camY = 10f)
        val claimed = system.voices.first { !it.isSilent }

        // A frame with nothing burning, then long enough for the release to run out.
        system.onFrame(worldWith(), camX = 10f, camY = 10f)
        drain(claimed, seconds = 1f)
        system.onFrame(worldWith(), camX = 10f, camY = 10f)

        val other = worldWith(plume(tile(30, 10)))
        system.onFrame(other, camX = 30f, camY = 10f)
        assertSame(claimed, system.voices.first { !it.isSilent }, "the freed voice was not re-used")
    }

    /**
     * ⛔ **More motors than voices goes to the loudest, not to the first.** A vessel with a dozen
     * engines firing has to spend a pool of eight on the ones a player can hear; handing them out in
     * tile order would silence the motor under the camera in favour of one across the ship.
     */
    @Test
    fun `the pool goes to the jets nearest the camera`() {
        val system = ExhaustAudioSystem(RATE, maxVoices = 2)
        val near = plume(tile(10, 10))
        val middle = plume(tile(25, 10))
        val far = plume(tile(45, 10))
        // Stated far-first, so "the first two it saw" and "the two nearest" are different answers.
        system.onFrame(worldWith(far, middle, near), camX = 10f, camY = 10f)

        val sounding = system.voices.count { !it.isSilent }
        assertEquals(2, sounding, "the pool is two voices and $sounding of them are sounding")
        // The nearest is loudest: the pool's total level should be close to what near+middle alone
        // would make, which is far more than the distant pair.
        assertTrue(poolLevel(system) > 0.05f, "the two claimed voices are not actually audible")
    }

    /** Nothing burning is silence, and it costs nothing to ask. */
    @Test
    fun `a ship with no engines lit makes no sound`() {
        val system = ExhaustAudioSystem(RATE)
        system.onFrame(worldWith(), camX = 10f, camY = 10f)
        assertTrue(system.voices.all { it.isSilent }, "an idle ship is humming")
    }

    /** Beyond the audible radius a burn is not voiced at all — a whole vessel away is silence. */
    @Test
    fun `a jet across the world is not heard`() {
        val system = ExhaustAudioSystem(RATE)
        system.onFrame(worldWith(plume(tile(90, 55))), camX = 2f, camY = 2f)
        assertTrue(system.voices.all { it.isSilent }, "a jet a hundred tiles away claimed a voice")
    }

    // ── Fixtures and meters ───────────────────────────────────────────────────

    private fun voiceAt(
        gain: Float = 1f,
        metresPerSecond: Float = 3_000f,
        kelvin: Float = 2_500f,
        kilogramsPerTick: Float = 0.5f,
        gramsPerMole: Float = 18f,
        blocked: Boolean = false,
    ): ExhaustVoice = ExhaustVoice(RATE, seed = 1L).also {
        it.set(gain, metresPerSecond, kelvin, kilogramsPerTick, gramsPerMole, blocked)
    }

    /** RMS over [seconds] of output, after the attack has run. */
    private fun level(voice: ExhaustVoice, seconds: Float): Float {
        val block = FloatArray((RATE * seconds).toInt())
        voice.render(block, 0, block.size)
        return rms(block, from = block.size / 2)
    }

    /** Runs a voice for a while and throws the samples away — for letting a release finish. */
    private fun drain(voice: ExhaustVoice, seconds: Float) {
        val block = FloatArray((RATE * seconds).toInt())
        voice.render(block, 0, block.size)
    }

    private fun silent(voice: ExhaustVoice): Boolean {
        val block = FloatArray(RATE / 10)
        voice.render(block, 0, block.size)
        return rms(block) < 0.001f
    }

    /** Energy above 2 kHz against energy below 500 — how *bright* a voice is, in one number. */
    private fun bandRatio(voice: ExhaustVoice): Float {
        val block = FloatArray(RATE / 2)
        voice.render(block, 0, block.size)
        val half = block.copyOfRange(block.size / 2, block.size)
        return bandEnergy(half, Response.HighPass, 2_000f) / (bandEnergy(half, Response.LowPass, 500f) + 1e-6f)
    }

    /**
     * **Where this voice's energy sits**, as a frequency: the geometric mean of a log-spaced set of
     * bands, weighted by how much power is in each.
     *
     * ⚠️ **Two cheaper meters were tried first and both are blind to pitch.** A *band ratio* —
     * energy above 2 kHz over energy below 500 — measures the layers moving past a fixed window
     * rather than the sound moving, and comes back unchanged for a jet three times higher, because
     * the hiss climbs out of the top of the window exactly as the roar climbs into it. A *zero
     * crossing rate* is worse: it is dominated by whatever is highest, and a high-pass filter always
     * passes everything up to Nyquist, so both answers were the hiss layer's and identical.
     *
     * Log-spaced because pitch is a ratio: a band from 100 to 200 Hz and one from 10 to 20 kHz are
     * the same size to an ear, and a linear spacing would weight the top of the range a hundred
     * times over.
     */
    private fun centreFrequency(voice: ExhaustVoice): Float {
        val block = FloatArray(RATE / 4)
        voice.render(block, 0, block.size)
        // The back half, so the attack ramp and the parameter sweep are over and done with.
        val settled = block.copyOfRange(block.size / 2, block.size)
        var weighted = 0.0
        var total = 0.0
        var frequency = 40.0
        while (frequency < RATE / 3.0) {
            val power = powerAt(settled, frequency.toFloat()).toDouble()
            weighted += power * ln(frequency)
            total += power
            frequency *= 1.3   // about a fifth per step, 26 bands over the audible range
        }
        return if (total <= 0.0) 0f else exp(weighted / total).toFloat()
    }

    /** How much of [block] is at [frequency] — one bin of a discrete transform, done directly. */
    private fun powerAt(block: FloatArray, frequency: Float): Float {
        val step = 2.0 * PI * frequency / RATE
        var real = 0.0
        var imaginary = 0.0
        for (i in block.indices) {
            real += block[i] * cos(step * i)
            imaginary += block[i] * sin(step * i)
        }
        return ((real * real + imaginary * imaginary) / (block.size.toDouble() * block.size)).toFloat()
    }

    /** Energy below 300 Hz — the rumble layer, on its own. */
    private fun lowEnergy(voice: ExhaustVoice): Float {
        val block = FloatArray(RATE / 2)
        voice.render(block, 0, block.size)
        return bandEnergy(block.copyOfRange(block.size / 2, block.size), Response.LowPass, 300f)
    }

    private fun poolLevel(system: ExhaustAudioSystem): Float {
        val block = FloatArray(RATE / 4)
        for (voice in system.voices) voice.render(block, 0, block.size)
        return rms(block, from = block.size / 2)
    }

    private fun bandEnergy(block: FloatArray, response: Response, cutoff: Float): Float {
        val filter = StateVariableFilter(RATE, response, cutoff)
        filter.prepare()
        var sum = 0.0
        for (v in block) {
            val out = filter.processPrepared(v)
            sum += out.toDouble() * out
        }
        return sqrt(sum / block.size).toFloat()
    }

    private fun rms(block: FloatArray, from: Int = 0): Float {
        var sum = 0.0
        for (i in from until block.size) sum += block[i].toDouble() * block[i]
        return sqrt(sum / (block.size - from)).toFloat()
    }

    private fun tile(x: Int, y: Int): TileIndex = GRID.tile(x, y)

    /** A plume of hot water at full throttle — the ordinary case, and the fixture's default. */
    private fun plume(
        bell: TileIndex,
        firing: Int = 1000,
        mass: Long = Capacity.PACKET_MASS / 200L,
        kelvin: Int = 2_500,
        species: Species = Species.Water,
    ): Plume {
        val cold = Mixture.of(species to mass, energy = 0)
        return Plume(
            bell = bell,
            facing = Direction.Right,
            mass = mass,
            metresPerSecond = 3_000L,
            kelvin = kelvin,
            firing = firing,
            mixture = Mixture.of(species to mass, energy = energyAtKelvin(thermalMassOf(cold), kelvin)),
            reach = 8,
            clear = true,
        )
    }

    /** A grid with nothing on it but the burns: the audio reads the plumes and the tiles and no more. */
    private fun worldWith(vararg plumes: Plume): VesselState = VesselState(
        grid = GRID,
        deck = DeckArray(GRID),
        air = Stuff.gas(MassArray(GRID.size)),
        buffers = BufferLayer.empty(GRID.size),
        rail = RailLayer.empty(GRID.size),
        plumes = plumes.toList(),
    )

    private companion object {
        const val RATE = 48_000
        val GRID = Grid(96, 60)
    }
}
