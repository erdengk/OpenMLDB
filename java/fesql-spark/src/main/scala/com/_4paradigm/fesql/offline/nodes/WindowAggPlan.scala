package com._4paradigm.fesql.offline.nodes

import com._4paradigm.fesql.offline._
import com._4paradigm.fesql.offline.utils.{FesqlUtil, SparkColumnUtil, SparkRowUtil}
import com._4paradigm.fesql.vm.{CoreAPI, FeSQLJITWrapper, PhysicalWindowAggrerationNode, WindowInterface}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.{Column, DataFrame, Row}
import org.apache.spark.sql.types._

import scala.collection.mutable


object WindowAggPlan {

  def gen(ctx: PlanContext, node: PhysicalWindowAggrerationNode, input: SparkInstance): SparkInstance = {
    val inputDf = groupAndSort(ctx, node, input)

    val inputSchemaSlices = FesqlUtil.getOutputSchemaSlices(node.GetProducer(0))
    val outputSchemaSlices = FesqlUtil.getOutputSchemaSlices(node)
    val outputSchema = FesqlUtil.getSparkSchema(node.GetOutputSchema())

    // process window op
    val windowOp = node.window()

    // process order key
    val orders = windowOp.sort().orders().order_by()
    if (orders.GetChildNum() > 1) {
      throw new FeSQLException("Multiple window order not supported")
    }
    val (orderName, orderIdx, _) = SparkColumnUtil.resolveColumn(orders.GetChild(0), inputDf, ctx)
    if (orderIdx < 0) {
      throw new FeSQLException(s"Fail to find column $orderName")
    }

    // process group-by keys
    val groups = windowOp.partition().keys()

    val groupIdxs = mutable.ArrayBuffer[Int]()
    for (k <- 0 until groups.GetChildNum()) {
      val (colName, colIdx, _) = SparkColumnUtil.resolveColumn(groups.GetChild(k), inputDf, ctx)
      if (colIdx < 0) {
        throw new FeSQLException(s"Fail to find column $colName")
      }
      groupIdxs += colIdx
    }

    val windowAggConfig = WindowAggConfig(
      startOffset = node.window.range.start_offset,
      orderIdx = orderIdx,
      groupIdxs = groupIdxs.toArray,
      functionName = node.project.fn_name,
      moduleTag = ctx.getTag,
      moduleBroadcast = ctx.getModuleBufferBroadcast,
      inputSchema = inputDf.schema,
      inputSchemaSlices = inputSchemaSlices,
      outputSchemaSlices = outputSchemaSlices
    )

    val resultRDD = inputDf.rdd.mapPartitions(iter => {
      // ensure worker native
      val tag = windowAggConfig.moduleTag
      val buffer = windowAggConfig.moduleBroadcast.value.getBuffer
      JITManager.initJITModule(tag, buffer)
      val jit = JITManager.getJIT(tag)

      windowAggIter(iter, jit, windowAggConfig)
    })

    SparkInstance.fromRDD(null, outputSchema, resultRDD)
  }


  def groupAndSort(ctx: PlanContext, node: PhysicalWindowAggrerationNode, input: SparkInstance): DataFrame = {
    val inputDf = input.getDf(ctx.getSparkSession)
    val windowOp = node.window()
    val groupByExprs = windowOp.partition().keys()

    val groupByCols = mutable.ArrayBuffer[Column]()
    for (i <- 0 until groupByExprs.GetChildNum()) {
      val expr = groupByExprs.GetChild(i)
      val (_, _, column) = SparkColumnUtil.resolveColumn(expr, inputDf, ctx)
      groupByCols += column
    }

    val partitions = ctx.getConf("fesql.group.partitions", 0)
    val groupedDf = if (partitions > 0) {
      inputDf.repartition(partitions, groupByCols: _*)
    } else {
      inputDf.repartition(groupByCols: _*)
    }

    val orders = windowOp.sort().orders()
    val orderExprs = orders.order_by()
    val orderByCols = mutable.ArrayBuffer[Column]()
    for (i <- 0 until orderExprs.GetChildNum()) {
      val expr = orderExprs.GetChild(i)
      val (_, _, column) = SparkColumnUtil.resolveColumn(expr, inputDf, ctx)
      if (orders.is_asc()) {
        orderByCols += column.asc
      } else {
        orderByCols += column.desc
      }
    }
    val sortedDf = groupedDf.sortWithinPartitions(groupByCols ++ orderByCols: _*)
    sortedDf
  }


  def windowAggIter(inputIter: Iterator[Row], jit: FeSQLJITWrapper, config: WindowAggConfig): Iterator[Row] = {
    var lastRow: Row = null
    var window = new WindowInterface(config.startOffset, 0, 0)


    // reusable output row inst
    val outputFieldNum = config.outputSchemaSlices.map(_.size).sum
    val outputArr = Array.fill[Any](outputFieldNum)(null)

    val fn = jit.FindFunction(config.functionName)
    val bufferPool = new NativeBufferPool

    // TODO: these objects are now leaked
    val encoder = new SparkRowCodec(config.inputSchemaSlices, bufferPool)
    val decoder = new SparkRowCodec(config.outputSchemaSlices, null)

    // order key extractor
    val orderField = config.inputSchema(config.orderIdx)
    val orderKeyExtractor = SparkRowUtil.createOrderKeyExtractor(
      config.orderIdx, orderField.dataType, orderField.nullable)

    // group key comparation
    val groupKeyComparator = createGroupKeyComparator(
      config.groupIdxs, config.inputSchema)

    inputIter.map(row => {
      // build new window for each group
      if (lastRow != null) {
        val groupChanged = groupKeyComparator.apply(row, lastRow)
        if (groupChanged) {
          // TODO: wrap iter to hook iter end; now last window is leak
          window.delete()
          window = new WindowInterface(config.startOffset, 0, 0)

          bufferPool.freeAll()
        }
      }
      lastRow = row

      // do window compute
      computeWindow(row, fn, orderKeyExtractor,
        window, encoder, decoder, outputArr)
    })
  }


  def computeWindow(row: Row, fn: Long,
                    orderKeyExtractor: Row => Long,
                    window: WindowInterface,
                    encoder: SparkRowCodec,
                    decoder: SparkRowCodec,
                    outputArr: Array[Any]): Row = {
    // call encode
    val nativeInputRow = encoder.encode(row, keepBuffer=true)

    // extract key
    val key = orderKeyExtractor.apply(row)

    // call native compute
    val outputNativeRow = CoreAPI.WindowProject(fn, key, nativeInputRow, window)

    // call decode
    decoder.decode(outputNativeRow, outputArr)

    // release swig jni objects
    nativeInputRow.delete()
    outputNativeRow.delete()

    Row.fromSeq(outputArr)  // can reuse backed array
  }


  def createGroupKeyComparator(keyIdxs: Array[Int], schema: StructType): (Row, Row) => Boolean = {
    if (keyIdxs.length == 1) {
       val idx = keyIdxs(0)
      (row1, row2) => {
        row1.get(idx) != row2.get(idx)
      }
    } else {
      (row1, row2) => {
        keyIdxs.exists(i => row1.get(i) != row2.get(i))
      }
    }
  }


  // spark closure class
  case class WindowAggConfig(startOffset: Long,
                             orderIdx: Int,
                             groupIdxs: Array[Int],
                             functionName: String,
                             moduleTag: String,
                             moduleBroadcast: Broadcast[SerializableByteBuffer],
                             inputSchema: StructType,
                             inputSchemaSlices: Array[StructType],
                             outputSchemaSlices: Array[StructType])
}
