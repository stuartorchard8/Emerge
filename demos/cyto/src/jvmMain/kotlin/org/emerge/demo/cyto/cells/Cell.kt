package org.emerge.demo.cyto.cells

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.physics.box2d.*
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType
import org.emerge.demo.cyto.environment.WorldInterface
import ktx.box2d.body
import ktx.collections.GdxArray
import ktx.collections.filter
import ktx.collections.map
import ktx.collections.removeAll
import ktx.math.*
import kotlin.math.*

class Cell(
  world: World,
  private val savedData : CellData,
) {
  private var disposeWhenSafe = false
  private var disconnectWhenSafe = false
  private var dividing = false
  private var _isSticky = false
  var isSticky: Boolean
    get() = _isSticky || isStickyTemp
    set(value) { _isSticky = value }
  var isStickyTemp = false
  var connections = GdxArray<CellConnection>(); private set
  private var jointsToAdd = GdxArray<JointCreationData>()
  val body = world.body(BodyType.DynamicBody) {
    position.set(savedData.position)
    linearVelocity.set(savedData.linearVelocity)
    angle = savedData.direction
    angularVelocity = savedData.spin
    angularDamping = 0.1f
  }

  var touch = 0f
  var chemicals = savedData.chemicals.toMutableMap()
  var chemicalTransfers = mutableMapOf<String, Float> ()
  var chemicalSuppression = mutableMapOf<String, Float> ()
  var enzymes = mutableSetOf<Pair<String, String>> ()
  private var divideCooldown = 5f
  fun divide() : MutableMap<String, Float> {
    divideCooldown = 5f
    chemicals.forEach { (k, v) -> chemicals[k] = v/2f }
    return chemicals
  }

  private val energy get() = chemicals["energy"] ?: 1f

  var contraction = 0f
  val shape = CircleShape().apply {
    radius = max(savedData.radius, MIN_RADIUS)
  }

  var type: CellType = savedData.type
  var genes: List<Gene> = genesForType(type)

  val data get() = savedData.copy(
    position = body.position,
    linearVelocity = body.linearVelocity,
    direction = body.angle,
    spin = body.angularVelocity,
    chemicals = chemicals,
    type = type,
    radius = shape.radius,
    id = id,
  )
  var id = savedData.id

  private var fixture: Fixture = body.createFixture(shape, 1f/shape.radius)

  init {
    body.userData = this
  }

  /**
   * Per-cell membrane-blend data for [org.emerge.demo.cyto.CytoRenderer], replicating
   * the `relativeConnections` Cyto computed inside its old `Cell.draw`. Each entry is
   * (relative neighbour x, relative neighbour y, neighbour radius) — the y is flipped
   * for the A-side of a connection exactly as in the original so the fragment shader's
   * neck geometry matches.
   */
  fun neighbourRenderData(): GdxArray<Vector3> = connections.map {
    val pos = it.cellB.body.position - it.cellA.body.position
    if (it.cellA == this) {
      Vector3(pos.x, pos.y * -1f, it.cellB.shape.radius)
    } else {
      Vector3(pos.x * -1f, pos.y, it.cellA.shape.radius)
    }
  }

  fun activate(delta: Float, source: CellConnection?) {
    enzymes.add(Pair("e", "n"))
  }

  fun removeJoint(joint: Joint) : Boolean {
    val countBefore = connections.count()
    connections.removeAll { it.joint == joint }
    val countAfter = connections.count()
    return countBefore > countAfter
  }

  fun disconnectSafely() {
    disconnectWhenSafe = true
  }

  fun disposeSafely() {
    disposeWhenSafe = true
  }

  fun update(delta: Float) : CellUpdateResponse {
    connections.removeAll { !it.joint.isActive }
    act(delta)

    val queuedJoints = jointsToAdd
    if (jointsToAdd.size > 0) {
      jointsToAdd = GdxArray()
    }

    val unshieldedVelocity = body.linearVelocity
    connections.forEach {
      val normal = (it.cellB.body.position - it.cellA.body.position).nor()
      val shieldNormal = Vector2(normal)
      if (it.cellB == this) {
        shieldNormal *= -1f
      }
      val shieldedSpeed = unshieldedVelocity.dot(shieldNormal)
      if (shieldedSpeed > 0f) {
        unshieldedVelocity -= shieldNormal*shieldedSpeed
      }

      it.joint.length = it.cellB.shape.radius+it.cellA.shape.radius
      val reactionForce = it.joint.getReactionForce(WorldInterface.TIME_STEP)
      val stress = max(0f,reactionForce.dot(-normal)) - 0.25f
      it.damage = max(0f, it.damage+stress)
    }
    body.applyForceToCenter(unshieldedVelocity*-10f, true)

    val removedJoints = if (disposeWhenSafe || disconnectWhenSafe) connections else connections.filter {
      it.damage > 3f
    }
    disconnectWhenSafe = false
    return CellUpdateResponse(
      newJoints = queuedJoints,
      disconnects = removedJoints,
      destroy = disposeWhenSafe,
      divide = dividing,
    )
  }

  fun chemistry(delta: Float) {
    chemicalTransfers.forEach { (k, v) -> chemicals[k] = max(0f, min(MAX_CHEM, (chemicals[k] ?: 0f) + v)) }
    chemicalTransfers.clear()


    dividing = false
    isStickyTemp = false
    // Genes react to internal state
    genes.forEach { gene -> gene.act(this, delta) }
    touch = 0f


    // Use intermediate data structure to manage intents to utilize each chemical.
    val reactionIntents = mutableListOf<ChemicalReaction>()
    enzymes.forEach { enzyme ->
      val a = enzyme.first
      val b = enzyme.second
      val aMatches = chemicals.filter { it.key.takeLast(a.length) == a }
      val bMatches = chemicals.filter { it.key.take(b.length) == b }
      // Merge aMatches with bMatches to form all combinations.
      for (aMatch in aMatches) {
        for (bMatch in bMatches) {
          reactionIntents.add(ChemicalReaction(
            Pair(aMatch.key, bMatch.key),
          ))
        }
      }

      val ab = "$a$b"
      val abMatches = chemicals.filter { it.key.contains(ab) }
      // Split abMatches into pairs of distinct chemicals.
      for (abMatch in abMatches) {
        val segments = abMatch.key.split(ab)
        for (index in 1..<segments.size ) {
          val prefix = segments.take(index).joinToString(ab)
          val chemA = "$prefix$a"
          val suffix = segments.takeLast(segments.size-index).joinToString(ab)
          val chemB = "$b$suffix"

          reactionIntents.add(ChemicalReaction(
            Pair(chemA, chemB),
          ))
        }
      }
    }
    enzymes.clear()

    // When all intents are calculated, chemicals can be distributed evenly to intents without exceeding availability.
    chemicals.forEach { chemical ->
      val chemAIntents = reactionIntents.filter { it.catalyst.first == chemical.key }
      val chemBIntents = reactionIntents.filter { it.catalyst.second == chemical.key }
      val chemCIntents = reactionIntents.filter { "${it.catalyst.first}${it.catalyst.second}" == chemical.key }

      val totalIntents = chemAIntents.size+chemBIntents.size+chemCIntents.size
      val allocation = chemical.value/(totalIntents + 1f) // +1 here increases stability
      chemAIntents.forEach { it.chemA = allocation }
      chemBIntents.forEach { it.chemB = allocation }
      chemCIntents.forEach { it.chemC = allocation }
    }

    // Reactions take place to balance chemA+chemB=chemC as much as possible with given allocations of AB&C.
    reactionIntents.forEach { reaction ->
      val chemA = reaction.catalyst.first
      val chemB = reaction.catalyst.second
      val chemC = "$chemA$chemB"
      val minAB = min(reaction.chemA, reaction.chemB)
      val diffABC = (minAB - reaction.chemC)/2f

      // Adjust chemicals directly here since this is an internal action
      if (diffABC != 0f) {
        chemicals[chemA] = (chemicals[chemA] ?: 0f) - diffABC
        chemicals[chemB] = (chemicals[chemB] ?: 0f) - diffABC
        chemicals[chemC] = (chemicals[chemC] ?: 0f) + diffABC
      }
    }
  }

  private fun act(delta: Float) {
    if (delta <= 0f) return

    if (energy <= 0f) {
      disposeSafely()
      return
    }

    val neighbours = connections.map { if (it.cellA == this) it.cellB else it.cellA }
    neighbours.forEach {
      val maxConnections = max(connections.size, it.connections.size)+1
      chemicals.forEach { (k, v) ->
        val totalInhibition = abs((chemicalSuppression[k] ?: 0f) + (it.chemicalSuppression[k] ?: 0f))
        val transfer = v/maxConnections-totalInhibition
        if (transfer > 0f) {
          chemicalTransfers[k] = (chemicalTransfers[k] ?: 0f) - transfer
          it.chemicalTransfers[k] = (it.chemicalTransfers[k] ?: 0f) + transfer
        }
      }
    }

    var targetRadius = 1f
    if (energy >= 1f) {
      chemicalTransfers["energy"] = (chemicalTransfers["energy"] ?: 0f) - delta
    } else {
      val decay = energy*0.125f+0.125f
      chemicalTransfers["energy"] = (chemicalTransfers["energy"] ?: 0f) - delta*decay*decay
      targetRadius = sqrt(energy)
    }


    if (contraction > 0) {
      val chargeToUse = min(contraction, delta)
      val strength = chargeToUse/delta
      targetRadius *= 1f-strength*0.5f
      contraction = 0f
    }

    when (type) {
      CellType.Support -> {
        chemicalTransfers["energy"] = (chemicalTransfers["energy"] ?: 0f) + 5f
      }
      CellType.Stem -> {
        if (divideCooldown > 0f) {
          divideCooldown -= delta
        } else if (energy > 0.5f){
          dividing = true
        }
      }
      else -> Unit
    }

    val radius = (shape.radius*RADIUS_ELASTICITY + max(targetRadius, MIN_RADIUS))/(RADIUS_ELASTICITY+1)
    shape.radius = radius
    fixture.shape.radius = radius
  }

  fun collide(partner: Cell, pressure: Float = 0f, sticky: Boolean = false) {
    if (!(sticky || isSticky || partner.isSticky)) {
      touch += pressure
      partner.touch += pressure
      return
    }

    if (disconnectWhenSafe || partner.disconnectWhenSafe) return

    if (isSticky) {
      val jointData = JointCreationData(
        partner,
        this,
        shape.radius+partner.shape.radius,
      )
      jointsToAdd.add(jointData)
    } else {
      val jointData = JointCreationData(
        this,
        partner,
        shape.radius+partner.shape.radius,
      )
      jointsToAdd.add(jointData)
    }
  }

  fun connect(connection: CellConnection) {
    connections.add(connection)
  }

  fun containsPoint(point : Vector2) : Boolean {
    return (body.position - point).len2() < shape.radius*shape.radius
  }

  companion object {
    private const val RADIUS_ELASTICITY = 3f
    private const val MIN_RADIUS = 0.25f
    private const val MAX_CHEM = 10f

    fun genesForType(type: CellType): List<Gene> = when (type) {
      CellType.Muscle -> listOf(
        Gene(
          inputs = listOf(
            GeneInput(
              type = GeneInputType.Chem,
              chem = "e",
              weight = 1f,
            )
          ),
          output = GeneOutput(
            type = GeneOutputType.Contract,
            chem1 = "",
            chem2 = "",
            bias = 0f,
          ),
        ),
      )
      CellType.Not -> listOf(
        Gene(
          inputs = listOf(
            GeneInput(
              type = GeneInputType.Chem,
              chem = "e",
              weight = -1f,
            )
          ),
          output = GeneOutput(
            type = GeneOutputType.Contract,
            chem1 = "",
            chem2 = "",
            bias = 1f,
          ),
        ),
      )
      CellType.Jump -> listOf(
        Gene(
          inputs = listOf(),
          output = GeneOutput(
            type = GeneOutputType.Contract,
            chem1 = "",
            chem2 = "",
            bias = 1f,
          ),
        ),
      )
      CellType.Touch -> listOf(
        Gene(
          inputs = listOf(
            GeneInput(
              type = GeneInputType.Touch,
              chem = "",
              weight = 1f,
            )
          ),
          output = GeneOutput(
            type = GeneOutputType.Enzyme,
            chem1 = "e",
            chem2 = "n",
            bias = 0f,
          ),
        ),
      )
      else -> listOf()
    }
  }
}
