package com.palantir.atlasdb.keyvalue.dbkvs.impl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.palantir.atlasdb.AtlasSystemPropertyManager;
import com.palantir.atlasdb.DeclaredAtlasSystemProperty;
import com.palantir.atlasdb.keyvalue.dbkvs.impl.postgres.PostgresDdlTable;
import com.palantir.atlasdb.keyvalue.dbkvs.impl.postgres.PostgresQueryFactory;
import com.palantir.common.concurrent.NamedThreadFactory;
import com.palantir.common.concurrent.PTExecutors;
import com.palantir.db.oracle.OracleShim;

public class PostgresDbTableFactory implements DbTableFactory {

    private final AtlasSystemPropertyManager systemProperties;
    private final ExecutorService exec;

    public PostgresDbTableFactory(AtlasSystemPropertyManager systemProperties) {
        this.systemProperties = systemProperties;
        int poolSize = systemProperties.getSystemPropertyInteger(
                DeclaredAtlasSystemProperty.__ATLASDB_POSTGRES_QUERY_POOL_SIZE);
        this.exec = newFixedThreadPool(poolSize);
    }

    private static ThreadPoolExecutor newFixedThreadPool(int maxPoolSize) {
        ThreadPoolExecutor pool = PTExecutors.newThreadPoolExecutor(maxPoolSize, maxPoolSize,
                15L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                new NamedThreadFactory("Atlas postgres reader", true /* daemon */));

        pool.allowCoreThreadTimeOut(false);
        return pool;
    }

    @Override
    public DbMetadataTable createMetadata(String tableName, ConnectionSupplier conns) {
        return new SimpleDbMetadataTable(tableName, conns);
    }

    @Override
    public DbDdlTable createDdl(String tableName, ConnectionSupplier conns, AtlasSystemPropertyManager systemProperties) {
        return new PostgresDdlTable(tableName, conns);
    }

    @Override
    public DbReadTable createRead(String tableName, ConnectionSupplier conns, OracleShim oracleShim) {
        return new BatchedDbReadTable(conns, new PostgresQueryFactory(tableName), exec, systemProperties);
    }

    @Override
    public DbWriteTable createWrite(String tableName, ConnectionSupplier conns) {
        return new SimpleDbWriteTable(tableName, conns);
    }

    @Override
    public void close() {
        exec.shutdown();
    }
}
