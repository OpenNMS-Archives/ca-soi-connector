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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.log4j.Logger;
import org.opennms.features.kafka.producer.model.OpennmsModelProtos;

import com.ca.connector.impl.util.BeanXmlHelper;
import com.ca.ucf.api.InvalidParameterException;
import com.ca.ucf.api.UCFException;
import com.ca.usm.ucf.utils.USMSiloDataObjectType;
import com.google.protobuf.InvalidProtocolBufferException;

import commonj.sdo.DataObject;

/**
 * TODO:
 *  * Do updates instead of always issuing creates.
 */
public class OpennmsConnector extends BaseConnectorLifecycle {
    private static final Logger LOG = Logger.getLogger(OpennmsConnector.class);

    private static final String ALARM_STORE_NAME = "alarm_store";
    private static final String NODE_STORE_NAME = "node_store";

    protected static String ALARM_ENTITY_SEVERITY_KEY = "mdr_severity";

    private KafkaStreams streams;
    private ReadOnlyKeyValueStore<String, byte[]> alarmView;
    private ReadOnlyKeyValueStore<String, byte[]> nodeView;
    private final Map<String,OpennmsModelProtos.Node> nodeCache = new ConcurrentSkipListMap<>();

    @Override
    public void initialize(Map<String, String> configParam) throws UCFException {
        LOG.info(String.format("initialize(%s)", configParam));

        // Parse the configuration options
        final OpennmsConnectorConfig config = new OpennmsConnectorConfig(configParam);

        // Load the stream properties
        final String streamPropertiesFile = config.getStreamProperties();
        final Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(new File(streamPropertiesFile))) {
            props.load(fis);
        } catch (IOException e) {
            throw new UCFException("Failed to load stream properties from: " + streamPropertiesFile , e);
        }

        // Override the serializers/deserializers
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.ByteArray().getClass());

        final KStreamBuilder builder = new KStreamBuilder();
        // Build a view of the alarms to perform the initial synchronization
        final KTable<String, byte[]> alarmBytesTable = builder.table(config.getAlarmTopic(), ALARM_STORE_NAME);
        final KTable<String, OpennmsModelProtos.Alarm> alarmTable = alarmBytesTable.mapValues(alarmBytes -> {
                    try {
                        return OpennmsModelProtos.Alarm.parseFrom(alarmBytes);
                    } catch (InvalidProtocolBufferException ex) {
                        throw new RuntimeException(ex);
                    }
                });
        // Process alarms as they come in
        alarmTable.toStream()
                .foreach(this::handleNewOrUpdatedAlarm);

        // Build a view of the nodes for lookup
        builder.table(config.getNodeTopic(), NODE_STORE_NAME);

        LOG.info("Building and starting stream topology...");
        streams = new KafkaStreams(builder, props);
        streams.setUncaughtExceptionHandler((t, e) -> LOG.error(String.format("Stream error on thread: %s", t.getName()), e));
        streams.start();
    }

    @Override
    public void shutdown() throws UCFException {
        streams.close();
        super.shutdown();
    }

    @Override
    public void run() {
        LOG.info(String.format("Waiting for alarm store: %s", ALARM_STORE_NAME));
        try {
            alarmView = waitUntilStoreIsQueryable(ALARM_STORE_NAME, QueryableStoreTypes.keyValueStore(), streams);
        } catch (InterruptedException e) {
            LOG.error("Interrupted. Aborting thread.");
            return;
        }
        LOG.info("Alarm store is ready.");

        LOG.info(String.format("Waiting for node store: %s", NODE_STORE_NAME));
        try {
            nodeView = waitUntilStoreIsQueryable(NODE_STORE_NAME, QueryableStoreTypes.keyValueStore(), streams);
        } catch (InterruptedException e) {
            LOG.error("Interrupted. Aborting thread.");
            return;
        }
        LOG.info("Node store is ready.");

        LOG.info(String.format("Processing %d (approximate) alarms in view.", alarmView.approximateNumEntries()));
        try (KeyValueIterator<String, byte[]> it = alarmView.all()) {
            while (it.hasNext()) {
                final KeyValue<String, byte[]> kv = it.next();
                try {
                    handleNewOrUpdatedAlarm(kv.key, OpennmsModelProtos.Alarm.parseFrom(kv.value));
                } catch (InvalidProtocolBufferException e) {
                    LOG.error("Failed to parse alarm bytes.");
                }
            }
        }

        /* Debug code used to create static elements */
        try {
            Thread.sleep(30000);
            OpennmsConnectorCodeSamples cs = new OpennmsConnectorCodeSamples(getChangeEvtMgr());
            cs.createThings();
        } catch (InterruptedException e) {
            LOG.error("Interrupted.", e);
        }
    }

    private void handleNewOrUpdatedAlarm(String reductionKey, OpennmsModelProtos.Alarm alarm) {
        if(LOG.isDebugEnabled()) {
            LOG.debug(String.format("handleNewOrUpdatedAlarm(%s, %s)", reductionKey, alarm));
        }

        if (alarm == null) {
            try {
                deleteEntity(createAlertEntityFromReductionKey(reductionKey));
            } catch (InvalidParameterException e) {
                LOG.warn(String.format("Failed to delete entity for reduction key: %s", reductionKey));
            }
            return;
        }

        final OpennmsModelProtos.Node node = lookupNodeForAlarm(alarm);
        if (node != null) {
            handleNode(node);
        }

        try {
            createEntity(createAlertEntityForAlarm(alarm));
        } catch (InvalidParameterException e) {
            LOG.warn(String.format("Failed to create entity for node: %s", node));
        }
    }

    private void handleNode(OpennmsModelProtos.Node node) {
        final String nodeCriteria = getNodeCriteria(node);
        if(LOG.isDebugEnabled()) {
            LOG.debug(String.format("handleNode(%s)", nodeCriteria));
        }
        if(LOG.isTraceEnabled()) {
            // The node objects can be particularly verbose, so we log as TRACE instead of DEBUG
            LOG.trace(String.format("handleNode(%s)", node));
        }

        // Lookup the node in the cache to see if it needs updating
        final OpennmsModelProtos.Node existingNode = nodeCache.get(nodeCriteria);
        if (existingNode == null || !existingNode.equals(node)) {
            if(LOG.isDebugEnabled()) {
                LOG.debug(String.format("Creating node '%s'.", nodeCriteria));
            }
            try {
                createEntity(createItemEntityForNode(node));
            } catch (InvalidParameterException e) {
                LOG.warn(String.format("Failed to create entity for node: %s", node));
            }
            // Update the cache with the new node
            nodeCache.put(nodeCriteria, node);
        } else {
            LOG.debug(String.format("Node '%s' is already up-to-date.", nodeCriteria));
        }
    }

    private OpennmsModelProtos.Node lookupNodeForAlarm(OpennmsModelProtos.Alarm alarm) {
        final String lookupCriteria = getNodeCriteria(alarm);
        if (lookupCriteria == null) {
            // The alarm is not related to a node
            return null;
        }

        if (nodeView == null) {
            LOG.warn(String.format("Node view is not ready yet. Alarm with reduction key %s may be created/updated before the node with criteria %s",
                    alarm.getReductionKey(), lookupCriteria));
            return null;
        }

        final byte[] nodeBytes = nodeView.get(lookupCriteria);
        if (nodeBytes == null) {
            LOG.warn(String.format("Alarm with reduction key: %s is related to node with criteria: %s, but no node was found in the view.",
                    alarm.getReductionKey(), lookupCriteria));
            return null;
        }

        try {
            return OpennmsModelProtos.Node.parseFrom(nodeBytes);
        } catch (InvalidProtocolBufferException e) {
            LOG.error(String.format("Failed to parse the node with criteria: %s", lookupCriteria), e);
            return null;
        }
    }

    private void createEntity(DataObject siloData) {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Creating entity: %s", BeanXmlHelper.toXML(siloData)));
            }
            getChangeEvtMgr().entityCreated(siloData);
        } catch (Exception e) {
            LOG.error("Error occurred while creating entity.", e);
        }
    }

    private void deleteEntity(DataObject siloData) {
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Deleting entity: %s", BeanXmlHelper.toXML(siloData)));
            }
            getChangeEvtMgr().entityDeleted(siloData);
        } catch (Exception e) {
            LOG.error("Error occurred while deleting entity.", e);
        }
    }

    private static DataObject createItemEntityForNode(OpennmsModelProtos.Node node) throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("entitytype", "Item");
        map.put("id", getNodeCriteria(node));
        map.put("name", node.getLabel());
        node.getIpInterfaceList().stream().findFirst().ifPresent(ip -> {
            map.put("ip_address", ip.getIpAddress());
        });
        map.put("class", "System");
        if (node.getSysDescription() != null) {
            map.put("description", node.getSysDescription());
        }
        map.put("sysname", node.getLabel());
        map.put("dnsname", node.getLabel());
        return USMSiloDataObjectType.extractFromMap(map);
    }

    protected static DataObject createAlertEntityForAlarm(OpennmsModelProtos.Alarm alarm) throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        final String nodeCriteria = getNodeCriteria(alarm);
        if (nodeCriteria != null) {
            map.put("mdr_alerted_object_id", nodeCriteria);
        }
        map.put("mdr_id", alarm.getReductionKey());
        map.put("mdr_message", alarm.getDescription());
        map.put("mdr_summary", alarm.getLogMessage());
        map.put(ALARM_ENTITY_SEVERITY_KEY, SOISeverity.fromOpennmsSeverity(alarm.getSeverity()).getStringValue());
        map.put("mdr_alerttype", "Risk");
        map.put("entitytype", "Alert");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static DataObject createAlertEntityFromReductionKey(String reductionKey) throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("mdr_id", reductionKey);
        map.put("mdr_alerttype", "Risk");
        map.put("entitytype", "Alert");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static String getNodeCriteria(OpennmsModelProtos.Node node) {
        if (isNotEmpty(node.getForeignSource()) && isNotEmpty(node.getForeignId())) {
            return String.format("%s:%s", node.getForeignSource(), node.getForeignId());
        } else {
            return Long.toString(node.getId());
        }
    }

    private static String getNodeCriteria(OpennmsModelProtos.Alarm alarm) {
        final OpennmsModelProtos.NodeCriteria nodeCriteria = alarm.getNodeCriteria();
        if (nodeCriteria == null) {
            // The alarm is not related to a node
            return null;
        }

        if (isNotEmpty(nodeCriteria.getForeignSource()) && isNotEmpty(nodeCriteria.getForeignId())) {
            return String.format("%s:%s", nodeCriteria.getForeignSource(), nodeCriteria.getForeignId());
        } else {
            return Long.toString(nodeCriteria.getId());
        }
    }

    private static <T> T waitUntilStoreIsQueryable(final String storeName,
                                                   final QueryableStoreType<T> queryableStoreType,
                                                   final KafkaStreams streams) throws InterruptedException {
        while (true) {
            try {
                return streams.store(storeName, queryableStoreType);
            } catch (InvalidStateStoreException ignored) {
                // store not yet ready for querying
                Thread.sleep(100);
            }
        }
    }

    private static boolean isNotEmpty(String string) {
        return string != null && string.trim().length() > 1;
    }
}
