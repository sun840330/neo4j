/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted.pipes

import org.neo4j.cypher.internal.runtime.Operations
import org.neo4j.cypher.internal.runtime.ExecutionContext
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.NumericHelper
import org.neo4j.internal.kernel.api.{NodeCursor, RelationshipScanCursor}
import org.neo4j.values.AnyValue
import org.neo4j.values.virtual.{NodeValue, RelationshipValue}

abstract class IdSeekIterator[T, CURSOR]
  extends Iterator[ExecutionContext] {

  private var cachedEntity: T = computeNextEntity()

  protected def operations: Operations[T, CURSOR]
  protected def entityIds: Iterator[AnyValue]

  protected def hasNextEntity = cachedEntity != null

  protected def nextEntity(): T = {
    if (hasNextEntity) {
      val result = cachedEntity
      cachedEntity = computeNextEntity()
      result
    } else {
      Iterator.empty.next()
    }
  }

  private def computeNextEntity(): T = {
    while (entityIds.hasNext) {
      val maybeEntity = for {
        id <- NumericHelper.asLongEntityId(entityIds.next())
        entity <- operations.getByIdIfExists(id)
      } yield entity

      if(maybeEntity.isDefined) return maybeEntity.get
    }
    null.asInstanceOf[T]
  }
}

final class NodeIdSeekIterator(ident: String,
                               baseContext: ExecutionContext,
                               executionContextFactory: ExecutionContextFactory,
                               protected val operations: Operations[NodeValue, NodeCursor],
                               protected val entityIds: Iterator[AnyValue])
  extends IdSeekIterator[NodeValue, NodeCursor] {

  def hasNext: Boolean = hasNextEntity

  def next(): ExecutionContext =
    executionContextFactory.copyWith(baseContext, ident, nextEntity())
}

final class DirectedRelationshipIdSeekIterator(ident: String,
                                               fromNode: String,
                                               toNode: String,
                                               baseContext: ExecutionContext,
                                               executionContextFactory: ExecutionContextFactory,
                                               protected val operations: Operations[RelationshipValue, RelationshipScanCursor],
                                               protected val entityIds: Iterator[AnyValue])
  extends IdSeekIterator[RelationshipValue, RelationshipScanCursor] {

  def hasNext: Boolean = hasNextEntity

  def next(): ExecutionContext = {
    val rel = nextEntity()
    executionContextFactory.copyWith(baseContext, ident, rel, fromNode, rel.startNode(), toNode, rel.endNode()
    )
  }
}

final class UndirectedRelationshipIdSeekIterator(ident: String,
                                                 fromNode: String,
                                                 toNode: String,
                                                 baseContext: ExecutionContext,
                                                 executionContextFactory: ExecutionContextFactory,
                                                 protected val operations: Operations[RelationshipValue, RelationshipScanCursor],
                                                 protected val entityIds: Iterator[AnyValue])
  extends IdSeekIterator[RelationshipValue, RelationshipScanCursor] {

  private var lastEntity: RelationshipValue = _
  private var lastStart: NodeValue = _
  private var lastEnd: NodeValue = _
  private var emitSibling = false

  def hasNext: Boolean = emitSibling || hasNextEntity

  def next(): ExecutionContext = {
    if (emitSibling) {
      emitSibling = false
      executionContextFactory.copyWith(baseContext, ident, lastEntity, fromNode, lastEnd, toNode, lastStart)
    } else {
      emitSibling = true
      lastEntity = nextEntity()
      lastStart = lastEntity.startNode()
      lastEnd = lastEntity.endNode()
      executionContextFactory.copyWith(baseContext, ident, lastEntity, fromNode, lastStart, toNode, lastEnd)
    }
  }
}
