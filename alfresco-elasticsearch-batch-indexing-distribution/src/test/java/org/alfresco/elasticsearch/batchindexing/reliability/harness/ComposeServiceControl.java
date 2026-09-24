/*-
 * #%L
 * Alfresco Elasticsearch Connector :: Batch Indexing Distribution
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

import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;

/**
 * Programmatic stop / start / kill of individual compose services from a lifecycle IT.
 * <p>
 * Testcontainers' {@link ComposeContainer} manages the whole stack as one unit; there's no built-in way to bounce one service. This helper reaches through {@code getContainerByServiceName} into the raw Docker API so tests can simulate a container crash and restart without tearing the whole compose stack down.
 * <p>
 * All operations block on the Docker call but do not wait for the app inside the container to be back at a functional state — callers should follow up with an appropriate {@code Awaitility.await(...)} on the effect they care about (log line, watermark update, port reachable, etc.).
 */
public final class ComposeServiceControl
{
    private static final Logger LOG = LoggerFactory.getLogger(ComposeServiceControl.class);

    private final ComposeContainer environment;
    private final DockerClient dockerClient;

    public ComposeServiceControl(ComposeContainer environment)
    {
        this.environment = environment;
        this.dockerClient = DockerClientFactory.instance().client();
    }

    /**
     * Graceful stop of a compose service (SIGTERM then SIGKILL after the daemon's grace period). Used to simulate an operator {@code docker compose stop} or a Kubernetes graceful termination.
     */
    public void stop(String serviceName)
    {
        String containerId = containerId(serviceName);
        LOG.info("[reliability] Stopping compose service {} (container {})", serviceName, shortId(containerId));
        dockerClient.stopContainerCmd(containerId).exec();
    }

    /**
     * Immediate SIGKILL of a compose service. Used to simulate an OOM kill or a hard crash — no graceful-shutdown code path runs.
     */
    public void kill(String serviceName)
    {
        String containerId = containerId(serviceName);
        LOG.info("[reliability] Killing compose service {} (container {})", serviceName, shortId(containerId));
        dockerClient.killContainerCmd(containerId).exec();
    }

    /**
     * Start a previously-stopped compose service. Idempotent-ish: starting an already-running container yields a Docker 304, which is swallowed.
     */
    public void start(String serviceName)
    {
        String containerId = containerId(serviceName);
        LOG.info("[reliability] Starting compose service {} (container {})", serviceName, shortId(containerId));
        try
        {
            dockerClient.startContainerCmd(containerId).exec();
        }
        catch (NotModifiedException ignore)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("[reliability] Container {} was already running", shortId(containerId));
            }
        }
    }

    /**
     * Freeze all processes in the container (SIGSTOP). File descriptors, TCP connections and memory stay exactly where they were — this simulates a "dependency temporarily unreachable" fault more accurately than {@link #stop(String)} because unpausing resumes without any reconnect dance.
     */
    public void pause(String serviceName)
    {
        String containerId = containerId(serviceName);
        LOG.info("[reliability] Pausing compose service {} (container {})", serviceName, shortId(containerId));
        dockerClient.pauseContainerCmd(containerId).exec();
    }

    /**
     * Resume a paused container (SIGCONT). Matching call for {@link #pause(String)}.
     */
    public void unpause(String serviceName)
    {
        String containerId = containerId(serviceName);
        LOG.info("[reliability] Unpausing compose service {} (container {})", serviceName, shortId(containerId));
        dockerClient.unpauseContainerCmd(containerId).exec();
    }

    /**
     * True when the container backing {@code serviceName} is currently in the Docker {@code running} state. Refreshes container info so a container that has crashed since startup is reflected accurately.
     */
    public boolean isRunning(String serviceName)
    {
        try
        {
            ContainerState state = environment.getContainerByServiceName(serviceName)
                    .orElseThrow(() -> new IllegalStateException("No compose service named " + serviceName));
            return state.getCurrentContainerInfo().getState().getRunning();
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("[reliability] Container state probe failed for {} (treating as not running): {}",
                        serviceName, e.getMessage());
            }
            return false;
        }
    }

    /**
     * Snapshot of the container's stdout+stderr so far. Used when tests need to assert on a log line emitted after a {@link #start(String) restart} — Testcontainers' log consumers do not follow a container across a stop/start, so the compose-time {@code withLogConsumer(...)} stream ends when the container stops.
     * <p>
     * Returns the whole log (no tail cap) since docker keeps the log driver output per container. Blocks up to the given timeout; callers should combine with {@code Awaitility.await(...).until(...)} for polling.
     */
    public String readContainerLogs(String serviceName)
    {
        String containerId = containerId(serviceName);
        StringBuilder buffer = new StringBuilder();
        try
        {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .withTailAll()
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Frame item)
                        {
                            buffer.append(new String(item.getPayload()));
                        }
                    }).awaitCompletion(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            LOG.warn("[reliability] Log fetch for {} interrupted", serviceName);
        }
        return buffer.toString();
    }

    /**
     * Last {@code maxLines} lines of the container's stdout+stderr, or {@code "<empty>"} when the container has produced no log output yet. Convenience wrapper over {@link #readContainerLogs(String)} for on-timeout diagnostics — the whole log is usually too noisy to attach to a test failure message.
     */
    public String readContainerLogsTail(String serviceName, int maxLines)
    {
        String full = readContainerLogs(serviceName);
        if (full == null || full.isEmpty())
        {
            return "<empty>";
        }
        String[] lines = full.split("\\R");
        int from = Math.max(0, lines.length - maxLines);
        StringBuilder buffer = new StringBuilder();
        for (int i = from; i < lines.length; i++)
        {
            buffer.append(lines[i]).append('\n');
        }
        return buffer.toString();
    }

    /**
     * Exit code of a stopped container, or {@code null} if the container is still running / the value is unavailable. Used by shutdown tests that need to distinguish "app exited cleanly" (0) from "app was SIGKILL-ed" (137).
     */
    public Integer exitCode(String serviceName)
    {
        ContainerState state = environment.getContainerByServiceName(serviceName)
                .orElseThrow(() -> new IllegalStateException("No compose service named " + serviceName));
        return state.getCurrentContainerInfo().getState().getExitCode();
    }

    private String containerId(String serviceName)
    {
        return environment.getContainerByServiceName(serviceName)
                .map(ContainerState::getContainerId)
                .orElseThrow(() -> new IllegalStateException("No compose service named " + serviceName));
    }

    private static String shortId(String containerId)
    {
        return containerId == null ? "<null>" : containerId.substring(0, Math.min(12, containerId.length()));
    }
}
