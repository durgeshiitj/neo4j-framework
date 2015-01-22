/*
 * Copyright (c) 2015 GraphAware
 *
 * This file is part of GraphAware.
 *
 * GraphAware is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.graphaware.runtime.spring;

import com.graphaware.module.changefeed.cache.CachingGraphChangeReader;
import com.graphaware.module.changefeed.io.GraphChangeReader;
import com.graphaware.runtime.RuntimeRegistry;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.graphaware.common.util.DatabaseUtils.registerShutdownHook;

@Configuration
    public class Config {

        @Bean(destroyMethod = "shutdown")
        public GraphDatabaseService graphDatabaseService() {
            GraphDatabaseService database = new GraphDatabaseFactory()
                    .newEmbeddedDatabaseBuilder("/tmp/test")
                    .loadPropertiesFromURL(
                            Config.class.getClassLoader().getResource("com/graphaware/runtime/spring/neo4j.properties"))
                    .newGraphDatabase();

            registerShutdownHook(database);

            RuntimeRegistry.getRuntime(database).waitUntilStarted();

            return database;
        }

        @Bean
        public GraphChangeReader graphChangeReader() {
            return new CachingGraphChangeReader(graphDatabaseService());
        }
    }
