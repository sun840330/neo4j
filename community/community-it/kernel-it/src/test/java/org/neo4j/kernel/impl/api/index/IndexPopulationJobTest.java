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
package org.neo4j.kernel.impl.api.index;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

import org.neo4j.common.EntityType;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.internal.helpers.collection.Visitor;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.kernel.api.PopulationProgress;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.LabelSchemaDescriptor;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.kernel.api.Kernel;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.impl.api.DatabaseSchemaState;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.kernel.impl.transaction.state.DefaultIndexProviderMap;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.AssertableLogProvider.LogMatcherBuilder;
import org.neo4j.logging.LogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.scheduler.JobHandle;
import org.neo4j.storageengine.api.EntityUpdates;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.storageengine.api.NodeLabelUpdate;
import org.neo4j.storageengine.api.NodePropertyAccessor;
import org.neo4j.test.DoubleLatch;
import org.neo4j.test.OtherThreadExecutor;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.internal.helpers.collection.Iterators.asSet;
import static org.neo4j.internal.helpers.collection.MapUtil.genericMap;
import static org.neo4j.internal.helpers.collection.MapUtil.map;
import static org.neo4j.internal.kernel.api.security.LoginContext.AUTH_DISABLED;
import static org.neo4j.io.memory.ByteBufferFactory.heapBufferFactory;
import static org.neo4j.kernel.api.KernelTransaction.Type.implicit;
import static org.neo4j.kernel.impl.api.index.IndexingService.NO_MONITOR;
import static org.neo4j.kernel.impl.api.index.TestIndexProviderDescriptor.PROVIDER_DESCRIPTOR;
import static org.neo4j.logging.AssertableLogProvider.inLog;
import static org.neo4j.storageengine.api.IndexEntryUpdate.add;

class IndexPopulationJobTest
{
    private GraphDatabaseAPI db;

    private static final Label FIRST = Label.label( "FIRST" );
    private static final Label SECOND = Label.label( "SECOND" );
    private static final String name = "name";
    private static final String age = "age";
    private static final RelationshipType likes = RelationshipType.withName( "likes" );
    private static final RelationshipType knows = RelationshipType.withName( "knows" );

    private Kernel kernel;
    private IndexStoreView indexStoreView;
    private DatabaseSchemaState stateHolder;
    private int labelId;
    private IndexStatisticsStore indexStatisticsStore;
    private DatabaseManagementService managementService;

    @BeforeEach
    void before() throws Exception
    {
        managementService = new TestDatabaseManagementServiceBuilder().impermanent().build();
        db = (GraphDatabaseAPI) managementService.database( DEFAULT_DATABASE_NAME );
        kernel = db.getDependencyResolver().resolveDependency( Kernel.class );
        stateHolder = new DatabaseSchemaState( NullLogProvider.getInstance() );
        indexStoreView = indexStoreView();
        indexStatisticsStore = db.getDependencyResolver().resolveDependency( IndexStatisticsStore.class );

        try ( KernelTransaction tx = kernel.beginTransaction( implicit, AUTH_DISABLED ) )
        {
            labelId = tx.tokenWrite().labelGetOrCreateForName( FIRST.name() );
            tx.tokenWrite().labelGetOrCreateForName( SECOND.name() );
            tx.commit();
        }
    }

    @AfterEach
    void after()
    {
        managementService.shutdown();
    }

    @Test
    void shouldPopulateIndexWithOneNode() throws Exception
    {
        // GIVEN
        String value = "Taylor";
        long nodeId = createNode( map( name, value ), FIRST );
        IndexPopulator populator = spy( indexPopulator( false ) );
        LabelSchemaDescriptor descriptor = SchemaDescriptor.forLabel( 0, 0 );
        IndexPopulationJob job = newIndexPopulationJob( populator, new FlippableIndexProxy(), EntityType.NODE, IndexPrototype.forSchema( descriptor ) );

        // WHEN
        job.run();

        // THEN
        IndexEntryUpdate<?> update = IndexEntryUpdate.add( nodeId, descriptor, Values.of( value ) );

        verify( populator ).create();
        verify( populator ).includeSample( update );
        verify( populator, times( 2 ) ).add( any( Collection.class ) );
        verify( populator ).sampleResult();
        verify( populator ).close( true );
    }

    @Test
    void shouldPopulateIndexWithOneRelationship() throws Exception
    {
        // GIVEN
        String value = "Taylor";
        long nodeId = createNode( map( name, value ), FIRST );
        long relationship = createRelationship( map( name, age ), likes, nodeId, nodeId );
        IndexPrototype descriptor = IndexPrototype.forSchema( SchemaDescriptor.forRelType( 0, 0 ) );
        IndexPopulator populator = spy( indexPopulator( descriptor ) );
        IndexPopulationJob job =
                newIndexPopulationJob( populator, new FlippableIndexProxy(), EntityType.RELATIONSHIP, descriptor );

        // WHEN
        job.run();

        // THEN
        IndexEntryUpdate<?> update = IndexEntryUpdate.add( relationship, descriptor, Values.of( age ) );

        verify( populator ).create();
        verify( populator ).includeSample( update );
        verify( populator, times( 2 ) ).add( any( Collection.class ) );
        verify( populator ).sampleResult();
        verify( populator ).close( true );
    }

    @Test
    void shouldFlushSchemaStateAfterPopulation() throws Exception
    {
        // GIVEN
        String value = "Taylor";
        createNode( map( name, value ), FIRST );
        stateHolder.put( "key", "original_value" );
        IndexPopulator populator = spy( indexPopulator( false ) );
        IndexPopulationJob job = newIndexPopulationJob( populator, new FlippableIndexProxy(), EntityType.NODE, indexPrototype( FIRST, name, false ) );

        // WHEN
        job.run();

        // THEN
        String result = stateHolder.get( "key" );
        assertNull( result );
    }

    @Test
    void shouldPopulateIndexWithASmallDataset() throws Exception
    {
        // GIVEN
        String value = "Mattias";
        long node1 = createNode( map( name, value ), FIRST );
        createNode( map( name, value ), SECOND );
        createNode( map( age, 31 ), FIRST );
        long node4 = createNode( map( age, 35, name, value ), FIRST );
        IndexPopulator populator = spy( indexPopulator( false ) );
        LabelSchemaDescriptor descriptor = SchemaDescriptor.forLabel( 0, 0 );
        IndexPopulationJob job = newIndexPopulationJob( populator, new FlippableIndexProxy(), EntityType.NODE, IndexPrototype.forSchema( descriptor ) );

        // WHEN
        job.run();

        // THEN
        IndexEntryUpdate<?> update1 = add( node1, descriptor, Values.of( value ) );
        IndexEntryUpdate<?> update2 = add( node4, descriptor, Values.of( value ) );

        verify( populator ).create();
        verify( populator ).includeSample( update1 );
        verify( populator ).includeSample( update2 );
        verify( populator, times( 2 ) ).add( anyCollection() );
        verify( populator ).sampleResult();
        verify( populator ).close( true );
    }

    @Test
    void shouldPopulateRelatonshipIndexWithASmallDataset() throws Exception
    {
        // GIVEN
        String value = "Philip J.Fry";
        long node1 = createNode( map( name, value ), FIRST );
        long node2 = createNode( map( name, value ), SECOND );
        long node3 = createNode( map( age, 31 ), FIRST );
        long node4 = createNode( map( age, 35, name, value ), FIRST );

        long rel1 = createRelationship( map( name, value ), likes, node1, node3 );
        createRelationship( map( name, value ), knows, node3, node1 );
        createRelationship( map( age, 31 ), likes, node2, node1 );
        long rel4 = createRelationship( map( age, 35, name, value ), likes, node4, node4 );

        IndexPrototype descriptor = IndexPrototype.forSchema( SchemaDescriptor.forRelType( 0, 0 ) );
        IndexPopulator populator = spy( indexPopulator( descriptor ) );
        IndexPopulationJob job =
                newIndexPopulationJob( populator, new FlippableIndexProxy(), EntityType.RELATIONSHIP, descriptor );

        // WHEN
        job.run();

        // THEN
        IndexEntryUpdate<?> update1 = add( rel1, descriptor, Values.of( value ) );
        IndexEntryUpdate<?> update2 = add( rel4, descriptor, Values.of( value ) );

        verify( populator ).create();
        verify( populator ).includeSample( update1 );
        verify( populator ).includeSample( update2 );
        verify( populator, times( 2 ) ).add( anyCollection() );
        verify( populator ).sampleResult();
        verify( populator ).close( true );
    }

    @Test
    void shouldIndexConcurrentUpdatesWhilePopulating() throws Exception
    {
        // GIVEN
        Object value1 = "Mattias";
        Object value2 = "Jacob";
        Object value3 = "Stefan";
        Object changedValue = "changed";
        long node1 = createNode( map( name, value1 ), FIRST );
        long node2 = createNode( map( name, value2 ), FIRST );
        long node3 = createNode( map( name, value3 ), FIRST );
        @SuppressWarnings( "UnnecessaryLocalVariable" )
        long changeNode = node1;
        int propertyKeyId = getPropertyKeyForName( name );
        NodeChangingWriter populator = new NodeChangingWriter( changeNode, propertyKeyId, value1, changedValue,
                labelId );
        IndexPopulationJob job = newIndexPopulationJob( populator, new FlippableIndexProxy(), EntityType.NODE, indexPrototype( FIRST, name, false ) );
        populator.setJob( job );

        // WHEN
        job.run();

        // THEN
        Set<Pair<Long, Object>> expected = asSet(
                Pair.of( node1, value1 ),
                Pair.of( node2, value2 ),
                Pair.of( node3, value3 ),
                Pair.of( node1, changedValue ) );
        assertEquals( expected, populator.added );
    }

    @Test
    void shouldRemoveViaConcurrentIndexUpdatesWhilePopulating() throws Exception
    {
        // GIVEN
        String value1 = "Mattias";
        String value2 = "Jacob";
        String value3 = "Stefan";
        long node1 = createNode( map( name, value1 ), FIRST );
        long node2 = createNode( map( name, value2 ), FIRST );
        long node3 = createNode( map( name, value3 ), FIRST );
        int propertyKeyId = getPropertyKeyForName( name );
        NodeDeletingWriter populator = new NodeDeletingWriter( node2, propertyKeyId, value2, labelId );
        IndexPopulationJob job = newIndexPopulationJob( populator, new FlippableIndexProxy(), EntityType.NODE, indexPrototype( FIRST, name, false ) );
        populator.setJob( job );

        // WHEN
        job.run();

        // THEN
        Map<Long, Object> expectedAdded = genericMap( node1, value1, node2, value2, node3, value3 );
        assertEquals( expectedAdded, populator.added );
        Map<Long, Object> expectedRemoved = genericMap( node2, value2 );
        assertEquals( expectedRemoved, populator.removed );
    }

    @Test
    void shouldTransitionToFailedStateIfPopulationJobCrashes() throws Exception
    {
        // GIVEN
        IndexPopulator failingPopulator = mock( IndexPopulator.class );
        doThrow( new RuntimeException( "BORK BORK" ) ).when( failingPopulator ).add( any( Collection.class ) );

        FlippableIndexProxy index = new FlippableIndexProxy();

        createNode( map( name, "Taylor" ), FIRST );
        IndexPopulationJob job = newIndexPopulationJob( failingPopulator, index, EntityType.NODE, indexPrototype( FIRST, name, false ) );

        // WHEN
        job.run();

        // THEN
        assertThat( index.getState(), equalTo( InternalIndexState.FAILED ) );
    }

    @Test
    void shouldBeAbleToCancelPopulationJob() throws Exception
    {
        // GIVEN
        createNode( map( name, "Mattias" ), FIRST );
        IndexPopulator populator = mock( IndexPopulator.class );
        FlippableIndexProxy index = mock( FlippableIndexProxy.class );
        IndexStoreView storeView = mock( IndexStoreView.class );
        ControlledStoreScan storeScan = new ControlledStoreScan();
        when( storeView.visitNodes( any(int[].class), any( IntPredicate.class ),
                ArgumentMatchers.any(),
                ArgumentMatchers.<Visitor<NodeLabelUpdate,RuntimeException>>any(), anyBoolean() ) )
                .thenReturn(storeScan );
        when( storeView.newPropertyAccessor() ).thenReturn( mock( NodePropertyAccessor.class ) );

        final IndexPopulationJob job =
                newIndexPopulationJob( populator, index, storeView, NullLogProvider.getInstance(), EntityType.NODE, indexPrototype( FIRST, name, false ) );
        JobHandle jobHandle = mock( JobHandle.class );
        job.setHandle( jobHandle );

        Future<Void> runFuture;
        try ( OtherThreadExecutor<Void> populationJobRunner = new OtherThreadExecutor<>(
            "Population job test runner", null ) )
        {
            runFuture = populationJobRunner
                .executeDontWait( state ->
                {
                    job.run();
                    return null;
                } );

            storeScan.latch.waitForAllToStart();
            job.cancel();
            job.awaitCompletion( 0, TimeUnit.SECONDS );
            storeScan.latch.waitForAllToFinish();

            // WHEN
            runFuture.get();
        }

        // THEN
        verify( populator ).close( false );
        verify( index, never() ).flip( any(), any() );
        verify( jobHandle ).cancel();
    }

    @Test
    void shouldLogJobProgress() throws Exception
    {
        // Given
        createNode( map( name, "irrelephant" ), FIRST );
        AssertableLogProvider logProvider = new AssertableLogProvider();
        FlippableIndexProxy index = mock( FlippableIndexProxy.class );
        when( index.getState() ).thenReturn( InternalIndexState.ONLINE );
        IndexPopulator populator = spy( indexPopulator( false ) );
        try
        {
            IndexPopulationJob job = newIndexPopulationJob( populator, index, indexStoreView, logProvider,
                            EntityType.NODE, indexPrototype( FIRST, name, false ) );

            // When
            job.run();

            // Then
            LogMatcherBuilder match = inLog( IndexPopulationJob.class );
            logProvider.assertExactly( match.info( "Index population started: [%s]", ":FIRST(name)" ),
                    match.info( "Index creation finished. Index [%s] is %s.", ":FIRST(name)", "ONLINE" ),
                    match.info( containsString( "TIME/PHASE Final: SCAN[" ) ) );
        }
        finally
        {
            populator.close( true );
        }
    }

    @Test
    void logConstraintJobProgress() throws Exception
    {
        // Given
        createNode( map( name, "irrelephant" ), FIRST );
        AssertableLogProvider logProvider = new AssertableLogProvider();
        FlippableIndexProxy index = mock( FlippableIndexProxy.class );
        when( index.getState() ).thenReturn( InternalIndexState.POPULATING );
        IndexPopulator populator = spy( indexPopulator( false ) );
        try
        {
            IndexPopulationJob job = newIndexPopulationJob( populator, index, indexStoreView, logProvider,
                    EntityType.NODE, indexPrototype( FIRST, name, true ) );

            // When
            job.run();

            // Then
            LogMatcherBuilder match = inLog( IndexPopulationJob.class );
            logProvider.assertExactly( match.info( "Index population started: [%s]", ":FIRST(name)" ),
                    match.info( "Index created. Starting data checks. Index [%s] is %s.", ":FIRST(name)", "POPULATING" ),
                    match.info( containsString( "TIME/PHASE Final: SCAN[" ) ));
        }
        finally
        {
            populator.close( true );
        }
    }

    @Test
    void shouldLogJobFailure() throws Exception
    {
        // Given
        createNode( map( name, "irrelephant" ), FIRST );
        AssertableLogProvider logProvider = new AssertableLogProvider();
        FlippableIndexProxy index = mock( FlippableIndexProxy.class );
        IndexPopulator populator = spy( indexPopulator( false ) );
        IndexPopulationJob job = newIndexPopulationJob( populator, index, indexStoreView, logProvider, EntityType.NODE, indexPrototype( FIRST, name, false ) );

        Throwable failure = new IllegalStateException( "not successful" );
        doThrow( failure ).when( populator ).create();

        // When
        job.run();

        // Then
        LogMatcherBuilder match = inLog( IndexPopulationJob.class );
        logProvider.assertAtLeastOnce(
                match.error( is( "Failed to populate index: [:FIRST(name)]" ), sameInstance( failure ) )
        );
    }

    @Test
    void shouldFlipToFailedUsingFailedIndexProxyFactory() throws Exception
    {
        // Given
        FailedIndexProxyFactory failureDelegateFactory = mock( FailedIndexProxyFactory.class );
        IndexPopulator populator = spy( indexPopulator( false ) );
        IndexPopulationJob job =
                newIndexPopulationJob( failureDelegateFactory, populator, new FlippableIndexProxy(), indexStoreView, NullLogProvider.getInstance(),
                        EntityType.NODE, indexPrototype( FIRST, name, false ) );

        IllegalStateException failure = new IllegalStateException( "not successful" );
        doThrow( failure ).when( populator ).close( true );

        // When
        job.run();

        // Then
        verify( failureDelegateFactory ).create( any( Throwable.class ) );
    }

    @Test
    void shouldCloseAndFailOnFailure() throws Exception
    {
        createNode( map( name, "irrelephant" ), FIRST );
        LogProvider logProvider = NullLogProvider.getInstance();
        FlippableIndexProxy index = mock( FlippableIndexProxy.class );
        IndexPopulator populator = spy( indexPopulator( false ) );
        IndexPopulationJob job = newIndexPopulationJob( populator, index, indexStoreView, logProvider, EntityType.NODE, indexPrototype( FIRST, name, false ) );

        String failureMessage = "not successful";
        IllegalStateException failure = new IllegalStateException( failureMessage );
        doThrow( failure ).when( populator ).create();

        // When
        job.run();

        // Then
        verify( populator ).markAsFailed( contains( failureMessage ) );
    }

    @Test
    void shouldCloseMultiPopulatorOnSuccessfulPopulation()
    {
        // given
        NullLogProvider logProvider = NullLogProvider.getInstance();
        TrackingMultipleIndexPopulator populator = new TrackingMultipleIndexPopulator( IndexStoreView.EMPTY, logProvider, EntityType.NODE,
                new DatabaseSchemaState( logProvider ), mock( IndexStatisticsStore.class ) );
        IndexPopulationJob populationJob = new IndexPopulationJob( populator, NO_MONITOR, false );

        // when
        populationJob.run();

        // then
        assertTrue( populator.closed );
    }

    @Test
    void shouldCloseMultiPopulatorOnFailedPopulation()
    {
        // given
        NullLogProvider logProvider = NullLogProvider.getInstance();
        IndexStoreView failingStoreView = new IndexStoreView.Adaptor()
        {
            @Override
            public <FAILURE extends Exception> StoreScan<FAILURE> visitNodes( int[] labelIds, IntPredicate propertyKeyIdFilter,
                    Visitor<EntityUpdates,FAILURE> propertyUpdateVisitor, Visitor<NodeLabelUpdate,FAILURE> labelUpdateVisitor, boolean forceStoreScan )
            {
                return new StoreScan<>()
                {
                    @Override
                    public void run()
                    {
                        throw new RuntimeException( "Just failing" );
                    }

                    @Override
                    public void stop()
                    {
                    }

                    @Override
                    public void acceptUpdate( MultipleIndexPopulator.MultipleIndexUpdater updater, IndexEntryUpdate<?> update, long currentlyIndexedNodeId )
                    {
                    }

                    @Override
                    public PopulationProgress getProgress()
                    {
                        return null;
                    }
                };
            }
        };
        TrackingMultipleIndexPopulator populator = new TrackingMultipleIndexPopulator( failingStoreView, logProvider, EntityType.NODE,
                new DatabaseSchemaState( logProvider ), mock( IndexStatisticsStore.class ) );
        IndexPopulationJob populationJob = new IndexPopulationJob( populator, NO_MONITOR, false );

        // when
        populationJob.run();

        // then
        assertTrue( populator.closed );
    }

    private static class ControlledStoreScan implements StoreScan<RuntimeException>
    {
        private final DoubleLatch latch = new DoubleLatch();

        @Override
        public void run()
        {
            latch.startAndWaitForAllToStartAndFinish();
        }

        @Override
        public void stop()
        {
            latch.finish();
        }

        @Override
        public void acceptUpdate( MultipleIndexPopulator.MultipleIndexUpdater updater, IndexEntryUpdate<?> update, long currentlyIndexedNodeId )
        {
        }

        @Override
        public PopulationProgress getProgress()
        {
            return PopulationProgress.single( 42, 100 );
        }
    }

    private class NodeChangingWriter extends IndexPopulator.Adapter
    {
        private final Set<Pair<Long, Object>> added = new HashSet<>();
        private IndexPopulationJob job;
        private final long nodeToChange;
        private final Value newValue;
        private final Value previousValue;
        private final LabelSchemaDescriptor index;

        NodeChangingWriter( long nodeToChange, int propertyKeyId, Object previousValue, Object newValue, int label )
        {
            this.nodeToChange = nodeToChange;
            this.previousValue = Values.of( previousValue );
            this.newValue = Values.of( newValue );
            this.index = SchemaDescriptor.forLabel( label, propertyKeyId );
        }

        @Override
        public void add( Collection<? extends IndexEntryUpdate<?>> updates )
        {
            for ( IndexEntryUpdate<?> update : updates )
            {
                add( update );
            }
        }

        void add( IndexEntryUpdate<?> update )
        {
            if ( update.getEntityId() == 2 )
            {
                job.update( IndexEntryUpdate.change( nodeToChange, index, previousValue, newValue ) );
            }
            added.add( Pair.of( update.getEntityId(), update.values()[0].asObjectCopy() ) );
        }

        @Override
        public IndexUpdater newPopulatingUpdater( NodePropertyAccessor nodePropertyAccessor )
        {
            return new IndexUpdater()
            {
                @Override
                public void process( IndexEntryUpdate<?> update )
                {
                    switch ( update.updateMode() )
                    {
                        case ADDED:
                        case CHANGED:
                            added.add( Pair.of( update.getEntityId(), update.values()[0].asObjectCopy() ) );
                            break;
                        default:
                            throw new IllegalArgumentException( update.updateMode().name() );
                    }
                }

                @Override
                public void close()
                {
                }
            };
        }

        void setJob( IndexPopulationJob job )
        {
            this.job = job;
        }
    }

    private class NodeDeletingWriter extends IndexPopulator.Adapter
    {
        private final Map<Long, Object> added = new HashMap<>();
        private final Map<Long, Object> removed = new HashMap<>();
        private final long nodeToDelete;
        private IndexPopulationJob job;
        private final Value valueToDelete;
        private final LabelSchemaDescriptor index;

        NodeDeletingWriter( long nodeToDelete, int propertyKeyId, Object valueToDelete, int label )
        {
            this.nodeToDelete = nodeToDelete;
            this.valueToDelete = Values.of( valueToDelete );
            this.index = SchemaDescriptor.forLabel( label, propertyKeyId );
        }

        void setJob( IndexPopulationJob job )
        {
            this.job = job;
        }

        @Override
        public void add( Collection<? extends IndexEntryUpdate<?>> updates )
        {
            for ( IndexEntryUpdate<?> update : updates )
            {
                add( update );
            }
        }

        void add( IndexEntryUpdate<?> update )
        {
            if ( update.getEntityId() == 2 )
            {
                job.update( IndexEntryUpdate.remove( nodeToDelete, index, valueToDelete ) );
            }
            added.put( update.getEntityId(), update.values()[0].asObjectCopy() );
        }

        @Override
        public IndexUpdater newPopulatingUpdater( NodePropertyAccessor nodePropertyAccessor )
        {
            return new IndexUpdater()
            {
                @Override
                public void process( IndexEntryUpdate<?> update )
                {
                    switch ( update.updateMode() )
                    {
                        case ADDED:
                        case CHANGED:
                            added.put( update.getEntityId(), update.values()[0].asObjectCopy() );
                            break;
                        case REMOVED:
                            removed.put( update.getEntityId(), update.values()[0].asObjectCopy() ); // on remove, value is the before value
                            break;
                        default:
                            throw new IllegalArgumentException( update.updateMode().name() );
                    }
                }

                @Override
                public void close()
                {
                }
            };
        }
    }

    private IndexPopulator indexPopulator( boolean constraint ) throws KernelException
    {
        IndexPrototype prototype = indexPrototype( FIRST, name, constraint );
        return indexPopulator( prototype );
    }

    private IndexPopulator indexPopulator( IndexPrototype prototype )
    {
        IndexSamplingConfig samplingConfig = new IndexSamplingConfig( Config.defaults() );
        IndexProvider indexProvider = db.getDependencyResolver().resolveDependency( DefaultIndexProviderMap.class ).getDefaultProvider();
        IndexDescriptor indexDescriptor = prototype.withName( "index_21" ).materialise( 21 );
        indexDescriptor = indexProvider.completeConfiguration( indexDescriptor );
        return indexProvider.getPopulator( indexDescriptor, samplingConfig, heapBufferFactory( 1024 ) );
    }

    private IndexPopulationJob newIndexPopulationJob( IndexPopulator populator, FlippableIndexProxy flipper, EntityType type, IndexPrototype prototype )
    {
        return newIndexPopulationJob( populator, flipper, indexStoreView, NullLogProvider.getInstance(), type, prototype );
    }

    private IndexPopulationJob newIndexPopulationJob( IndexPopulator populator, FlippableIndexProxy flipper, IndexStoreView storeView, LogProvider logProvider,
            EntityType type, IndexPrototype prototype )
    {
        return newIndexPopulationJob( mock( FailedIndexProxyFactory.class ), populator, flipper, storeView, logProvider, type, prototype );
    }

    private IndexPopulationJob newIndexPopulationJob( FailedIndexProxyFactory failureDelegateFactory, IndexPopulator populator, FlippableIndexProxy flipper,
            IndexStoreView storeView, LogProvider logProvider, EntityType type, IndexPrototype prototype )
    {
        long indexId = 0;
        flipper.setFlipTarget( mock( IndexProxyFactory.class ) );

        MultipleIndexPopulator multiPopulator = new MultipleIndexPopulator( storeView, logProvider, type, stateHolder, indexStatisticsStore );
        IndexPopulationJob job = new IndexPopulationJob( multiPopulator, NO_MONITOR, false );
        IndexDescriptor descriptor = prototype.withName( "index_" + indexId ).materialise( indexId );
        job.addPopulator( populator, descriptor, format( ":%s(%s)", FIRST.name(), name ), flipper, failureDelegateFactory );
        return job;
    }

    private IndexPrototype indexPrototype( Label label, String propertyKey, boolean constraint ) throws KernelException
    {
        try ( KernelTransaction tx = kernel.beginTransaction( implicit, AUTH_DISABLED ) )
        {
            int labelId = tx.tokenWrite().labelGetOrCreateForName( label.name() );
            int propertyKeyId = tx.tokenWrite().propertyKeyGetOrCreateForName( propertyKey );
            SchemaDescriptor schema = SchemaDescriptor.forLabel( labelId, propertyKeyId );
            IndexPrototype descriptor = constraint ?
                                         IndexPrototype.uniqueForSchema( schema, PROVIDER_DESCRIPTOR ) :
                                         IndexPrototype.forSchema( schema, PROVIDER_DESCRIPTOR );
            tx.commit();
            return descriptor;
        }
    }

    private long createNode( Map<String, Object> properties, Label... labels )
    {
        try ( org.neo4j.graphdb.Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode( labels );
            for ( Map.Entry<String,Object> property : properties.entrySet() )
            {
                node.setProperty( property.getKey(), property.getValue() );
            }
            tx.commit();
            return node.getId();
        }
    }

    private long createRelationship( Map<String,Object> properties, RelationshipType relType, long fromNode, long toNode )
    {
        try ( org.neo4j.graphdb.Transaction tx = db.beginTx() )
        {
            Node node1 = tx.getNodeById( fromNode );
            Node node2 = tx.getNodeById( toNode );
            Relationship relationship = node1.createRelationshipTo( node2, relType );
            for ( Map.Entry<String,Object> property : properties.entrySet() )
            {
                relationship.setProperty( property.getKey(), property.getValue() );
            }
            tx.commit();
            return relationship.getId();
        }
    }

    private int getPropertyKeyForName( String name ) throws TransactionFailureException
    {
        try ( KernelTransaction tx = kernel.beginTransaction( implicit, AUTH_DISABLED ) )
        {
            int result = tx.tokenRead().propertyKey( name );
            tx.commit();
            return result;
        }
    }

    private IndexStoreView indexStoreView()
    {
        return db.getDependencyResolver().resolveDependency( IndexStoreView.class );
    }

    private static class TrackingMultipleIndexPopulator extends MultipleIndexPopulator
    {
        private volatile boolean closed;

        TrackingMultipleIndexPopulator( IndexStoreView storeView, LogProvider logProvider, EntityType type, SchemaState schemaState,
                IndexStatisticsStore indexStatisticsStore )
        {
            super( storeView, logProvider, type, schemaState, indexStatisticsStore );
        }

        @Override
        public void close( boolean populationCompletedSuccessfully )
        {
            closed = true;
            super.close( populationCompletedSuccessfully );
        }
    }
}
