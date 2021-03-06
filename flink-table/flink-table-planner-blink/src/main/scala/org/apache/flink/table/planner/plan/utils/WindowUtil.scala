/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.plan.utils

import org.apache.calcite.util.ImmutableBitSet
import org.apache.flink.table.api.{TableException, ValidationException}
import org.apache.flink.table.planner.JBigDecimal
import org.apache.flink.table.planner.calcite.FlinkTypeFactory
import org.apache.flink.table.planner.functions.sql.{FlinkSqlOperatorTable, SqlWindowTableFunction}
import org.apache.flink.table.planner.plan.`trait`.RelWindowProperties
import org.apache.flink.table.planner.plan.logical.{CumulativeWindowSpec, HoppingWindowSpec, TimeAttributeWindowingStrategy, TumblingWindowSpec}
import org.apache.flink.table.planner.plan.metadata.FlinkRelMetadataQuery
import org.apache.flink.table.types.logical.TimestampType

import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.core.Calc
import org.apache.calcite.rex._
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.`type`.SqlTypeFamily

import java.time.Duration

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

/**
 * Utilities for window table-valued functions.
 */
object WindowUtil {

  /**
   * Returns true if the grouping keys contain window_start and window_end properties.
   */
  def groupingContainsWindowStartEnd(
      grouping: ImmutableBitSet,
      windowProperties: RelWindowProperties): Boolean = {
    if (windowProperties != null) {
      val windowStarts = windowProperties.getWindowStartColumns
      val windowEnds = windowProperties.getWindowEndColumns
      val hasWindowStart = !windowStarts.intersect(grouping).isEmpty
      val hasWindowEnd = !windowEnds.intersect(grouping).isEmpty
      hasWindowStart && hasWindowEnd
    } else {
      false
    }
  }

  /**
   * Returns true if the [[RexNode]] is a window table-valued function call.
   */
  def isWindowTableFunctionCall(node: RexNode): Boolean = node match {
    case call: RexCall => call.getOperator.isInstanceOf[SqlWindowTableFunction]
    case _ => false
  }

  /**
   * Returns true if expressions in [[Calc]] contain calls on window columns.
   */
  def calcContainsCallsOnWindowColumns(calc: Calc, fmq: FlinkRelMetadataQuery): Boolean = {
    val calcInput = calc.getInput
    val calcInputWindowColumns = fmq.getRelWindowProperties(calcInput).getWindowColumns
    val calcProgram = calc.getProgram
    val condition = calcProgram.getCondition
    if (condition != null) {
      val predicate = calcProgram.expandLocalRef(condition)
      // condition shouldn't contain window columns
      if (FlinkRexUtil.containsExpectedInputRef(predicate, calcInputWindowColumns)) {
        return true
      }
    }
    // the expressions shouldn't contain calls on window columns
    val callsContainProps = calcProgram.getProjectList.map(calcProgram.expandLocalRef).exists {
      case rex: RexCall => FlinkRexUtil.containsExpectedInputRef(rex, calcInputWindowColumns)
      case _ => false
    }
    callsContainProps
  }

  /**
   * Builds a new RexProgram on the input of window-tvf to exclude window columns,
   * but include time attribute.
   *
   * The return tuple consists of 4 elements:
   * (1) the new RexProgram
   * (2) the field index shifting
   * (3) the new index of time attribute on the new RexProgram
   * (4) whether the time attribute is new added
   */
  def buildNewProgramWithoutWindowColumns(
      rexBuilder: RexBuilder,
      oldProgram: RexProgram,
      inputRowType: RelDataType,
      inputTimeAttributeIndex: Int,
      windowColumns: Array[Int]): (RexProgram, Array[Int], Int, Boolean) = {
    val programBuilder = new RexProgramBuilder(inputRowType, rexBuilder)
    // mapping from original field index to new field index
    var containsTimeAttribute = false
    var newTimeAttributeIndex = -1
    val calcFieldShifting = ArrayBuffer[Int]()

    oldProgram.getNamedProjects.foreach { namedProject =>
      val expr = oldProgram.expandLocalRef(namedProject.left)
      val name = namedProject.right
      // project columns except window columns
      expr match {
        case inputRef: RexInputRef if windowColumns.contains(inputRef.getIndex) =>
          calcFieldShifting += -1

        case _ =>
          try {
            programBuilder.addProject(expr, name)
          } catch {
            case e: Throwable =>
              e.printStackTrace()
          }
          val fieldIndex = programBuilder.getProjectList.size() - 1
          calcFieldShifting += fieldIndex
          // check time attribute exists in the calc
          expr match {
            case ref: RexInputRef if ref.getIndex == inputTimeAttributeIndex =>
              containsTimeAttribute = true
              newTimeAttributeIndex = fieldIndex
            case _ => // nothing
          }
      }
    }

    // append time attribute if the calc doesn't refer it
    if (!containsTimeAttribute) {
      programBuilder.addProject(
        inputTimeAttributeIndex,
        inputRowType.getFieldNames.get(inputTimeAttributeIndex))
      newTimeAttributeIndex = programBuilder.getProjectList.size() - 1
    }

    if (oldProgram.getCondition != null) {
      val condition = oldProgram.expandLocalRef(oldProgram.getCondition)
      programBuilder.addCondition(condition)
    }

    val program = programBuilder.getProgram()
    (program, calcFieldShifting.toArray, newTimeAttributeIndex, !containsTimeAttribute)
  }

  /**
   * Converts a [[RexCall]] into [[TimeAttributeWindowingStrategy]], the [[RexCall]] must be a
   * window table-valued function call.
   */
  def convertToWindowingStrategy(
      windowCall: RexCall,
      inputRowType: RelDataType): TimeAttributeWindowingStrategy = {
    if (!isWindowTableFunctionCall(windowCall)) {
      throw new IllegalArgumentException(s"RexCall $windowCall is not a window table-valued " +
        "function, can't convert it into WindowingStrategy")
    }

    val timeIndex = getTimeAttributeIndex(windowCall.operands(1))
    val fieldType = inputRowType.getFieldList.get(timeIndex).getType
    if (!FlinkTypeFactory.isTimeIndicatorType(fieldType)) {
      throw new ValidationException("Window can only be defined on a time attribute column, " +
        "but is type of " + fieldType)
    }
    val timeAttributeType = FlinkTypeFactory.toLogicalType(fieldType).asInstanceOf[TimestampType]

    val windowFunction = windowCall.getOperator.asInstanceOf[SqlWindowTableFunction]
    val windowSpec = windowFunction match {
      case FlinkSqlOperatorTable.TUMBLE =>
        val interval = getOperandAsLong(windowCall.operands(2))
        TumblingWindowSpec(Duration.ofMillis(interval))

      case FlinkSqlOperatorTable.HOP =>
        val slide = getOperandAsLong(windowCall.operands(2))
        val size = getOperandAsLong(windowCall.operands(3))
        HoppingWindowSpec(Duration.ofMillis(size), Duration.ofMillis(slide))

      case FlinkSqlOperatorTable.CUMULATE =>
        val step = getOperandAsLong(windowCall.operands(2))
        val maxSize = getOperandAsLong(windowCall.operands(3))
        CumulativeWindowSpec(Duration.ofMillis(maxSize), Duration.ofMillis(step))
    }

    TimeAttributeWindowingStrategy(
      timeIndex,
      timeAttributeType,
      windowSpec)
  }

  // ------------------------------------------------------------------------------------------
  // Private Helpers
  // ------------------------------------------------------------------------------------------

  private def getTimeAttributeIndex(operand: RexNode): Int = {
    val timeAttributeIndex = operand match {
      case call: RexCall if call.getKind == SqlKind.DESCRIPTOR =>
        call.operands(0) match {
          case inputRef: RexInputRef => inputRef.getIndex
          case _ => -1
        }
      case _ => -1
    }
    if (timeAttributeIndex == -1) {
      throw new TableException(
        s"Failed to get time attribute index from $operand. " +
          "This is a bug, please file a JIRA issue.")
    }
    timeAttributeIndex
  }

  private def getOperandAsLong(operand: RexNode): Long = {
    operand match {
      case v: RexLiteral if v.getTypeName.getFamily == SqlTypeFamily.INTERVAL_DAY_TIME =>
        v.getValue.asInstanceOf[JBigDecimal].longValue()
      case _: RexLiteral => throw new TableException(
        "Window aggregate only support SECOND, MINUTE, HOUR, DAY as the time unit. " +
          "MONTH and YEAR time unit are not supported yet.")
      case _ => throw new TableException("Only constant window descriptors are supported.")
    }
  }

}
