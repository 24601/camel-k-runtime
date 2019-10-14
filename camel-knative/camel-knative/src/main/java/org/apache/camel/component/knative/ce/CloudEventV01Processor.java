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
package org.apache.camel.component.knative.ce;

import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.component.knative.KnativeEndpoint;
import org.apache.camel.component.knative.spi.CloudEvent;
import org.apache.camel.component.knative.spi.CloudEvents;
import org.apache.camel.component.knative.spi.Knative;
import org.apache.camel.component.knative.spi.KnativeEnvironment;
import org.apache.commons.lang3.StringUtils;

import static org.apache.camel.util.ObjectHelper.ifNotEmpty;

final class CloudEventV01Processor implements CloudEventProcessor {
    private final CloudEvent cloudEvent;

    public CloudEventV01Processor() {
        this.cloudEvent = CloudEvents.V01;
    }

    @Override
    public CloudEvent cloudEvent() {
        return cloudEvent;
    }

    @Override
    public Processor producer(KnativeEndpoint endpoint, KnativeEnvironment.KnativeServiceDefinition service) {
        return exchange -> {
            String eventType = service.getMetadata().get(Knative.KNATIVE_EVENT_TYPE);
            if (eventType == null) {
                eventType = endpoint.getConfiguration().getCloudEventsType();
            }

            final String contentType = service.getMetadata().get(Knative.CONTENT_TYPE);
            final ZonedDateTime created = exchange.getCreated().toInstant().atZone(ZoneId.systemDefault());
            final String eventTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(created);
            final Map<String, Object> headers = exchange.getIn().getHeaders();

            headers.putIfAbsent(cloudEvent.attributes().id(), exchange.getExchangeId());
            headers.putIfAbsent(cloudEvent.attributes().source(), endpoint.getEndpointUri());
            headers.putIfAbsent(cloudEvent.attributes().spec(), cloudEvent.version());
            headers.putIfAbsent(cloudEvent.attributes().type(), eventType);
            headers.putIfAbsent(cloudEvent.attributes().time(), eventTime);
            headers.putIfAbsent(Exchange.CONTENT_TYPE, contentType);

            // Always remove host so it's always computed from the URL and not inherited from the exchange
            headers.remove("Host");
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    public Processor consumer(KnativeEndpoint endpoint, KnativeEnvironment.KnativeServiceDefinition service) {
        return exchange -> {
            if (!Objects.equals(exchange.getIn().getHeader(Exchange.CONTENT_TYPE), Knative.MIME_STRUCTURED_CONTENT_MODE)) {
                //
                // The event is not in the form of Structured Content Mode
                // then leave it as it is.
                //
                // Note that this is true for http binding only.
                //
                // More info:
                //
                //   https://github.com/cloudevents/spec/blob/master/http-transport-binding.md#32-structured-content-mode
                //
                return;
            }

            try (InputStream is = exchange.getIn().getBody(InputStream.class)) {
                final Message message = exchange.getIn();
                final Map<String, Object> ce = Knative.MAPPER.readValue(is, Map.class);

                ifNotEmpty(ce.remove("contentType"), val -> message.setHeader(Exchange.CONTENT_TYPE, val));
                ifNotEmpty(ce.remove("data"), val -> message.setBody(val));

                //
                // Map extensions to standard camel headers
                //
                ifNotEmpty(ce.remove("extensions"), val -> {
                    if (val instanceof Map) {
                        ((Map<String, Object>) val).forEach(message::setHeader);
                    }
                });

                ce.forEach((key, val) -> {
                    message.setHeader("CE-" + StringUtils.capitalize(key), val);
                });
            }
        };
    }
}
