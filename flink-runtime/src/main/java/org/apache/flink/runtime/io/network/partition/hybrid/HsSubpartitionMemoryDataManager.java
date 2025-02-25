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

package org.apache.flink.runtime.io.network.partition.hybrid;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.core.memory.MemorySegmentFactory;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.Buffer.DataType;
import org.apache.flink.runtime.io.network.buffer.BufferBuilder;
import org.apache.flink.runtime.io.network.buffer.BufferConsumer;
import org.apache.flink.runtime.io.network.buffer.FreeingBufferRecycler;
import org.apache.flink.runtime.io.network.buffer.NetworkBuffer;
import org.apache.flink.runtime.io.network.partition.hybrid.HsSpillingInfoProvider.ConsumeStatus;
import org.apache.flink.runtime.io.network.partition.hybrid.HsSpillingInfoProvider.SpillStatus;
import org.apache.flink.util.function.SupplierWithException;
import org.apache.flink.util.function.ThrowingRunnable;

import javax.annotation.concurrent.GuardedBy;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * This class is responsible for managing the data in a single subpartition. One {@link
 * HsMemoryDataManager} will hold multiple {@link HsSubpartitionMemoryDataManager}.
 */
public class HsSubpartitionMemoryDataManager {
    private final int targetChannel;

    private final int bufferSize;

    private final HsMemoryDataManagerOperation memoryDataManagerOperation;

    // Not guarded by lock because it is expected only accessed from task's main thread.
    private final Queue<BufferBuilder> unfinishedBuffers = new LinkedList<>();

    // Not guarded by lock because it is expected only accessed from task's main thread.
    private int finishedBufferIndex;

    @GuardedBy("subpartitionLock")
    private final Deque<HsBufferContext> allBuffers = new LinkedList<>();

    @GuardedBy("subpartitionLock")
    private final Deque<HsBufferContext> unConsumedBuffers = new LinkedList<>();

    @GuardedBy("subpartitionLock")
    private final Map<Integer, HsBufferContext> bufferIndexToContexts = new HashMap<>();

    /** DO NOT USE DIRECTLY. Use {@link #runWithLock} or {@link #callWithLock} instead. */
    private final Lock resultPartitionLock;

    /** DO NOT USE DIRECTLY. Use {@link #runWithLock} or {@link #callWithLock} instead. */
    private final Object subpartitionLock = new Object();

    HsSubpartitionMemoryDataManager(
            int targetChannel,
            int bufferSize,
            Lock resultPartitionLock,
            HsMemoryDataManagerOperation memoryDataManagerOperation) {
        this.targetChannel = targetChannel;
        this.bufferSize = bufferSize;
        this.resultPartitionLock = resultPartitionLock;
        this.memoryDataManagerOperation = memoryDataManagerOperation;
    }

    // ------------------------------------------------------------------------
    //  Called by Consumer
    // ------------------------------------------------------------------------

    /**
     * Check whether the head of {@link #unConsumedBuffers} is the buffer to be consumed next time.
     * If so, return the next buffer's data type.
     *
     * @param nextToConsumeIndex index of the buffer to be consumed next time.
     * @return If the head of {@link #unConsumedBuffers} is target, return the buffer's data type.
     *     Otherwise, return {@link DataType#NONE}.
     */
    @SuppressWarnings("FieldAccessNotGuarded")
    // Note that: callWithLock ensure that code block guarded by resultPartitionReadLock and
    // subpartitionLock.
    public DataType peekNextToConsumeDataType(int nextToConsumeIndex) {
        return callWithLock(() -> peekNextToConsumeDataTypeInternal(nextToConsumeIndex));
    }

    /**
     * Check whether the head of {@link #unConsumedBuffers} is the buffer to be consumed. If so,
     * return the buffer and next data type.
     *
     * @param toConsumeIndex index of buffer to be consumed.
     * @return If the head of {@link #unConsumedBuffers} is target, return optional of the buffer
     *     and next data type. Otherwise, return {@link Optional#empty()}.
     */
    @SuppressWarnings("FieldAccessNotGuarded")
    // Note that: callWithLock ensure that code block guarded by resultPartitionReadLock and
    // subpartitionLock.
    public Optional<HsMemoryDataManager.BufferAndNextDataType> consumeBuffer(int toConsumeIndex) {
        Optional<Tuple2<HsBufferContext, DataType>> bufferAndNextDataType =
                callWithLock(
                        () -> {
                            if (!checkFirstUnConsumedBufferIndex(toConsumeIndex)) {
                                return Optional.empty();
                            }

                            HsBufferContext bufferContext =
                                    checkNotNull(unConsumedBuffers.pollFirst());
                            bufferContext.consumed();
                            DataType nextDataType =
                                    peekNextToConsumeDataTypeInternal(toConsumeIndex + 1);
                            return Optional.of(Tuple2.of(bufferContext, nextDataType));
                        });

        bufferAndNextDataType.ifPresent(
                tuple ->
                        memoryDataManagerOperation.onBufferConsumed(
                                tuple.f0.getBufferIndexAndChannel()));
        return bufferAndNextDataType.map(
                tuple ->
                        new HsMemoryDataManager.BufferAndNextDataType(
                                tuple.f0.getBuffer(), tuple.f1));
    }

    // ------------------------------------------------------------------------
    //  Called by MemoryDataManager
    // ------------------------------------------------------------------------

    /**
     * Append record to {@link HsSubpartitionMemoryDataManager}.
     *
     * @param record to be managed by this class.
     * @param dataType the type of this record. In other words, is it data or event.
     */
    public void append(ByteBuffer record, DataType dataType) throws InterruptedException {
        if (dataType.isEvent()) {
            writeEvent(record, dataType);
        } else {
            writeRecord(record, dataType);
        }
    }

    /**
     * Get buffers in {@link #allBuffers} that satisfy expected {@link SpillStatus} and {@link
     * ConsumeStatus}.
     *
     * @param spillStatus the status of spilling expected.
     * @param consumeStatus the status of consuming expected.
     * @return buffers satisfy expected status in order.
     */
    @SuppressWarnings("FieldAccessNotGuarded")
    // Note that: callWithLock ensure that code block guarded by resultPartitionReadLock and
    // subpartitionLock.
    public Deque<BufferIndexAndChannel> getBuffersSatisfyStatus(
            SpillStatus spillStatus, ConsumeStatus consumeStatus) {
        return callWithLock(
                () -> {
                    // TODO return iterator to avoid completely traversing the queue for each call.
                    Deque<BufferIndexAndChannel> targetBuffers = new ArrayDeque<>();
                    // traverse buffers in order.
                    allBuffers.forEach(
                            (bufferContext -> {
                                if (isBufferSatisfyStatus(
                                        bufferContext, spillStatus, consumeStatus)) {
                                    targetBuffers.add(bufferContext.getBufferIndexAndChannel());
                                }
                            }));
                    return targetBuffers;
                });
    }

    /**
     * Spill this subpartition's buffers in a decision.
     *
     * @param toSpill All buffers that need to be spilled belong to this subpartition in a decision.
     * @param spillDoneFuture completed when spill is finished.
     * @return {@link BufferWithIdentity}s about these spill buffers.
     */
    @SuppressWarnings("FieldAccessNotGuarded")
    // Note that: callWithLock ensure that code block guarded by resultPartitionReadLock and
    // subpartitionLock.
    public List<BufferWithIdentity> spillSubpartitionBuffers(
            List<BufferIndexAndChannel> toSpill, CompletableFuture<Void> spillDoneFuture) {
        return callWithLock(
                () ->
                        toSpill.stream()
                                .map(
                                        indexAndChannel -> {
                                            int bufferIndex = indexAndChannel.getBufferIndex();
                                            HsBufferContext bufferContext =
                                                    startSpillingBuffer(
                                                            bufferIndex, spillDoneFuture);
                                            return new BufferWithIdentity(
                                                    bufferContext.getBuffer(),
                                                    bufferIndex,
                                                    targetChannel);
                                        })
                                .collect(Collectors.toList()));
    }

    /**
     * Release this subpartition's buffers in a decision.
     *
     * @param toRelease All buffers that need to be released belong to this subpartition in a
     *     decision.
     */
    @SuppressWarnings("FieldAccessNotGuarded")
    // Note that: runWithLock ensure that code block guarded by resultPartitionReadLock and
    // subpartitionLock.
    public void releaseSubpartitionBuffers(List<BufferIndexAndChannel> toRelease) {
        runWithLock(
                () ->
                        toRelease.forEach(
                                (indexAndChannel) -> {
                                    int bufferIndex = indexAndChannel.getBufferIndex();
                                    HsBufferContext bufferContext =
                                            checkNotNull(bufferIndexToContexts.get(bufferIndex));
                                    checkAndMarkBufferReadable(bufferContext);
                                    releaseBuffer(bufferIndex);
                                }));
    }

    // ------------------------------------------------------------------------
    //  Internal Methods
    // ------------------------------------------------------------------------

    private void writeEvent(ByteBuffer event, DataType dataType) {
        checkArgument(dataType.isEvent());

        // each Event must take an exclusive buffer
        finishCurrentWritingBufferIfNotEmpty();

        // store Events in adhoc heap segments, for network memory efficiency
        MemorySegment data = MemorySegmentFactory.wrap(event.array());
        Buffer buffer =
                new NetworkBuffer(data, FreeingBufferRecycler.INSTANCE, dataType, data.size());

        HsBufferContext bufferContext =
                new HsBufferContext(buffer, finishedBufferIndex, targetChannel);
        addFinishedBuffer(bufferContext);
        memoryDataManagerOperation.onBufferFinished();
    }

    private void writeRecord(ByteBuffer record, DataType dataType) throws InterruptedException {
        checkArgument(!dataType.isEvent());

        ensureCapacityForRecord(record);

        writeRecord(record);
    }

    private void ensureCapacityForRecord(ByteBuffer record) throws InterruptedException {
        final int numRecordBytes = record.remaining();
        int availableBytes =
                Optional.ofNullable(unfinishedBuffers.peek())
                        .map(
                                currentWritingBuffer ->
                                        currentWritingBuffer.getWritableBytes()
                                                + bufferSize * (unfinishedBuffers.size() - 1))
                        .orElse(0);

        while (availableBytes < numRecordBytes) {
            // request unfinished buffer.
            BufferBuilder bufferBuilder = memoryDataManagerOperation.requestBufferFromPool();
            unfinishedBuffers.add(bufferBuilder);
            availableBytes += bufferSize;
        }
    }

    private void writeRecord(ByteBuffer record) {
        while (record.hasRemaining()) {
            BufferBuilder currentWritingBuffer =
                    checkNotNull(
                            unfinishedBuffers.peek(), "Expect enough capacity for the record.");
            currentWritingBuffer.append(record);

            if (currentWritingBuffer.isFull()) {
                finishCurrentWritingBuffer();
            }
        }
    }

    private void finishCurrentWritingBufferIfNotEmpty() {
        BufferBuilder currentWritingBuffer = unfinishedBuffers.peek();
        if (currentWritingBuffer == null || currentWritingBuffer.getWritableBytes() == bufferSize) {
            return;
        }

        finishCurrentWritingBuffer();
    }

    private void finishCurrentWritingBuffer() {
        BufferBuilder currentWritingBuffer = unfinishedBuffers.poll();

        if (currentWritingBuffer == null) {
            return;
        }

        currentWritingBuffer.finish();
        BufferConsumer bufferConsumer = currentWritingBuffer.createBufferConsumerFromBeginning();
        Buffer buffer = bufferConsumer.build();
        currentWritingBuffer.close();
        bufferConsumer.close();

        HsBufferContext bufferContext =
                new HsBufferContext(buffer, finishedBufferIndex, targetChannel);
        addFinishedBuffer(bufferContext);
        memoryDataManagerOperation.onBufferFinished();
    }

    @SuppressWarnings("FieldAccessNotGuarded")
    // Note that: callWithLock ensure that code block guarded by resultPartitionReadLock and
    // subpartitionLock.
    private void addFinishedBuffer(HsBufferContext bufferContext) {
        finishedBufferIndex++;
        boolean needNotify =
                callWithLock(
                        () -> {
                            allBuffers.add(bufferContext);
                            unConsumedBuffers.add(bufferContext);
                            bufferIndexToContexts.put(
                                    bufferContext.getBufferIndexAndChannel().getBufferIndex(),
                                    bufferContext);
                            trimHeadingReleasedBuffers(unConsumedBuffers);
                            return unConsumedBuffers.isEmpty();
                        });
        if (needNotify) {
            // TODO notify data available, the notification mechanism may need further
            // consideration.
        }
    }

    @GuardedBy("subpartitionLock")
    private DataType peekNextToConsumeDataTypeInternal(int nextToConsumeIndex) {
        return checkFirstUnConsumedBufferIndex(nextToConsumeIndex)
                ? checkNotNull(unConsumedBuffers.peekFirst()).getBuffer().getDataType()
                : DataType.NONE;
    }

    @GuardedBy("subpartitionLock")
    private boolean checkFirstUnConsumedBufferIndex(int expectedBufferIndex) {
        trimHeadingReleasedBuffers(unConsumedBuffers);
        return !unConsumedBuffers.isEmpty()
                && unConsumedBuffers.peekFirst().getBufferIndexAndChannel().getBufferIndex()
                        == expectedBufferIndex;
    }

    /**
     * Remove all released buffer from head of queue until buffer queue is empty or meet un-released
     * buffer.
     */
    @GuardedBy("subpartitionLock")
    private void trimHeadingReleasedBuffers(Deque<HsBufferContext> bufferQueue) {
        while (!bufferQueue.isEmpty() && bufferQueue.peekFirst().isReleased()) {
            bufferQueue.removeFirst();
        }
    }

    @GuardedBy("subpartitionLock")
    private void releaseBuffer(int bufferIndex) {
        HsBufferContext bufferContext = checkNotNull(bufferIndexToContexts.remove(bufferIndex));
        bufferContext.release();
        // remove released buffers from head lazy.
        trimHeadingReleasedBuffers(allBuffers);
    }

    @GuardedBy("subpartitionLock")
    private HsBufferContext startSpillingBuffer(
            int bufferIndex, CompletableFuture<Void> spillFuture) {
        HsBufferContext bufferContext = checkNotNull(bufferIndexToContexts.get(bufferIndex));
        bufferContext.startSpilling(spillFuture);
        return bufferContext;
    }

    @GuardedBy("subpartitionLock")
    private void checkAndMarkBufferReadable(HsBufferContext bufferContext) {
        // only spill and not consumed buffer needs to be marked as readable.
        if (isBufferSatisfyStatus(bufferContext, SpillStatus.SPILL, ConsumeStatus.NOT_CONSUMED)) {
            bufferContext
                    .getSpilledFuture()
                    .orElseThrow(
                            () ->
                                    new IllegalStateException(
                                            "Buffer in spill status should already set spilled future."))
                    .thenRun(
                            () -> {
                                BufferIndexAndChannel bufferIndexAndChannel =
                                        bufferContext.getBufferIndexAndChannel();
                                memoryDataManagerOperation.markBufferReadableFromFile(
                                        bufferIndexAndChannel.getChannel(),
                                        bufferIndexAndChannel.getBufferIndex());
                            });
        }
    }

    @GuardedBy("subpartitionLock")
    private boolean isBufferSatisfyStatus(
            HsBufferContext bufferContext, SpillStatus spillStatus, ConsumeStatus consumeStatus) {
        // released buffer is not needed.
        if (bufferContext.isReleased()) {
            return false;
        }
        boolean match = true;
        switch (spillStatus) {
            case NOT_SPILL:
                match = !bufferContext.isSpillStarted();
                break;
            case SPILL:
                match = bufferContext.isSpillStarted();
                break;
        }
        switch (consumeStatus) {
            case NOT_CONSUMED:
                match &= !bufferContext.isConsumed();
                break;
            case CONSUMED:
                match &= bufferContext.isConsumed();
                break;
        }
        return match;
    }

    private <E extends Exception> void runWithLock(ThrowingRunnable<E> runnable) throws E {
        try {
            resultPartitionLock.lock();
            synchronized (subpartitionLock) {
                runnable.run();
            }
        } finally {
            resultPartitionLock.unlock();
        }
    }

    private <R, E extends Exception> R callWithLock(SupplierWithException<R, E> callable) throws E {
        try {
            resultPartitionLock.lock();
            synchronized (subpartitionLock) {
                return callable.get();
            }
        } finally {
            resultPartitionLock.unlock();
        }
    }
}
