/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package edp.wormhole.swifts

import com.alibaba.fastjson.{JSON, JSONObject}
import edp.wormhole.pattern.JsonFieldName.{KEYBYFILEDS, OUTPUT}
import edp.wormhole.pattern.Output.{FIELDLIST, TYPE}
import edp.wormhole.pattern.{OutputType, PatternGenerator, PatternOutput}
import edp.wormhole.util.FlinkSchemaUtils
import edp.wormhole.util.FlinkSchemaUtils._
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.cep.scala.CEP
import org.apache.flink.streaming.api.scala.{DataStream, _}
import org.apache.flink.table.api.Types
import org.apache.flink.table.api.scala.StreamTableEnvironment
import org.apache.flink.types.Row
import org.apache.log4j.Logger



object SwiftsProcess extends Serializable {
  val logger: Logger = Logger.getLogger(this.getClass)

  private var preSchemaMap: Map[String, (TypeInformation[_], Int)] = FlinkSchemaUtils.sourceSchemaMap.toMap

  def process(dataStream: DataStream[Row], tableEnv: StreamTableEnvironment, swiftsSql: Option[Array[SwiftsSql]]): (DataStream[Row], Map[String, (TypeInformation[_], Int)]) = {
    var transformedStream = dataStream
    if (swiftsSql.nonEmpty) {
      val swiftsSqlGet = swiftsSql.get
      for (index <- swiftsSqlGet.indices) {
        val element = swiftsSqlGet(index)
        element.optType match {
          case SqlOptType.FLINK_SQL => transformedStream = doFlinkSql(transformedStream, tableEnv, element.sql, index)
          case SqlOptType.CEP => transformedStream = doCEP(transformedStream, element.sql, index)
          case SqlOptType.JOIN | SqlOptType.LEFT_JOIN => transformedStream = doLookup(dataStream, element, index)
        }
      }
    }
    (transformedStream, preSchemaMap)
  }


  private def doFlinkSql(dataStream: DataStream[Row], tableEnv: StreamTableEnvironment, sql: String, index: Int) = {
    var table = tableEnv.fromDataStream(dataStream)
    table.printSchema()
    val newSql = sql.substring(0, sql.indexOf("from"))
    logger.info( s"""$newSql from $table""")
    table = tableEnv.sqlQuery(s"""$newSql from $table""")
    table.printSchema()
    val key = s"swifts$index"
    val value = FlinkSchemaUtils.getSchemaMapFromTable(table.getSchema)
    preSchemaMap = value
    FlinkSchemaUtils.setSwiftsSchema(key, value)
    val resultDataStream = tableEnv.toAppendStream[Row](table).map(o => o)(Types.ROW(tableFieldNameArray(table.getSchema), tableFieldTypeArray(table.getSchema)))
    resultDataStream.print()
    logger.info(resultDataStream.dataType.toString + "in  doFlinkSql")
    resultDataStream
  }


  private def doCEP(dataStream: DataStream[Row], sql: String, index: Int) = {
    val patternSeq = JSON.parseObject(sql)
    val patternGenerator = new PatternGenerator(patternSeq, preSchemaMap)
    val pattern = patternGenerator.getPattern
    val keyByFields = patternSeq.getString(KEYBYFILEDS.toString).trim
    val patternStream = if (keyByFields != null && keyByFields.nonEmpty) {
      val keyArray = keyByFields.split(",").map(key => preSchemaMap(key)._2)
      CEP.pattern(dataStream.keyBy(keyArray: _*), pattern)
    } else CEP.pattern(dataStream, pattern)
    val resultDataStream = new PatternOutput(patternSeq.getJSONObject(OUTPUT.toString), preSchemaMap).getOutput(patternStream, patternGenerator, keyByFields)
    resultDataStream.print()
    logger.info(resultDataStream.dataType.toString + "in  doCep")
    setSwiftsSchemaWithCEP(patternSeq, index, keyByFields)
    resultDataStream
  }


  private def setSwiftsSchemaWithCEP(patternSeq: JSONObject, index: Int, keyByFields: String): Unit = {
    val key = s"swifts$index"
    if (!FlinkSchemaUtils.swiftsProcessSchemaMap.contains(key)) {
      val output = patternSeq.getJSONObject(OUTPUT.toString)
      val outputFieldList: Array[String] =
        if (output.containsKey(FIELDLIST.toString)) {
          output.getString(FIELDLIST.toString).split(",")
        } else {
          Array.empty[String]
        }
      val outputType: String = output.getString(TYPE.toString)
      val newSchema = if (OutputType.outputType(outputType) == OutputType.AGG) {
        val fieldNames = getOutputFieldNames(outputFieldList, keyByFields)
        val fieldTypes = getOutPutFieldTypes(fieldNames, preSchemaMap)
        FlinkSchemaUtils.getSchemaMapFromArray(fieldNames, fieldTypes)
      } else preSchemaMap
      swiftsProcessSchemaMap += key -> newSchema
    }
    preSchemaMap = swiftsProcessSchemaMap(key)
  }

  private def doLookup(dataStream: DataStream[Row], element: SwiftsSql, index: Int) = {
    val lookupSchemaMap = LookupHelper.getLookupSchemaMap(preSchemaMap, element)
    val fieldNames = FlinkSchemaUtils.getFieldNamesFromSchema(lookupSchemaMap)
    val fieldTypes = FlinkSchemaUtils.getOutPutFieldTypes(fieldNames, lookupSchemaMap)
    val resultDataStream = dataStream.map(new LookupMapper(element, preSchemaMap, SwiftsConfMemoryStorage.getDataStoreConnectionsMap)).flatMap(o => o)(Types.ROW(fieldNames, fieldTypes))
    val key = s"swifts$index"
    if (!FlinkSchemaUtils.swiftsProcessSchemaMap.contains(key))
      swiftsProcessSchemaMap += key -> lookupSchemaMap
    preSchemaMap = swiftsProcessSchemaMap(key)
    resultDataStream.print()
    logger.info(resultDataStream.dataType.toString + "in  doLookup")
    resultDataStream
  }


}
