/*-
 * #%L
 * Alfresco Search Community :: Batch Indexing Distribution
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
package org.alfresco.elasticsearch.batchindexing.reliability.harness;

import java.io.IOException;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper over the ES snapshot REST API. The high-level {@link org.opensearch.client.opensearch.OpenSearchClient} does expose snapshot operations, but the shapes it wants for a fs repository are awkward and the underlying error responses are more useful raw. Every method takes a repository / snapshot name so a test can juggle multiple snapshots if it needs to.
 * <p>
 * Assumes the target ES container has {@code path.repo} set to something the JVM inside ES can write to. See the reliability compose files for the mount / env setup.
 */
public final class SnapshotHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotHelper.class);

    private final RestClient restClient;

    public SnapshotHelper(RestClient restClient)
    {
        this.restClient = restClient;
    }

    /**
     * Register a filesystem snapshot repository. Idempotent — PUT overwrites the definition. {@code location} must be a subdirectory of the {@code path.repo} configured on the ES container.
     */
    public void registerFsRepository(String repositoryName, String location) throws IOException
    {
        Request request = new Request("PUT", "/_snapshot/" + repositoryName);
        String body = "{\"type\":\"fs\",\"settings\":{\"location\":\"" + location + "\",\"compress\":true}}";
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        Response response = restClient.performRequest(request);
        LOG.info("[snapshot] registered fs repository '{}' at {} (status {})",
                repositoryName, location, response.getStatusLine().getStatusCode());
    }

    /**
     * Take a snapshot of {@code indices} into {@code snapshotName} under {@code repositoryName}. Blocks until the snapshot finishes (wait_for_completion=true).
     */
    public void takeSnapshot(String repositoryName, String snapshotName, String... indices) throws IOException
    {
        Request request = new Request("PUT",
                "/_snapshot/" + repositoryName + "/" + snapshotName + "?wait_for_completion=true");
        String body = "{\"indices\":\"" + String.join(",", indices)
                + "\",\"include_global_state\":false,\"partial\":false}";
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        Response response = restClient.performRequest(request);
        LOG.info("[snapshot] took snapshot '{}/{}' of {} (status {})",
                repositoryName, snapshotName, String.join(",", indices),
                response.getStatusLine().getStatusCode());
    }

    /**
     * Restore all indices in {@code snapshotName} under {@code repositoryName}. Blocks until the restore finishes. The target index must be closed or missing first, otherwise ES rejects with a 400.
     */
    public void restoreSnapshot(String repositoryName, String snapshotName) throws IOException
    {
        Request request = new Request("POST",
                "/_snapshot/" + repositoryName + "/" + snapshotName + "/_restore?wait_for_completion=true");
        request.setEntity(new StringEntity("{\"include_global_state\":false}", ContentType.APPLICATION_JSON));
        Response response = restClient.performRequest(request);
        LOG.info("[snapshot] restored snapshot '{}/{}' (status {})",
                repositoryName, snapshotName, response.getStatusLine().getStatusCode());
    }
}
