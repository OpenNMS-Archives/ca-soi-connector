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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.opennms.features.kafka.producer.model.OpennmsModelProtos;

import com.ca.ucf.api.InvalidParameterException;
import com.ca.ucf.api.UCFException;
import com.ca.usm.ucf.utils.USMSiloDataObjectType;

import commonj.sdo.DataObject;

public class OpennmsConnectorTest {

    private OpennmsConnector connector;

    @Before
    public void setUp() {
        // Create the connector before each test to ensure
        // that the USM classes are properly initialized
        connector = new OpennmsConnector();
    }

    @Test(expected = UCFException.class)
    public void failsToLoadWithEmptyConfig() throws UCFException {
        connector.initialize(Collections.emptyMap());
    }

    @Test
    public void canMapAlarmSeverity() throws InvalidParameterException {
        // Build a set containing the valid string values: Normal, Minor, Major, Critical, Down.
        final Set<String> validSeverities =  Arrays.stream(SOISeverity.values())
                .map(SOISeverity::getStringValue)
                .collect(Collectors.toSet());

        // Build an alarm with each severity and
        for (OpennmsModelProtos.Severity severity : OpennmsModelProtos.Severity.values()) {
            if (OpennmsModelProtos.Severity.UNRECOGNIZED.equals(severity)) {
                continue;
            }
            OpennmsModelProtos.Alarm alarm = OpennmsModelProtos.Alarm.newBuilder()
                    .setSeverity(severity)
                    .build();
            DataObject alarmEntity = OpennmsConnector.createAlertEntityForAlarm(alarm);
            // Verify that the mapped entity contains a valid severity
            Map<String,String> alarmEntityMap = USMSiloDataObjectType.convertToMap(alarmEntity);
            assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_SEVERITY_KEY), isIn(validSeverities));
        }

    }

    @Test
    public void canMapEventParameters() throws InvalidParameterException {
        // Build an alarm with some event params
        OpennmsModelProtos.Alarm alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setSeverity(OpennmsModelProtos.Severity.MINOR)
                .setLastEvent(OpennmsModelProtos.Event.newBuilder()
                        .addParameter(OpennmsModelProtos.EventParameter.newBuilder()) // no key, no value
                        .addParameter(OpennmsModelProtos.EventParameter.newBuilder().setName("key1")) // no value
                        .addParameter(OpennmsModelProtos.EventParameter.newBuilder() // both a key and value
                                .setName("key2")
                                .setValue("value2")))
                .build();

        DataObject alarmEntity = OpennmsConnector.createAlertEntityForAlarm(alarm);
        // Verify that the mapped entity contains the expected parameters
        Map<String,String> alarmEntityMap = USMSiloDataObjectType.convertToMap(alarmEntity);
        assertThat(alarmEntityMap, not(hasKey("key1")));
        assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_EVENT_PARM_PREFIX_KEY + "key2"),
                equalTo("value2"));
    }

    @Test
    public void canTruncateAlarmDescription() throws InvalidParameterException {
        // A short description which should not be truncated
        OpennmsModelProtos.Alarm alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setDescription("short descr.")
                .build();
        DataObject alarmEntity = OpennmsConnector.createAlertEntityForAlarm(alarm);
        Map<String,String> alarmEntityMap = USMSiloDataObjectType.convertToMap(alarmEntity);
        assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_MESSAGE_KEY), equalTo(alarm.getDescription()));
        assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_MESSAGE_FULL_KEY), equalTo(alarm.getDescription()));

        // A longer description which should be truncated
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < OpennmsConnector.MAX_ALARM_MESSAGE_LEN * 2; i++) {
            sb.append(i);
        }
        alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setDescription(sb.toString())
                .build();
        alarmEntity = OpennmsConnector.createAlertEntityForAlarm(alarm);
        alarmEntityMap = USMSiloDataObjectType.convertToMap(alarmEntity);
        assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_MESSAGE_KEY).length(), equalTo(OpennmsConnector.MAX_ALARM_MESSAGE_LEN));
        assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_MESSAGE_FULL_KEY).length(), greaterThan(OpennmsConnector.MAX_ALARM_MESSAGE_LEN));
        assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_MESSAGE_FULL_KEY), equalTo(alarm.getDescription()));
    }

    @Test
    public void canTrimAlarmSummaryAndDescription() throws InvalidParameterException {
        OpennmsModelProtos.Alarm alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setLogMessage("    log  ")
                .setDescription(" description ")
                .build();
        DataObject alarmEntity = OpennmsConnector.createAlertEntityForAlarm(alarm);
        Map<String,String> alarmEntityMap = USMSiloDataObjectType.convertToMap(alarmEntity);
        // Whitespace should be trimmed for the summary and message, but not for messsage full
        assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_SUMMARY_KEY), equalTo("log"));
        assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_MESSAGE_KEY), equalTo("description"));
        assertThat(alarmEntityMap.get(OpennmsConnector.ALARM_ENTITY_MESSAGE_FULL_KEY), equalTo(alarm.getDescription()));
    }

    @Test
    public void canAcknowledgeAlarmOnUpdate() throws Exception {
        // Mock the REST client
        OpennmsRestClient restClient = mock(OpennmsRestClient.class);
        connector.setRestClient(restClient);

        // Create the alarm, and store in the connector so that it can lookup
        // the alarm id for the associated reduction key
        // (this would be done automatically if the connector had already created the alarm)
        final OpennmsModelProtos.Alarm alarm = OpennmsModelProtos.Alarm.newBuilder()
                .setReductionKey("reduction-key")
                .setId(1L)
                .build();
        connector.storeAlarmForLookup(alarm);

        // Trigger an update
        Map<String, String> connectorConfig = new HashMap<>();
        connectorConfig.put("class", "Alert");
        connectorConfig.put(OpennmsConnector.ALARM_ENTITY_ID_KEY, alarm.getReductionKey());
        connectorConfig.put("mdr_isacknowledged", "true");
        DataObject entity = USMSiloDataObjectType.extractFromMap(connectorConfig);
        connector.update(entity);

        // We should have ACKed the alarm
        verify(restClient, times(1)).acknowledgeAlarm(alarm.getId());
    }
}
