/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.integrations.ca;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.opennms.integrations.ca.OpennmsConnector.ALARM_ENTITY_ID_KEY;
import static org.opennms.integrations.ca.OpennmsConnector.ALARM_ENTITY_SEVERITY_KEY;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.opennms.features.kafka.producer.model.OpennmsModelProtos;
import org.springframework.kafka.test.rule.KafkaEmbedded;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import com.ca.connector.runtime.EntityChangeSubscriber;
import com.ca.ucf.api.InvalidParameterException;
import com.ca.ucf.api.UCFException;
import com.ca.usm.ucf.utils.KwdValuePairType;
import com.ca.usm.ucf.utils.USMSiloDataFilterObjectType;
import com.ca.usm.ucf.utils.USMSiloDataObjectType;

import commonj.sdo.DataObject;

public class OpennmsConnectorIT {

    @ClassRule
    public static KafkaEmbedded embeddedKafka = new KafkaEmbedded(2, true, 2,
            "alarms", "nodes");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Map<String, DataObject> alertsById = new LinkedHashMap<>();

    private KafkaProducer<String, byte[]> producer;

    private OpennmsConnector connector;

    @Before
    public void setUp() {
        // Create the producer
        Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
        senderProps.put("key.serializer", StringSerializer.class.getCanonicalName());
        senderProps.put("value.serializer", ByteArraySerializer.class.getCanonicalName());
        producer = new KafkaProducer<>(senderProps);

        // Create, but don't initialize the connector
        connector = new OpennmsConnector();
    }

    @After
    public void tearDown() throws UCFException {
        connector.shutdown();
    }

    @Test
    public void canSynchronizeWithAlarms() throws ExecutionException, InterruptedException, IOException, UCFException {
        // Create an alarm
        OpennmsModelProtos.Alarm alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setReductionKey("nodeDown")
                .setSeverity(OpennmsModelProtos.Severity.MAJOR)
                .build();
        producer.send(new ProducerRecord<>("alarms", alarm.getReductionKey(), alarm.toByteArray())).get();

        // Create and subsequently delete another alarm
        alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setReductionKey("interfaceDown")
                .setSeverity(OpennmsModelProtos.Severity.MAJOR)
                .build();
        producer.send(new ProducerRecord<>("alarms", alarm.getReductionKey(), alarm.toByteArray())).get();
        producer.send(new ProducerRecord<>("alarms", alarm.getReductionKey(), null)).get();

        // Initialize the connector
        connector.initialize(UUID.randomUUID(), getConnectorConfig(), new Properties());

        // Issue a GET
        await().atMost(15, TimeUnit.SECONDS).until(() -> connector.get(null), hasSize(1));

        // The map should be empty
        assertThat(alertsById.keySet(), hasSize(0));

        // Subscribe to alert changes
        subscribeToAlerts();

        // Now let's walk through a basic alarm lifecyle
        alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setReductionKey("bgpPeerDown")
                .setSeverity(OpennmsModelProtos.Severity.MINOR)
                .build();
        producer.send(new ProducerRecord<>("alarms", alarm.getReductionKey(), alarm.toByteArray())).get();

        // Wait for the alarm to be created by the change events
        await().atMost(15, TimeUnit.SECONDS).until(getSeverityForAlarm("bgpPeerDown"), equalTo("Minor"));

        // Now clear the alarm
        alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setReductionKey("bgpPeerDown")
                .setSeverity(OpennmsModelProtos.Severity.CLEARED)
                .build();
        producer.send(new ProducerRecord<>("alarms", alarm.getReductionKey(), alarm.toByteArray())).get();

        // Wait for the alarm to be created by the change events
        await().atMost(15, TimeUnit.SECONDS).until(getSeverityForAlarm("bgpPeerDown"), equalTo("Normal"));

        // Delete the alarm
        producer.send(new ProducerRecord<>("alarms", alarm.getReductionKey(), null)).get();
        await().atMost(15, TimeUnit.SECONDS).until(() -> alertsById.get("bgpPeerDown"), nullValue());

        // Now trigger the alarm again
        alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setReductionKey("bgpPeerDown")
                .setSeverity(OpennmsModelProtos.Severity.MINOR)
                .build();
        producer.send(new ProducerRecord<>("alarms", alarm.getReductionKey(), alarm.toByteArray())).get();
        await().atMost(15, TimeUnit.SECONDS).until(getSeverityForAlarm("bgpPeerDown"), equalTo("Minor"));

        // Issue a GET
        await().atMost(15, TimeUnit.SECONDS).until(() -> connector.get(null), hasSize(2));
    }

    private Callable<String> getSeverityForAlarm(String reductionKey) {
        return () -> {
            final DataObject alarm = alertsById.get(reductionKey);
            if (alarm == null) {
                return null;
            }
            final Map<String, String> alarmMap = USMSiloDataObjectType.convertToMap(alarm);
            return alarmMap.get(ALARM_ENTITY_SEVERITY_KEY);
        };
    }

    private DataObject getConnectorConfig() throws IOException {
        File streamPropertiesFile = temporaryFolder.newFile();
        try (FileOutputStream fos = new FileOutputStream(streamPropertiesFile)) {
            final Properties props = new Properties();
            KafkaTestUtils.consumerProps("connector", "false", embeddedKafka)
                    .forEach((key, value) -> props.put(key, value.toString()));
            props.put("application.id", "connector");
            props.put("state.dir", temporaryFolder.newFolder().getAbsolutePath());
            props.put("commit.interval.ms", "500");
            props.remove("enable.auto.commit"); // remove this since we use streams
            props.store(fos, "kafka");
        }
        Map<String, String> connectorConfig = new HashMap<>();
        connectorConfig.put(OpennmsConnectorConfig.STREAM_PROPERTIES_KEY, streamPropertiesFile.getAbsolutePath());
        connectorConfig.put(OpennmsConnectorConfig.URL_KEY, "http://localhost:8980");
        connectorConfig.put(OpennmsConnectorConfig.USERNAME_KEY, "admin");
        connectorConfig.put(OpennmsConnectorConfig.PASSWORD_KEY, "admin");
        return KwdValuePairType.extractFromMap(connectorConfig);
    }

    private void subscribeToAlerts() throws InvalidParameterException {
        final EntityChangeSubscriber subscriber = new EntityChangeSubscriber() {
            @Override
            public void onCreate(DataObject dataObject) throws InvalidParameterException {
                final String id = USMSiloDataObjectType.convertToMap(dataObject).get(ALARM_ENTITY_ID_KEY);
                alertsById.put(id, dataObject);
            }

            @Override
            public void onDelete(DataObject dataObject) throws InvalidParameterException {
                final String id = USMSiloDataObjectType.convertToMap(dataObject).get(ALARM_ENTITY_ID_KEY);
                alertsById.remove(id);
            }

            @Override
            public void onUpdate(DataObject dataObject) throws InvalidParameterException {
                final String id = USMSiloDataObjectType.convertToMap(dataObject).get(ALARM_ENTITY_ID_KEY);
                alertsById.put(id, dataObject);
            }
        };
        final Map<String, String> filterMap = new HashMap<>();
        filterMap.put("entitytype", "Alert");
        final DataObject selector = USMSiloDataFilterObjectType.extractFromMap(filterMap);
        connector.subscribeToChanges(selector, subscriber);
    }
}
