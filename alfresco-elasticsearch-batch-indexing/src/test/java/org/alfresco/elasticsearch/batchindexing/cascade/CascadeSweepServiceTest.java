/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CascadeSweepServiceTest
{
    @Test
    @DisplayName("onJobStart empties the stash so no observations leak between jobs")
    void onJobStartEmptiesStash()
    {
        CascadeSourceStash stash = new CascadeSourceStash();
        stash.recordRename(CascadeTestHelper.skeleton("ref-A", "new-A"));
        stash.recordCategoryDelete(CascadeTestHelper.categoryDelete("ref-X", "X"));
        RenameCascadeService rename = mock(RenameCascadeService.class);
        CategoryDeleteCascadeService delete = mock(CategoryDeleteCascadeService.class);
        CascadeSweepService sweep = new CascadeSweepService(stash, rename, delete);

        sweep.onJobStart();

        assertThat(stash.renameCount()).isZero();
        assertThat(stash.categoryDeleteCount()).isZero();
        verifyNoInteractions(rename, delete);
    }

    @Test
    @DisplayName("onJobSuccess dispatches every rename source to the rename service")
    void onJobSuccessDispatchesRenames()
    {
        CascadeSourceStash stash = new CascadeSourceStash();
        RenameCascadeSource src1 = new RenameCascadeSource(
                "workspace://SpacesStore/uuid-1", "uuid-1", "cm:old1", "cm:new1", "old1", "new1");
        RenameCascadeSource src2 = new RenameCascadeSource(
                "workspace://SpacesStore/uuid-2", "uuid-2", "cm:old2", "cm:new2", "old2", "new2");
        stash.recordRename(src1);
        stash.recordRename(src2);
        RenameCascadeService rename = mock(RenameCascadeService.class);
        given(rename.apply(any())).willReturn(3L);
        CategoryDeleteCascadeService delete = mock(CategoryDeleteCascadeService.class);
        CascadeSweepService sweep = new CascadeSweepService(stash, rename, delete);

        sweep.onJobSuccess();

        then(rename).should(times(2)).apply(any());
        verifyNoInteractions(delete);
        assertThat(stash.renameCount()).isZero();
    }

    @Test
    @DisplayName("onJobSuccess dispatches every category-delete to the delete service")
    void onJobSuccessDispatchesCategoryDeletes()
    {
        CascadeSourceStash stash = new CascadeSourceStash();
        stash.recordCategoryDelete(CascadeTestHelper.categoryDelete("workspace://SpacesStore/uuid-X", "uuid-X"));
        stash.recordCategoryDelete(CascadeTestHelper.categoryDelete("workspace://SpacesStore/uuid-Y", "uuid-Y"));
        RenameCascadeService rename = mock(RenameCascadeService.class);
        CategoryDeleteCascadeService delete = mock(CategoryDeleteCascadeService.class);
        given(delete.apply(any())).willReturn(5L);
        CascadeSweepService sweep = new CascadeSweepService(stash, rename, delete);

        sweep.onJobSuccess();

        then(delete).should(times(2)).apply(any());
        verifyNoInteractions(rename);
        assertThat(stash.categoryDeleteCount()).isZero();
    }

    @Test
    @DisplayName("onJobSuccess with empty stash is a safe no-op")
    void onJobSuccessWithEmptyStashIsSafe()
    {
        CascadeSourceStash stash = new CascadeSourceStash();
        RenameCascadeService rename = mock(RenameCascadeService.class);
        CategoryDeleteCascadeService delete = mock(CategoryDeleteCascadeService.class);
        CascadeSweepService sweep = new CascadeSweepService(stash, rename, delete);

        assertThatCode(sweep::onJobSuccess).doesNotThrowAnyException();

        verifyNoInteractions(rename, delete);
    }

    @Test
    @DisplayName("onJobSuccess isolates per-source failures — one throwing source does not abort the rest")
    void onJobSuccessIsolatesFailures()
    {
        CascadeSourceStash stash = new CascadeSourceStash();
        stash.recordRename(new RenameCascadeSource("ref-1", "uuid-1", "cm:o", "cm:n", "o", "n"));
        stash.recordRename(new RenameCascadeSource("ref-2", "uuid-2", "cm:o", "cm:n", "o", "n"));
        RenameCascadeService rename = mock(RenameCascadeService.class);
        given(rename.apply(any())).willThrow(new RuntimeException("boom")).willReturn(1L);
        CategoryDeleteCascadeService delete = mock(CategoryDeleteCascadeService.class);
        CascadeSweepService sweep = new CascadeSweepService(stash, rename, delete);

        assertThatCode(sweep::onJobSuccess).doesNotThrowAnyException();

        then(rename).should(times(2)).apply(any());
    }

    @Test
    @DisplayName("safely no-ops when constructed with null collaborators")
    void nullWiringIsSafe()
    {
        CascadeSweepService safe = CascadeTestHelper.nullWiredSweepService();
        assertThatCode(() -> {
            safe.onJobStart();
            safe.onJobSuccess();
        }).doesNotThrowAnyException();
    }
}
