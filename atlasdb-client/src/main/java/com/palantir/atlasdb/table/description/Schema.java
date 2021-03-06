/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.table.description;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang.Validate;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.palantir.atlasdb.cleaner.api.OnCleanupTask;
import com.palantir.atlasdb.keyvalue.api.Namespace;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.schema.stream.StreamStoreDefinition;
import com.palantir.atlasdb.table.description.IndexDefinition.IndexType;
import com.palantir.atlasdb.table.description.render.StreamStoreRenderer;
import com.palantir.atlasdb.table.description.render.TableFactoryRenderer;
import com.palantir.atlasdb.table.description.render.TableRenderer;
import com.palantir.atlasdb.transaction.api.ConflictHandler;

/**
 * Defines a schema.
 *
 * A schema consists of table definitions and indexes.
 *
 * Schema objects can be used for creating/dropping tables within key values
 * stores, as well as compiling automatically generated code for accessing
 * tables in a type-safe fashion.
 */
public class Schema {
    private final String name;
    private final String packageName;
    private final Namespace namespace;

    private final Multimap<String, Supplier<OnCleanupTask>> cleanupTasks = ArrayListMultimap.create();
    private final Map<String, TableDefinition> tempTableDefinitions = Maps.newHashMap();
    private final Map<String, TableDefinition> tableDefinitions = Maps.newHashMap();
    private final Map<String, IndexDefinition> indexDefinitions = Maps.newHashMap();
    private final List<StreamStoreRenderer> streamStoreRenderers = Lists.newArrayList();

    // N.B., the following is a list multimap because we want to preserve order
    // for code generation purposes.
    private final ListMultimap<String, String> indexesByTable = ArrayListMultimap.create();

    public Schema(Namespace namespace) {
        this(null, null, namespace);
    }

    public Schema() {
        this(null, null, Namespace.DEFAULT_NAMESPACE);
    }

    public Schema(String name, String packageName, Namespace namespace) {
        this.name = name;
        this.packageName = packageName;
        this.namespace = namespace;
    }

    public void addTableDefinition(String tableName, TableDefinition definition) {
        Preconditions.checkArgument(
                !tableDefinitions.containsKey(tableName) && !indexDefinitions.containsKey(tableName),
                "Table already defined: %s", tableName);
        Preconditions.checkArgument(
                Schemas.isTableNameValid(tableName),
                "Invalid table name " + tableName);
        tableDefinitions.put(tableName, definition);
    }

    public void addDefinitionsForTables(Iterable<String> tableNames, TableDefinition definition) {
        for (String t : tableNames) {
            addTableDefinition(t, definition);
        }
    }

    public TableDefinition getTableDefinition(TableReference tableRef) {
        return tableDefinitions.get(tableRef.getTablename());
    }

    public Map<TableReference, TableMetadata> getAllTablesAndIndexMetadata() {
        Map<TableReference, TableMetadata> ret = Maps.newHashMap();
        for (Map.Entry<String, TableDefinition> e : tableDefinitions.entrySet()) {
            ret.put(TableReference.create(namespace, e.getKey()), e.getValue().toTableMetadata());
        }
        for (Map.Entry<String, IndexDefinition> e : indexDefinitions.entrySet()) {
            ret.put(TableReference.create(namespace, e.getKey()), e.getValue().toIndexMetadata(e.getKey()).getTableMetadata());
        }
        return ret;
    }

    public Set<TableReference> getAllIndexes() {
        return indexDefinitions.keySet().stream().map((table) -> TableReference.create(namespace, table)).collect(Collectors.toSet());
    }

    public Set<TableReference> getAllTables() {
        return tableDefinitions.keySet().stream().map((table) -> TableReference.create(namespace, table)).collect(Collectors.toSet());
    }

    public void addIndexDefinition(String idxName, IndexDefinition definition) {
        validateIndex(idxName, definition);
        String indexName = Schemas.appendIndexSuffix(idxName, definition).getQualifiedName();
        indexesByTable.put(definition.getSourceTable(), indexName);
        indexDefinitions.put(indexName, definition);
    }

    /**
     * Adds the given stream store to your schema.
     *
     * @param streamStoreDefinition You probably want to use a @{StreamStoreDefinitionBuilder} for convenience.
     */
    public void addStreamStoreDefinition(StreamStoreDefinition streamStoreDefinition) {
        streamStoreDefinition.getTables().forEach((tableName, definition) -> addTableDefinition(tableName, definition));
        StreamStoreRenderer renderer = streamStoreDefinition.getRenderer(packageName, name);
        Multimap<String, Supplier<OnCleanupTask>> streamStoreCleanupTasks = streamStoreDefinition.getCleanupTasks(packageName, name, renderer);

        cleanupTasks.putAll(streamStoreCleanupTasks);
        streamStoreRenderers.add(renderer);
    }

    private void validateIndex(String idxName, IndexDefinition definition) {
        for (IndexType type : IndexType.values()) {
            Preconditions.checkArgument(
                    !idxName.endsWith(type.getIndexSuffix()),
                    "Index name cannot end with '" + type.getIndexSuffix() + "'.");
            String indexName = idxName + type.getIndexSuffix();
            Preconditions.checkArgument(
                    !tableDefinitions.containsKey(indexName) && !indexDefinitions.containsKey(indexName),
                    "Table already defined.");
        }
        Preconditions.checkArgument(
                tableDefinitions.containsKey(definition.getSourceTable()),
                "Index source table undefined.");
        Preconditions.checkArgument(
                Schemas.isTableNameValid(idxName),
                "Invalid table name " + idxName);
        Preconditions.checkArgument(!tableDefinitions.get(definition.getSourceTable()).toTableMetadata().getColumns().hasDynamicColumns() || !definition.getIndexType().equals(IndexType.CELL_REFERENCING),
                "Cell referencing indexes not implemented for tables with dynamic columns.");
    }

    public IndexDefinition getIndex(TableReference indexRef) {
        return indexDefinitions.get(indexRef.getTablename());
    }

    /**
     * Performs some basic checks on this schema to check its validity.
     */
    public void validate() {
        // Try converting to metadata to see if any validation logic throws.
        for (TableDefinition d : tableDefinitions.values()) {
            d.toTableMetadata();
            d.getConstraintMetadata();
        }

        for (Entry<String, IndexDefinition> indexEntry : indexDefinitions.entrySet()) {
            IndexDefinition d = indexEntry.getValue();
            d.toIndexMetadata(indexEntry.getKey()).getTableMetadata();
        }

        for (Entry<String, String> e : indexesByTable.entries()) {
            TableMetadata tableMetadata = tableDefinitions.get(e.getKey()).toTableMetadata();

            Collection<String> rowNames = Collections2.transform(tableMetadata.getRowMetadata().getRowParts(), new Function<NameComponentDescription, String>() {
                @Override
                public String apply(NameComponentDescription input) {
                    return input.getComponentName();
                }
            });

            IndexMetadata indexMetadata = indexDefinitions.get(e.getValue()).toIndexMetadata(e.getValue());
            for (IndexComponent c : Iterables.concat(indexMetadata.getRowComponents(), indexMetadata.getColumnComponents())) {
                if (c.rowComponentName != null) {
                    Validate.isTrue(rowNames.contains(c.rowComponentName));
                }
            }

            if(indexMetadata.getColumnNameToAccessData() != null) {
                Validate.isTrue(tableMetadata.getColumns().getDynamicColumn() == null, "Indexes accessing columns not supported for tables with dynamic columns.");
                Collection<String> columnNames = Collections2.transform(tableMetadata.getColumns().getNamedColumns(), new Function<NamedColumnDescription, String>() {
                    @Override
                    public String apply(NamedColumnDescription input) {
                        return input.getLongName();
                    }
                });
                Validate.isTrue(columnNames.contains(indexMetadata.getColumnNameToAccessData()));
            }

            if (indexMetadata.getIndexType().equals(IndexType.CELL_REFERENCING)) {
                Validate.isTrue(ConflictHandler.RETRY_ON_WRITE_WRITE.equals(tableMetadata.conflictHandler), "Nonadditive indexes require write-write conflicts on their tables");
            }
        }
    }

    public Map<TableReference, TableDefinition> getTableDefinitions() {
        return tableDefinitions.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> TableReference.create(namespace, e.getKey()),
                        e -> e.getValue()
                ));
    }

    public Map<TableReference, IndexDefinition> getIndexDefinitions() {
        return indexDefinitions.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> TableReference.create(namespace, e.getKey()),
                        e -> e.getValue()
                ));
    }

    public Namespace getNamespace() {
        return namespace;
    }

    /**
     * Performs code generation.
     *
     * @param srcDir root source directory where code generation is performed.
     */
    public void renderTables(File srcDir) throws IOException {
        Preconditions.checkNotNull(name, "schema name not set");
        Preconditions.checkNotNull(packageName, "package name not set");

        TableRenderer tableRenderer = new TableRenderer(packageName, namespace);
        for (Entry<String, TableDefinition> entry : tableDefinitions.entrySet()) {
            String rawTableName = entry.getKey();
            TableDefinition table = entry.getValue();
            ImmutableSortedSet.Builder<IndexMetadata> indices = ImmutableSortedSet.orderedBy(Ordering.natural().onResultOf(
                    new Function<IndexMetadata, String>() {
                @Override
                public String apply(IndexMetadata index) {
                    return index.getIndexName();
                }
            }));
            if (table.getGenericTableName() != null) {
                Preconditions.checkState(!indexesByTable.containsKey(rawTableName), "Generic tables cannot have indices");
            } else {
                for (String indexName : indexesByTable.get(rawTableName)) {
                    indices.add(indexDefinitions.get(indexName).toIndexMetadata(indexName));
                }
            }
            emit(srcDir,
                 tableRenderer.render(rawTableName, table, indices.build()),
                 packageName,
                 tableRenderer.getClassName(rawTableName, table));
        }
        for (Entry<String, TableDefinition> entry : tempTableDefinitions.entrySet()) {
            String rawTableName = entry.getKey();
            TableDefinition table = entry.getValue();
            emit(srcDir,
                 tableRenderer.render(rawTableName, table, ImmutableSortedSet.<IndexMetadata>of()),
                 packageName,
                 tableRenderer.getClassName(rawTableName, table));
        }

        for (StreamStoreRenderer renderer : streamStoreRenderers) {
            emit(srcDir,
                 renderer.renderStreamStore(),
                 renderer.getPackageName(),
                 renderer.getStreamStoreClassName());
            emit(srcDir,
                 renderer.renderIndexCleanupTask(),
                 renderer.getPackageName(),
                 renderer.getIndexCleanupTaskClassName());
            emit(srcDir,
                 renderer.renderMetadataCleanupTask(),
                 renderer.getPackageName(),
                 renderer.getMetadataCleanupTaskClassName());
        }
        TableFactoryRenderer tableFactoryRenderer =
                new TableFactoryRenderer(
                        name,
                        packageName,
                        namespace,
                        tableDefinitions);
        emit(srcDir,
             tableFactoryRenderer.render(),
             tableFactoryRenderer.getPackageName(),
             tableFactoryRenderer.getClassName());
    }

    private void emit(File srcDir, String code, String packName, String className)
            throws IOException {
        File outputDir = new File(srcDir, packName.replace(".", "/"));
        File outputFile = new File(outputDir, className + ".java");

        // create paths if they don't exist
        outputDir.mkdirs();
        outputFile = outputFile.getAbsoluteFile();
        outputFile.createNewFile();
        FileWriter os = null;
        try {
            os = new FileWriter(outputFile);
            os.write(code);
        } finally {
            if (os != null) {
                os.close();
            }
        }
    }

    public void addCleanupTask(String tableName, OnCleanupTask task) {
        String fullTableName = Schemas.getFullTableName(tableName, namespace);
        cleanupTasks.put(fullTableName, Suppliers.ofInstance(task));
    }

    public void addCleanupTask(String rawTableName, Supplier<OnCleanupTask> task) {
        cleanupTasks.put(rawTableName, task);
    }

    public Multimap<TableReference, OnCleanupTask> getCleanupTasksByTable() {
        Multimap<TableReference, OnCleanupTask> ret = ArrayListMultimap.create();
        for (Map.Entry<String, Supplier<OnCleanupTask>> e : cleanupTasks.entries()) {
            ret.put(TableReference.create(namespace, e.getKey()), e.getValue().get());
        }
        return ret;
    }
}
