package rpi.context

import rpi.util.{Collections, Expressions}
import viper.silver.ast

/**
  * Atoms for the a program.
  *
  * Note: currently, the program is ignored but it is passed as an argument to make future extensions easier.
  *
  * @param program The program.
  */
class Atoms(program: ast.Program) {
  /**
    * The atom templates.
    */
  private val templates = {
    val x = ast.LocalVarDecl("x", ast.Ref)()
    val y = ast.LocalVarDecl("y", ast.Ref)()
    val nullity = ast.Predicate("nullity", Seq(x), Some(ast.NeCmp(x.localVar, ast.NullLit()())()))()
    val equality = ast.Predicate("equality", Seq(x, y), Some(ast.NeCmp(x.localVar, y.localVar)()))()
    Seq(nullity, equality)
  }

  def fromParameters(parameters: Seq[ast.LocalVarDecl]): Seq[ast.Exp] = {
    val variables = parameters.map { parameter => parameter.localVar }
    fromExpressions(variables)
  }

  def fromExpressions(expressions: Seq[ast.Exp]): Seq[ast.Exp] = {
    // get reference-typed expressions
    val references = expressions
      .filter { expression => expression.typ == ast.Ref }
    // instantiate templates
    templates.flatMap { template =>
      template.formalArgs.length match {
        case 1 => references
          .map { variable =>
            Expressions.instantiate(template, Seq(variable))
          }
        case 2 => Collections
          .pairs(references)
          .map { case (first, second) =>
            Expressions.instantiate(template, Seq(first, second))
          }
      }
    }
  }
}
