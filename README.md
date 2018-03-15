# OpenNMS Connector for CA SOI [![CircleCI](https://circleci.com/gh/OpenNMS/ca-soi-connector.svg?style=svg)](https://circleci.com/gh/OpenNMS/ca-soi-connector)

## Building

```
mvn clean package
```

## Installation

After compiling, copy `target/releases/opennms-ca-soi-connector-*.zip` to the target system.
Extract the contents over `$SOI_HOME`.

### Configure log file

Edit $SOI_HOME/resources/log4j.xml, and add:

```
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

## Debugging

* Generic logs (root logger) go to ssa.log
* Connector generated logs appear in opennms.log
* Kafka related logs appear in kafka.log

