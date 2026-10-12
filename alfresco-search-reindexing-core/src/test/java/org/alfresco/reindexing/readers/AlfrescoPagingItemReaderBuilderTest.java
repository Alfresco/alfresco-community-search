/*-
 * #%L
 * Alfresco Search Community :: Reindexing Core
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
package org.alfresco.reindexing.readers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alfresco.database.connector.AlfrescoMetadataRepository;

class AlfrescoPagingItemReaderBuilderTest
{
    private AlfrescoMetadataRepository metadataRepository;

    @BeforeEach
    void setUp()
    {
        metadataRepository = mock(AlfrescoMetadataRepository.class);
    }

    @Test
    void forReadingById_shouldCreateAlfrescoPagingItemReaderById()
    {
        var builder = AlfrescoPagingItemReaderBuilder
                .forReadingById(100, 200)
                .name("testReader")
                .metadataRepository(metadataRepository)
                .pageSize(10);

        var reader = builder.build();

        // Should use keyset pagination reader for IDs
        assertThat(reader).isInstanceOf(AlfrescoPagingItemReaderById.class);
    }

    @Test
    void forTimestampRange_shouldCreateAlfrescoPagingItemReaderByTimestamp()
    {
        var builder = AlfrescoPagingItemReaderBuilder
                .forTimestampRange(LocalDateTime.now().minusDays(7), LocalDateTime.now())
                .name("testReader")
                .metadataRepository(metadataRepository)
                .pageSize(10);

        var reader = builder.build();

        // Should use keyset pagination reader for timestamps
        assertThat(reader).isInstanceOf(AlfrescoPagingItemReaderByTimestamp.class);
    }

    @Test
    void forIdRange_shouldCreateAlfrescoPagingItemReader()
    {
        var builder = AlfrescoPagingItemReaderBuilder
                .forIdRange(100, 200)
                .name("testReader")
                .metadataRepository(metadataRepository)
                .pageSize(10);

        var reader = builder.build();

        // forIdRange uses the original offset-based reader (backward compatibility)
        assertThat(reader).isInstanceOf(AlfrescoPagingItemReader.class);
    }

    @Test
    void forSchema_shouldCreateAlfrescoPagingItemReader()
    {
        var builder = AlfrescoPagingItemReaderBuilder
                .forSchema()
                .name("testReader")
                .metadataRepository(metadataRepository)
                .pageSize(10);

        var reader = builder.build();

        // Schema query uses the original reader
        assertThat(reader).isInstanceOf(AlfrescoPagingItemReader.class);
    }
}
