// See LICENSE for license details.

package firrtl.passes

// Datastructures
import scala.collection.mutable
import scala.collection.immutable.ListMap

import firrtl._
import firrtl.ir._
import firrtl.Utils._
import firrtl.Mappers._

class ConstraintSolver {
  def addGeq(big: Any, small: Any): Unit = (big, small) match {
    case (IsVar(name), other: IsConstrainable) => add(GEQ(name, other))
    case (IsVar(name), other: IntWidth) => add(GEQ(name, Implicits.width2constraint(other)))
    case (IsKnown(u), IsKnown(l)) if l > u => sys.error(s"addGeq BAD! L: $l, U: $u")
    case _ =>
  }
  def addLeq(small: Any, big: Any): Unit = (small, big) match {
    case (IsVar(name), other: IsConstrainable) => add(LEQ(name, other))
    case (IsKnown(l), IsKnown(u)) if u < l => sys.error(s"addLeq BAD! L: $l, U: $u")
    case _ =>
  }
  def get(b: Any): Option[IsKnown] = {
    val name = b match {
      case IsVar(name) => name
      //case VarWidth(name) => name
      case x => ""
    }
    solvedConstraintMap.get(name) match {
      case None => None
      case Some((k: IsKnown, _)) => Some(k)
      case Some(_) => None
    }
  }

  /** Internal State and Accessors */
  private def genConst(left: String, right: IsConstrainable, geq: Boolean): Constraint = geq match {
    case true => GEQ(left, right)
    case false => LEQ(left, right)
  }
  // initial constraints
  val constraints = mutable.ArrayBuffer[Constraint]()
  def add(c: Constraint) = constraints += c
  def serializeConstraints: String = constraints.mkString("\n")

  // solved constraints
  type ConstraintMap = mutable.HashMap[String, (IsConstrainable, Boolean)]
  val solvedConstraintMap = new ConstraintMap()
  def serializeSolutions: String = solvedConstraintMap.map{
    case (k, (v, true))  => s"$k >= ${v.serialize}"
    case (k, (v, false)) => s"$k <= ${v.serialize}"
  }.mkString("\n")

  /** Constraint Solver */

  private def mergeConstraints(constraints: Seq[Constraint]): Seq[Constraint] = {
    val mergedMap = mutable.HashMap[String, Constraint]()
    constraints.foreach {
        case c if c.geq  && mergedMap.contains(c.left) => mergedMap(c.left) = genConst(c.left, IsMax(mergedMap(c.left).right, c.right), true)
        case c if !c.geq && mergedMap.contains(c.left) => mergedMap(c.left) = genConst(c.left, IsMin(mergedMap(c.left).right, c.right), false)
        case c                                                   => mergedMap(c.left) = c
    }
    mergedMap.values.toSeq
  }
  private def substitute(h: ConstraintMap)(t: IsConstrainable): IsConstrainable = {
    val x = t map substitute(h)
    //println("SDFL:")
    x match {
      case isVar: IsVar => h get isVar.name match {
        case None => isVar.asInstanceOf[IsConstrainable]
        case Some((p, geq)) =>
          //println("HERE 2")
          val newT = substitute(h)(p)
          val newTOptimized = newT//.reduce()
          //println("HERE 3")
          h(isVar.name) = (newTOptimized, geq)
          newTOptimized//.reduce()
      }
      case other => other//.reduce()
    }
  }

  private def backwardSubstitution(h: ConstraintMap)(t: IsConstrainable): IsConstrainable = {
    t match {
      case isVar: IsVar => h.get(isVar.name) match {
        case Some((p, geq)) => p
        case _ => isVar
      }
      case other => (other map backwardSubstitution(h))//.reduce()
    }
  }

  private def removeCycle(n: String, geq: Boolean)(t: IsConstrainable): IsConstrainable = if(geq) removeGeqCycle(n)(t) else removeLeqCycle(n)(t)

  private def removeLeqCycle(name: String)(t: IsConstrainable): IsConstrainable = t match {
    case x if greaterEqThan(name)(x) => VarCon(name)
    case isMin: IsMin => IsMin(isMin.children.filter{ c => !greaterEqThan(name)(c)}:_*)
    case x => x
  }
  private def greaterEqThan(name: String)(t: IsConstrainable): Boolean = t match {
    case isMin: IsMin => isMin.children.map(greaterEqThan(name)).reduce(_ && _)
    case isAdd: IsAdd => isAdd.children match {
      case Seq(isVar: IsVar, isVal: IsKnown) if (isVar.name == name) && (isVal.value >= 0) => true
      case Seq(isVal: IsKnown, isVar: IsVar) if (isVar.name == name) && (isVal.value >= 0) => true
      case _ => false
    }
    case isMul: IsMul => isMul.children match {
      case Seq(isVar: IsVar, isVal: IsKnown) if (isVar.name == name) && (isVal.value >= 0) => true
      case Seq(isVal: IsKnown, isVar: IsVar) if (isVar.name == name) && (isVal.value >= 0) => true
      case _ => false
    }
    case isVar: IsVar if isVar.name == name => true
    case _ => false
  }

  private def removeGeqCycle(name: String)(t: IsConstrainable): IsConstrainable = t match {
    case x if lessEqThan(name)(x) => VarCon(name)
    case isMax: IsMax => IsMax(isMax.children.filter{c => !lessEqThan(name)(c)}:_*)
    case x => x
  }
  private def lessEqThan(name: String)(t: IsConstrainable): Boolean = t/*.optimize()*/ match {
    case isMax: IsMax => isMax.children.map(lessEqThan(name)).reduce(_ && _)
    case isAdd: IsAdd => isAdd.children match {
      case Seq(isVar: IsVar, isVal: IsKnown) if (isVar.name == name) && (isVal.value <= 0) => true
      case Seq(isVal: IsKnown, isVar: IsVar) if (isVar.name == name) && (isVal.value <= 0) => true
      case _ => false
    }
    case isMul: IsMul => isMul.children match {
      case Seq(isVar: IsVar, isVal: IsKnown) if (isVar.name == name) && (isVal.value <= 0) => true
      case Seq(isVal: IsKnown, isVar: IsVar) if (isVar.name == name) && (isVal.value <= 0) => true
      case _ => false
    }
    case isVar: IsVar if isVar.name == name => true
    case isNeg: IsNeg => isNeg.children match {
      case Seq(isVar: IsVar) if isVar.name == name => true
      case _ => false
    }
    case _ => false
  }

  private def hasVar(n: String)(t: IsConstrainable): Boolean = {
    var has = false
    def rec(t: IsConstrainable): IsConstrainable = {
      t match {
        case isVar: IsVar if isVar.name == n => has = true
        case _ =>
      }
      t map rec
    }
    rec(t)
    has
  }
  def check(): Seq[Constraint] = {
    val checkMap = new mutable.HashMap[String, Constraint]()
    constraints.foldLeft(Seq[Constraint]()) { (seq, c) =>
      checkMap.get(c.left) match {
        case None =>
          checkMap(c.left) = c
          seq ++ Nil
        case Some(x) if x.geq != c.geq => seq ++ Seq(x, c)
        case Some(x) => seq ++ Nil
      }
    }
  }
  def getDepth(i: IsConstrainable): Int = i.children match {
    case Nil => 1
    case other => other.map(getDepth).max + 1
  }
  def getSize(i: IsConstrainable): Int = i.children match {
    case Nil => 1
    case other => other.map(getSize).sum
  }

  def solve() = {
    // Check that all constraints per name are either geq or leq, but not both
    val illegals = check()
    if (illegals != Nil) sys.error("Illegal constraints!: $illegals")

    // Combines constraints on the same VarWidth into the same constraint
    val uniqueConstraints = mergeConstraints(constraints.toSeq)
    //println("Unique Constraints!")
    //println(uniqueConstraints.mkString("\n"))

    /* Forward solve
     * Returns a solved list where each constraint undergoes:
     *  1) Continuous Solving (using triangular solving)
     *  2) Remove Cycles
     *  3) Move to solved if not self-recursive
     */
    val forwardConstraintMap = new ConstraintMap
    val orderedVars = mutable.HashMap[Int, String]()

    /* Keep track of size of expression to time to optimize */
    val subMap = mutable.HashMap[Int, (Int, Double)]()
    val optMap = mutable.HashMap[Int, (Int, Double)]()
    val rmcMap = mutable.HashMap[Int, (Int, Double)]()
    def update(map: mutable.HashMap[Int, (Int, Double)], time: Double, size: Int): Unit = {
      val (n, prev) = map.getOrElse(size, (0, 0.0))
      map(size) = (n + 1, prev + time)
    }

    //val (total, result) = time {
      var index = 0
      for (constraint <- uniqueConstraints) {
        //TODO: Risky if used improperly... need to check whether substitution from a leq to a geq is negated (always).

        //val (timesub, subbedRight) = time { substitute(forwardConstraintMap)(constraint.right) }
        val subbedRight = substitute(forwardConstraintMap)(constraint.right)
        //update(subMap, timesub, getDepth(subbedRight))


        //val (timeopt, optSubbedRight) = time { subbedRight.optimize() }
        val optSubbedRight = subbedRight//.optimize()
        //update(optMap, timeopt, getDepth(subbedRight))

        val name = constraint.left
        //val (timermc, finishedRight) = time { removeCycle(name, constraint.geq)(subbedRight)}
        val finishedRight = removeCycle(name, constraint.geq)(subbedRight)
        //update(rmcMap, timermc, getDepth(finishedRight))
        if (!hasVar(name)(finishedRight)) {
          forwardConstraintMap(name) = (finishedRight, constraint.geq)
          orderedVars(index) = name
          index += 1
        }
      }
    //}
    //println(s"All Together (ms): $total")
    //println(s"Substitution (ms): ${subMap.valuesIterator.map(_._2).sum}")
    //println(subMap.keys.toSeq.sorted.map(k => (k, subMap(k))).map { case (k, (n, time)) => s"$k -> $n, $time, ${time/n}"}.mkString("\n"))
    //println(s"Optimization (ms): ${optMap.valuesIterator.map(_._2).sum}")
    //println(optMap.keys.toSeq.sorted.map(k => (k, optMap(k))).map { case (k, (n, time)) => s"$k -> $n, $time, ${time/n}"}.mkString("\n"))
    //println(s"Remove Cycle (ms): ${rmcMap.valuesIterator.map(_._2).sum}")
    //println(rmcMap.keys.toSeq.sorted.map(k => (k, rmcMap(k))).map { case (k, (n, time)) => s"$k -> $n, $time, ${time/n}"}.mkString("\n"))
    //println("Forward Constraints!")
    //println(forwardConstraintMap.mkString("\n"))

    //println(orderedVars.size)
    val bsubMap = mutable.HashMap[Int, (Int, Double)]()
    val boptMap = mutable.HashMap[Int, (Int, Double)]()
    //val (btime, dc) = time {
      // Backwards Solve
      for (i <- (orderedVars.size - 1) to 0 by -1) {
        val name = orderedVars(i) // Should visit `orderedVars` backward
        val (forwardRight, forwardGeq) = forwardConstraintMap(name)
        //val (subtime, solvedRight) = time { backwardSubstitution(solvedConstraintMap)(forwardRight) }
        val solvedRight = backwardSubstitution(solvedConstraintMap)(forwardRight)
        //val size = getSize(solvedRight)
        //update(bsubMap, subtime, size)
        //val (opttime, optSolvedRight) = time { solvedRight.optimize() }
        val optSolvedRight = solvedRight//.optimize()
        //update(boptMap, opttime, getDepth(solvedRight))
        //println("HERE 4")
        solvedConstraintMap(name) = (optSolvedRight, forwardGeq)
      }
    //}
    //println(s"Backwards Sub: $btime")
    //println(s"Substitution (ms): ${bsubMap.valuesIterator.map(_._2).sum}")
    //println(bsubMap.keys.toSeq.sorted.map(k => (k, bsubMap(k))).map { case (k, (n, time)) => s"$k -> $n, $time, ${time/n}"}.mkString("\n"))
    //println(s"Optimization (ms): ${boptMap.valuesIterator.map(_._2).sum}")
    //println(boptMap.keys.toSeq.sorted.map(k => (k, boptMap(k))).map { case (k, (n, time)) => s"$k -> $n, $time, ${time/n}"}.mkString("\n"))
  }
}


