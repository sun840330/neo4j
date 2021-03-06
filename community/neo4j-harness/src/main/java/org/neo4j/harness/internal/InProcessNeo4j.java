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
package org.neo4j.harness.internal;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;

import org.neo4j.annotations.api.PublicApi;
import org.neo4j.configuration.connectors.BoltConnector;
import org.neo4j.configuration.connectors.ConnectorPortRegister;
import org.neo4j.configuration.connectors.HttpConnector;
import org.neo4j.configuration.connectors.HttpsConnector;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.config.Configuration;
import org.neo4j.harness.Neo4j;
import org.neo4j.internal.helpers.HostnamePort;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.server.NeoServer;

@PublicApi
public class InProcessNeo4j implements Neo4j
{
    private final File serverFolder;
    private final File userLogFile;
    private final File internalLogFile;
    private final NeoServer server;
    private final Closeable additionalClosable;
    private ConnectorPortRegister connectorPortRegister;

    public InProcessNeo4j( File serverFolder, File userLogFile, File internalLogFile, NeoServer server, Closeable additionalClosable )
    {
        this.serverFolder = serverFolder;
        this.userLogFile = userLogFile;
        this.internalLogFile = internalLogFile;
        this.server = server;
        this.additionalClosable = additionalClosable;
    }

    @Override
    public URI boltURI()
    {
        if ( server.getConfig().get( BoltConnector.enabled ) )
        {
            return connectorUri( "bolt", BoltConnector.NAME );
        }
        throw new IllegalStateException( "Bolt connector is not configured" );
    }

    @Override
    public URI httpURI()
    {
        if ( server.getConfig().get( HttpConnector.enabled ) )
        {
            return connectorUri( "http", HttpConnector.NAME );
        }
        throw new IllegalStateException( "HTTP connector is not configured" );
    }

    @Override
    public URI httpsURI()
    {
        if ( server.getConfig().get( HttpsConnector.enabled ) )
        {
            return connectorUri( "https", HttpsConnector.NAME );
        }
        throw new IllegalStateException( "HTTPS connector is not configured" );
    }

    public void start()
    {
        this.server.start();
        this.connectorPortRegister = connectorPortRegister( server );
    }

    @Override
    public void close()
    {
        server.stop();
        this.connectorPortRegister = null;
        try
        {
            additionalClosable.close();
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
        try
        {
            if ( looksLikeMd5Hash( serverFolder.getName() ) )
            {
                FileUtils.deleteRecursively( serverFolder );
            }
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Failed to clean up test server directory.", e );
        }
    }

    @Override
    public void printLogs( PrintStream out )
    {
        printLog( "User Log File", userLogFile, out );
        printLog( "Internal Log File", internalLogFile, out );
    }

    private static void printLog( String description, File file, PrintStream out )
    {
        if ( file != null && file.exists() )
        {
            out.println( String.format( "---------- BEGIN %s ----------", description ) );

            try ( BufferedReader reader = new BufferedReader( new FileReader( file ) ) )
            {
                reader.lines().forEach( out::println );
            }
            catch ( IOException ex )
            {
                out.println( "Unable to collect log files: " + ex.getMessage() );
                ex.printStackTrace( out );
            }
            finally
            {
                out.println( String.format( "---------- END %s ----------", description ) );
            }
        }
    }

    private boolean looksLikeMd5Hash( String name )
    {
        // Pure paranoia, and a silly check - but this decreases the likelihood that we delete something that isn't
        // our randomly generated folder significantly.
        return name.length() == 32;
    }

    @Override
    public DatabaseManagementService databaseManagementService()
    {
        return server.getDatabaseService().getDatabaseManagementService();
    }

    @Override
    public GraphDatabaseService defaultDatabaseService()
    {
        return server.getDatabaseService().getDatabase();
    }

    @Override
    public Configuration config()
    {
        return server.getConfig();
    }

    private URI connectorUri( String scheme, String connectorName )
    {
        HostnamePort hostPort = connectorPortRegister.getLocalAddress( connectorName );
        return URI.create( scheme + "://" + hostPort + "/" );
    }

    private static ConnectorPortRegister connectorPortRegister( NeoServer server )
    {
        return ((GraphDatabaseAPI) server.getDatabaseService().getDatabase()).getDependencyResolver().resolveDependency( ConnectorPortRegister.class );
    }
}
