package tarski

import scala.language.implicitConversions
import org.apache.commons.lang.StringEscapeUtils._
import tarski.AST._
import tarski.Denotations._
import tarski.Items._

object TestUtils {
  // AST implicit conversions
  implicit def toAExp(i: Int): AExp = IntALit(i.toString)
  implicit def toAExp(s: String): AExp = NameAExp(s)
  implicit def toAStmt(e: AExp): AStmt = ExpAStmt(e)
  implicit def toAStmts(e: AExp): List[AStmt] = List(ExpAStmt(e))

  // Denotation implicit conversions
  implicit def toExp(i: Int): Exp = IntLit(i,i.toString)
  implicit def toExp(c: Char): Exp = CharLit(c, "'" + escapeJava(c.toString) + "'")
  implicit def toExp(x: LocalVariableItem): Exp = LocalVariableExp(x)
  implicit def toExps[A](xs: List[A])(implicit to: A => Exp): List[Exp] = xs map to

  def assertIn[A](x: A, xs: Set[A]): Unit =
    if (!xs.contains(x))
      throw new AssertionError("assertIn failed:\nx  = "+x+"xs = "+xs.mkString("\n     "))

  def assertSetsEqual[A](exp: Traversable[A], got: Traversable[A]): Unit = {
    def s(n: Name, xs: Set[A]) = f"\n$n%-7s = ${xs.mkString("\n          ")}"
    val e = exp.toSet
    val g = got.toSet
    if (e != g)
      throw new AssertionError("assertSetsEqual failed:"
        +s("exp",e)+s("got",g)+s("exp-got",e--g)+s("got-exp",g--e))
  }
}