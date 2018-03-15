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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.apache.log4j.Logger;

import com.ca.connector.impl.util.BeanXmlHelper;
import com.ca.ucf.api.InvalidParameterException;
import com.ca.usm.ucf.utils.EntityChangeSubscriptionManager;
import com.ca.usm.ucf.utils.USMSiloDataObjectType;

import commonj.sdo.DataObject;

public class OpennmsConnectorCodeSamples {
    private static final Logger LOG = Logger.getLogger(OpennmsConnectorCodeSamples.class);

    private final EntityChangeSubscriptionManager changeEvtMgr;

    public OpennmsConnectorCodeSamples(EntityChangeSubscriptionManager changeEvtMgr) {
        this.changeEvtMgr = Objects.requireNonNull(changeEvtMgr);
    }

    public void createThings() {
        createEntity(OpennmsConnectorCodeSamples::createService);
        createEntity(OpennmsConnectorCodeSamples::createRouter);
        createEntity(OpennmsConnectorCodeSamples::createComputer);
        createEntity(OpennmsConnectorCodeSamples::createComputer2);
        createEntity(OpennmsConnectorCodeSamples::createRelationship);
        createEntity(OpennmsConnectorCodeSamples::createAlert);
        createEntity(OpennmsConnectorCodeSamples::createAlert2);
        createEntity(OpennmsConnectorCodeSamples::createAlert3);
    }

    private void createEntity(Callable<DataObject> entityCallable) {
        try {
            DataObject siloData = entityCallable.call();

            /*
            Type sdoType = TypeHelper.INSTANCE.getType("http://www.ca.com/usm", "SiloDataChange");
            DataObject siloDataChange = DataFactory.INSTANCE.create(sdoType);
            siloDataChange.setString("action", "create");
            siloDataChange.setString("entitytype", "Item");
            siloDataChange.setDataObject("silodata", siloData);
            */

            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Creating entity: %s", BeanXmlHelper.toXML(siloData)));
            }

            changeEvtMgr.entityCreated(siloData);
        } catch (Exception e) {
            LOG.error("Error occurred while creating entity.", e);
        }
    }

    private static DataObject createService() throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("entitytype", "Item");
        map.put("id", "10");
        map.put("name", "OpenNMS Service");
        map.put("class", "Service");
        map.put("description", "OpenNMS Managed Elements");
        map.put("version", "1.0");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static DataObject createRouter() throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("entitytype", "Item");
        map.put("id", "11");
        map.put("name", "router-RH1");
        map.put("ip_address", "123.322.149.101");
        map.put("class", "Router");
        map.put("description", "Cisco Router");
        map.put("sysname", "router-RH1");
        map.put("dnsname", "router-RH1.xyz.com");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static DataObject createComputer() throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("entitytype", "Item");
        map.put("id", "999");
        map.put("name", "computer-C1");
        map.put("ip_address", "123.322.149.102");
        map.put("class", "System");
        map.put("description", "Some computer");
        map.put("sysname", "computer-C1");
        map.put("dnsname", "computer-C1.xyz.com");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static DataObject createComputer2() throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("entitytype", "Item");
        map.put("id", "9991");
        map.put("name", "MA01");
        map.put("ip_address", "10.0.0.1");
        map.put("class", "Router");
        map.put("description", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        map.put("sysname", "MA01");
        map.put("dnsname", "MA01");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static DataObject createRelationship() throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("entitytype", "Relationship");
        map.put("id", "router_on_svc");
        map.put("service_id", "10");
        map.put("child_id", "11");// Mapped to TargetMdrElementID by policy
        map.put("parent_id", "10"); // Mapped to SourceMdrElementID by policy
        map.put("semantic", "HasMember");
        map.put("class", "BinaryRelationship");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static DataObject createAlert() throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("mdr_alerted_object_id", "11");
        map.put("mdr_id", "1011");
        map.put("mdr_message", "Packet loss over 90%");
        map.put("mdr_summary", "Packet loss");
        map.put("mdr_severity", "Major");
        map.put("mdr_alerttype", "Risk");
        map.put("entitytype", "Alert");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static DataObject createAlert2() throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("mdr_alerted_object_id", "11");
        map.put("mdr_id", "1012");
        map.put("mdr_message", "Packet loss over 91%");
        map.put("mdr_summary", "Packet loss");
        map.put("mdr_severity", "WARNING");
        map.put("mdr_alerttype", "Risk");
        map.put("entitytype", "Alert");
        return USMSiloDataObjectType.extractFromMap(map);
    }

    private static DataObject createAlert3() throws InvalidParameterException {
        final Map<String, String> map = new LinkedHashMap<>();
        map.put("mdr_alerted_object_id", "11");
        map.put("mdr_id", "abc:1013");
        map.put("mdr_message", "Packet loss over 92%");
        map.put("mdr_summary", "Packet loss");
        map.put("mdr_severity", "Major");
        map.put("mdr_alerttype", "Risk");
        map.put("entitytype", "Alert");
        return USMSiloDataObjectType.extractFromMap(map);
    }
}
