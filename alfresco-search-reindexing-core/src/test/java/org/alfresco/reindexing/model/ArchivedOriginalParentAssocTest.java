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
package org.alfresco.reindexing.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArchivedOriginalParentAssocTest
{
    private static final String ORIGINAL_ASSOC_VALUE = "workspace://SpacesStore/4f94a1a5-a227-44d7-94a1-a5a22744d740"
            + "|workspace://SpacesStore/c4c6b3ef-40af-4dfc-86b3-ef40afbdfcad"
            + "|{http://www.alfresco.org/model/content/1.0}contains"
            + "|{http://www.alfresco.org/model/content/1.0}acs-9468.txt"
            + "|true|-1";

    @Test
    void parse_repositoryValue_allPartsExtracted()
    {
        assertThat(ArchivedOriginalParentAssoc.parse(ORIGINAL_ASSOC_VALUE)).contains(new ArchivedOriginalParentAssoc(
                "4f94a1a5-a227-44d7-94a1-a5a22744d740",
                "http://www.alfresco.org/model/content/1.0",
                "acs-9468.txt"));
    }

    @Test
    void parse_parentWithoutStorePrefix_keptAsIs()
    {
        // Not rejected: a value that is not a real UUID simply matches no node and the reindexer falls back to the archive-store paths.
        String value = "p|workspace://SpacesStore/c|{u}contains|{u}name|true|-1";

        assertThat(ArchivedOriginalParentAssoc.parse(value))
                .contains(new ArchivedOriginalParentAssoc("p", "u", "name"));
    }

    @Test
    void parse_nonString_empty()
    {
        assertThat(ArchivedOriginalParentAssoc.parse(42L)).isEmpty();
        assertThat(ArchivedOriginalParentAssoc.parse(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            // 5 tokens
            "workspace://SpacesStore/p|workspace://SpacesStore/c|{u}contains|{u}name|true",
            // 7 tokens
            "workspace://SpacesStore/p|workspace://SpacesStore/c|{u}contains|{u}name|true|-1|x",
            // assoc QName without namespace or local name
            "workspace://SpacesStore/p|workspace://SpacesStore/c|{u}contains|name|true|-1",
            "workspace://SpacesStore/p|workspace://SpacesStore/c|{u}contains|{u}|true|-1",
            "workspace://SpacesStore/p|workspace://SpacesStore/c|{u}contains|{uname|true|-1"
    })
    void parse_malformed_empty(String value)
    {
        assertThat(ArchivedOriginalParentAssoc.parse(value)).isEmpty();
    }
}
