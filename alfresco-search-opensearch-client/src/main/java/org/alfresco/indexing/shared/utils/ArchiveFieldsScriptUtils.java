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

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

import static org.alfresco.elasticsearch.shared.ElasticsearchConstants.ALIVE;
import static org.alfresco.elasticsearch.shared.translator.AlfrescoQualifiedNameTranslator.encode;

import java.util.Set;

import lombok.experimental.UtilityClass;

/**
 * Painless script fragments handling the archive (trashcan) properties of a node.
 * <p>
 * ACS adds these properties to the archive {@code NODE_DELETED} event, so they get merged into the index document when the node is moved to the trashcan. A restore is emitted as a plain {@code NODE_CREATED} event that no longer carries them, and since the metadata and reindexing scripts only merge the supplied fields, they have to be removed explicitly once the node becomes live again.
 */
@UtilityClass
class ArchiveFieldsScriptUtils
{
    static final Set<String> ARCHIVE_FIELDS = Set.of(
            encode("sys:archivedBy"),
            encode("sys:archivedDate"));
    private static final String NO_OP = "";

    /**
     * Builds the script fragment removing the archive fields, executed only when the script params flip the document to {@code ALIVE=true}.
     *
     * @param encodedFieldNames
     *            encoded names of the fields the calling script assigns from its params
     * @return the script fragment, or an empty string when there is nothing to remove
     */
    static String removeArchiveFieldsWhenAlive(Set<String> encodedFieldNames)
    {
        if (!encodedFieldNames.contains(ALIVE))
        {
            return NO_OP;
        }
        String removals = ARCHIVE_FIELDS.stream()
                .filter(not(encodedFieldNames::contains))
                .map(EsScriptUtils::removeFieldScriptSource)
                .collect(joining());
        if (removals.isEmpty())
        {
            return NO_OP;
        }
        return "if (params['" + ALIVE + "'] == true) { " + removals + "} ";
    }
}
