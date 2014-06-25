/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.backup;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.index.Index;
import org.neo4j.helpers.Settings;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.StoreLockException;
import org.neo4j.kernel.impl.nioneo.store.MismatchingStoreIdException;
import org.neo4j.kernel.impl.nioneo.store.NeoStoreUtil;
import org.neo4j.kernel.impl.nioneo.store.TransactionIdStore;
import org.neo4j.kernel.impl.transaction.xaframework.LogicalTransactionStore;
import org.neo4j.kernel.impl.transaction.xaframework.NoSuchTransactionException;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.test.DbRepresentation;
import org.neo4j.test.TargetDirectory;
import org.neo4j.test.subprocess.SubProcess;

import static java.lang.Integer.parseInt;

import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.neo4j.graphdb.factory.GraphDatabaseSettings.dense_node_threshold;
import static org.neo4j.kernel.impl.MyRelTypes.TEST;

public class TestBackup
{
    private File serverPath;
    private File otherServerPath;
    private File backupPath;
    private List<ServerInterface> servers;

    @Rule
    public TargetDirectory.TestDirectory testDir = TargetDirectory.testDirForTest( TestBackup.class );

    @Before
    public void before() throws Exception
    {
        servers = new ArrayList<>();
        File base = testDir.directory();
        serverPath = new File( base, "server" );
        otherServerPath = new File( base, "server2" );
        backupPath = new File( base, "backuedup-serverdb" );
    }

    @After
    public void shutDownServers()
    {
        for ( ServerInterface server : servers )
        {
            server.shutdown();
        }
        servers.clear();
    }

    @Test
    public void makeSureFullFailsWhenDbExists() throws Exception
    {
        createInitialDataSet( serverPath );
        ServerInterface server = startServer( serverPath );
        OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );
        createInitialDataSet( backupPath );
        try
        {
            backup.full( backupPath.getPath() );
            fail( "Shouldn't be able to do full backup into existing db" );
        }
        catch ( Exception e )
        {
            // good
        }
        shutdownServer( server );
    }

    @Test
    public void makeSureIncrementalFailsWhenNoDb() throws Exception
    {
        createInitialDataSet( serverPath );
        ServerInterface server = startServer( serverPath );
        OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );
        try
        {
            backup.incremental( backupPath.getPath() );
            fail( "Shouldn't be able to do incremental backup into non-existing db" );
        }
        catch ( Exception e )
        {
            // Good
        }
        shutdownServer( server );
    }

    @Test
    public void backupLeavesLastTxInLog() throws Exception
    {
        GraphDatabaseAPI db = null;
        ServerInterface server = null;
        try
        {
            createInitialDataSet( serverPath );
            server = startServer( serverPath );
            OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );
            backup.full( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            shutdownServer( server );
            server = null;

            assertMetadataAboutLastTransactionExists( backupPath );;

            addMoreData( serverPath );
            server = startServer( serverPath );
            backup.incremental( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            shutdownServer( server );
            server = null;

            assertMetadataAboutLastTransactionExists( backupPath );;
        }
        finally
        {
            if ( db != null )
            {
                db.shutdown();
            }
            if ( server != null )
            {
                shutdownServer( server );
            }
        }
    }

    @Test
    public void incrementalBackupLeavesOnlyLastTxInLog() throws Exception
    {
        GraphDatabaseAPI db = null;
        ServerInterface server = null;
        try
        {
            createInitialDataSet( serverPath );
            server = startServer( serverPath );
            OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );
            backup.full( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            shutdownServer( server );
            server = null;

            addMoreData( serverPath );
            server = startServer( serverPath );
            backup.incremental( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            shutdownServer( server );
            server = null;

            // do 2 rotations, add two empty logs
            new GraphDatabaseFactory().newEmbeddedDatabase( backupPath.getPath() ).shutdown();
            new GraphDatabaseFactory().newEmbeddedDatabase( backupPath.getPath() ).shutdown();

            addMoreData( serverPath );
            server = startServer( serverPath );
            backup.incremental( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            shutdownServer( server );
            server = null;

            int logsFound = backupPath.listFiles( new FilenameFilter()
            {
                @Override
                public boolean accept( File dir, String name )
                {
                    return name.startsWith( "nioneo_logical.log" )
                           && !name.endsWith( "active" );
                }
            } ).length;

            // 2 one the real and the other from the rotation of shutdown
            assertEquals( 2, logsFound );
            assertMetadataAboutLastTransactionExists( backupPath );
        }
        finally
        {
            if ( db != null )
            {
                db.shutdown();
            }
            if ( server != null )
            {
                shutdownServer( server );
            }
        }
    }

    @Test
    public void fullThenIncremental() throws Exception
    {
        DbRepresentation initialDataSetRepresentation = createInitialDataSet( serverPath );
        ServerInterface server = startServer( serverPath );

        // START SNIPPET: onlineBackup
        OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );
        backup.full( backupPath.getPath() );
        assertTrue( "Should be consistent", backup.isConsistent() );
        // END SNIPPET: onlineBackup
        assertEquals( initialDataSetRepresentation, DbRepresentation.of( backupPath ) );
        shutdownServer( server );

        DbRepresentation furtherRepresentation = addMoreData( serverPath );
        server = startServer( serverPath );
        // START SNIPPET: onlineBackup
        backup.incremental( backupPath.getPath() );
        // END SNIPPET: onlineBackup
        assertTrue( "Should be consistent", backup.isConsistent() );
        assertEquals( furtherRepresentation, DbRepresentation.of( backupPath ) );
        shutdownServer( server );
    }

    @Test
    public void makeSureNoLogFileRemains() throws Exception
    {
        createInitialDataSet( serverPath );
        ServerInterface server = startServer( serverPath );
        OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );

        // First check full
        backup.full( backupPath.getPath() );
        assertTrue( "Should be consistent", backup.isConsistent() );
        assertFalse( checkLogFileExistence( backupPath.getPath() ) );
        // Then check empty incremental
        backup.incremental( backupPath.getPath() );
        assertTrue( "Should be consistent", backup.isConsistent() );
        assertFalse( checkLogFileExistence( backupPath.getPath() ) );
        // Then check real incremental
        shutdownServer( server );
        addMoreData( serverPath );
        server = startServer( serverPath );
        backup.incremental( backupPath.getPath() );
        assertTrue( "Should be consistent", backup.isConsistent() );
        assertFalse( checkLogFileExistence( backupPath.getPath() ) );
        shutdownServer( server );
    }

    @Test
    public void makeSureStoreIdIsEnforced() throws Exception
    {
        // Create data set X on server A
        DbRepresentation initialDataSetRepresentation = createInitialDataSet( serverPath );
        ServerInterface server = startServer( serverPath );

        // Grab initial backup from server A
        OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );
        backup.full( backupPath.getPath() );
        assertTrue( "Should be consistent", backup.isConsistent() );
        assertEquals( initialDataSetRepresentation, DbRepresentation.of( backupPath ) );
        shutdownServer( server );

        // Create data set X+Y on server B
        createInitialDataSet( otherServerPath );
        addMoreData( otherServerPath );
        server = startServer( otherServerPath );

        // Try to grab incremental backup from server B.
        // Data should be OK, but store id check should prevent that.
        try
        {
            backup.incremental( backupPath.getPath() );
            fail( "Shouldn't work" );
        }
        catch ( RuntimeException e )
        {
            assertThat(e.getCause(), instanceOf(MismatchingStoreIdException.class));
        }
        shutdownServer( server );
        // Just make sure incremental backup can be received properly from
        // server A, even after a failed attempt from server B
        DbRepresentation furtherRepresentation = addMoreData( serverPath );
        server = startServer( serverPath );
        backup.incremental( backupPath.getPath() );
        assertTrue( "Should be consistent", backup.isConsistent() );
        assertEquals( furtherRepresentation, DbRepresentation.of( backupPath ) );
        shutdownServer( server );
    }

    @Test
    public void multipleIncrementals() throws Exception
    {
        GraphDatabaseService db = null;
        try
        {
            db = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder( serverPath.getPath() ).
                setConfig( OnlineBackupSettings.online_backup_enabled, Settings.TRUE ).
                newGraphDatabase();

            Index<Node> index;
            try ( Transaction tx = db.beginTx() )
            {
                index = db.index().forNodes( "yo" );
                index.add( db.createNode(), "justTo", "commitATx" );
                db.createNode();
                tx.success();
            }

            OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );
            backup.full( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            long lastCommittedTx = getLastCommittedTx( backupPath.getPath() );

            for ( int i = 0; i < 5; i++ )
            {
                try ( Transaction tx = db.beginTx() )
                {
                    Node node = db.createNode();
                    index.add( node, "key", "value" + i );
                    tx.success();
                }
                backup = backup.incremental( backupPath.getPath() );
                assertTrue( "Should be consistent", backup.isConsistent() );
                assertEquals( lastCommittedTx + i + 1, getLastCommittedTx( backupPath.getPath() ) );
            }
        }
        finally
        {
            if ( db != null )
            {
                db.shutdown();
            }
        }
    }

    @Test
    public void backupIndexWithNoCommits() throws Exception
    {
        GraphDatabaseService db = null;
        try
        {
            db = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder( serverPath.getPath() ).
                setConfig( OnlineBackupSettings.online_backup_enabled, Settings.TRUE ).
                newGraphDatabase();

            try ( Transaction transaction = db.beginTx() )
            {
                db.index().forNodes( "created-no-commits" );
                transaction.success();
            }

            OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );
            backup.full( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            assertTrue( backup.isConsistent() );
        }
        finally
        {
            if ( db != null )
            {
                db.shutdown();
            }
        }
    }

    private long getLastCommittedTx( String path )
    {
        return new NeoStoreUtil( new File( path ) ).getLastCommittedTx();
    }

    @Test
    public void backupEmptyIndex() throws Exception
    {
        String key = "name";
        String value = "Neo";
        GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder( serverPath.getPath() ).
            setConfig( OnlineBackupSettings.online_backup_enabled, Settings.TRUE ).
            newGraphDatabase();

        try
        {
            Index<Node> index;
            Node node;
            try ( Transaction tx = db.beginTx() )
            {
                index = db.index().forNodes( key );
                node = db.createNode();
                node.setProperty( key, value );
                tx.success();
            }
            OnlineBackup backup = OnlineBackup.from( "127.0.0.1" ).full( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            assertEquals( DbRepresentation.of( db ), DbRepresentation.of( backupPath ) );
            FileUtils.deleteDirectory( new File( backupPath.getPath() ) );
            backup = OnlineBackup.from( "127.0.0.1" ).full( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            assertEquals( DbRepresentation.of( db ), DbRepresentation.of( backupPath ) );

            try ( Transaction tx = db.beginTx() )
            {
                index.add( node, key, value );
                tx.success();
            }
            FileUtils.deleteDirectory( new File( backupPath.getPath() ) );
            backup = OnlineBackup.from( "127.0.0.1" ).full( backupPath.getPath() );
            assertTrue( "Should be consistent", backup.isConsistent() );
            assertEquals( DbRepresentation.of( db ), DbRepresentation.of( backupPath ) );
        }
        finally
        {
            db.shutdown();
        }
    }

    @Test
    public void shouldRetainFileLocksAfterFullBackupOnLiveDatabase() throws Exception
    {
        String sourcePath = "target/var/serverdb-lock";
        FileUtils.deleteDirectory( new File( sourcePath ) );

        GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder( sourcePath ).
            setConfig( OnlineBackupSettings.online_backup_enabled, Settings.TRUE ).
            newGraphDatabase();
        try
        {
            assertStoreIsLocked( sourcePath );
            OnlineBackup.from( "127.0.0.1" ).full( backupPath.getPath() );
            assertStoreIsLocked( sourcePath );
        }
        finally
        {
            db.shutdown();
        }
    }

    @Test
    public void shouldIncrementallyBackupDenseNodes() throws Exception
    {
        GraphDatabaseService db = startGraphDatabase( serverPath, true );
        try
        {
            createInitialDataset( db );

            OnlineBackup backup = OnlineBackup.from( "127.0.0.1" );
            backup.full( backupPath.getPath() );

            DbRepresentation representation = addLotsOfData( db );
            backup.incremental( backupPath.getPath() );
            assertEquals( representation, DbRepresentation.of( backupPath ) );
        }
        finally
        {
            db.shutdown();
        }
    }

    private DbRepresentation addLotsOfData( GraphDatabaseService db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode();
            int threshold = parseInt( dense_node_threshold.getDefaultValue() );
            for ( int i = 0; i < threshold*2; i++ )
            {
                node.createRelationshipTo( db.createNode(), TEST );
            }
            tx.success();
        }
        return DbRepresentation.of( db );
    }

    private static void assertStoreIsLocked( String path )
    {
        try
        {
            new GraphDatabaseFactory().newEmbeddedDatabase( path).shutdown();
            fail( "Could start up database in same process, store not locked" );
        }
        catch ( RuntimeException ex )
        {
            assertThat( ex.getCause().getCause(), instanceOf( StoreLockException.class ) );
        }

        StartupChecker proc = new LockProcess().start( path );
        try
        {
            assertFalse( "Could start up database in subprocess, store is not locked", proc.startupOk() );
        }
        finally
        {
            SubProcess.stop( proc );
        }
    }

    public interface StartupChecker
    {
        boolean startupOk();
    }

    @SuppressWarnings( "serial" )
    private static class LockProcess extends SubProcess<StartupChecker, String> implements StartupChecker
    {
        private volatile Object state;

        @Override
        public boolean startupOk()
        {
            Object result;
            do
            {
                result = state;
            }
            while ( result == null );
            return !( state instanceof Exception );
        }

        @Override
        protected void startup( String path ) throws Throwable
        {
            GraphDatabaseService db = null;
            try
            {
                db = new GraphDatabaseFactory().newEmbeddedDatabase( path );
            }
            catch ( RuntimeException ex )
            {
                if ( ex.getCause().getCause() instanceof StoreLockException )
                {
                    state = ex;
                    return;
                }
            }
            state = new Object();
            if ( db != null )
            {
                db.shutdown();
            }
        }
    }

    private static boolean checkLogFileExistence( String directory )
    {
        return new File( directory, StringLogger.DEFAULT_NAME ).exists();
    }

    private void assertMetadataAboutLastTransactionExists( File storeDir )
    {
        GraphDatabaseAPI db = (GraphDatabaseAPI) new GraphDatabaseFactory().newEmbeddedDatabase( storeDir.getPath() );
        long lastCommittingTransactionId = -1;
        try
        {
            DependencyResolver resolver = db.getDependencyResolver();
            LogicalTransactionStore transactionStore = resolver.resolveDependency( LogicalTransactionStore.class );
            TransactionIdStore transactionIdStore = resolver.resolveDependency( TransactionIdStore.class );
            lastCommittingTransactionId = transactionIdStore.getLastCommittingTransactionId();
            assertNotNull( transactionStore.getMetadataFor( lastCommittingTransactionId ) );
            // Good, we got the metadata. If it's missing a NoSuchTransactionException should have been thrown
            // the not-null check is just because it looks better.
        }
        catch ( NoSuchTransactionException e )
        {
            fail( "Transaction metadata for " + lastCommittingTransactionId + " not found: " + e );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
        finally
        {
            db.shutdown();
        }
    }

    private ServerInterface startServer( File path ) throws Exception
    {
        ServerInterface server = new EmbeddedServer( path.getPath(), "127.0.0.1:6362" );
        server.awaitStarted();
        servers.add( server );
        return server;
    }

    private void shutdownServer( ServerInterface server ) throws Exception
    {
        server.shutdown();
        servers.remove( server );
    }

    private DbRepresentation addMoreData( File path )
    {
        GraphDatabaseService db = startGraphDatabase( path, false );
        DbRepresentation representation;
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode();
            node.setProperty( "backup", "Is great" );
            db.createNode().createRelationshipTo( node,
                    DynamicRelationshipType.withName( "LOVES" ) );
            tx.success();
        }
        finally
        {
            representation = DbRepresentation.of( db );
            db.shutdown();
        }
        return representation;
    }

    private GraphDatabaseService startGraphDatabase( File path, boolean withOnlineBackup )
    {
        return new GraphDatabaseFactory().
            newEmbeddedDatabaseBuilder( path.getPath() ).
            setConfig( OnlineBackupSettings.online_backup_enabled, String.valueOf( withOnlineBackup ) ).
            setConfig( GraphDatabaseSettings.keep_logical_logs, Settings.TRUE ).
            newGraphDatabase();
    }

    private DbRepresentation createInitialDataSet( File path )
    {
        GraphDatabaseService db = startGraphDatabase( path, false );
        try
        {
            createInitialDataset( db );
            return DbRepresentation.of( db );
        }
        finally
        {
            db.shutdown();
        }
    }

    private void createInitialDataset( GraphDatabaseService db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode();
            node.setProperty( "myKey", "myValue" );
            Index<Node> nodeIndex = db.index().forNodes( "db-index" );
            nodeIndex.add( node, "myKey", "myValue" );
            db.createNode().createRelationshipTo( node,
                    DynamicRelationshipType.withName( "KNOWS" ) );
            tx.success();
        }
    }
}
