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

import java.util.Map;

public class OpennmsConnectorConfig {

    protected static final String STREAM_PROPERTIES_KEY = "stream-properties";
    protected static final String ALARM_TOPIC_KEY = "alarm-topic";
    protected static final String NODE_TOPIC_KEY = "node-topic";
    protected static final String STATE_DIR_KEY = "state-dir";

    private final String streamProperties;
    private final String alarmTopic;
    private final String nodeTopic;
    private final String stateDir;

    OpennmsConnectorConfig(Map<String, String> params) {
        streamProperties = params.getOrDefault(STREAM_PROPERTIES_KEY, "stream.properties");
        alarmTopic = params.getOrDefault(ALARM_TOPIC_KEY, "alarms");
        nodeTopic = params.getOrDefault(NODE_TOPIC_KEY, "nodes");
        stateDir = params.get(STATE_DIR_KEY);
    }

    public String getStreamProperties() {
        return streamProperties;
    }

    public String getAlarmTopic() {
        return alarmTopic;
    }

    public String getNodeTopic() {
        return nodeTopic;
    }

    public String getStateDir() {
        return stateDir;
    }
}
