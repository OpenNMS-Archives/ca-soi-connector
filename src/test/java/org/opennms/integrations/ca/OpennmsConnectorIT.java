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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

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

import com.ca.ucf.api.InvalidParameterException;
import com.ca.ucf.api.UCFException;
import com.ca.usm.ucf.utils.KwdValuePairType;

import commonj.sdo.DataObject;

public class OpennmsConnectorIT {

    @ClassRule
    public static KafkaEmbedded embeddedKafka = new KafkaEmbedded(2, true, 2,
            "alarms", "nodes");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

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

        // Initialize the connector
        connector.initialize(UUID.randomUUID(), getConnectorConfig(), new Properties());

        // Issue a GET
        List<DataObject> entities = connector.get(null);

        // We should get back a single alarm
        assertThat(entities, hasSize(1));
    }

    private DataObject getConnectorConfig() throws IOException, InvalidParameterException {
        File streamPropertiesFile = temporaryFolder.newFile();
        try (FileOutputStream fos = new FileOutputStream(streamPropertiesFile)) {
            final Properties props = new Properties();
            KafkaTestUtils.consumerProps("connector", "false", embeddedKafka)
                    .forEach((key, value) -> props.put(key, value.toString()));
            props.put("application.id", "connector");
            props.remove("enable.auto.commit"); // remove this since we use streams
            props.store(fos, "kafka");
        }
        Map<String, String> connectorConfig = new HashMap<>();
        connectorConfig.put(OpennmsConnectorConfig.STREAM_PROPERTIES_KEY, streamPropertiesFile.getAbsolutePath());
        return KwdValuePairType.extractFromMap(connectorConfig);
    }
}
