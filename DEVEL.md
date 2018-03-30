# Initialization

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

# Updates

## Ack

```
2018-03-30 13:29:16,018 DEBUG [Thread-834] ca.BaseConnectorLifecycle.update(500)  - update(  properties, type=KeywordValuePairs, data object:
  	property, type=KeywordValuePair, list:
  	[0]
  		name, type=String: mdr_summary
  		value, type=Object: <p>Cisco Event: HSRP State Change to standby(5).</p>
  	[1]
  		name, type=String: mdr_message
  		value, type=Object: <p>A cHsrpStateChange notification is sent when a  cHsrpGrpStandbyState transitions to either active or  standby state, or leaves active or standby state. There  will be only one notification issued when the state change   is from standby to active and vice versa.</p><table>  <tr><td><b>  cHsrpGrpStandbyState</b></td><td>active(6)  </td><td><p;>  initial(1) learn(2) listen(3) speak(4) standby(5) active(6)</p>  </td;></tr></table>
  	[2]
  		name, type=String: mdr_isacknowledgeable
  		value, type=Object: true
  	[3]
  		name, type=String: mdr_alerttype
  		value, type=Object: Risk
  	[4]
  		name, type=String: mdr_id
  		value, type=Object: uei.opennms.org/vendor/Cisco/traps/cHsrpStateChange:ca36b006-1332-11e8-a2a3-005056151483:233
  	[5]
  		name, type=String: mdr_alerted_object_id
  		value, type=Object: 233
  	[6]
  		name, type=String: class
  		value, type=Object: Alert
  	[7]
  		name, type=String: mdr_severity
  		value, type=Object: Minor
  	[8]
  		name, type=String: mdr_isacknowledged
  		value, type=Object: true
  	[9]
  		name, type=String: mdr_iscleared
  		value, type=Object: false



  details, type=DataObject, not set
  entitytype, type=entitytype2, not set
)

```

## Clear

```
2018-03-30 12:33:05,691 DEBUG [Thread-814] ca.BaseConnectorLifecycle.update(500)  - update(  properties, type=KeywordValuePairs, data object:
  	property, type=KeywordValuePair, list:
  	[0]
  		name, type=String: mdr_summary
  		value, type=Object: <p>bgpBackwardTransition trap received bgpPeerLastError=0x0400 bgpPeerState=1</p>
  	[1]
  		name, type=String: mdr_message
  		value, type=Object: <p>The BGPBackwardTransition Event is generated when the BGP FSM moves from a higher numbered state to a lower numbered state.</p><table><tr><td><b>bgpPeerLastError</b></td><td>0x0400</td><td><p;></p></td;></tr><tr><td><b>bgpPeerState</b></td><td>1</td><td><p;>idle(1) connect(2) active(3) opensent(4) openconfirm(5) established(6)</p></td;></tr></table>
  	[2]
  		name, type=String: mdr_isacknowledgeable
  		value, type=Object: true
  	[3]
  		name, type=String: mdr_alerttype
  		value, type=Object: Risk
  	[4]
  		name, type=String: mdr_id
  		value, type=Object: uei.opennms.org/standard/rfc1657/traps/bgpBackwardTransition:ca36b006-1332-11e8-a2a3-005056151483:138:.1.3.6.1.2.1.15.3.1.14.172.26.10.15 .1.3.6.1.2.1.15.3.1.2.172.26.10.15
  	[5]
  		name, type=String: mdr_alerted_object_id
  		value, type=Object: 138
  	[6]
  		name, type=String: class
  		value, type=Object: Alert
  	[7]
  		name, type=String: mdr_severity
  		value, type=Object: Minor
  	[8]
  		name, type=String: mdr_isacknowledged
  		value, type=Object: false
  	[9]
  		name, type=String: mdr_iscleared
  		value, type=Object: true



  details, type=DataObject, not set
  entitytype, type=entitytype2, not set
)

```
