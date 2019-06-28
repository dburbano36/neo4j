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
package org.neo4j.bolt.dbapi.impl;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.neo4j.bolt.dbapi.BoltGraphDatabaseServiceSPI;
import org.neo4j.bolt.dbapi.BoltQueryExecutor;
import org.neo4j.bolt.dbapi.BoltTransaction;
import org.neo4j.bolt.runtime.AccessMode;
import org.neo4j.internal.kernel.api.Transaction;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.kernel.GraphDatabaseQueryService;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.txtracking.TransactionIdTracker;
import org.neo4j.kernel.availability.AvailabilityGuard;
import org.neo4j.kernel.availability.DatabaseAvailabilityGuard;
import org.neo4j.kernel.availability.UnavailableException;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.query.Neo4jTransactionalContextFactory;
import org.neo4j.kernel.impl.query.QueryExecutionEngine;
import org.neo4j.kernel.impl.query.TransactionalContextFactory;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.time.SystemNanoClock;

import static java.lang.String.format;

public class BoltKernelGraphDatabaseServiceProvider implements BoltGraphDatabaseServiceSPI
{
    private final TransactionIdTracker transactionIdTracker;
    private final GraphDatabaseFacade databaseFacade;
    private final QueryExecutionEngine queryExecutionEngine;
    private final ThreadToStatementContextBridge txBridge;
    private final TransactionalContextFactory transactionalContextFactory;
    private final String databaseName;

    public BoltKernelGraphDatabaseServiceProvider( GraphDatabaseFacade facade, SystemNanoClock clock, String databaseName ) throws UnavailableException
    {
        this.databaseFacade = facade;
        this.databaseName = databaseName;
        if ( !databaseFacade.isAvailable( 0 ) )
        {
            throw new UnavailableException( format( "Database `%s` is unavailable.", databaseName ) );
        }

        this.txBridge = resolveDependency( facade, ThreadToStatementContextBridge.class );
        this.queryExecutionEngine = resolveDependency( facade, QueryExecutionEngine.class );
        this.transactionIdTracker = newTransactionIdTracker( facade, clock );
        this.transactionalContextFactory = newTransactionalContextFactory( facade );
    }

    private static <T> T resolveDependency( GraphDatabaseFacade databaseContext, Class<T> clazz )
    {
        return databaseContext.getDependencyResolver().resolveDependency( clazz );
    }

    private static TransactionIdTracker newTransactionIdTracker( GraphDatabaseFacade databaseContext, SystemNanoClock clock )
    {
        Supplier<TransactionIdStore> transactionIdStoreSupplier = databaseContext.getDependencyResolver().provideDependency( TransactionIdStore.class );
        AvailabilityGuard guard = resolveDependency( databaseContext, DatabaseAvailabilityGuard.class );
        return new TransactionIdTracker( transactionIdStoreSupplier, guard, clock );
    }

    private static TransactionalContextFactory newTransactionalContextFactory( GraphDatabaseFacade databaseContext )
    {
        GraphDatabaseQueryService queryService = resolveDependency( databaseContext, GraphDatabaseQueryService.class );
        return Neo4jTransactionalContextFactory.create( queryService );
    }

    @Override
    public void awaitUpToDate( long oldestAcceptableTxId, Duration timeout ) throws TransactionFailureException
    {
        transactionIdTracker.awaitUpToDate( oldestAcceptableTxId, timeout );
    }

    @Override
    public long newestEncounteredTxId()
    {
        return transactionIdTracker.newestEncounteredTxId();
    }

    @Override
    public BoltTransaction beginTransaction( KernelTransaction.Type type, LoginContext loginContext, ClientConnectionInfo clientInfo, Duration txTimeout,
            AccessMode accessMode, Map<String,Object> txMetadata )
    {

        Supplier<InternalTransaction> internalTransactionSupplier = () -> beginInternalTransaction( type, loginContext, clientInfo, txTimeout, txMetadata );
        InternalTransaction topLevelInternalTransaction = internalTransactionSupplier.get();
        KernelTransaction kernelTransaction = txBridge.getKernelTransactionBoundToThisThread( false );
        return new BoltKernelTransaction( queryExecutionEngine, txBridge, transactionalContextFactory, kernelTransaction, topLevelInternalTransaction,
                internalTransactionSupplier );
    }

    @Override
    public BoltQueryExecutor getPeriodicCommitExecutor( LoginContext loginContext, ClientConnectionInfo clientInfo, Duration txTimeout,
            AccessMode accessMode, Map<String,Object> txMetadata )
    {
        Supplier<InternalTransaction> internalTransactionSupplier =
                () -> beginInternalTransaction( Transaction.Type.implicit, loginContext, clientInfo, txTimeout, txMetadata );
        return new BoltQueryExecutorImpl( queryExecutionEngine, transactionalContextFactory, internalTransactionSupplier );
    }

    @Override
    public boolean isPeriodicCommit( String query )
    {
        return queryExecutionEngine.isPeriodicCommit( query );
    }

    @Override
    public String getDatabaseName()
    {
        return databaseName;
    }

    private InternalTransaction beginInternalTransaction( KernelTransaction.Type type, LoginContext loginContext, ClientConnectionInfo clientInfo,
            Duration txTimeout, Map<String,Object> txMetadata )
    {
        InternalTransaction internalTransaction;
        if ( txTimeout == null )
        {
            internalTransaction = databaseFacade.beginTransaction( type, loginContext, clientInfo );
        }
        else
        {
            internalTransaction = databaseFacade.beginTransaction( type, loginContext, clientInfo, txTimeout.toMillis(), TimeUnit.MILLISECONDS );
        }

        if ( txMetadata != null )
        {
            internalTransaction.setMetaData( txMetadata );
        }

        return internalTransaction;
    }
}