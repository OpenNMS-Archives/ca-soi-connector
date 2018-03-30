# OpenNMS Connector for CA SOI [![CircleCI](https://circleci.com/gh/OpenNMS/ca-soi-connector.svg?style=svg)](https://circleci.com/gh/OpenNMS/ca-soi-connector)

## Overview

This connector is used to forward alarms and associated nodes from OpenNMS to CA SOI.
Alarms are streamed from OpenNMS into CA SOI by leveraging the Kafka producer implementation available in OpenNMS 21.1.0.

## How it works

After enabling the Kafka producer implementation in OpenNMS, all changes to alarms will be forwarded to a Kafka topic, keyed by reduction key.
When alarms are forwarded, any node associated with the alarm is also forwarded to a separate topic.

Leveraging these two topics, it is possible to build a [KTable](https://docs.confluent.io/current/streams/concepts.html#ktable) with a representation of the current alarms and associated nodes.
Changes to these are then published via the available connector APIs.

Clears and acknowledgements on alerts which are relayed to the connector from SOI will be also be performed on the associated alarms by the means of REST API calls.

## Installation

After compiling, copy `target/releases/opennms-ca-soi-connector-*.zip` to the target system.
Extract the contents over `$SOI_HOME`.

### Connector configuration

1. Rename `opennmsConnector_connectorserver.xml` to `opennmsConnector_@instance.xml` where `@instance` is the FQDN of the connector server.
1. Replace cases of the `@instance` placeholder in `opennmsConnector_@instance.xml` with the FQDN of the connector server.
1. Set the URL and credentials to your OpenNMS instance. The configured user should have the entitlements necessary to clear alarms.
1. Add your Kafka configuration to a new `stream.properties` file. See https://kafka.apache.org/0110/documentation.html#streamsconfigs for options.
1. Update `stream-properties="/path/to/stream.properties"` with the full path the the `stream.properties` files created above.

#### Kafka Streams Configuration

At a minimum you should configure the following properties:

* application.id
   * i.e. `soi-dev`
* bootstrap.servers
   * i.e. `kafka:9092`
* commit.interval.ms
   * i.e. `5000`
* state.dir
   * i.e. `C:\\Program Files (x86)\\CA\\SOI\\resources\\OpennmsConnector\\state`

### Configure logging

Edit $SOI_HOME/resources/log4j.xml, and add:

```xml
<appender name="ONMS" class="org.apache.log4j.RollingFileAppender">
    <param name="File" value="&logDir;/opennms.log"/>
    <param name="Append" value="true"/>
    <param name="MaxFileSize" value="20MB"/>
    <param name="MaxBackupIndex" value="10"/>
    <layout class="org.apache.log4j.PatternLayout">
        <param name="ConversionPattern" value="&filePattern;"/>
    </layout>
</appender>
<appender name="KAFKA" class="org.apache.log4j.RollingFileAppender">
    <param name="File" value="&logDir;/kafka.log"/>
    <param name="Append" value="true"/>
    <param name="MaxFileSize" value="20MB"/>
    <param name="MaxBackupIndex" value="10"/>
    <layout class="org.apache.log4j.PatternLayout">
        <param name="ConversionPattern" value="&filePattern;"/>
    </layout>
</appender>

<logger name="org.opennms.integrations.ca" additivity="false">
    <level value="DEBUG" />
    <appender-ref ref="ONMS" />
</logger>
<logger name="org.apache.kafka" additivity="false">
    <level value="INFO" />
    <appender-ref ref="KAFKA" />
</logger>
```

## Mappings

### Alarm Mapping

Alarms are mapped to alerts as follows:

* mdr_id
   * alarm reduction key
* mdr_alerted_object_id
   * node criteria (node id or fs:fid) for the node associated with the alarm
* mdr_message
   * alarm description truncated to 2048 characters
* mdr_message_full
   * alarm description
* mdr_summary
   * alarm log message
* mdr_severity
   * alarm severity (see severity mapping)
* mdr_alert_parm_$name
   * key/value pairs for the event parameters of the last event associated with the alarm
   * i.e. mdr_alert_parm_key1 = val1, mdr_alert_parm_key2 = val2, etc...
* mdr_alerttype
   * "Risk" (constant)
* entitytype
   * "Alert" (constant)

### Node Mapping

Nodes are mapped to item entities as follows:

* id
   * node criteria (node id or fs:fid) 
* name
   * node label
* ip_address
   * first IP interface on the node
* class
   * "System" (constant)
* description
   * node sysDescription
* sysname
   * node label
* dnsname
   * node label
* sysoid
   * node sysObjectId
* sysdescr
   * node sysDescription

### Severity Mapping

In SOI, there are 4 different severities: Normal, Minor, Major and Critical
These are mapped from the corresponding severities in OpenNMS as follows:

* Normal
   * Indeterminate
   * Cleared
   * Normal
* Minor
   * Warning
   * Minor
* Major
   * Major
* Critical
   * Critical
   * Any other unrecognized value


## Mapping Event Parameters to Alert Attributes

In order to populate arbitrary alert attributes, it is possible to include the necessary information as an event parameter.
This can be added to the event when sent, or a default value may be in included in the event definition as follows:

```xml
<event>
  ...
  <parameter name="attrib3" value="0x9999"/>
</event>
```

As noted above, parameters for the last event associated with the alarm as mapped to fields of the form `mdr_alert_parm_$name` making it possible to use policies to transform these.
For example, can can transform the parameter named 'attrib3' to 'userAttribute3' with the following:

```xml
<Field conditional='mdr_alert_parm_attrib3' output='userAttribute3' format='{0}' input='mdr_alert_parm_attrib3'/>
```

## Debugging

If the logging was configured using the appenders above, the logs will be structured as follows:
* Generic logs (root logger) go to ssa.log
* Connector generated logs appear in opennms.log
* Kafka related logs appear in kafka.log

## Building from source

Initialize and update submodules (compilation requires vendor artifacts which are stored in a private repository and are not publically accessible):

```
git submodule update --init --recursive --remote
```

Compile:

```
mvn clean package
```
