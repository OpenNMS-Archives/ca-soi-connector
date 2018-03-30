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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import com.ca.ucf.api.NotImplementedException;
import com.ca.ucf.api.UCFException;
import com.ca.usm.ucf.utils.USMSiloDataObjectType;
import com.google.protobuf.InvalidProtocolBufferException;

import commonj.sdo.DataObject;

public class OpennmsConnector extends BaseConnectorLifecycle {
    private static final Logger LOG = Logger.getLogger(OpennmsConnector.class);

    private static final String ALARM_STORE_NAME = "alarm_store";
    private static final String NODE_STORE_NAME = "node_store";

    protected static final String ALARM_ENTITY_ID_KEY = "mdr_id";
    protected static final String ALARM_ENTITY_MESSAGE_KEY = "mdr_message";
    protected static final String ALARM_ENTITY_MESSAGE_FULL_KEY = "mdr_message_full";
    protected static final String ALARM_ENTITY_SEVERITY_KEY = "mdr_severity";
    protected static final String ALARM_ENTITY_EVENT_PARM_PREFIX_KEY = "mdr_alert_parm_";

    /**
     * The alarm message will typically get mapped to the alert detail field, which has a limit
     * of 2048 characters, so we truncate it before sending it to the connector to avoid any
     * server side errors.
     */
    protected static final int MAX_ALARM_MESSAGE_LEN = 2048;

    private KafkaStreams streams;
    private volatile ReadOnlyKeyValueStore<String, byte[]> alarmView;
    private volatile ReadOnlyKeyValueStore<String, byte[]> nodeView;
    private final Map<String,OpennmsModelProtos.Node> nodeCache = new ConcurrentSkipListMap<>();
    private final Map<String,Long> alarmIdByReductionKey = new ConcurrentSkipListMap<>();

    private OpennmsRestClient restClient;

    private CountDownLatch latch;

    @Override
    public void initialize(Map<String, String> configParam) throws UCFException {
        LOG.info(String.format("initialize(%s)", configParam));

        // Parse the configuration options
        final OpennmsConnectorConfig config = new OpennmsConnectorConfig(configParam);

        // Create the REST(ful) client
        restClient = new OpennmsRestClient(config.getUrl(), config.getUsername(), config.getPassword());

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

        // Create the latch, will be triggered once the stores are ready
        latch = new CountDownLatch(1);

        LOG.info("Building and starting stream topology...");
        streams = new KafkaStreams(builder, props);
        streams.setUncaughtExceptionHandler((t, e) -> LOG.error(String.format("Stream error on thread: %s", t.getName()), e));
        streams.start();

        LOG.info("Testing OpenNMS server connectivity via REST....");
        try {
            LOG.info(String.format("OpenNMS is running server version '%s'. REST communication is OK.",
                    restClient.getServerVersion()));
        } catch (Exception e) {
            LOG.warn(String.format("Failed to communicate with OpenNMS server via REST: %s", e.getMessage()), e);
        }
    }

    @Override
    public void shutdown() throws UCFException {
        if (streams != null) {
            streams.close();
        }
        if (latch != null) {
            latch.countDown();
        }
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

        // The stores are all ready
        latch.countDown();

        /* Debug code used to create static elements
        try {
            Thread.sleep(30000);
            OpennmsConnectorCodeSamples cs = new OpennmsConnectorCodeSamples(getChangeEvtMgr());
            cs.createThings();
        } catch (InterruptedException e) {
            LOG.error("Interrupted.", e);
        }
        */
    }

    @Override
    public List<DataObject> get(DataObject selector) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("get(%s)", objectDump(selector)));
        }

        // Parse the selector
        String entitySelector = null;
        String itemTypeSelector = null;
        Date updateAfterSelector = null;
        String idSelector = null;
        boolean recursiveSelector = true;
        if (selector != null) {
            entitySelector = selector.getString("entitytype");
            itemTypeSelector = selector.getString("itemtype");
            updateAfterSelector = selector.getDate("updatedAfter");
            idSelector = selector.getString("id");
            recursiveSelector = selector.getBoolean("recursive");
        }
        LOG.info(String.format("Received GET request for entityType: %s, itemType: %s," +
                        " updatedAfter: %s, id: %s, recursive: %s",
                entitySelector, itemTypeSelector, updateAfterSelector, idSelector, recursiveSelector));

        // Wait for the stores to be ready
        try {
            LOG.info("Waiting for the stores to be ready.");
            if (!latch.await(5, TimeUnit.MINUTES)) {
                throw new UCFException("Timed out while waiting for stores.");
            }
            LOG.info("Stores are ready.");
        } catch (InterruptedException e) {
            throw new UCFException("Interrupted while waiting for stores.");
        }

        // Retrieve the alarms
        final List<DataObject> entities = new ArrayList<>();
        if (entitySelector == null || "Alert".equals(entitySelector)) {
            List<DataObject> alarmEntities = new ArrayList<>();
            LOG.info(String.format("Processing %d (approximate) alarms in view.", alarmView.approximateNumEntries()));
            try (KeyValueIterator<String, byte[]> it = alarmView.all()) {
                while (it.hasNext()) {
                    final KeyValue<String, byte[]> kv = it.next();

                    OpennmsModelProtos.Alarm alarm = null;
                    try {
                        alarm = OpennmsModelProtos.Alarm.parseFrom(kv.value);
                    } catch (InvalidProtocolBufferException e) {
                        LOG.error("Failed to parse alarm bytes. Skipping alarm at reduction key: " + kv.key);
                    }

                    if (alarm != null) {
                        // Create the entity for the alarm
                        alarmEntities.add(createAlertEntityForAlarm(alarm));
                        // Store the alarm id for this reduction key
                        storeAlarmIdForLookup(alarm);
                    }
                }
            }
            LOG.info(String.format("Processed %d alarms.", alarmEntities.size()));
            entities.addAll(alarmEntities);
        }

        // Retrieve the nodes
        if (entitySelector == null || "Item".equals(entitySelector)) {
            List<DataObject> nodeEntities = new ArrayList<>();
            LOG.info(String.format("Processing %d (approximate) nodes in view.", nodeView.approximateNumEntries()));
            try (KeyValueIterator<String, byte[]> it = nodeView.all()) {
                while (it.hasNext()) {
                    final KeyValue<String, byte[]> kv = it.next();

                    OpennmsModelProtos.Node node = null;
                    try {
                        node = OpennmsModelProtos.Node.parseFrom(kv.value);
                    } catch (InvalidProtocolBufferException e) {
                        LOG.error("Failed to parse node bytes. Skipping node with id: " + kv.key);
                    }

                    if (node != null) {
                        // Create the entity for the node
                        nodeEntities.add(createItemEntityForNode(node));
                    }
                }
            }
            LOG.info(String.format("Processed %d nodes.", nodeEntities.size()));
            entities.addAll(nodeEntities);
        }

        LOG.info(String.format("Retrieved %d entities.", entities.size()));
        return entities;
    }

    public DataObject update(DataObject config) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("update(%s)", objectDump(config)));
        }

        final String clazz = config.getString("class");
        if (!"Alert".equalsIgnoreCase(clazz)) {
            throw new NotImplementedException("update not implement for objects of type: " + clazz);
        }

        final String reductionKey = config.getString(ALARM_ENTITY_ID_KEY);
        if (reductionKey == null) {
            throw new UCFException("Cannot update alert without entity id: " + objectDump(config));
        }

        final Long alarmId = alarmIdByReductionKey.get(reductionKey);
        if (alarmId == null) {
            LOG.warn(String.format("Got update for alarm with reduction key '%s', but not associated alarm id was found. No updated will be performed.",
                    reductionKey));
            return config;
        }

        final Boolean shouldAck = config.getBoolean("mdr_isacknowledged");
        if (Objects.equals(Boolean.TRUE, shouldAck)) {
            try {
                LOG.info(String.format("Acknowledging alarm with id %d (for reduction key '%s').", alarmId, reductionKey));
                restClient.acknowledgeAlarm(alarmId);
                LOG.info(String.format("Successfully acknowledged alarm with id %d.", alarmId));
            } catch (Exception e) {
                LOG.error(String.format("Error occurred while acknowledging alarm with id %d (for reduction key '%s'): %s",
                        alarmId, reductionKey, e.getMessage()), e);
            }
        }

        final Boolean shouldClear = config.getBoolean("mdr_iscleared");
        if (Objects.equals(Boolean.TRUE, shouldClear)) {
            try {
                LOG.info(String.format("Clearing alarm with id %d (for reduction key '%s').", alarmId, reductionKey));
                restClient.clearAlarm(alarmId);
                LOG.info(String.format("Successfully cleared alarm with id %d.", alarmId));
            } catch (Exception e) {
                LOG.error(String.format("Error occurred while clearing alarm with id %d (for reduction key '%s'): %s",
                        alarmId, reductionKey, e.getMessage()), e);
            }
        }
        return config;
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
            storeAlarmIdForLookup(alarm);
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

    private void storeAlarmIdForLookup(OpennmsModelProtos.Alarm alarm) {
        alarmIdByReductionKey.put(alarm.getReductionKey(), alarm.getId());
    }

    /**
     * Creates an item entity for the corresponding node.
     *
     * NOTE: Make sure to update the README file when changing any of the mappings here.
     *
     * @param node the node
     * @return an item entity
     * @throws InvalidParameterException
     */
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
        map.put("sysoid", node.getSysObjectId());
        map.put("sysdescr", node.getSysDescription());
        return USMSiloDataObjectType.extractFromMap(map);
    }

    /**
     * Creates an alert entity for the corresponding alarm.
     *
     * NOTE: Make sure to update the README file when changing any of the mappings here.
     *
     * @param alarm the alarm
     * @return an alert entity
     * @throws InvalidParameterException
     */
    protected static DataObject createAlertEntityForAlarm(OpennmsModelProtos.Alarm alarm) throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        final String nodeCriteria = getNodeCriteria(alarm);
        if (nodeCriteria != null) {
            map.put("mdr_alerted_object_id", nodeCriteria);
        }
        map.put(ALARM_ENTITY_ID_KEY, alarm.getReductionKey());
        map.put(ALARM_ENTITY_MESSAGE_KEY, truncateTo(alarm.getDescription(), MAX_ALARM_MESSAGE_LEN));
        map.put(ALARM_ENTITY_MESSAGE_FULL_KEY, alarm.getDescription());
        map.put("mdr_summary", alarm.getLogMessage());
        map.put(ALARM_ENTITY_SEVERITY_KEY, SOISeverity.fromOpennmsSeverity(alarm.getSeverity()).getStringValue());
        final OpennmsModelProtos.Event lastEvent = alarm.getLastEvent();
        if (lastEvent != null) {
            for (OpennmsModelProtos.EventParameter parm : lastEvent.getParameterList()) {
                if (parm.getName() == null) {
                    continue;
                }
                map.put(ALARM_ENTITY_EVENT_PARM_PREFIX_KEY + parm.getName(), parm.getValue());
            }
        }
        map.put("mdr_alerttype", "Risk");
        map.put("entitytype", "Alert");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static DataObject createAlertEntityFromReductionKey(String reductionKey) throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put(ALARM_ENTITY_ID_KEY, reductionKey);
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

    private static String truncateTo(String string, int maxLen) {
        if (string == null) {
            return null;
        }
        return string.substring(0, Math.min(string.length(), maxLen));
    }
}
