/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.source;

import org.apache.paimon.utils.Reference;

import org.apache.flink.api.connector.source.SourceOutput;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.file.src.reader.BulkFormat.RecordIterator;
import org.apache.flink.connector.file.src.util.RecordAndPosition;
import org.apache.flink.table.data.RowData;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.Set;

/**
 * A {@link RecordsWithSplitIds} which contains only one iterator record. This can ensure that there
 * will be no checkpoint segmentation in iterator consumption.
 */
public class FlinkRecordsWithSplitIds implements RecordsWithSplitIds<RecordIterator<RowData>> {

    @Nullable private String splitId;

    @Nullable private Reference<RecordIterator<RowData>> recordsForSplitCurrent;

    @Nullable private final RecordIterator<RowData> recordsForSplit;

    private final Set<String> finishedSplits;

    private FlinkRecordsWithSplitIds(
            @Nullable String splitId,
            @Nullable RecordIterator<RowData> recordsForSplit,
            Set<String> finishedSplits) {
        this.splitId = splitId;
        this.recordsForSplit = recordsForSplit;
        this.finishedSplits = finishedSplits;
    }

    @Nullable
    @Override
    public String nextSplit() {
        // move the split one (from current value to null)
        final String nextSplit = this.splitId;
        this.splitId = null;

        // move the iterator, from null to value (if first move) or to null (if second move)
        this.recordsForSplitCurrent =
                nextSplit != null ? new Reference<>(this.recordsForSplit) : null;

        return nextSplit;
    }

    @Nullable
    @Override
    public RecordIterator<RowData> nextRecordFromSplit() {
        if (this.recordsForSplitCurrent == null) {
            throw new IllegalStateException();
        }

        RecordIterator<RowData> recordsForSplit = this.recordsForSplitCurrent.get();
        this.recordsForSplitCurrent.set(null);
        return recordsForSplit;
    }

    @Override
    public Set<String> finishedSplits() {
        return finishedSplits;
    }

    @Override
    public void recycle() {
        if (recordsForSplit != null) {
            recordsForSplit.releaseBatch();
        }
    }

    public static FlinkRecordsWithSplitIds forRecords(
            String splitId, RecordIterator<RowData> recordsForSplit) {
        return new FlinkRecordsWithSplitIds(splitId, recordsForSplit, Collections.emptySet());
    }

    public static FlinkRecordsWithSplitIds finishedSplit(String splitId) {
        return new FlinkRecordsWithSplitIds(null, null, Collections.singleton(splitId));
    }

    public static void emitRecord(
            RecordIterator<RowData> element,
            SourceOutput<RowData> output,
            FileStoreSourceSplitState state) {
        RecordAndPosition<RowData> record;
        while ((record = element.next()) != null) {
            output.collect(record.getRecord());
            state.setPosition(record);
        }
    }
}
