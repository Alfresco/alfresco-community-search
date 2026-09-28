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

import org.alfresco.database.connector.NodeParams;

/**
 * Optional hook invoked when an {@link AbstractAlfrescoNodeReader} page query fails. Enterprise reindexing ships without a bean; batch-indexing registers one to route the failure into its dead-letter index. When no bean is registered the reader re-throws.
 *
 * <p>
 * The reader passes the {@link NodeParams} that produced the failure so the listener can pull the window bounds ({@link NodeParams#getTimestampRange()} for the continuous by-date scan, {@link NodeParams#getIdRange()} for the CLI by-id job) and record something operator-visible before the reader silently advances past the failed page.
 */
@FunctionalInterface
public interface PageReadFailureListener
{
    void onPageReadFailure(String readerName, NodeParams params, Throwable cause);
}
