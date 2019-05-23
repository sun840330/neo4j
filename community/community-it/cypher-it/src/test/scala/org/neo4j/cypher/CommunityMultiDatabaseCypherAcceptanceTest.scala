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
package org.neo4j.cypher

import java.util.Optional

import org.neo4j.configuration.GraphDatabaseSettings.default_database
import org.neo4j.configuration.{Config, GraphDatabaseSettings}
import org.neo4j.cypher.internal.DatabaseStatus
import org.neo4j.cypher.internal.javacompat.GraphDatabaseCypherService
import org.neo4j.dbms.database.{DatabaseContext, DatabaseManager}
import org.neo4j.kernel.database.DatabaseId
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge
import org.neo4j.logging.Log
import org.neo4j.server.security.auth.{InMemoryUserRepository, SecureHasher}
import org.neo4j.server.security.systemgraph.{BasicSystemGraphInitializer, BasicSystemGraphOperations, ContextSwitchingSystemGraphQueryExecutor}

class CommunityMultiDatabaseCypherAcceptanceTest extends ExecutionEngineFunSuite with GraphDatabaseTestSupport {
  private val onlineStatus = DatabaseStatus.Online.stringValue()
  private val offlineStatus = DatabaseStatus.Offline.stringValue()
  private val defaultConfig = Config.defaults()

  test("should list default database") {
    // GIVEN
    setup( defaultConfig )

    // WHEN
    val result = execute("SHOW DATABASE neo4j")

    // THEN
    result.toList should be(List(Map("name" -> "neo4j", "status" -> onlineStatus, "default" -> true)))
  }

  test("should list custom default database") {
    // GIVEN
    val config = Config.defaults()
    config.augment(default_database, "foo")
    setup(config)

    // WHEN
    val result = execute("SHOW DATABASE foo")

    // THEN
    result.toList should be(List(Map("name" -> "foo", "status" -> onlineStatus, "default" -> true)))

    // WHEN
    val result2 = execute("SHOW DATABASE neo4j")

    // THEN
    result2.toList should be(empty)
  }

  test("should list default and system databases") {
    // GIVEN
    setup( defaultConfig )

    // WHEN
    val result = execute("SHOW DATABASES")

    // THEN
    result.toSet should be(Set(
      Map("name" -> "neo4j", "status" -> onlineStatus, "default" -> true),
      Map("name" -> "system", "status" -> onlineStatus, "default" -> false)))
  }

  test("should list custom default and system databases") {
    // GIVEN
    val config = Config.defaults()
    config.augment(default_database, "foo")
    setup(config)

    // WHEN
    val result = execute("SHOW DATABASES")

    // THEN
    result.toSet should be(Set(
      Map("name" -> "foo", "status" -> onlineStatus, "default" -> true),
      Map("name" -> "system", "status" -> onlineStatus, "default" -> false)))
     }

  test("should fail on create database from community") {
    setup( defaultConfig )
    assertFailure("CREATE DATABASE foo", "Unsupported management command: CREATE DATABASE foo")
  }

  test("should fail on creating already existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("CREATE DATABASE neo4j", "Unsupported management command: CREATE DATABASE neo4j")
  }

  test("should fail on dropping database from community") {
    setup( defaultConfig )
    assertFailure("DROP DATABASE neo4j", "Unsupported management command: DROP DATABASE neo4j")
  }

  test("should fail on dropping non-existing database with correct error message") {
    setup( defaultConfig )
    assertFailure("DROP DATABASE foo", "Unsupported management command: DROP DATABASE foo")
  }

  test("should be able to start a started database") {
    // GIVEN
    setup( defaultConfig )

    // WHEN
    execute("START DATABASE neo4j")

    // THEN
    val result = execute("SHOW DATABASE neo4j")
    result.toList should be(List(Map("name" -> "neo4j", "status" -> onlineStatus, "default" -> true)))
  }

  test("should not be able to start non-existing database") {
    setup( defaultConfig )

    assertFailure("START DATABASE foo", "Database 'foo' does not exist.")

    val result = execute("SHOW DATABASE foo")
    result.toList should be(List.empty)
  }

  test("should stop database") {
    // GIVEN
    setup( defaultConfig )

    // WHEN
    execute("STOP DATABASE neo4j")
    val result2 = execute("SHOW DATABASE neo4j")
    result2.toList should be(List(Map("name" -> "neo4j", "status" -> offlineStatus, "default" -> true)))
  }

  test("should not be able to stop non-existing database") {
    setup( defaultConfig )

    assertFailure("STOP DATABASE foo", "Database 'foo' does not exist.")

    val result = execute("SHOW DATABASE foo")
    result.toList should be(List.empty)
  }

  test("should be able to stop a stopped database") {

    // GIVEN
    setup( defaultConfig )

    execute("STOP DATABASE neo4j")
    val result = execute("SHOW DATABASE neo4j")
    result.toList should be(List(Map("name" -> "neo4j", "status" -> offlineStatus, "default" -> true)))

    // WHEN
    execute("STOP DATABASE neo4j")

    // THEN
    val result2 = execute("SHOW DATABASE neo4j")
    result2.toList should be(List(Map("name" -> "neo4j", "status" -> offlineStatus, "default" -> true)))
  }

  test("should re-start database") {

    // GIVEN
    setup( defaultConfig )

    val result = execute("SHOW DATABASE neo4j")
    result.toList should be(List(Map("name" -> "neo4j", "status" -> onlineStatus, "default" -> true))) // make sure it was started
    execute("STOP DATABASE neo4j")
    val result2 = execute("SHOW DATABASE neo4j")
    result2.toList should be(List(Map("name" -> "neo4j", "status" -> offlineStatus, "default" -> true))) // and stopped

    // WHEN
    execute("START DATABASE neo4j")

    // THEN
    val result3 = execute("SHOW DATABASE neo4j")
    result3.toList should be(List(Map("name" -> "neo4j", "status" -> onlineStatus, "default" -> true)))
  }

  private def setup(config: Config): Unit = {
    val queryExecutor: ContextSwitchingSystemGraphQueryExecutor = new ContextSwitchingSystemGraphQueryExecutor(databaseManager(), threadToStatementContextBridge())
    val secureHasher: SecureHasher = new SecureHasher
    val systemGraphOperations: BasicSystemGraphOperations = new BasicSystemGraphOperations(queryExecutor, secureHasher)

    val systemGraphInitializer = new BasicSystemGraphInitializer(
      queryExecutor,
      systemGraphOperations,
      () => new InMemoryUserRepository,
      () => new InMemoryUserRepository,
      secureHasher,
      mock[Log],
      config)

    systemGraphInitializer.initializeSystemGraph()
    selectDatabase(GraphDatabaseSettings.SYSTEM_DATABASE_NAME)
  }

  private def databaseManager() = graph.getDependencyResolver.resolveDependency(classOf[DatabaseManager[DatabaseContext]])

  private def threadToStatementContextBridge() = graph.getDependencyResolver.resolveDependency(classOf[ThreadToStatementContextBridge])

  private def selectDatabase(name: String): Unit = {
    val manager = databaseManager()
    val maybeCtx: Optional[DatabaseContext] = manager.getDatabaseContext(new DatabaseId(name))
    val dbCtx: DatabaseContext = maybeCtx.orElseGet(() => throw new RuntimeException(s"No such database: $name"))
    graphOps = dbCtx.databaseFacade()
    graph = new GraphDatabaseCypherService(graphOps)
    eengine = ExecutionEngineHelper.createEngine(graph)
  }

  private def assertFailure(command: String, errorMsg: String): Unit = {
    try {
      // WHEN
      execute(command)

      fail("Expected error " + errorMsg)
    } catch {
      // THEN
      case e: Exception => e.getMessage should be(errorMsg)
    }
  }
}