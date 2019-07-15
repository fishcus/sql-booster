package org.apache.spark.sql.catalyst


import org.apache.spark.SparkFunSuite
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.expressions.{Expression, PredicateHelper}
import org.apache.spark.sql.catalyst.optimizer._
import org.apache.spark.sql.catalyst.plans.PlanTest
import org.apache.spark.sql.catalyst.plans.logical.{Filter, LocalRelation, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types.StructType
import tech.mlsql.schema.parser.SparkSimpleSchemaParser
import tech.mlsql.sqlbooster.meta.ViewCatalyst

/**
  * 2019-07-11 WilliamZhu(allwefantasy@gmail.com)
  */
class PlanDebugSuite extends PlanTest with PredicateHelper {
  val testRelation0 = LocalRelation('a.int, 'b.int, 'c.int).as("t0")
  val testRelation1 = LocalRelation('d.int).as("t1")
  val testRelation2 = LocalRelation('b.int, 'c.int, 'e.int).as("t2")
  ViewCatalyst.createViewCatalyst(None)

  //  test("mv") {
  //    val viewLp = testRelation0.where("t0.a".attr === "jack").select("t0.*".attr)
  //    ViewCatalyst.meta.registerViewForTest("ct", viewLp)
  //
  //    val lp = testRelation0.where("t0.d".attr === 3 && "t0.a".attr === "jack").select("t0.*".attr)
  //    val analyzed = lp.analyze
  //    println(analyzed)
  //    val optimized = Optimize.execute(analyzed)
  //    println(optimized)
  //  }

  test("join only") {
    val left = testRelation0.where('a === 1)
    val right = testRelation1
    val originalQuery =
      left.join(right, condition = Some("t0.d".attr === "t1.b".attr || "t0.d".attr === "t1.c".attr)).join(
        testRelation2, condition = Some("t0.d".attr === "t2.c".attr)).analyze
    println(originalQuery)
    val m = testRelation0.where("t0.d".attr === "t1.b".attr || "t0.d".attr === "t1.c".attr)
    m match {
      case Filter(condition, _) =>
        condition.toString()
        println(splitConjunctivePredicates(condition))
    }
  }

  test("where wow") {
    val wow = testRelation0.where(("t0.a".attr === 1 && ("t0.a".attr > 0 || ("t0.a".attr < 4 && "t0.a".attr > 2))) && ("t0.b".attr === 3))
    var wowE: Seq[Expression] = null
    normalizePlan(normalizeExprIds(wow)) match {
      case Filter(condition, _) =>
        wowE = splitConjunctivePredicates(condition)
    }
    val wow2 = testRelation0.where(("t0.a".attr === 1 && ("t0.a".attr > 0 || ("t0.a".attr < 4 && "t0.a".attr > 2))))
    var wowE2: Seq[Expression] = null
    normalizePlan(normalizeExprIds(wow2)) match {
      case Filter(condition, _) =>
        wowE2 = splitConjunctivePredicates(condition)
    }
    println(wowE(0) == wowE2(0))

    val zipCount = Math.min(wowE.size, wowE2.size)
    (0 until zipCount).map { index => if (wowE(index) == wowE2(index)) 0 else 1 }.sum == 0

    println(wowE.toSet -- wowE2)
  }


}

object Optimize extends RuleExecutor[LogicalPlan] {
  val batches =
    Batch("Operator Optimizations", FixedPoint(100),
      CombineFilters,
      PushDownPredicate,
      ReorderJoin,
      PushPredicateThroughJoin,
      ColumnPruning,
      CollapseProject) ::
      Batch("Join Reorder", Once,
        CostBasedJoinReorder) :: Nil
}

object OptimizeRewrite extends RuleExecutor[LogicalPlan] {
  val batches =
    Batch("User Rewriter", Once,
      RewriteTableToViews) :: Nil
}

class WholeTestSuite extends SparkFunSuite {


  type ExtensionsBuilder = SparkSessionExtensions => Unit

  private def create(builder: ExtensionsBuilder): ExtensionsBuilder = builder

  private def stop(spark: SparkSession): Unit = {
    spark.stop()
    SparkSession.clearActiveSession()
    SparkSession.clearDefaultSession()
  }

  private def withSession(builder: ExtensionsBuilder)(f: SparkSession => Unit): Unit = {
    val spark = SparkSession.builder().master("local[1]").withExtensions(builder).getOrCreate()
    try f(spark) finally {
      stop(spark)
    }
  }

  test("mv") {
    val extension = create { extensions =>
      //extensions.injectOptimizerRule(RewriteTableToView)
    }

    withSession(extension) { spark =>
      ViewCatalyst.createViewCatalyst()

      spark.sql("select 1 as a, 2 as b,3 as c").write.mode(SaveMode.Overwrite).parquet("/tmp/table1")
      spark.sql("select 1 as a1, 2 as b1,3 as c1").write.mode(SaveMode.Overwrite).parquet("/tmp/table2")

      val table1 = spark.read.parquet("/tmp/table1")
      table1.createOrReplaceTempView("table1")
      val table2 = spark.read.parquet("/tmp/table2")
      table2.createOrReplaceTempView("table2")

      spark.sql("select a,b,c from table1 where a=1").write.mode(SaveMode.Overwrite).parquet("/tmp/viewTable1")
      val viewTable1 = spark.read.parquet("/tmp/viewTable1").select("*")
      val createViewTable1 = spark.sql("select a,b,c from table1 where a=1")

      ViewCatalyst.meta.registerFromLogicalPlan("viewTable1", viewTable1.logicalPlan, createViewTable1.logicalPlan)

      val analyzed = spark.sql(""" select a,b from table1 where a=1 and b=2 """).queryExecution.analyzed

      //      val optimized = OptimizeRewrite.execute(analyzed)
      //      println(optimized)

      spark.sql("select a,b,count(*) as total,sum(b) as wow from table1 where a=1 group by a,b").write.mode(SaveMode.Overwrite).parquet("/tmp/viewTable2")
      val viewTable2 = spark.read.parquet("/tmp/viewTable2").select("*")
      val createViewTable2 = spark.sql("select a,b,count(*) as total,sum(b) as wow from table1 where a=1 group by a,b")

      ViewCatalyst.meta.registerFromLogicalPlan("viewTable2", viewTable2.logicalPlan, createViewTable2.logicalPlan)

      val analyzed2 = spark.sql(""" select b,count(*) as jack,sum(b) as wow1 from table1 where a=1 group by b """).queryExecution.analyzed
      println("before:" + analyzed2)

      val optimized2 = OptimizeRewrite.execute(analyzed2)
      println(optimized2)

      //      println(analyzed)
      //      println(new LogicalPlanSQL(analyzed, new BasicSQLDialect).toSQL)
      //
      //      val analyzed2 = spark.sql(""" select sum(c)/kcount as k from (select a,count(*) as kcount,a as b,1 as c from at group by a) group by kcount  """).queryExecution.analyzed
      //      println(analyzed2)

    }
  }
}

class DFDebugSuite extends QueryTest with SharedSQLContext {

  def createTable(name: String, schema: String) = {
    val df = spark.createDataFrame(spark.sparkContext.emptyRDD[Row], SparkSimpleSchemaParser.parse(schema).asInstanceOf[StructType])
    df.createOrReplaceTempView(name)
  }

  test("test") {
    createTable("at", "st(field(a,string),field(b,string))")
    createTable("bt", "st(field(a1,string),field(b1,string))")

    val df = spark.sql("select * from (select at.b,count(bt.b1) as c from at left join bt on at.a == bt.a1 where at.a='yes' group by at.b) m")
    println(df.queryExecution.analyzed)

    println(spark.sql("select * from at where at.a='yes' order by at.b desc").queryExecution.analyzed)
    println(spark.sql("select * from at,bt where at.a=bt.a1 and (at.b=bt.b1 or at.a=bt.b1)").queryExecution.analyzed)

  }
}




