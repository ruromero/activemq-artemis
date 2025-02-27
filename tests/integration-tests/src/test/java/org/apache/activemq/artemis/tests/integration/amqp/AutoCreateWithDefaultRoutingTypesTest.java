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
package org.apache.activemq.artemis.tests.integration.amqp;

import static org.apache.qpid.jms.provider.amqp.message.AmqpDestinationHelper.QUEUE_CAPABILITY;
import static org.apache.qpid.jms.provider.amqp.message.AmqpDestinationHelper.TOPIC_CAPABILITY;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.AddressQueryResult;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.transport.amqp.client.AmqpClient;
import org.apache.activemq.transport.amqp.client.AmqpConnection;
import org.apache.activemq.transport.amqp.client.AmqpReceiver;
import org.apache.activemq.transport.amqp.client.AmqpSender;
import org.apache.activemq.transport.amqp.client.AmqpSession;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.messaging.Target;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(Parameterized.class)
public class AutoCreateWithDefaultRoutingTypesTest extends JMSClientTestSupport {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   @Parameterized.Parameters(name = "routingType={0}")
   public static Collection<Object[]> parameters() {
      return Arrays.asList(new Object[][] {
         {RoutingType.ANYCAST}, {RoutingType.MULTICAST}
      });
   }

   @Parameterized.Parameter(0)
   public RoutingType routingType;

   @Override
   protected String getConfiguredProtocols() {
      return "AMQP";
   }

   @Override
   protected void createAddressAndQueues(ActiveMQServer server) throws Exception {
      // Don't create anything by default since we are testing auto create
   }

   @Override
   protected void configureAddressPolicy(ActiveMQServer server) {
      Configuration serverConfig = server.getConfiguration();
      serverConfig.setJournalType(JournalType.NIO);
      Map<String, AddressSettings> map = serverConfig.getAddressSettings();
      if (map.size() == 0) {
         AddressSettings as = new AddressSettings();
         map.put("#", as);
      }
      Map.Entry<String, AddressSettings> entry = map.entrySet().iterator().next();
      AddressSettings settings = entry.getValue();
      settings.setAutoCreateQueues(true);
      settings.setDefaultAddressRoutingType(routingType);
      settings.setDefaultQueueRoutingType(routingType);
      logger.info("server config, isauto? {}", entry.getValue().isAutoCreateQueues());
      logger.info("server config, default queue routing type? {}", entry.getValue().getDefaultQueueRoutingType());
      logger.info("server config, default address routing type? {}", entry.getValue().getDefaultAddressRoutingType());
   }

   @Test(timeout = 30_000)
   public void testCreateSender() throws Exception {
      final String addressName = "sender-address";

      AmqpClient client = createAmqpClient();
      AmqpConnection connection = addConnection(client.connect());
      AmqpSession session = connection.createSession();

      AmqpSender sender = session.createSender(addressName);

      AddressQueryResult address = getProxyToAddress(addressName);

      assertNotNull(address);
      assertEquals(Set.of(routingType), address.getRoutingTypes());

      sender.close();
      connection.close();
   }

   @Test(timeout = 30_000)
   public void testCreateReceiver() throws Exception {
      final String addressName = "receiver-address";

      AmqpClient client = createAmqpClient();
      AmqpConnection connection = addConnection(client.connect());
      AmqpSession session = connection.createSession();

      AmqpReceiver receiver = session.createReceiver(addressName);

      AddressQueryResult address = getProxyToAddress(addressName);

      assertNotNull(address);
      assertEquals(Set.of(routingType), address.getRoutingTypes());

      receiver.close();
      connection.close();
   }

   @Test(timeout = 30_000)
   public void testCreateSenderThatRequestsMultiCast() throws Exception {
      dotestCreateSenderThatRequestsSpecificRoutingType(RoutingType.MULTICAST);
   }

   @Test(timeout = 30_000)
   public void testCreateSenderThatRequestsAnyCast() throws Exception {
      dotestCreateSenderThatRequestsSpecificRoutingType(RoutingType.ANYCAST);
   }

   private void dotestCreateSenderThatRequestsSpecificRoutingType(RoutingType routingType) throws Exception {
      final String addressName = "sender-defined-address";

      AmqpClient client = createAmqpClient();
      AmqpConnection connection = addConnection(client.connect());
      AmqpSession session = connection.createSession();

      Target target = new Target();
      target.setAddress(addressName);
      if (routingType == RoutingType.ANYCAST) {
         target.setCapabilities(QUEUE_CAPABILITY);
      } else {
         target.setCapabilities(TOPIC_CAPABILITY);
      }

      AmqpSender sender = session.createSender(target);

      AddressQueryResult address = getProxyToAddress(addressName);

      assertNotNull(address);
      assertEquals(Set.of(routingType), address.getRoutingTypes());

      sender.close();
      connection.close();
   }

   @Test(timeout = 30_000)
   public void testCreateReceiverThatRequestsMultiCast() throws Exception {
      dotestCreateReceiverThatRequestsSpecificRoutingType(RoutingType.MULTICAST);
   }

   @Test(timeout = 30_000)
   public void testCreateReceiverThatRequestsAnyCast() throws Exception {
      dotestCreateReceiverThatRequestsSpecificRoutingType(RoutingType.ANYCAST);
   }

   private void dotestCreateReceiverThatRequestsSpecificRoutingType(RoutingType routingType) throws Exception {
      final String addressName = "receiver-defined-address";

      AmqpClient client = createAmqpClient();
      AmqpConnection connection = addConnection(client.connect());
      AmqpSession session = connection.createSession();

      Source source = new Source();
      source.setAddress(addressName);
      if (routingType == RoutingType.ANYCAST) {
         source.setCapabilities(QUEUE_CAPABILITY);
      } else {
         source.setCapabilities(TOPIC_CAPABILITY);
      }

      AmqpReceiver receiver = session.createReceiver(source);

      AddressQueryResult address = getProxyToAddress(addressName);

      assertNotNull(address);
      assertEquals(Set.of(routingType), address.getRoutingTypes());

      receiver.close();
      connection.close();
   }

   public AddressQueryResult getProxyToAddress(String addressName) throws Exception {
      return server.addressQuery(SimpleString.toSimpleString(addressName));
   }
}
