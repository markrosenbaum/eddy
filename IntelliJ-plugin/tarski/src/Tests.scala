package tarski

import tarski.AST._
import tarski.Denotations._

import org.apache.commons.lang.StringEscapeUtils.escapeJava
import scala.language.implicitConversions
import org.testng.annotations.{BeforeClass, Test}
import org.testng.AssertJUnit._

import tarski.Tarski.fix
import tarski.Environment.{Env,baseEnvironment}
import tarski.Items._
import tarski.Lexer._
import tarski.Tokens._
import tarski.Pretty._
import ambiguity.Utility._

class Tests {

  @BeforeClass
  def init(): Unit = {
    // this happens once

    // read default java environment from file
    // TODO
  }

  // Useful implicit conversions
  implicit def toExp(i: Int): AExp = IntALit(i.toString)
  implicit def toExpDen(i: Int): ExpDen = IntLit(i,i.toString)
  implicit def toExpDen(c: Char): ExpDen = CharLit(c, "'" + escapeJava(c.toString) + "'")
  implicit def toExpDen(x: LocalVariableItem): ExpDen = LocalVariableExpDen(x)
  implicit def toInitDen[A](x: A)(implicit to: A => ExpDen): InitDen = ExpInitDen(to(x))
  implicit def toExpDens[A](xs: List[A])(implicit to: A => ExpDen): List[ExpDen] = xs map to
  implicit def toInitDens[A](xs: List[A])(implicit to: A => ExpInitDen): List[ExpInitDen] = xs map to

  def testDenotation(input: String, best: Env => List[StmtDen])(implicit env: Env) = {
    val (env2,stmt) = fix(lex(input).filterNot(isSpace)).best.get
    assertEquals(stmt, best(env2))
  }

  @Test
  def assignExp(): Unit = {
    val x = LocalVariableItem("x", IntType)
    implicit val env = new Env(List(x))
    testDenotation("x = 1", env => List(ExprStmtDen(AssignExpDen(None,x,1))))
  }

  @Test
  def variableStmt(): Unit = {
    implicit val env = baseEnvironment
    testDenotation("x = 1", env => List(VarStmtDen(IntType, List((env.exactLocal("x"), Some(toInitDen(1)))))))
  }

  @Test
  def arrayVariableStmtCurly(): Unit = {
    implicit val env = baseEnvironment
    testDenotation("x = {1,2,3,4}", env => List(VarStmtDen(ArrayType(IntType), List((env.exactLocal("x"),
      Some(ArrayInitDen(List(1,2,3,4),IntType)))))))
  }

  @Test
  def arrayVariableStmtParen(): Unit = {
    implicit val env = baseEnvironment
    testDenotation("x = (1,2,3,4)", env => List(VarStmtDen(ArrayType(IntType), List((env.exactLocal("x"),
      Some(ArrayInitDen(List(1,2,3,4),IntType)))))))
  }

  @Test
  def arrayVariableStmtBare(): Unit = {
    implicit val env = baseEnvironment
    testDenotation("x = 1,2,3,4", env => List(VarStmtDen(ArrayType(IntType), List((env.exactLocal("x"),
      Some(ArrayInitDen(List(1,2,3,4),IntType)))))))
  }

  @Test
  def arrayVariableStmtBrack(): Unit = {
    implicit val env = baseEnvironment
    testDenotation("x = [1,2,3,4]", env => List(VarStmtDen(ArrayType(IntType), List((env.exactLocal("x"),
      Some(ArrayInitDen(List(1,2,3,4),IntType)))))))
  }

  @Test
  def arrayLiteral(): Unit = {
    val main = ClassType("Main", LocalPkg, "Main", ObjectType, Nil)
    val f = MethodItem("f", main, "f", VoidType, List(ArrayType(IntType)))
    implicit val env = Env(List(main,f))
    testDenotation("f({1,2,3,4})", env => List(VarStmtDen(ArrayType(IntType), List((env.exactLocal("x"),
      Some(ArrayInitDen(List(1,2,3,4),IntType)))))))
  }

  @Test
  def makeAndSet(): Unit = {
    implicit val env = baseEnvironment
    testDenotation("x = 1; x = 2", env => {
      val x = env.exactLocal("x")
      List(VarStmtDen(IntType, List((x,Some(toInitDen(1))))),
           ExprStmtDen(AssignExpDen(None,x,2)))
    })
  }

  @Test
  def indexExp(): Unit = {
    val x = LocalVariableItem("x", ArrayType(CharType))
    implicit val env = Env(List(x))
    testDenotation("""x[4] = '\n'""", env => List(ExprStmtDen(AssignExpDen(None, IndexExpDen(x,4), '\n'))))
  }

  @Test
  def nestedIndexExpBrack(): Unit = {
    val x = new LocalVariableItem("x", ArrayType(ArrayType(CharType)))
    implicit val env = Env(List(x))
    testDenotation("""x[4,5] = x[2][5]""", env => List(ExprStmtDen(AssignExpDen(None, IndexExpDen(IndexExpDen(x,4),5), IndexExpDen(IndexExpDen(x,2),5)))))
  }

  @Test
  def nestedIndexExpJuxt(): Unit = {
    val x = new LocalVariableItem("x", ArrayType(ArrayType(CharType)))
    implicit val env = Env(List(x))
    testDenotation("""x 4 5 = x 2 5""", env => List(ExprStmtDen(AssignExpDen(None, IndexExpDen(IndexExpDen(x,4),5), IndexExpDen(IndexExpDen(x,2),5)))))
  }

  @Test
  def nestedIndexExpMixed(): Unit = {
    val x = new LocalVariableItem("x", ArrayType(ArrayType(CharType)))
    implicit val env = Env(List(x))
    testDenotation("""x{4,5} = x{2}[5]""", env => List(ExprStmtDen(AssignExpDen(None, IndexExpDen(IndexExpDen(x,4),5), IndexExpDen(IndexExpDen(x,2),5)))))
  }

  @Test
  def nestedIndexExpParen(): Unit = {
    val x = new LocalVariableItem("x", ArrayType(ArrayType(CharType)))
    implicit val env = Env(List(x))
    testDenotation("""x(4,5) = x(2)(5)""", env => List(ExprStmtDen(AssignExpDen(None, IndexExpDen(IndexExpDen(LocalVariableExpDen(x), 4), 5), IndexExpDen(IndexExpDen(LocalVariableExpDen(x), 2), 5)))))
  }

  @Test
  def indexOpExp(): Unit = {
    val x = LocalVariableItem("x", ArrayType(CharType))
    implicit val env = Env(List(x))
    testDenotation("""x[4] *= '\n'""", env => List(ExprStmtDen(AssignExpDen(Some(MulOp()), IndexExpDen(LocalVariableExpDen(x), 4), '\n'))))
  }

  @Test
  def mapExp(): Unit = {
    val main = new ClassType("Main", LocalPkg, "Main", ObjectType, Nil)
    val f = new StaticMethodItem("f", main, "f", FloatType, List(ArrayType(IntType)))
    val x = new LocalVariableItem("x", ArrayType(DoubleType))
    val y = new LocalVariableItem("y", ArrayType(DoubleType))
    implicit val env = Env(List(main,f))
    testDenotation("y = f(x)", env => Nil)
    throw notImplemented
  }

  @Test
  def lexer(): Unit = {
    // Utilities
    def spaced(ts: List[Token]): List[Token] = ts match {
      case Nil|List(_) => ts
      case x :: xs => x :: WhitespaceTok(" ") :: spaced(xs)
    }
    def check(name: String, cons: String => Token, options: String) =
      assertEquals(spaced(splitWhitespace(options) map cons),lex(options))

    assertEquals(spaced(List(AbstractTok(),FinalTok(),DoTok())),lex("abstract final do"))
    check("ints",IntLitTok,"0 1 17l 0x81 07_43 0b1010_110")
    check("floats",FloatLitTok,"5.3 .4e-8 0x4.aP1_7")
    check("chars",CharLitTok,"""'x' '\t' '\n' '\0133'""")
    check("strings",StringLitTok,""""xyz" "\n\b\r\t" "\0\1\2"""")
  }

  @Test
  def pretty(): Unit = {
    def check(s: String, e: AExp) = assertEquals(s,show(tokens(e)))
    def add(x: AExp, y: AExp) = BinaryAExp(AddOp(),x,y)
    def mul(x: AExp, y: AExp) = BinaryAExp(MulOp(),x,y)

    check("1 + 2 + 3",     add(add(1,2),3))
    check("1 + ( 2 + 3 )", add(1,add(2,3)))
    check("1 + 2 * 3",     add(1,mul(2,3)))
    check("1 * 2 + 3",     add(mul(1,2),3))
    check("1 * ( 2 + 3 )", mul(1,add(2,3)))
    check("( 1 + 2 ) * 3", mul(add(1,2),3))
  }
}
