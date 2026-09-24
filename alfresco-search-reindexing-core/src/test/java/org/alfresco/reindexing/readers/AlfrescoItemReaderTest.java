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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.alfresco.database.connector.AlfrescoMetadataRepository;

public class AlfrescoItemReaderTest
{
    private final int pageSize = 10;
    private final String readerId = "This_is_a_reader_id";

    private AlfrescoMetadataRepository metadataRepository;

    @BeforeEach
    public void setUp()
    {
        metadataRepository = mock(AlfrescoMetadataRepository.class);
    }

    @Test
    public void shouldCreateReaderForIdRange()
    {
        var reader = AlfrescoPagingItemReaderBuilder
                .forIdRange(10, 20)
                .name(readerId)
                .metadataRepository(metadataRepository)
                .pageSize(pageSize)
                .build();

        assertThat(reader).isInstanceOf(AlfrescoPagingItemReader.class);
    }

    @Test
    public void shouldCreateReaderForReadingById()
    {
        var reader = AlfrescoPagingItemReaderBuilder
                .forReadingById(10, 20)
                .name(readerId)
                .metadataRepository(metadataRepository)
                .pageSize(pageSize)
                .build();

        assertThat(reader).isInstanceOf(AlfrescoPagingItemReaderById.class);
    }

    @Test
    public void shouldCreateReaderForSchema()
    {
        var reader = AlfrescoPagingItemReaderBuilder
                .forSchema()
                .name(readerId)
                .metadataRepository(metadataRepository)
                .pageSize(pageSize)
                .build();

        assertThat(reader).isInstanceOf(AlfrescoPagingItemReader.class);
    }
}
