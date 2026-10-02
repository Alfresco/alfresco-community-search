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
package org.alfresco.reindexing.processors;

import static org.slf4j.Logger.ROOT_LOGGER_NAME;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/** Captures logback events for one logger so a test can assert on absence as well as presence. */
final class LogSpy
{
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;

    private LogSpy(Logger logger)
    {
        this.logger = logger;
    }

    static LogSpy of(Class<?> loggerClass)
    {
        return new LogSpy((Logger) LoggerFactory.getLogger(loggerClass));
    }

    static LogSpy ofRoot()
    {
        return new LogSpy((Logger) LoggerFactory.getLogger(ROOT_LOGGER_NAME));
    }

    void attach()
    {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
    }

    void detach()
    {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }

    List<String> formattedAtOrAbove(Level level)
    {
        return appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(level))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
