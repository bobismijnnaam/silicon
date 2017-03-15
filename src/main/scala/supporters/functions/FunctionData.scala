/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon.supporters.functions

import viper.silver.ast
import viper.silver.ast.utility.Functions
import viper.silicon.{Config, Map, Stack, toMap}
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.interfaces.FatalResult
import viper.silicon.rules.{InverseFunction, functionSupporter}
import viper.silicon.state.{IdentifierFactory, SymbolConverter}
import viper.silicon.state.terms._
import viper.silicon.state.terms.predef._
import viper.silicon.supporters.PredicateData
import viper.silicon.supporters.qps._

/* TODO: Refactor FunctionData!
 *       Separate computations from "storing" the final results and sharing
 *       them with other components. Computations should probably be moved to the
 *       FunctionVerificationUnit.
 */
class FunctionData(val programFunction: ast.Function,
                   val height: Int,
                   val quantifiedFields: InsertionOrderedSet[ast.Field],
                   val program: ast.Program)
                  /* Note: Holding references to fixed symbol converter, identifier factory, ...
                   *       (instead of going through a verifier) is only safe if these are
                   *       either effectively independent of verifiers or if they are not used
                   *       with/in the context of different verifiers.
                   */
                  (symbolConverter: SymbolConverter,
                   expressionTranslator: HeapAccessReplacingExpressionTranslator,
                   identifierFactory: IdentifierFactory,
                   predicateData: ast.Predicate => PredicateData,
                   config: Config) {

  private[this] var phase = 0

  /*
   * Properties computed from the constructor arguments
   */

  val function: HeapDepFun = symbolConverter.toFunction(programFunction)
  val limitedFunction = functionSupporter.limitedVersion(function)
  val statelessFunction = functionSupporter.statelessVersion(function)

  val formalArgs: Map[ast.AbstractLocalVar, Var] = toMap(
    for (arg <- programFunction.formalArgs;
         x = arg.localVar)
    yield
      x -> Var(identifierFactory.fresh(x.name),
               symbolConverter.toSort(x.typ)))

  val formalResult = Var(identifierFactory.fresh(programFunction.result.name),
                         symbolConverter.toSort(programFunction.result.typ))

  val arguments = Seq(`?s`) ++ formalArgs.values

  val functionApplication = App(function, `?s` +: formalArgs.values.toSeq)
  val limitedFunctionApplication = App(limitedFunction, `?s` +: formalArgs.values.toSeq)
  val triggerFunctionApplication = App(statelessFunction, formalArgs.values.toSeq)

  val limitedAxiom =
    Forall(arguments,
           limitedFunctionApplication === functionApplication,
           Trigger(functionApplication))

  val triggerAxiom =
    Forall(arguments, triggerFunctionApplication, Trigger(limitedFunctionApplication))

  /*
   * Data collected during phases 1 (well-definedness checking) and 2 (verification)
   */

  /* TODO: Analogous to fresh FVFs, Nadja records PSFs in the FunctionRecorder,
   *       but they are never used. My guess is, that QP assertions over predicates
   *       are currently not really supported (incomplete? unsound?)
   */

  private[functions] var verificationFailures: Seq[FatalResult] = Vector.empty
  private[functions] var locToSnap: Map[ast.LocationAccess, Term] = Map.empty
  private[functions] var fappToSnap: Map[ast.FuncApp, Term] = Map.empty
  private[this] var freshFvfsAndDomains: InsertionOrderedSet[(FvfDefinition, Seq[Term], Stack[Var])] = InsertionOrderedSet.empty
//  private[this] var freshPsfsAndDomains: InsertionOrderedSet[(PsfDefinition, Seq[Term])] = InsertionOrderedSet.empty
  private[this] var freshFieldInvs: InsertionOrderedSet[InverseFunction] = InsertionOrderedSet.empty
//  private[this] var freshPredInvs: InsertionOrderedSet[PredicateInverseFunction] = InsertionOrderedSet.empty
  private[this] var freshArps: InsertionOrderedSet[(Var, Term)] = InsertionOrderedSet.empty
  private[this] var freshSymbolsAcrossAllPhases: InsertionOrderedSet[Function] = InsertionOrderedSet.empty

  private[functions] def getFreshFieldInvs: InsertionOrderedSet[InverseFunction] = freshFieldInvs
//  private[functions] def getFreshPredInvs: InsertionOrderedSet[PredicateInverseFunction] = freshPredInvs
  private[functions] def getFreshArps: InsertionOrderedSet[Var] = freshArps.map(_._1)
  private[functions] def getFreshSymbolsAcrossAllPhases: InsertionOrderedSet[Function] = freshSymbolsAcrossAllPhases

  private[functions] def advancePhase(recorders: Seq[FunctionRecorder]): Unit = {
    assert(0 <= phase && phase <= 1, s"Cannot advance from phase $phase")

    val mergedFunctionRecorder: FunctionRecorder =
      if (recorders.isEmpty)
        NoopFunctionRecorder
      else
        recorders.tail.foldLeft(recorders.head)((summaryRec, nextRec) => summaryRec.merge(nextRec))

    locToSnap = mergedFunctionRecorder.locToSnap
    fappToSnap = mergedFunctionRecorder.fappToSnap
    freshFvfsAndDomains = mergedFunctionRecorder.freshFvfsAndDomains
//    freshPsfsAndDomains = mergedFunctionRecorder.freshPsfsAndDomains
    freshFieldInvs = mergedFunctionRecorder.freshFieldInvs
//    freshPredInvs = mergedFunctionRecorder.freshPredInvs
    freshArps = mergedFunctionRecorder.freshArps

    freshSymbolsAcrossAllPhases ++= freshArps.map(_._1)
    freshSymbolsAcrossAllPhases ++= freshFieldInvs.map(_.func)

    freshSymbolsAcrossAllPhases ++= freshFvfsAndDomains.map { case (fvfDef, _, _) =>
      fvfDef.fvf match {
        case x: Var => x
        case App(f: Function, _) => f
      }
    }

//    freshSymbolsAcrossAllPhases ++= freshPredInvs.map(_.func)

    phase += 1
  }

  private def generateNestedDefinitionalAxioms: InsertionOrderedSet[Term] = (
       freshFieldInvs.flatMap(_.definitionalAxioms)
//    ++ freshPredInvs.flatMap(_.definitionalAxioms)
    ++ freshFvfsAndDomains.flatMap { case (fvfDef, domDef, _qvars) =>
          val qvars = _qvars filterNot arguments.contains

           val (fvfDefTerms, domDefTerms) = fvfDef match {
              case fvfDef: SummarisingFvfDefinition =>
                (fvfDef.quantifiedValueDefinitions, domDef)
              case fvfDef: QuantifiedChunkFvfDefinition =>
                (fvfDef.valueDefinitions, domDef)
              case fvfDef: SingletonChunkFvfDefinition =>
                val unquantifiedValueDefs = fvfDef.valueDefinitions

                val varsToQuantify =
                  unquantifiedValueDefs.collect{case vd => vd.freeVariables filter qvars.contains}
                                       .flatten

                varsToQuantify match {
                  case Seq() =>
                    (unquantifiedValueDefs, domDef)
                  case Seq(qv) =>
                    val fvfDefTerms = fvfDef.quantifiedValueDefinitions(qv)
                    val domDefTerms =
                      /* TODO: The code here assumes that domDef == fvfDef.domainDefinitions, but
                       *       this is not enforced.
                       *       The whole design of FvfDefinition and its implementations should be
                       *       reconsidered: e.g. separate the pure data groups/containers from the
                       *       computation of definitionals axioms; is it really worth collecting
                       *       fvfDef and domDef independently?
                       */
                      fvfDef.quantifiedDomainDefinitions(qv)

                    (fvfDefTerms, domDefTerms)
                  case _ =>
                    sys.error(s"Expected at most one variable that needs to be quantified, but found $varsToQuantify")
                }
            }

          fvfDefTerms ++ domDefTerms
       }
    ++ freshArps.map(_._2)
  )

  /*
   * Properties resulting from phase 1 (well-definedness checking)
   */

  lazy val translatedPres: Seq[Term] = {
    assert(1 <= phase && phase <= 2, s"Cannot translate precondition in phase $phase")

    expressionTranslator.translatePrecondition(program, programFunction.pres, this)
  }

  lazy val postAxiom: Option[Term] = {
    assert(phase == 1, s"Postcondition axiom must be generated in phase 1, current phase is $phase")

    if (programFunction.posts.nonEmpty) {
      val posts =
        expressionTranslator.translatePostcondition(program, programFunction.posts, this)

      val pre = And(translatedPres)
      val innermostBody = And(generateNestedDefinitionalAxioms ++ List(Implies(pre, And(posts))))
      val bodyBindings: Map[Var, Term] = Map(formalResult -> limitedFunctionApplication)
      val body = Let(toMap(bodyBindings), innermostBody)

      Some(Forall(arguments, body, Trigger(limitedFunctionApplication)))
    } else
      None
  }

  /*
   * Properties resulting from phase 2 (verification)
   */

  lazy val predicateTriggers: Map[ast.Predicate, App] = {
    val recursiveCallsAndUnfoldings: Seq[(ast.FuncApp, Seq[ast.Unfolding])] =
      Functions.recursiveCallsAndSurroundingUnfoldings(programFunction)

    val outerUnfoldings: Seq[ast.Unfolding] =
      recursiveCallsAndUnfoldings.flatMap(_._2.headOption)

    val predicateAccesses: Seq[ast.PredicateAccess] =
      if (recursiveCallsAndUnfoldings.isEmpty)
        Vector.empty
      //        programFunction.pres flatMap (pre =>
      //          pre.shallowCollect { case predacc: ast.PredicateAccess => predacc })
      else
        outerUnfoldings map (_.acc.loc)

    toMap(predicateAccesses.map(predacc => {
      val predicate = program.findPredicate(predacc.predicateName)
      val triggerFunction = predicateData(predicate).triggerFunction

      /* TODO: Don't use translatePrecondition - refactor expressionTranslator */
      val args = (
           expressionTranslator.getOrFail(locToSnap, predacc, sorts.Snap, programFunction.name)
        +: expressionTranslator.translatePrecondition(program, predacc.args, this))

      val fapp = App(triggerFunction, args)

      predicate -> fapp
    }))
  }

  lazy val definitionalAxiom: Option[Term] = {
    assert(phase == 2, s"Definitional axiom must be generated in phase 2, current phase is $phase")

    val optBody = expressionTranslator.translate(program, programFunction, this)

    optBody.map(translatedBody => {
      val pre = And(translatedPres)
      val nestedDefinitionalAxioms = generateNestedDefinitionalAxioms
      val innermostBody = And(nestedDefinitionalAxioms ++ List(Implies(pre, And(functionApplication === translatedBody))))
      val bodyBindings: Map[Var, Term] = Map(formalResult -> limitedFunctionApplication)
      val body = Let(toMap(bodyBindings), innermostBody)
      val allTriggers = (
           Seq(Trigger(functionApplication))
        ++ predicateTriggers.values.map(pt => Trigger(Seq(triggerFunctionApplication, pt))))

      Forall(arguments, body, allTriggers)})
  }
}
