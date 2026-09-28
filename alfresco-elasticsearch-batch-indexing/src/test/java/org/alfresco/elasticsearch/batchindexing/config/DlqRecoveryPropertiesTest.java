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
package org.alfresco.elasticsearch.batchindexing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DlqRecoveryProperties validation")
class DlqRecoveryPropertiesTest
{
    @Test
    @DisplayName("defaults are valid (maxItems=10000, deleteOnSuccess=true)")
    void defaultsAreValid()
    {
        DlqRecoveryProperties props = new DlqRecoveryProperties();

        assertThat(props.getMaxItems()).isEqualTo(10_000);
        assertThat(props.isDeleteOnSuccess()).isTrue();
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("maxItems=1 (lower bound) is valid")
    void lowerBoundIsValid()
    {
        DlqRecoveryProperties props = new DlqRecoveryProperties();
        props.setMaxItems(1);

        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("maxItems=0 is rejected")
    void zeroIsRejected()
    {
        DlqRecoveryProperties props = new DlqRecoveryProperties();
        props.setMaxItems(0);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    @DisplayName("a negative maxItems is rejected")
    void negativeIsRejected()
    {
        DlqRecoveryProperties props = new DlqRecoveryProperties();
        props.setMaxItems(-5);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxItems");
    }
}
