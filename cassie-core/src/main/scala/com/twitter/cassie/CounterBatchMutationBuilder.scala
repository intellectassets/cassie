// Copyright 2012 Twitter, Inc.

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

// http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.twitter.cassie

import com.twitter.cassie.codecs.Codec
import com.twitter.util.Future
import java.nio.ByteBuffer
import java.util.Collections.{ singleton => singletonJSet }
import java.util.{List => JList,Map => JMap, Set => JSet, ArrayList => JArrayList, HashMap => JHashMap}
import org.apache.cassandra.finagle.thrift
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

/**
 * A ColumnFamily-alike which batches mutations into a single API call for counters.
 */
class CounterBatchMutationBuilder[Key, Name](cf: CounterColumnFamily[Key, Name])
  extends BatchMutation {

  type This = CounterBatchMutationBuilder[Key, Name]

  case class Insert(key: Key, column: CounterColumn[Name])
  case class Deletions(key: Key, columnNames: JSet[Name])

  private val ops = new ListBuffer[Either[Insert, Deletions]]

  def insert(key: Key, column: CounterColumn[Name]): This = synchronized {
    ops.append(Left(Insert(key, column)))
    this
  }

  def removeColumn(key: Key, columnName: Name): This =
    removeColumns(key, singletonJSet(columnName))

  def removeColumns(key: Key, columnNames: JSet[Name]): This = synchronized {
    ops.append(Right(Deletions(key, columnNames)))
    this
  }

  /**
   * Submits the batch of operations, returning a future to allow blocking for success.
   */
  def execute(): Future[Void] = {
    Future {
      cf.batch(mutations)
    }.flatten
  }

  private[cassie] def mutations: JMap[ByteBuffer, JMap[String, JList[thrift.Mutation]]] = synchronized {
    val mutations = new JHashMap[ByteBuffer, JMap[String, JList[thrift.Mutation]]]()

    ops.map {
      case Left(insert) => {
        val cosc = new thrift.ColumnOrSuperColumn()
        val counterColumn = new thrift.CounterColumn(cf.nameCodec.encode(insert.column.name), insert.column.value)
        cosc.setCounter_column(counterColumn)
        val mutation = new thrift.Mutation
        mutation.setColumn_or_supercolumn(cosc)

        val encodedKey = cf.keyCodec.encode(insert.key)

        val h = Option(mutations.get(encodedKey)).getOrElse { val x = new JHashMap[String, JList[thrift.Mutation]]; mutations.put(encodedKey, x); x }
        val l = Option(h.get(cf.name)).getOrElse { val y = new JArrayList[thrift.Mutation]; h.put(cf.name, y); y }
        l.add(mutation)
      }
      case Right(deletions) => {
        val pred = new thrift.SlicePredicate
        pred.setColumn_names(cf.nameCodec.encodeSet(deletions.columnNames))

        val deletion = new thrift.Deletion
        deletion.setPredicate(pred)

        val mutation = new thrift.Mutation
        mutation.setDeletion(deletion)

        val encodedKey = cf.keyCodec.encode(deletions.key)

        val h = Option(mutations.get(encodedKey)).getOrElse { val x = new JHashMap[String, JList[thrift.Mutation]]; mutations.put(encodedKey, x); x }
        val l = Option(h.get(cf.name)).getOrElse { val y = new JArrayList[thrift.Mutation]; h.put(cf.name, y); y }
        l.add(mutation)
      }
    }
    mutations
  }
}
