/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.k;

import java.util.Properties;

import org.apache.camel.CamelContext;
import org.apache.camel.Ordered;
import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.spi.Registry;

public interface Runtime {
    /**
     * Returns the context associated to this runtime.
     */
    CamelContext getContext();

    /**
     * Returns the registry associated to this runtime.
     */
    default Registry getRegistry() {
        return getContext().getRegistry();
    }

    default void setProperties(Properties properties) {
        PropertiesComponent pc = new PropertiesComponent();
        pc.setInitialProperties(properties);

        getRegistry().bind("properties", pc);
    }

    enum Phase {
        Starting,
        ConfigureContext,
        ConfigureRoutes,
        Started,
        Stopping,
        Stopped
    }

    @FunctionalInterface
    interface Listener extends Ordered {
        boolean accept(Phase phase, Runtime runtime);

        @Override
        default int getOrder() {
            return Ordered.LOWEST;
        }
    }

    /**
     * Helper to create a simple runtime from a given Camel Context and Runtime Registry.
     *
     * @param camelContext the camel context
     * @return the runtime
     */
    static Runtime of(CamelContext camelContext) {
        return () -> camelContext;
    }
}
