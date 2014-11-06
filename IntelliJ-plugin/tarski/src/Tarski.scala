package tarski

import Environment._
import Tokens._
import Items._
import tarski.Scores._
import tarski.Denotations._
import tarski.Semantics._
import tarski.Pretty._

import scala.collection.JavaConverters._

object Tarski {
  def environment(types: java.util.Collection[NamedItem], values: java.util.Collection[NamedItem]): Env = {
    baseEnvironment.addObjects(types.asScala.toList++values.asScala.toList)
  }

  def fixJava(tokens: java.util.List[Token], env: Env): java.util.List[(Score,java.util.List[StmtDen])] = {
    val toks = tokens.asScala.toList
    val r = fix(toks)(env)
    (r map {case (e,ss) => ss.asJava}).c.asJava
  }

  def pretty(stmts: java.util.List[StmtDen]): String = {
    val ss: List[StmtDen] = stmts.asScala.toList
    show(tokens(ss))
  }

  def fix(tokens: List[Token])(implicit env: Env): Scored[(Env,List[StmtDen])] = {
    println("parsing " + show(tokens))
    val asts = ParseEddy.parse(tokens.filterNot(isSpace))
    if (asts.isEmpty)
      println("  no asts")

    // Check for duplicates
    val uasts = asts.toSet
    var bad = false
    for (a <- uasts; n = asts.count(a==_); if n > 1) {
      println(s"  $n copied ast: $a")
      bad = true
    }
    if (bad)
      throw new RuntimeException("duplicated ast")

    // Determine meaning(s)
    var results: Scored[(Env,List[StmtDen])] = fail
    for (root <- uasts) {
      println("  ast: " + show(Pretty.tokens(root)))
      //println("  ast: " + root)
      println("  meanings: ")
      val ds = denoteStmts(root)(env)
      for ((s,(e,d)) <- ds.c) {
        println(s"    $s: $d")
      }
      results ++= ds
    }
    results
  }
}