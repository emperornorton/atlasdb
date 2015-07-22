// Copyright 2015 Palantir Technologies
//
// Licensed under the BSD-3 License (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.palantir.atlasdb.keyvalue.cassandra;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.BaseEncoding;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.ColumnSelection;
import com.palantir.atlasdb.keyvalue.api.RangeRequests;
import com.palantir.atlasdb.keyvalue.api.RowResult;
import com.palantir.atlasdb.keyvalue.impl.Cells;
import com.palantir.atlasdb.keyvalue.impl.RowResults;
import com.palantir.util.Pair;
import com.palantir.util.paging.SimpleTokenBackedResultsPage;
import com.palantir.util.paging.TokenBackedBasicResultsPage;

abstract class ResultsExtractor<T, U> {
    private static final Logger log = LoggerFactory.getLogger(ResultsExtractor.class);
    protected final T collector;

    public ResultsExtractor(T collector) {
        this.collector = collector;
    }

    public final byte[] extractResults(Map<ByteBuffer, List<ColumnOrSuperColumn>> colsByKey,
                                       long startTs,
                                       ColumnSelection selection) {
        byte[] maxRow = null;
        for (ByteBuffer rowName : colsByKey.keySet()) {
            byte[] row = CassandraKeyValueServices.getBytesFromByteBuffer(rowName);
            if (maxRow == null) {
                maxRow = row;
            } else {
                maxRow = PtBytes.BYTES_COMPARATOR.max(maxRow, row);
            }
            if (CassandraKeyValueService.THOROUGH_LOGGING.get() != null) {
                log.error("{} extractResults for row {} with {} columns",
                        Thread.currentThread().getId(), BaseEncoding.base16().lowerCase().encode(row), colsByKey.get(rowName).size());
            }
            for (ColumnOrSuperColumn c : colsByKey.get(rowName)) {
                Pair<byte[], Long> pair = CassandraKeyValueServices.decompose(c.column.getName());
                internalExtractResult(startTs, selection, row, pair.lhSide, c.column.getValue(), pair.rhSide);
                if (CassandraKeyValueService.THOROUGH_LOGGING.get() != null) {
                    log.error("{} extractResultsInternal, startTs = {}, rowTs = {}",
                            Thread.currentThread().getId(), startTs, pair.rhSide);
                }
            }
        }
        return maxRow;
    }

    public TokenBackedBasicResultsPage<RowResult<U>, byte[]> getPageFromRangeResults(
            Map<ByteBuffer, List<ColumnOrSuperColumn>> colsByKey,
            long startTs,
            ColumnSelection selection,
            byte[] endExclusive) {
        byte[] lastRow = extractResults(colsByKey, startTs, selection);
        SortedMap<byte[], SortedMap<byte[], U>> resultsByRow = Cells.breakCellsUpByRow(asMap());
        return getRowResults(endExclusive, lastRow, resultsByRow);
    }

    public static <T> TokenBackedBasicResultsPage<RowResult<T>, byte[]> getRowResults(final byte[] endExclusive,
            byte[] lastRow, SortedMap<byte[], SortedMap<byte[], T>> resultsByRow) {
        SortedMap<byte[], RowResult<T>> ret = RowResults.viewOfSortedMap(resultsByRow);
        if (lastRow == null || RangeRequests.isLastRowName(lastRow)) {
            return new SimpleTokenBackedResultsPage<RowResult<T>, byte[]>(endExclusive, ret.values(), false);
        }
        byte[] nextStart = RangeRequests.nextLexicographicName(lastRow);
        if (Arrays.equals(nextStart, endExclusive)) {
            return new SimpleTokenBackedResultsPage<RowResult<T>, byte[]>(endExclusive, ret.values(), false);
        }
        return new SimpleTokenBackedResultsPage<RowResult<T>, byte[]>(nextStart, ret.values(), true);
    }

    public abstract void internalExtractResult(long startTs,
                                               ColumnSelection selection,
                                               byte[] row,
                                               byte[] col,
                                               byte[] val,
                                               long ts);

    public abstract Map<Cell, U> asMap();
}
