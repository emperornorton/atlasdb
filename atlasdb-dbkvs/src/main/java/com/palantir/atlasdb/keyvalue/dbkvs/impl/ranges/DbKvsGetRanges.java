package com.palantir.atlasdb.keyvalue.dbkvs.impl.ranges;

import java.sql.Connection;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import com.google.common.primitives.UnsignedBytes;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.ColumnSelection;
import com.palantir.atlasdb.keyvalue.api.RangeRequest;
import com.palantir.atlasdb.keyvalue.api.RangeRequests;
import com.palantir.atlasdb.keyvalue.api.RowResult;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.api.Value;
import com.palantir.atlasdb.keyvalue.dbkvs.impl.DbKvs;
import com.palantir.atlasdb.keyvalue.impl.Cells;
import com.palantir.atlasdb.keyvalue.impl.RowResults;
import com.palantir.common.collect.IterableView;
import com.palantir.exception.PalantirSqlException;
import com.palantir.nexus.db.DBType;
import com.palantir.nexus.db.sql.BasicSQLUtils;
import com.palantir.nexus.db.sql.PalantirSqlConnection;
import com.palantir.sql.Connections;
import com.palantir.util.AssertUtils;
import com.palantir.util.Pair;
import com.palantir.util.jmx.OperationTimer;
import com.palantir.util.jmx.OperationTimer.TimingState;
import com.palantir.util.paging.SimpleTokenBackedResultsPage;
import com.palantir.util.paging.TokenBackedBasicResultsPage;
import com.palantir.util.timer.LoggingOperationTimer;

public class DbKvsGetRanges {
    private static final Logger log = LoggerFactory.getLogger(DbKvsGetRanges.class);
    private static final OperationTimer logTimer = LoggingOperationTimer.create(log);
    private static final byte[] SMALLEST_NAME = Cells.createSmallestCellForRow(new byte[] {0}).getColumnName();
    private static final byte[] LARGEST_NAME = Cells.createLargestCellForRow(new byte[] {0}).getColumnName();
    private final DbKvs kvs;
    private final DBType dbType;
    private final Supplier<PalantirSqlConnection> connectionSupplier;

    public DbKvsGetRanges(DbKvs kvs,
                          DBType dbType,
                          Supplier<PalantirSqlConnection> connectionSupplier) {
        this.kvs = kvs;
        this.dbType = dbType;
        this.connectionSupplier = connectionSupplier;
    }

    public Map<RangeRequest, TokenBackedBasicResultsPage<RowResult<Value>, byte[]>> getFirstBatchForRanges(final TableReference tableRef,
                                                                                                           Iterable<RangeRequest> rangeRequests,
                                                                                                           final long timestamp) {
        return getFirstPages(tableRef, ImmutableList.copyOf(rangeRequests), timestamp);
    }

    private Map<RangeRequest, TokenBackedBasicResultsPage<RowResult<Value>, byte[]>> getFirstPages(TableReference tableRef,
            List<RangeRequest> requests,
            long timestamp) {
        Multimap<String, Object[]> argListByQuery = ArrayListMultimap.create();
        for (int i = 0; i < requests.size(); i++) {
            RangeRequest request = requests.get(i);
            Pair<String, List<Object>> queryAndArgs = getRangeQueryAndArgs(
                    tableRef.getQualifiedName(),
                    request.getStartInclusive(),
                    request.getEndExclusive(),
                    request.isReverse(),
                    request.getBatchHint() == null ? 1 : request.getBatchHint(),
                    i);
            argListByQuery.put(queryAndArgs.lhSide, queryAndArgs.rhSide.toArray());
        }

        TimingState timer = logTimer.begin("Table: " + tableRef.getQualifiedName() + " get_page");
        final PalantirSqlConnection conn = connectionSupplier.get();
        try {
            final boolean oldAutoCommitFlag = setAutoCommitFlag(conn, false);
            try {
                return getFirstPagesFromDb(tableRef, requests, timestamp, argListByQuery, conn);
            } finally {
                setAutoCommitFlag(conn, oldAutoCommitFlag);
            }
        } finally {
            closeSql(conn);
            timer.end();
        }
    }

    private boolean setAutoCommitFlag(final PalantirSqlConnection conn, final boolean autoCommitFlag) {
        return BasicSQLUtils.runUninterruptably(new Callable<Boolean>() {
            @Override
            public Boolean call() throws PalantirSqlException  {
                boolean status = Connections.getAutoCommit(conn.getUnderlyingConnection());
                Connections.setAutoCommit(conn.getUnderlyingConnection(), autoCommitFlag);
                return status;
            }
        }, "metro setAutoCommit"); //$NON-NLS-1$
    }

    private Map<RangeRequest, TokenBackedBasicResultsPage<RowResult<Value>, byte[]>> getFirstPagesFromDb(TableReference tableRef,
                                                                             List<RangeRequest> requests,
                                                                             long timestamp,
                                                                             Multimap<String, Object[]> argListByQuery,
                                                                             final PalantirSqlConnection conn) {
        TempTables.truncateRowTable(conn);
        for (String query : argListByQuery.keySet()) {
            final Collection<Object[]> argLists = argListByQuery.get(query);
            String insertQuery = String.format(SQL_MET_TEMP_INSERT_TEMPLATE, tableRef.getQualifiedName()) + "(" + query + ")";
            conn.updateManyUnregisteredQuery(insertQuery, argLists);
        }
        TreeMultimap<Integer, byte[]> rowsForBatches = TempTables.getRowsForBatches(conn);
        Map<Cell, Value> cells = kvs.getRows(tableRef, rowsForBatches.values(),
                                         ColumnSelection.all(), timestamp);
        NavigableMap<byte[], SortedMap<byte[], Value>> cellsByRow = Cells.breakCellsUpByRow(cells);
        log.info("getRange actualRowsReturned: {}", cellsByRow.size());
        return breakUpByBatch(requests, rowsForBatches, cellsByRow);
    }

    private Pair<String, List<Object>> getRangeQueryAndArgs(String tableName,
                                                            byte[] startRow,
                                                            byte[] endRow,
                                                            boolean reverse,
                                                            int numRowsToGet,
                                                            int queryNum) {
        if (startRow.length == 0) {
            if (reverse) {
                startRow = LARGEST_NAME;
            } else {
                startRow = SMALLEST_NAME;
            }
        }

        String extraWhere;
        List<Object> args = Lists.newArrayList();
        args.add(queryNum);
        if (reverse) {
            extraWhere = " met.row_name <= ? ";
        } else {
            extraWhere = " met.row_name >= ? ";
        }
        args.add(startRow);

        if (endRow.length > 0) {
            if (reverse) {
                extraWhere += " AND met.row_name > ? ";
            } else {
                extraWhere += " AND met.row_name < ? ";
            }
            args.add(endRow);
        }

        String order = reverse ? "DESC" : "ASC";
        if (numRowsToGet == 1) {
            String minMax = reverse ? "max" : "min";
            // QA-69854 Special case 1 row reads because oracle is terrible at optimizing queries
            String query = dbType == DBType.ORACLE
                    ? getSimpleRowSelectOneQueryOracle(tableName, minMax, extraWhere, order)
                    : getSimpleRowSelectOneQueryPostgres(tableName, minMax, extraWhere, order);
            return Pair.create(query, args);
        } else {
            String query = String.format(
                    SIMPLE_ROW_SELECT_TEMPLATE,
                    tableName,
                    tableName,
                    tableName,
                    extraWhere,
                    order);
            String limitQuery = BasicSQLUtils.limitQuery(query, numRowsToGet, args, dbType);
            return Pair.create(limitQuery, args);
        }
    }
    /**
     * This method expects the input to be sorted by rowname ASC for both rowsForBatches and
     * cellsByRow.
     */
    private Map<RangeRequest, TokenBackedBasicResultsPage<RowResult<Value>, byte[]>> breakUpByBatch(List<RangeRequest> requests,
            TreeMultimap<Integer, byte[]> rowsForBatches,
            NavigableMap<byte[], SortedMap<byte[], Value>>   cellsByRow) {
        Map<RangeRequest, TokenBackedBasicResultsPage<RowResult<Value>, byte[]>> ret = Maps.newHashMap();
        for (int i = 0; i < requests.size(); i++) {
            RangeRequest request = requests.get(i);
            if (ret.containsKey(request)) {
                continue;
            }
            SortedSet<byte[]> rowNames = rowsForBatches.get(i);
            SortedMap<byte[], SortedMap<byte[], Value>> cellsForBatch = Maps.filterKeys(
                    request.isReverse() ? cellsByRow.descendingMap() : cellsByRow,
                    Predicates.in(rowNames));
            validateRowNames(cellsForBatch.keySet(), request.getStartInclusive(), request.getEndExclusive(), request.isReverse());
            IterableView<RowResult<Value>> rows = RowResults.viewOfMap(cellsForBatch);
            if (!request.getColumnNames().isEmpty()) {
                rows = filterColumnSelection(rows, request);
            }
            if (rowNames.isEmpty()) {
                assert rows.isEmpty();
                ret.put(request, SimpleTokenBackedResultsPage.create(request.getEndExclusive(), rows, false));
            } else {
                byte[] last = rowNames.last();
                if (request.isReverse()) {
                    last = rowNames.first();
                }
                if (RangeRequests.isTerminalRow(request.isReverse(), last)) {
                    ret.put(request, SimpleTokenBackedResultsPage.create(last, rows, false));
                } else {
                    // If rowNames isn't a whole batch we know we don't have any more results.
                    boolean hasMore = request.getBatchHint() == null || request.getBatchHint() <= rowNames.size();
                    byte[] nextStartRow = RangeRequests.getNextStartRow(request.isReverse(), last);
                    ret.put(request, SimpleTokenBackedResultsPage.create(nextStartRow, rows, hasMore));
                }
            }
        }
        return ret;
    }

    private void validateRowNames(Iterable<byte[]> rows, byte[] startInclusive, byte[] endExclusive, boolean reverse) {
        for (byte[] row : rows) {
            if (reverse) {
                AssertUtils.assertAndLog(startInclusive.length == 0
                        || UnsignedBytes.lexicographicalComparator().compare(startInclusive, row) >= 0, "row was out of range");
                AssertUtils.assertAndLog(endExclusive.length == 0
                        || UnsignedBytes.lexicographicalComparator().compare(row, endExclusive) > 0, "row was out of range");
            } else {
                AssertUtils.assertAndLog(startInclusive.length == 0
                        || UnsignedBytes.lexicographicalComparator().compare(startInclusive, row) <= 0, "row was out of range");
                AssertUtils.assertAndLog(endExclusive.length == 0
                        || UnsignedBytes.lexicographicalComparator().compare(row, endExclusive) < 0, "row was out of range");
            }
        }
    }

    private IterableView<RowResult<Value>> filterColumnSelection(IterableView<RowResult<Value>> rows,
                                                                 final RangeRequest request) {
        return rows.transform(RowResults.<Value>createFilterColumns(new Predicate<byte[]>() {
            @Override
            public boolean apply(byte[] col) {
                return request.containsColumn(col);
            }
        })).filter(Predicates.not(RowResults.<Value>createIsEmptyPredicate()));
    }

    private static void closeSql(PalantirSqlConnection conn) {
        Connection underlyingConnection = conn.getUnderlyingConnection();
        if (underlyingConnection != null) {
            try {
                underlyingConnection.close();
            } catch (Exception e) {
                log.debug(e.getMessage(), e);
            }
        }
    }

    private String getSimpleRowSelectOneQueryPostgres(String tableName, String minMax, String extraWhere, String order) {
        return String.format(SIMPLE_ROW_SELECT_ONE_POSTGRES_TEMPLATE, tableName, tableName, tableName, extraWhere, order);
    }

    private String getSimpleRowSelectOneQueryOracle(String tableName,
                                                    String minMax,
                                                    String extraWhere,
                                                    String order) {
        return String.format(
                SIMPLE_ROW_SELECT_ONE_ORACLE_TEMPLATE,
                tableName,
                tableName,
                minMax,
                tableName,
                extraWhere);
    }

    private static final String SIMPLE_ROW_SELECT_TEMPLATE =
            " /* SQL_MET_SIMPLE_ROW_SELECT_TEMPLATE (%s) */ " +
            " SELECT /*+ INDEX(met pk_pt_met_%s) */ " +
            "   DISTINCT row_name, ? as batch_num " +
            " FROM pt_met_%s met " +
            " WHERE %s " +
            " ORDER BY row_name %s ";

    private static final String SQL_MET_TEMP_INSERT_TEMPLATE =
            "/* SQL_MET_TEMP_INSERT_TEMPLATE (%s) */" +
            " INSERT INTO pt_metropolis_row_temp (row_name, batch_num) ";

    private static final String SIMPLE_ROW_SELECT_ONE_POSTGRES_TEMPLATE =
            " /* SQL_MET_SIMPLE_ROW_SELECT_ONE_TEMPLATE_PSQL (%s) */ " +
                    " SELECT /*+ INDEX(met pk_pt_met_%s) */ " +
                    "   DISTINCT row_name, ? as batch_num " +
                    " FROM pt_met_%s met " +
                    " WHERE %s " +
                    " ORDER BY row_name %s LIMIT 1";

    private static final String SIMPLE_ROW_SELECT_ONE_ORACLE_TEMPLATE =
            " /* SQL_MET_SIMPLE_ROW_SELECT_ONE_TEMPLATE_ORA (%s) */ "
            + " SELECT /*+ INDEX(met pk_pt_met_%s) */ "
            + "   %s(row_name) as row_name, ? as batch_num " + " FROM pt_met_%s met " + " WHERE %s";
}
