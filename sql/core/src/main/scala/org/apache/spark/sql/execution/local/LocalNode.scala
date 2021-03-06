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

package org.apache.spark.sql.execution.local

import scala.util.control.NonFatal

import org.apache.spark.Logging
import org.apache.spark.sql.{SQLConf, Row}
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateMutableProjection
import org.apache.spark.sql.catalyst.trees.TreeNode
import org.apache.spark.sql.types.StructType

/**
 * A local physical operator, in the form of an iterator.
 *
 * Before consuming the iterator, open function must be called.
 * After consuming the iterator, close function must be called.
 */
abstract class LocalNode(conf: SQLConf) extends TreeNode[LocalNode] with Logging {

  protected val codegenEnabled: Boolean = conf.codegenEnabled

  protected val unsafeEnabled: Boolean = conf.unsafeEnabled

  lazy val schema: StructType = StructType.fromAttributes(output)

  private[this] lazy val isTesting: Boolean = sys.props.contains("spark.testing")

  def output: Seq[Attribute]

  /**
   * Initializes the iterator state. Must be called before calling `next()`.
   *
   * Implementations of this must also call the `open()` function of its children.
   */
  def open(): Unit

  /**
   * Advances the iterator to the next tuple. Returns true if there is at least one more tuple.
   */
  def next(): Boolean

  /**
   * Returns the current tuple.
   */
  def fetch(): InternalRow

  /**
   * Closes the iterator and releases all resources. It should be idempotent.
   *
   * Implementations of this must also call the `close()` function of its children.
   */
  def close(): Unit

  /**
   * Returns the content of the iterator from the beginning to the end in the form of a Scala Seq.
   */
  def collect(): Seq[Row] = {
    val converter = CatalystTypeConverters.createToScalaConverter(StructType.fromAttributes(output))
    val result = new scala.collection.mutable.ArrayBuffer[Row]
    open()
    try {
      while (next()) {
        result += converter.apply(fetch()).asInstanceOf[Row]
      }
    } finally {
      close()
    }
    result
  }

  protected def newMutableProjection(
      expressions: Seq[Expression],
      inputSchema: Seq[Attribute]): () => MutableProjection = {
    log.debug(
      s"Creating MutableProj: $expressions, inputSchema: $inputSchema, codegen:$codegenEnabled")
    if (codegenEnabled) {
      try {
        GenerateMutableProjection.generate(expressions, inputSchema)
      } catch {
        case NonFatal(e) =>
          if (isTesting) {
            throw e
          } else {
            log.error("Failed to generate mutable projection, fallback to interpreted", e)
            () => new InterpretedMutableProjection(expressions, inputSchema)
          }
      }
    } else {
      () => new InterpretedMutableProjection(expressions, inputSchema)
    }
  }

}


abstract class LeafLocalNode(conf: SQLConf) extends LocalNode(conf) {
  override def children: Seq[LocalNode] = Seq.empty
}


abstract class UnaryLocalNode(conf: SQLConf) extends LocalNode(conf) {

  def child: LocalNode

  override def children: Seq[LocalNode] = Seq(child)
}

abstract class BinaryLocalNode(conf: SQLConf) extends LocalNode(conf) {

  def left: LocalNode

  def right: LocalNode

  override def children: Seq[LocalNode] = Seq(left, right)
}

/**
 * An thin wrapper around a [[LocalNode]] that provides an `Iterator` interface.
 */
private[local] class LocalNodeIterator(localNode: LocalNode) extends Iterator[InternalRow] {
  private var nextRow: InternalRow = _

  override def hasNext: Boolean = {
    if (nextRow == null) {
      val res = localNode.next()
      if (res) {
        nextRow = localNode.fetch()
      }
      res
    } else {
      true
    }
  }

  override def next(): InternalRow = {
    if (hasNext) {
      val res = nextRow
      nextRow = null
      res
    } else {
      throw new NoSuchElementException
    }
  }
}
