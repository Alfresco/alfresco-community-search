/*-
 * #%L
 * Alfresco Search Community :: OpenSearch Client
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
package org.alfresco.indexing.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NodeRefUtilsTest
{
    private static final String UUID = "bf9c687c-0499-40ad-9dca-29f8e5f512b0";

    @Test
    void shouldStripWorkspaceStorePrefix()
    {
        assertThat(NodeRefUtils.extractUuid("workspace://SpacesStore/" + UUID)).isEqualTo(UUID);
    }

    @Test
    void shouldStripArchiveStorePrefix()
    {
        assertThat(NodeRefUtils.extractUuid("archive://SpacesStore/" + UUID)).isEqualTo(UUID);
    }

    @Test
    void shouldStripAnyOtherStorePrefix()
    {
        assertThat(NodeRefUtils.extractUuid("versionStore://version2Store/" + UUID)).isEqualTo(UUID);
    }

    @Test
    void shouldLeaveBareNodeIdUntouched()
    {
        assertThat(NodeRefUtils.extractUuid(UUID)).isEqualTo(UUID);
        assertThat(NodeRefUtils.extractUuid("KDJFBCUD")).isEqualTo("KDJFBCUD");
    }

    @Test
    void shouldStripOnlyTheLeadingPrefix()
    {
        assertThat(NodeRefUtils.extractUuid("archive://SpacesStore/workspace://SpacesStore/" + UUID))
                .isEqualTo("workspace://SpacesStore/" + UUID);
    }

    @Test
    void shouldReturnNullForNullInput()
    {
        assertThat(NodeRefUtils.extractUuid(null)).isNull();
    }
}
