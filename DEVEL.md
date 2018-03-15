

During initialization, the following calls are made to the BaseConnectorLifecycle class:
```
2018-03-15 13:46:18,700 DEBUG [ONMS:00001_soi@soi] ca.BaseConnectorLifecycle.initialize(275)  - initialize(80ce5cde-6fcf-4f2a-bdc8-6ed05e160094,   property, type=KeywordValuePair, list:
  [0]
  	name, type=String: node-topic
  	value, type=Object: nodes
  [1]
  	name, type=String: password
  	value, type=Object: admin
  [2]
  	name, type=String: event-topic
  	value, type=Object: events
  [3]
  	name, type=String: stream-properties
  	value, type=Object: C:\Program Files (x86)\CA\SOI\resources\opennms.stream.properties
  [4]
  	name, type=String: alarm-topic
  	value, type=Object: alarms
  [5]
  	name, type=String: url
  	value, type=Object: http://onms:8980/opennms
  [6]
  	name, type=String: username
  	value, type=Object: admin

, {siloName=ONMS:00001_soi@soi, SSAAutoImport=false, syncPublisher=JMS, connectorName=soi})
2018-03-15 13:46:19,230 DEBUG [ONMS:00001_soi@soi] ca.BaseConnectorLifecycle.get(204)  - get(  updatedAfter, type=DateTime, not set
  entitytype, type=entitytype1: Item
  id, type=String, not set
  itemtype, type=String: null
  recursive, type=Boolean, not set
)
2018-03-15 13:46:19,230 DEBUG [ONMS:00001_soi@soi] ca.BaseConnectorLifecycle.subscribeToChanges(179)  - subscribeToChanges(  updatedAfter, type=DateTime, not set
  entitytype, type=entitytype1: Alert
  id, type=String, not set
  itemtype, type=String, not set
  recursive, type=Boolean, not set
, com.ca.sam.ifw.framework.Silo@6b7a53bc)
2018-03-15 13:46:19,230 DEBUG [ONMS:00001_soi@soi] ca.BaseConnectorLifecycle.get(204)  - get(  updatedAfter, type=DateTime, not set
  entitytype, type=entitytype1: Alert
  id, type=String, not set
  itemtype, type=String: null
  recursive, type=Boolean, not set
)
2018-03-15 13:46:19,230 DEBUG [MonitorThread] ca.BaseConnectorLifecycle.subscribeToChanges(179)  - subscribeToChanges(  updatedAfter, type=DateTime, not set
  entitytype, type=entitytype1: Item
  id, type=String, not set
  itemtype, type=String: null
  recursive, type=Boolean, not set
, com.ca.sam.ifw.framework.Silo@6b7a53bc)
2018-03-15 13:46:19,246 DEBUG [MonitorThread] ca.BaseConnectorLifecycle.subscribeToChanges(179)  - subscribeToChanges(  updatedAfter, type=DateTime, not set
  entitytype, type=entitytype1: Relationship
  id, type=String, not set
  itemtype, type=String: null
  recursive, type=Boolean, not set
, com.ca.sam.ifw.framework.Silo@6b7a53bc)
2018-03-15 13:46:19,246 DEBUG [MonitorThread] ca.BaseConnectorLifecycle.subscribeToErrors(418)  - subscribeToErrors(null, com.ca.sam.ifw.framework.Silo@6b7a53bc)
2018-03-15 13:46:19,246 INFO  [MonitorThread] runtime.ErrorNotifierImpl.subscribeToErrors(68)  - Adding ErrorListener com.ca.sam.ifw.framework.Silo@6b7a53bc with subscription ID d499a0aa-2aa1-423c-a3dd-546c6b421096
```