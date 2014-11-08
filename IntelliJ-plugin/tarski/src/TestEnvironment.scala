package tarski

import org.testng.annotations.Test
import org.testng.AssertJUnit._
import tarski.Base._
import tarski.Environment._
import tarski.Items._
import tarski.Types._
import tarski.Pretty._
import tarski.Tokens._

class TestEnvironment {

  @Test
  def testStaticShadowedByLocal() = {
    val main = NormalClassItem("Main",LocalPkg,Nil,ObjectType,Nil)
    val yf = StaticFieldItem("y",FloatType,main)
    val f = StaticMethodItem("f",main,FloatType,List(ArrayType(IntType)))
    val y = LocalVariableItem("y",ArrayType(DoubleType))
    val scope = Map[NamedItem,Int]((LocalPkg,4),(main,3),(yf,2),(f,2),(y,1))
    implicit val env = Env(List(main,f),scope)
    assertEquals(tokens(y), List(IdentTok("y")))
    assertEquals(tokens(yf), List(IdentTok("Main"),DotTok(), IdentTok("y")))
  }

}