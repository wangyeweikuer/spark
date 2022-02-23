/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.joins

import org.apache.spark.{PartitionEvaluator, PartitionEvaluatorFactory}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, GenericInternalRow, JoinedRow, Predicate, Projection, RowOrdering, UnsafeProjection, UnsafeRow}
import org.apache.spark.sql.catalyst.plans.{ExistenceJoin, FullOuter, InnerLike, JoinType, LeftAnti, LeftOuter, LeftSemi, RightOuter}
import org.apache.spark.sql.execution.{ExternalAppendOnlyUnsafeRowArray, RowIterator, SparkPlan}
import org.apache.spark.sql.execution.metric.SQLMetric

class SortMergeJoinEvaluatorFactory(
    leftKeys: Seq[Expression],
    rightKeys: Seq[Expression],
    joinType: JoinType,
    condition: Option[Expression],
    left: SparkPlan,
    right: SparkPlan,
    output: Seq[Attribute],
    inMemoryThreshold: Int,
    spillThreshold: Int,
    numOutputRows: SQLMetric,
    spillSize: SQLMetric,
    onlyBufferFirstMatchedRow: Boolean)
    extends PartitionEvaluatorFactory[InternalRow, InternalRow] {
  override def createEvaluator(): PartitionEvaluator[InternalRow, InternalRow] =
    new SortMergeJoinEvaluator

  private class SortMergeJoinEvaluator extends PartitionEvaluator[InternalRow, InternalRow] {

    private def cleanupResources(): Unit = {
      IndexedSeq(left, right).foreach(_.cleanupResources())
    }
    private def createLeftKeyGenerator(): Projection =
      UnsafeProjection.create(leftKeys, left.output)

    private def createRightKeyGenerator(): Projection =
      UnsafeProjection.create(rightKeys, right.output)

    override def eval(
        partitionIndex: Int,
        inputs: Iterator[InternalRow]*): Iterator[InternalRow] = {
      assert(inputs.length == 2)
      val leftIter = inputs(0)
      val rightIter = inputs(1)

      val boundCondition: InternalRow => Boolean = {
        condition.map { cond =>
          Predicate.create(cond, left.output ++ right.output).eval _
        }.getOrElse {
          (r: InternalRow) => true
        }
      }

      // An ordering that can be used to compare keys from both sides.
      val keyOrdering = RowOrdering.createNaturalAscendingOrdering(leftKeys.map(_.dataType))
      val resultProj: InternalRow => InternalRow = UnsafeProjection.create(output, output)

      joinType match {
        case _: InnerLike =>
          new RowIterator {
            private[this] var currentLeftRow: InternalRow = _
            // md： 这里为什么要用external结构？因为相同key值得记录行可能非常多（比如，k=1、1、2...2、3、3，中间有1亿个2），需要有spill能力支持才行，
            private[this] var currentRightMatches: ExternalAppendOnlyUnsafeRowArray = _
            private[this] var rightMatchesIterator: Iterator[UnsafeRow] = null
            private[this] val smjScanner = new SortMergeJoinScanner(
              createLeftKeyGenerator(),
              createRightKeyGenerator(),
              keyOrdering,
              RowIterator.fromScala(leftIter),
              RowIterator.fromScala(rightIter),
              inMemoryThreshold,
              spillThreshold,
              spillSize,
              cleanupResources)
            private[this] val joinRow = new JoinedRow

            // md: 大概的逻辑: 每次先从stream表前进一条数据，然后再前进buffer表，找到一批匹配相同key的数据，
            //  然后暂存到buffer区内；后续再前进stream表，复用buffer区已经等值匹配的数据，然后做join条件判断并输出；
            //  持续这个过程；
            if (smjScanner.findNextInnerJoinRows()) {
              currentRightMatches = smjScanner.getBufferedMatches
              currentLeftRow = smjScanner.getStreamedRow
              rightMatchesIterator = currentRightMatches.generateIterator()
            }

            override def advanceNext(): Boolean = {
              while (rightMatchesIterator != null) {
                if (!rightMatchesIterator.hasNext) {
                  if (smjScanner.findNextInnerJoinRows()) {
                    currentRightMatches = smjScanner.getBufferedMatches
                    currentLeftRow = smjScanner.getStreamedRow
                    // md： 因为上一条streamed记录消费了buffer区的所有记录，所以这里找到一条新的streamed行，重新再获取一次buffer区的迭代器，再循环一此
                    rightMatchesIterator = currentRightMatches.generateIterator()
                  } else {
                    currentRightMatches = null
                    currentLeftRow = null
                    rightMatchesIterator = null
                    return false
                  }
                }
                joinRow(currentLeftRow, rightMatchesIterator.next())
                if (boundCondition(joinRow)) {
                  numOutputRows += 1
                  return true
                }
              }
              false
            }

            override def getRow: InternalRow = resultProj(joinRow)
          }.toScala

        case LeftOuter =>
          val smjScanner = new SortMergeJoinScanner(
            streamedKeyGenerator = createLeftKeyGenerator(),
            bufferedKeyGenerator = createRightKeyGenerator(),
            keyOrdering,
            streamedIter = RowIterator.fromScala(leftIter),
            bufferedIter = RowIterator.fromScala(rightIter),
            inMemoryThreshold,
            spillThreshold,
            spillSize,
            cleanupResources)
          val rightNullRow = new GenericInternalRow(right.output.length)
          new LeftOuterIterator(
            smjScanner,
            rightNullRow,
            boundCondition,
            resultProj,
            numOutputRows).toScala

        case RightOuter =>
          val smjScanner = new SortMergeJoinScanner(
            streamedKeyGenerator = createRightKeyGenerator(),
            bufferedKeyGenerator = createLeftKeyGenerator(),
            keyOrdering,
            streamedIter = RowIterator.fromScala(rightIter),
            bufferedIter = RowIterator.fromScala(leftIter),
            inMemoryThreshold,
            spillThreshold,
            spillSize,
            cleanupResources)
          val leftNullRow = new GenericInternalRow(left.output.length)
          new RightOuterIterator(
            smjScanner,
            leftNullRow,
            boundCondition,
            resultProj,
            numOutputRows).toScala

        // md: 从源码上可以看到，inner、left outer和right outer，都只要缓存一侧的表即可，然后迭代另一侧的表的行记录，在buffer中找匹配
        //  的记录，如果匹配的话则多次迭代把所有匹配的行记录都输出，如果不匹配对outer join单独输出一行带null列的记录即可；

        // md: 但是对于full outer来说，必须左右两侧的表都缓存才行，因为左表和右边都有可能存在不匹配的情况，需要先从buffer角度的全局匹配都
        //  执行完之后，再review一次哪些buffer行未被另一侧表的任意一行匹配到，如果有的话就以outer方式输出（所以需要buffer起来，最终可能再输出一次）
        case FullOuter =>
          val leftNullRow = new GenericInternalRow(left.output.length)
          val rightNullRow = new GenericInternalRow(right.output.length)
          val smjScanner = new SortMergeFullOuterJoinScanner(
            leftKeyGenerator = createLeftKeyGenerator(),
            rightKeyGenerator = createRightKeyGenerator(),
            keyOrdering,
            leftIter = RowIterator.fromScala(leftIter),
            rightIter = RowIterator.fromScala(rightIter),
            boundCondition,
            leftNullRow,
            rightNullRow)

          new FullOuterIterator(smjScanner, resultProj, numOutputRows).toScala

        case LeftSemi =>
          new RowIterator {
            private[this] var currentLeftRow: InternalRow = _
            private[this] val smjScanner = new SortMergeJoinScanner(
              createLeftKeyGenerator(),
              createRightKeyGenerator(),
              keyOrdering,
              RowIterator.fromScala(leftIter),
              RowIterator.fromScala(rightIter),
              inMemoryThreshold,
              spillThreshold,
              spillSize,
              cleanupResources,
              onlyBufferFirstMatchedRow)
            private[this] val joinRow = new JoinedRow

            override def advanceNext(): Boolean = {
              while (smjScanner.findNextInnerJoinRows()) {
                // todo-md： 这里稍微有一点点性能浪费，因为semi join时只需要找到一条匹配的记录，但下面是把所有等值key的记录都找出来放到buffer区，
                //  然后再做条件匹配并短路匹配；如果某个join key存在data skew时（且其中有一条数据满足semi join条件），可能导致严重的性能浪费；

                // md: 这个问题可以向社区提issue来优化！！为了内存效率等，可以控制buffer的上限来分批来加载数据然后匹配，然后再分批加载数据再匹配；
                //  不过，数据扫描是少不了，顶多就是减少spill的概率；
                val currentRightMatches = smjScanner.getBufferedMatches
                currentLeftRow = smjScanner.getStreamedRow
                if (currentRightMatches != null && currentRightMatches.length > 0) {
                  val rightMatchesIterator = currentRightMatches.generateIterator()
                  while (rightMatchesIterator.hasNext) {
                    joinRow(currentLeftRow, rightMatchesIterator.next())
                    if (boundCondition(joinRow)) {
                      numOutputRows += 1
                      // md： 因为left semi如果join条件匹配成功，只需要输出左表一条数据，因此这里直接跳出即可
                      return true
                    }
                  }
                }
              }
              false
            }

            override def getRow: InternalRow = currentLeftRow
          }.toScala

        case LeftAnti =>
          new RowIterator {
            private[this] var currentLeftRow: InternalRow = _
            private[this] val smjScanner = new SortMergeJoinScanner(
              createLeftKeyGenerator(),
              createRightKeyGenerator(),
              keyOrdering,
              RowIterator.fromScala(leftIter),
              RowIterator.fromScala(rightIter),
              inMemoryThreshold,
              spillThreshold,
              spillSize,
              cleanupResources,
              onlyBufferFirstMatchedRow)
            private[this] val joinRow = new JoinedRow

            override def advanceNext(): Boolean = {
              while (smjScanner.findNextOuterJoinRows()) {
                currentLeftRow = smjScanner.getStreamedRow
                // md: 这里必须找到所有的记录，因为anti是所有右表都不满足条件时才需要返回左表
                val currentRightMatches = smjScanner.getBufferedMatches
                if (currentRightMatches == null || currentRightMatches.length == 0) {
                  numOutputRows += 1
                  return true
                }
                var found = false
                val rightMatchesIterator = currentRightMatches.generateIterator()
                while (!found && rightMatchesIterator.hasNext) {
                  joinRow(currentLeftRow, rightMatchesIterator.next())
                  // md： 为什么要输出左边和右边，不是left semi和left anti么，因为参与boundCondition判断的是左右两行的数据
                  if (boundCondition(joinRow)) {
                    found = true
                  }
                }
                if (!found) {
                  numOutputRows += 1
                  return true
                }
              }
              false
            }

            override def getRow: InternalRow = currentLeftRow
          }.toScala

        // md: where xxx in (select x from ...)
        case j: ExistenceJoin =>
          new RowIterator {
            private[this] var currentLeftRow: InternalRow = _
            private[this] val result: InternalRow = new GenericInternalRow(Array[Any](null))
            private[this] val smjScanner = new SortMergeJoinScanner(
              createLeftKeyGenerator(),
              createRightKeyGenerator(),
              keyOrdering,
              RowIterator.fromScala(leftIter),
              RowIterator.fromScala(rightIter),
              inMemoryThreshold,
              spillThreshold,
              spillSize,
              cleanupResources,
              onlyBufferFirstMatchedRow)
            private[this] val joinRow = new JoinedRow

            override def advanceNext(): Boolean = {
              while (smjScanner.findNextOuterJoinRows()) {
                currentLeftRow = smjScanner.getStreamedRow
                val currentRightMatches = smjScanner.getBufferedMatches
                var found = false
                if (currentRightMatches != null && currentRightMatches.length > 0) {
                  val rightMatchesIterator = currentRightMatches.generateIterator()
                  while (!found && rightMatchesIterator.hasNext) {
                    joinRow(currentLeftRow, rightMatchesIterator.next())
                    if (boundCondition(joinRow)) {
                      found = true
                    }
                  }
                }
                result.setBoolean(0, found)
                numOutputRows += 1
                return true
              }
              false
            }

            override def getRow: InternalRow = resultProj(joinRow(currentLeftRow, result))
          }.toScala

        case x =>
          throw new IllegalArgumentException(s"SortMergeJoin should not take $x as the JoinType")
      }

    }
  }
}
