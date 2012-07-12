/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
#include "storage/TableStats.h"
#include "stats/StatsSource.h"
#include "common/TupleSchema.h"
#include "common/ids.h"
#include "common/ValueFactory.hpp"
#include "common/tabletuple.h"
#include "storage/table.h"
#include "storage/tablefactory.h"
#include <vector>
#include <string>

using namespace voltdb;
using namespace std;

vector<string> TableStats::generateTableStatsColumnNames() {
    vector<string> columnNames = StatsSource::generateBaseStatsColumnNames();
    columnNames.push_back("TABLE_NAME");
    columnNames.push_back("TABLE_TYPE");
    columnNames.push_back("TUPLE_COUNT");
    columnNames.push_back("TUPLE_ALLOCATED_MEMORY");
    columnNames.push_back("TUPLE_DATA_MEMORY");
    columnNames.push_back("STRING_DATA_MEMORY");
    return columnNames;
}

void TableStats::populateTableStatsSchema(
        vector<ValueType> &types,
        vector<int32_t> &columnLengths,
        vector<bool> &allowNull) {
    StatsSource::populateBaseSchema(types, columnLengths, allowNull);
    types.push_back(VALUE_TYPE_VARCHAR); columnLengths.push_back(4096); allowNull.push_back(false);
    types.push_back(VALUE_TYPE_VARCHAR); columnLengths.push_back(4096); allowNull.push_back(false);
    types.push_back(VALUE_TYPE_BIGINT); columnLengths.push_back(NValue::getTupleStorageSize(VALUE_TYPE_BIGINT)); allowNull.push_back(false);
    types.push_back(VALUE_TYPE_INTEGER); columnLengths.push_back(NValue::getTupleStorageSize(VALUE_TYPE_INTEGER)); allowNull.push_back(false);
    types.push_back(VALUE_TYPE_INTEGER); columnLengths.push_back(NValue::getTupleStorageSize(VALUE_TYPE_INTEGER)); allowNull.push_back(false);
    types.push_back(VALUE_TYPE_INTEGER); columnLengths.push_back(NValue::getTupleStorageSize(VALUE_TYPE_INTEGER)); allowNull.push_back(false);
}

Table*
TableStats::generateEmptyTableStatsTable()
{
    string name = "Persistent Table aggregated table stats temp table";
    // An empty stats table isn't clearly associated with any specific
    // database ID.  Just pick something that works for now (Yes,
    // abstractplannode::databaseId(), I'm looking in your direction)
    CatalogId databaseId = 1;
    vector<string> columnNames = TableStats::generateTableStatsColumnNames();
    vector<ValueType> columnTypes;
    vector<int32_t> columnLengths;
    vector<bool> columnAllowNull;
    TableStats::populateTableStatsSchema(columnTypes, columnLengths,
                                         columnAllowNull);
    TupleSchema *schema =
        TupleSchema::createTupleSchema(columnTypes, columnLengths,
                                       columnAllowNull, true);

    return
        reinterpret_cast<Table*>(TableFactory::getTempTable(databaseId,
                                                            name,
                                                            schema,
                                                            &columnNames[0],
                                                            NULL));
}

/*
 * Constructor caches reference to the table that will be generating the statistics
 */
TableStats::TableStats(Table* table) : StatsSource(), m_table(table),
        m_lastActiveTupleCount(0), m_lastAllocatedTupleCount(0), m_lastDeletedTupleCount(0) {
}

/**
 * Configure a StatsSource superclass for a set of statistics. Since this class is only used in the EE it can be assumed that
 * it is part of an Execution Site and that there is a site Id.
 * @parameter name Name of this set of statistics
 * @parameter hostId id of the host this partition is on
 * @parameter hostname name of the host this partition is on
 * @parameter siteId this stat source is associated with
 * @parameter partitionId this stat source is associated with
 * @parameter databaseId Database this source is associated with
 */
void TableStats::configure(
        std::string name,
        CatalogId hostId,
        std::string hostname,
        CatalogId siteId,
        CatalogId partitionId,
        CatalogId databaseId) {
    StatsSource::configure(name, hostId, hostname, siteId, partitionId, databaseId);
    m_tableName = ValueFactory::getStringValue(m_table->name());
    m_tableType = ValueFactory::getStringValue(m_table->tableType());
}

/**
 * Generates the list of column names that will be in the statTable_. Derived classes must override this method and call
 * the parent class's version to obtain the list of columns contributed by ancestors and then append the columns they will be
 * contributing to the end of the list.
 */
std::vector<std::string> TableStats::generateStatsColumnNames() {
    std::vector<std::string> columnNames = StatsSource::generateStatsColumnNames();
    columnNames.push_back("TABLE_NAME");
    columnNames.push_back("TABLE_TYPE");
    columnNames.push_back("TABLE_ACTIVE_TUPLE_COUNT");
    columnNames.push_back("TABLE_ALLOCATED_TUPLE_COUNT");
    columnNames.push_back("TABLE_DELETED_TUPLE_COUNT");
    return columnNames;
}

/**
 * Update the stats tuple with the latest statistics available to this StatsSource.
 */
void TableStats::updateStatsTuple(TableTuple *tuple) {
    tuple->setNValue( StatsSource::m_columnName2Index["TABLE_NAME"], m_tableName);
    tuple->setNValue( StatsSource::m_columnName2Index["TABLE_TYPE"], m_tableType);
    int64_t activeTupleCount = m_table->activeTupleCount();
    int64_t allocatedTupleCount = m_table->allocatedTupleCount();
    int64_t deletedTupleCount = 0; // m_table->deletedTupleCount();

    if (interval()) {
        activeTupleCount = activeTupleCount - m_lastActiveTupleCount;
        m_lastActiveTupleCount = m_table->activeTupleCount();

        allocatedTupleCount = allocatedTupleCount - m_lastAllocatedTupleCount;
        m_lastAllocatedTupleCount = m_table->allocatedTupleCount();

        deletedTupleCount = deletedTupleCount - m_lastDeletedTupleCount;
        m_lastDeletedTupleCount = 0; // m_table->deletedTupleCount();
    }

    tuple->setNValue(
            StatsSource::m_columnName2Index["TABLE_ACTIVE_TUPLE_COUNT"],
            ValueFactory::getBigIntValue(activeTupleCount));
    tuple->setNValue( StatsSource::m_columnName2Index["TABLE_ALLOCATED_TUPLE_COUNT"],
            ValueFactory::getBigIntValue(allocatedTupleCount));
    tuple->setNValue( StatsSource::m_columnName2Index["TABLE_DELETED_TUPLE_COUNT"],
            ValueFactory::getBigIntValue(deletedTupleCount));
}

/**
 * Same pattern as generateStatsColumnNames except the return value is used as an offset into the tuple schema instead of appending to
 * end of a list.
 */
void TableStats::populateSchema(
        std::vector<ValueType> &types,
        std::vector<int32_t> &columnLengths,
        std::vector<bool> &allowNull) {
    StatsSource::populateSchema(types, columnLengths, allowNull);
    types.push_back(VALUE_TYPE_VARCHAR); columnLengths.push_back(4096); allowNull.push_back(false);
    types.push_back(VALUE_TYPE_VARCHAR); columnLengths.push_back(4096); allowNull.push_back(false);
    types.push_back(VALUE_TYPE_BIGINT); columnLengths.push_back(NValue::getTupleStorageSize(VALUE_TYPE_BIGINT)); allowNull.push_back(false);
    types.push_back(VALUE_TYPE_BIGINT); columnLengths.push_back(NValue::getTupleStorageSize(VALUE_TYPE_BIGINT)); allowNull.push_back(false);
    types.push_back(VALUE_TYPE_BIGINT); columnLengths.push_back(NValue::getTupleStorageSize(VALUE_TYPE_BIGINT)); allowNull.push_back(false);
}

TableStats::~TableStats() {
    m_tableName.free();
    m_tableType.free();
}
