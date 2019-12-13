/*
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
package org.apache.camel.component.knative.http;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.client.WebClientOptions;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.component.knative.spi.KnativeEnvironment;
import org.apache.camel.component.knative.spi.KnativeTransport;
import org.apache.camel.component.knative.spi.KnativeTransportConfiguration;
import org.apache.camel.support.service.ServiceSupport;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KnativeHttpTransport extends ServiceSupport implements CamelContextAware, KnativeTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(KnativeHttpTransport.class);

    private final Map<KnativeHttp.ServerKey, KnativeHttpConsumerDispatcher> registry;

    private Vertx vertx;
    private VertxOptions vertxOptions;
    private HttpServerOptions vertxHttpServerOptions;
    private WebClientOptions vertxHttpClientOptions;
    private CamelContext camelContext;

    private boolean localVertx;
    private ExecutorService executor;

    public KnativeHttpTransport() {
        this.registry = new ConcurrentHashMap<>();
        this.localVertx = false;
    }

    public Vertx getVertx() {
        return vertx;
    }

    public void setVertx(Vertx vertx) {
        this.vertx = vertx;
    }

    public VertxOptions getVertxOptions() {
        return vertxOptions;
    }

    public void setVertxOptions(VertxOptions vertxOptions) {
        this.vertxOptions = vertxOptions;
    }

    public HttpServerOptions getVertxHttpServerOptions() {
        return vertxHttpServerOptions;
    }

    public void setVertxHttpServerOptions(HttpServerOptions vertxHttpServerOptions) {
        this.vertxHttpServerOptions = vertxHttpServerOptions;
    }

    public WebClientOptions getVertxHttpClientOptions() {
        return vertxHttpClientOptions;
    }

    public void setVertxHttpClientOptions(WebClientOptions vertxHttpClientOptions) {
        this.vertxHttpClientOptions = vertxHttpClientOptions;
    }

    KnativeHttpConsumerDispatcher getDispatcher(KnativeHttp.ServerKey key) {
        return registry.computeIfAbsent(key, k -> new KnativeHttpConsumerDispatcher(executor, vertx, k, vertxHttpServerOptions));
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    // *****************************
    //
    // Lifecycle
    //
    // *****************************

    @Override
    protected void doStart() throws Exception {
        this.executor = getCamelContext().getExecutorServiceManager().newSingleThreadExecutor(this, "knative-http-component");

        if (this.vertx != null)  {
            LOGGER.info("Using Vert.x instance configured on component: {}", this.vertx);
            return;
        }

        if (this.vertx == null) {
            Set<Vertx> instances = getCamelContext().getRegistry().findByType(Vertx.class);
            if (instances.size() == 1) {
                this.vertx = instances.iterator().next();

                //
                // if this method is executed before the container is fully started,
                // it may return a null reference, may be related to:
                //
                //    https://groups.google.com/forum/#!topic/quarkus-dev/qSo65fTyYVA
                //
                if (this.vertx != null) {
                    LOGGER.info("Found Vert.x instance in registry: {}", this.vertx);
                }
            }
        }

        if (this.vertx == null) {
            LOGGER.info("Creating new Vert.x instance");

            VertxOptions options = ObjectHelper.supplyIfEmpty(this.vertxOptions, VertxOptions::new);

            this.vertx = Vertx.vertx(options);
            this.localVertx = true;
        }
    }

    @Override
    protected void doStop() throws Exception {
        if (this.vertx != null && this.localVertx) {
            Future<?> future = this.executor.submit(
                () -> {
                    CountDownLatch latch = new CountDownLatch(1);

                    this.vertx.close(result -> {
                        try {
                            if (result.failed()) {
                                LOGGER.warn("Failed to close Vert.x HttpServer reason: {}",
                                    result.cause().getMessage()
                                );

                                throw new RuntimeException(result.cause());
                            }

                            LOGGER.info("Vert.x HttpServer stopped");
                        } finally {
                            latch.countDown();
                        }
                    });

                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            );

            try {
                future.get();
            } finally {
                this.vertx = null;
                this.localVertx = false;
            }
        }

        if (this.executor != null) {
            getCamelContext().getExecutorServiceManager().shutdownNow(this.executor);
        }
    }

    // *****************************
    //
    //
    //
    // *****************************

    @Override
    public Producer createProducer(Endpoint endpoint, KnativeTransportConfiguration config, KnativeEnvironment.KnativeServiceDefinition service) {
        return new KnativeHttpProducer(this, endpoint, service, vertx, vertxHttpClientOptions);
    }

    @Override
    public Consumer createConsumer(Endpoint endpoint, KnativeTransportConfiguration config, KnativeEnvironment.KnativeServiceDefinition service, Processor processor) {
        Processor next = processor;
        if (config.isRemoveCloudEventHeadersInReply()) {
            next = KnativeHttpSupport.withoutCloudEventHeaders(processor, config.getCloudEvent());
        }
        return new KnativeHttpConsumer(this, endpoint, service, next);
    }

}
