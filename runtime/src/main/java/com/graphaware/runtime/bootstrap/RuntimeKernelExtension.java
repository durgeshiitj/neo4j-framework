/*
 * Copyright (c) 2013-2020 GraphAware
 *
 * This file is part of the GraphAware Framework.
 *
 * GraphAware Framework is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.graphaware.runtime.bootstrap;

import com.graphaware.common.log.LoggerFactory;
import com.graphaware.runtime.GraphAwareRuntime;
import com.graphaware.runtime.GraphAwareRuntimeFactory;
import com.graphaware.runtime.config.Neo4jConfigBasedRuntimeConfiguration;
import com.graphaware.runtime.module.RuntimeModuleBootstrapper;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.helpers.collection.Pair;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.Log;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.graphaware.runtime.GraphAwareRuntimeFactory.createRuntime;
import static org.neo4j.kernel.configuration.Settings.*;

/**
 * Neo4j kernel extension that automatically creates a {@link GraphAwareRuntime} and registers
 * {@link com.graphaware.runtime.module.RuntimeModule}s with it.
 * <p/>
 * The mechanism of this extension works as follows. Of course, the GraphAware Framework .jar file must be present on
 * classpath (embedded mode), or in the "plugins" directory (server mode).
 * <p/>
 * The Runtime is only created when a setting called "com.graphaware.runtime.enabled" with value equal to "true" or "1"
 * is passed as a configuration to the database. This can be achieved by any of the standard mechanisms of passing
 * configuration to the database, for example programmaticaly using {@link org.neo4j.graphdb.factory.GraphDatabaseFactory}
 * (embedded mode), or declaratively using neo4j.conf (typically server mode).
 * <p/>
 * Modules are registered similarly. For each module that should be registered, there must be an entry in the configuration
 * passed to the database. The key of the entry should be "com.graphaware.module.X.Y", where X becomes the ID
 * of the module ({@link com.graphaware.runtime.module.RuntimeModule#getId()}) and Y becomes the order in which the
 * module gets registered with respect to other modules. The value of the configuration entry must be a fully qualified
 * class name of a {@link com.graphaware.runtime.module.RuntimeModuleBootstrapper} present on the classpath or as a .jar
 * file in the "plugins" directory. Of course, third party modules can be registered as well.
 * <p/>
 * Custom configuration to the modules can be also passed via database configuration in the form of
 * "com.graphaware.module.X.A = B", where X is the module ID, A is the configuration key, and B is the configuration value.
 * <p/>
 * For instance, if you develop a {@link com.graphaware.runtime.module.RuntimeModule} that is bootstrapped by
 * <code>com.mycompany.mymodule.MyBootstrapper</code> and want to register it as the first module of the runtime with MyModuleID as
 * the module ID, with an extra configuration called "threshold" equal to 20, then there should be the two following
 * configuration entries passed to the database:
 * <p/>
 * <pre>
 * com.graphaware.runtime.enabled=true
 * com.graphaware.module.MyModuleID.1=com.mycompany.mymodule.MyBootstrapper
 * com.graphaware.module.MyModuleID.threshold=20
 * </pre>
 * <p/>
 * The runtime has a capability of delegating to {@link com.graphaware.runtime.module.TimerDrivenModule}s on a scheduled
 * basis. For configuration of the timing, please see {@link Neo4jConfigBasedRuntimeConfiguration}.
 *
 * @see com.graphaware.runtime.module.RuntimeModuleBootstrapper
 * @see Neo4jConfigBasedRuntimeConfiguration
 */
public class RuntimeKernelExtension implements Lifecycle {
    private static final Log LOG = LoggerFactory.getLogger(RuntimeKernelExtension.class);

    public static final Setting<Boolean> RUNTIME_ENABLED = setting("com.graphaware.runtime.enabled", BOOLEAN, "false");
    public static final String MODULE_CONFIG_KEY = "com.graphaware.module"; //.ID.Order = fully qualified class name of bootstrapper
    private static final Pattern MODULE_ENABLED_KEY = Pattern.compile("com\\.graphaware\\.module\\.([a-zA-Z0-9]{1,})\\.([0-9]{1,})");
    private static final int WAIT_MINUTES = 5;
    private static final int WAIT_MS = WAIT_MINUTES * 60 * 1000;

    protected final Config config;
    protected final GraphDatabaseService database;

    public RuntimeKernelExtension(Config config, GraphDatabaseService database) {
        this.config = config;
        this.database = database;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        if (isOnEnterprise()) {
            throw new RuntimeException("GraphAware Framework Community Edition is not supported on Neo4j Enterprise. Please email info@graphaware.com to get access to GraphAware Framework Enterprise Edition instead.");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        if (!config.get(RUNTIME_ENABLED)) {
            LOG.info("GraphAware Runtime disabled.");
            return;
        }

        LOG.info("GraphAware Runtime enabled, bootstrapping...");

        final GraphAwareRuntime runtime = createRuntime();

        registerModules(runtime);

        new Thread(() -> {
            if (databaseIsAvailable()) {
                runtime.start();
                LOG.info("GraphAware Runtime automatically started.");
            } else {
                LOG.error("Could not start GraphAware Runtime because the database didn't get to a usable state within " + WAIT_MINUTES + " minutes.");
            }
        }, "GraphAware Starter").start();

        LOG.info("GraphAware Runtime bootstrapped, starting the Runtime...");
    }

    protected boolean databaseIsAvailable() {
        return database.isAvailable(WAIT_MS);
    }

    protected GraphAwareRuntime createRuntime() {
        return GraphAwareRuntimeFactory.createRuntime(database, new Neo4jConfigBasedRuntimeConfiguration(database, config));
    }

    private void registerModules(GraphAwareRuntime runtime) {
        List<Pair<Integer, Pair<String, String>>> orderedBootstrappers = findOrderedBootstrappers();

        int lastOrder = -1;
        for (Pair<Integer, Pair<String, String>> bootstrapperPair : orderedBootstrappers) {
            int order = bootstrapperPair.first();
            LOG.info("Bootstrapping module with order " + order + ", ID " + bootstrapperPair.other().first() + ", using " + bootstrapperPair.other().other());

            if (lastOrder == order) {
                LOG.warn("There is more than one module with order " + order + "! Will order clashing modules randomly");
            }

            lastOrder = order;

            try {
                RuntimeModuleBootstrapper bootstrapper = (RuntimeModuleBootstrapper) Class.forName(bootstrapperPair.other().other()).newInstance();
                runtime.registerModule(bootstrapper.bootstrapModule(bootstrapperPair.other().first(), findModuleConfig(bootstrapperPair.other().first()), database));
            } catch (Exception e) {
                LOG.error("Unable to bootstrap module " + bootstrapperPair.first(), e);
            }
        }
    }

    private List<Pair<Integer, Pair<String, String>>> findOrderedBootstrappers() {
        List<Pair<Integer, Pair<String, String>>> orderedBootstrappers = new ArrayList<>();

        for (String paramKey : config.getRaw().keySet()) {
            Matcher matcher = MODULE_ENABLED_KEY.matcher(paramKey);

            if (matcher.find()) {
                String moduleId = matcher.group(1);
                Integer moduleOrder = Integer.valueOf(matcher.group(2));
                String bootstrapperClass = config.getRaw(paramKey).orElse("UNKNOWN");
                orderedBootstrappers.add(Pair.of(moduleOrder, Pair.of(moduleId, bootstrapperClass)));
            }
        }

        Collections.sort(orderedBootstrappers, (o1, o2) -> Integer.compare(o1.first(), o2.first()));

        return orderedBootstrappers;
    }

    private Map<String, String> findModuleConfig(String moduleId) {
        Map<String, String> moduleConfig = new HashMap<>();

        String moduleConfigKeyPrefix = MODULE_CONFIG_KEY + "." + moduleId + ".";
        for (String paramKey : config.getRaw().keySet()) {
            if (paramKey.startsWith(moduleConfigKeyPrefix) || !MODULE_ENABLED_KEY.matcher(paramKey).find()) {
                moduleConfig.put(paramKey.replace(moduleConfigKeyPrefix, ""), config.getRaw(paramKey).get());
            }
        }

        return moduleConfig;
    }

    protected boolean isOnEnterprise() {
        try {
            Class.forName("org.neo4j.kernel.impl.enterprise.EnterpriseEditionModule");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        //do nothing
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        //do nothing
    }
}
