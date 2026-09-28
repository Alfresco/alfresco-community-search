/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Module
 * %%
 * Copyright (C) 2026 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.elasticsearch.batchindexing.cascade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CascadeSourceStashTest
{
    @Test
    @DisplayName("recordRename accumulates unique node references")
    void recordRenameAccumulatesUniqueRefs()
    {
        CascadeSourceStash stash = new CascadeSourceStash();

        stash.recordRename(CascadeTestHelper.skeleton("ref-A", "new-A"));
        stash.recordRename(CascadeTestHelper.skeleton("ref-B", "new-B"));

        assertThat(stash.renameCount()).isEqualTo(2);
        assertThat(stash.categoryDeleteCount()).isZero();
    }

    @Test
    @DisplayName("recordRename first-wins: duplicate node ref does not overwrite the recorded name")
    void recordRenameFirstWinsForDuplicateRef()
    {
        CascadeSourceStash stash = new CascadeSourceStash();

        stash.recordRename(CascadeTestHelper.skeleton("ref-A", "first"));
        stash.recordRename(CascadeTestHelper.skeleton("ref-A", "second"));

        CascadeSourceBatch drained = stash.drain();
        assertThat(drained.renames()).hasSize(1);
        assertThat(drained.renames().get(0).newUnprefixedSegment()).isEqualTo("first");
    }

    @Test
    @DisplayName("recordCategoryDelete deduplicates by node reference")
    void recordCategoryDeleteDeduplicates()
    {
        CascadeSourceStash stash = new CascadeSourceStash();

        stash.recordCategoryDelete(CascadeTestHelper.categoryDelete("ref-X", "X"));
        stash.recordCategoryDelete(CascadeTestHelper.categoryDelete("ref-X", "X"));
        stash.recordCategoryDelete(CascadeTestHelper.categoryDelete("ref-Y", "Y"));

        assertThat(stash.categoryDeleteCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("drain returns snapshot and empties the stash")
    void drainReturnsSnapshotAndEmpties()
    {
        CascadeSourceStash stash = new CascadeSourceStash();
        stash.recordRename(CascadeTestHelper.skeleton("ref-A", "new-A"));
        stash.recordCategoryDelete(CascadeTestHelper.categoryDelete("ref-X", "X"));

        CascadeSourceBatch first = stash.drain();

        assertThat(first.renames()).hasSize(1);
        assertThat(first.categoryDeletes()).hasSize(1);
        assertThat(first.isEmpty()).isFalse();
        assertThat(first.totalSize()).isEqualTo(2);

        CascadeSourceBatch second = stash.drain();
        assertThat(second.isEmpty()).isTrue();
        assertThat(stash.renameCount()).isZero();
        assertThat(stash.categoryDeleteCount()).isZero();
    }

    @Test
    @DisplayName("clear discards observations without producing a snapshot")
    void clearDiscardsObservations()
    {
        CascadeSourceStash stash = new CascadeSourceStash();
        stash.recordRename(CascadeTestHelper.skeleton("ref-A", "new-A"));
        stash.recordCategoryDelete(CascadeTestHelper.categoryDelete("ref-X", "X"));

        stash.clear();

        assertThat(stash.renameCount()).isZero();
        assertThat(stash.categoryDeleteCount()).isZero();
    }

    @Test
    @DisplayName("concurrent writers all contribute; drain observes every unique node ref")
    void concurrentWritersAllContribute() throws InterruptedException
    {
        CascadeSourceStash stash = new CascadeSourceStash();
        int threads = 8;
        int recordsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger threadIndex = new AtomicInteger();

        for (int t = 0; t < threads; t++)
        {
            pool.submit(() -> {
                int id = threadIndex.getAndIncrement();
                try
                {
                    startLatch.await();
                    for (int i = 0; i < recordsPerThread; i++)
                    {
                        stash.recordRename(CascadeTestHelper.skeleton("ref-" + id + "-" + i, "name-" + i));
                        stash.recordCategoryDelete(CascadeTestHelper.categoryDelete("del-" + id + "-" + i, "u-" + id + "-" + i));
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                finally
                {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        int expected = threads * recordsPerThread;
        assertThat(stash.renameCount()).isEqualTo(expected);
        assertThat(stash.categoryDeleteCount()).isEqualTo(expected);
    }
}
