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

import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.Vector;

import org.apache.log4j.Logger;

import com.ca.connector.impl.runtime.ErrorNotifierImpl;
import com.ca.connector.impl.util.BeanXmlHelper;
import com.ca.connector.runtime.Connector;
import com.ca.connector.runtime.ConnectorEventPublisher;
import com.ca.connector.runtime.ConnectorEventSubscriber;
import com.ca.connector.runtime.ConnectorOperationRunner;
import com.ca.connector.runtime.EntityChangePublisher;
import com.ca.connector.runtime.EntityChangeSubscriber;
import com.ca.connector.runtime.EntityManager;
import com.ca.connector.runtime.EntityOperationRunner;
import com.ca.connector.runtime.ErrorListener;
import com.ca.connector.runtime.ErrorNotifier;
import com.ca.connector.runtime.OperationListener;
import com.ca.ucf.api.NotImplementedException;
import com.ca.ucf.api.UCFException;
import com.ca.usm.ucf.utils.ConnectorEventSubscriptionManager;
import com.ca.usm.ucf.utils.EntityChangeSubscriptionManager;
import com.ca.usm.ucf.utils.KwdValuePairType;
import com.ca.usm.ucf.utils.USMBaseConnector;

import commonj.sdo.DataObject;

/**
 * The base connector with all of the necessary hooks and
 * life-cycle methods.
 *
 * TODO: Don't implement interfaces that are not required. It's not currently
 * clear which ones are and which ones aren't.
 *
 */
public abstract class BaseConnectorLifecycle extends USMBaseConnector implements Connector, EntityManager,
        ConnectorEventPublisher, EntityChangePublisher, EntityOperationRunner,
        ConnectorOperationRunner, ErrorNotifier, Runnable {
    private static final Logger LOG = Logger.getLogger(BaseConnectorLifecycle.class);

    /** A shared helper class to manage multiple connector event subscribers. */
    private final ConnectorEventSubscriptionManager connectorEvtMgr = new ConnectorEventSubscriptionManager();

    /** A shared helper class to manage multiple CI change event subscribers. */
    private final EntityChangeSubscriptionManager changeEvtMgr = new EntityChangeSubscriptionManager();

    /** An implementation of ErrorNotifier */
    private final ErrorNotifierImpl errorNotifier = new ErrorNotifierImpl(LOG);

    private Thread eventThread = null;
    private volatile boolean isShutdown = true;

    public abstract void initialize(Map<String, String> configParam) throws UCFException;

    public EntityChangeSubscriptionManager getChangeEvtMgr() {
        return changeEvtMgr;
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    public static String objectDump(DataObject obj) {
        if (obj == null) {
            return null;
        }
        return BeanXmlHelper.objectDump(obj, "  ");
    }

    /**
     * Subscribes to general events published by the connector.
     *
     * @param eventTypeID is the ID of a type of events published by the connector
     * @param filter      defines restrictions of events subscribed to; the object's type is the
     *                    filter type supported by the event type identified by eventTypeID.
     * @param subscriber the ConnectorEventSubscriber to notify of events
     * @return subscription ID
     */
    @Override
    public String subscribeToEvents(String eventTypeID,
                                    DataObject filter,
                                    ConnectorEventSubscriber subscriber) throws UCFException {
        return subscribeToEvents(eventTypeID, filter, getEarliestAvailableBookmark(), subscriber);
    }

    /**
     * Subscribes to general events published by the connector.  This variant of the subscription
     * method allows subscribers to ask for events (it may have missed for instance) generated since
     * a previously received event. The start event is identified by an ID associated with the
     * event, a bookmark. The bookmark is opaque to the subscriber.
     * This mechanism is derived from WS-MAN and provides some level of support for recovery.
     *
     * @param eventTypeID is the ID of a type of events published by the connector
     * @param filter      defines restrictions of events subscribed to; the object's type is the
     *                    filter type supported by the event type identified by eventTypeID.
     * @param bookmark    identifying a previously received event.  Any event that happened after
     *                    the bookmarked event will be regenerated for the subscriber.  A special
     *                    value of the bookmark is used by subscribers to request all available
     *                    events; it is returned by the getEarliestAvailableBookmark() method.
     * @param subscriber the ConnectorEventSubscriber to notify of events
     * @return subscription ID
     */
    public String subscribeToEvents(String eventTypeID,
                                    DataObject filter,
                                    Object bookmark,
                                    ConnectorEventSubscriber subscriber) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("subscribeToEvents(%s, %s, %s, %s)",
                    eventTypeID, objectDump(filter), bookmark, subscriber));
        }
        return connectorEvtMgr.addSubscription(eventTypeID,
                filter,
                bookmark,
                subscriber);
    }

    /**
     * @return a unique bookmark associated with the 'earliest' event available to the connector
     *         (see subscribeToEvents method).
     */
    @Override
    public Object getEarliestAvailableBookmark() throws UCFException {
        LOG.debug("getEarliestAvailableBookmark()");
        return null;
    }

    /**
     * Unsubscribe from general events.
     *
     * @param subscriptionID the subscription to cancel
     */
    public void unsubscribeFromEvents(String subscriptionID) {
        LOG.debug(String.format("unsubscribeFromEvents(%s)", subscriptionID));
        connectorEvtMgr.dropSubscription(subscriptionID);
    }

    /**
     * Subscribes to entity change events.
     *
     * @param selector   defines a filter on entities to subscribe to; the object's type is the
     *                   ChangeFilterType supported by the connector.
     * @param subscriber the EntityChangeSubscriber to notify
     * @return subscription id
     */
    public synchronized String subscribeToChanges(DataObject selector,
                                                  EntityChangeSubscriber subscriber) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("subscribeToChanges(%s, %s)", objectDump(selector), subscriber));
        }
        return changeEvtMgr.addSubscription(selector, subscriber);
    }

    /**
     * Unsubscribes from entity change events.
     *
     * @param subscriptionID the subscription to remove
     */
    public synchronized void unsubscribeFromChanges(String subscriptionID) {
        LOG.debug(String.format("unsubscribeFromChanges(%s)", subscriptionID));
        changeEvtMgr.dropSubscription(subscriptionID);
    }

    /**
     * Retrieves the entities that matches the provided selector.
     *
     * @param selector is an instance of the selector type associated with one of the entity types
     *                 managed by the connector.
     * @return a collection of the instances of the entity type that match the selector.
     */
    @Override
    public Collection<DataObject> get(DataObject selector) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("get(%s)", objectDump(selector)));
        }
        return Collections.emptySet();
    }

    /**
     * Discovers the entities that matches the provided selector.
     *
     * @param selector is an instance of the selector type associated with one of the entity types
     *                 managed by the connector.
     *                 If found, will be reported as a normal onCreate event to CI change
     *                 subscribers.
     */
    @Override
    public void discover(DataObject selector) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("discover(%s)", objectDump(selector)));
        }
    }

    /**
     * Enumerates the entities that matches the provided selector. While this method is semantically
     * equivalent to a (Java) enumeration over the results of the get(EntitySelector sel) method,
     * this call provides the opportunity iteratively build/transfer the results.  For example, an
     * implementation may compute/retrieve matching entities from the managed system in batches.
     *
     * @param selector is an instance of the selector type associated with one of the entity types
     *                 managed by the connector.
     * @param enumID   is a unique ID that is subsequently used by the caller in a call to
     *                 closeEnumeration() to indicate that it is no longer interested in the
     *                 enumeration.
     * @return an enumeration of the instances of the entity type that match the selector.
     */
    @Override
    public Enumeration<DataObject> enumerate(DataObject selector, UUID enumID) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("enumerate(%s, %s)", objectDump(selector), enumID));
        }
        // if possible, this should be implemented to do paged fetches from the actual
        // silo (instead of fetching everything then looping)
        return new Vector<>(get(selector)).elements();
    }

    /**
     * Used by callers to indicate that they are no longer interested in the remaining values of an
     * enumeration started earlier with the same ID.
     *
     * @param enumID is a unique ID that is subsequently used by the caller in a call to
     *               closeEnumeration() to indicate that it is no longer interested in the
     *               enumeration.
     */
    @Override
    public void closeEnumeration(UUID enumID) throws UCFException {
        LOG.debug(String.format("closeEnumerator(%s)", enumID));
    }

    /**
     * Initializes a connector, based on a configuration.
     *
     * @param uuid       uniquely identifies this instance of the connector.
     * @param config     is initially created from an instance of the ConnectorConfigDesc associated
     *                   with the connector.  The config object is edited by installers, Admin UIs,
     *                   and other means.
     * @param properties are provided to the instance and are typically used by proxy client
     *                   implementations to 'connect' to the 'real' implementation (i.e., the
     *                   implementation that directly communicates with the target system.
     */
    @Override
    public void initialize(UUID uuid, DataObject config, Properties properties) throws UCFException {
        super.initialize(uuid, config, properties);
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("initialize(%s, %s, %s)", uuid, objectDump(config), properties));
        }
        initialize(KwdValuePairType.convertToMap(config));
        startEventThread();
    }

    private void startEventThread() {
        if (eventThread == null) {
            isShutdown = false;
            eventThread = new Thread(this);
            eventThread.setDaemon(true);
            eventThread.setName(super.getClass().getName() + "-EventThread");
            eventThread.start();
        }
    }

    private void stopEventThread() throws InterruptedException {
        isShutdown = true;
        if (eventThread != null) {
            eventThread.join();
        }
    }

    /** Shuts down and cleans up connection with underlying system. */
    @Override
    public void shutdown() throws UCFException {
        LOG.debug("shutdown()");
        try {
            stopEventThread();
        } catch (InterruptedException e) {
            throw new UCFException("Interrupted while waiting for the event thread to stop.", e);
        }
    }

    /**
     * Restarts the connector.  This is equivalent to a shutdown and re-initialize with the current
     * configuration.
     */
    @Override
    public void restart() throws UCFException {
        LOG.debug("restart()");
        shutdown();
        startEventThread();
    }

    /**
     * Checks if the system is running.
     *
     * @return whether underlying system is Up/Down.
     */
    @Override
    public boolean isSystemUp() throws UCFException {
        LOG.trace("isSystemUp()");
        return true;
    }

    /**
     * Executes a synchronous operation against a connector
     *
     * @param operID     is the name of the operation
     * @param operParams are the operation input parameters
     * @return result of synchronous operation.
     */
    @Override
    public DataObject execute(String operID, DataObject operParams) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("execute(%s, %s)", operID, objectDump(operParams)));
        }
        return null;
    }

    /**
     * Starts an asynchronous operation against the connector
     *
     * @param operID     is the name of the operation
     * @param operParams are the operation input parameters
     * @param observer   is a listener for state changes related to the operation instance. This
     *                   parameter can be null and a listener can also register for the operation at
     *                   a later time
     * @param refID      is an ID understood by the caller
     * @return operation unique instance ID which can be used to refer to the operation
     */
    @Override
    public String start(String operID,
                        DataObject operParams,
                        OperationListener observer,
                        String refID) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("start(%s, %s, %s, %s)", operID, objectDump(operParams), observer, refID));
        }
        return null;
    }

    /**
     * Executes a synchronous operation against a managed entity(ies)
     *
     * @param operID     is the name of the operation
     * @param entityKey  specifies the entity instance(s) to which the operation applies
     * @param operParams are the operation input parameters
     * @return result of synchronous operation.
     */
    @Override
    public DataObject execute(String operID, DataObject entityKey, DataObject operParams)
            throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("execute(%s, %s, %s)", operID, objectDump(entityKey), objectDump(operParams)));
        }
        return null;
    }

    /**
     * Starts an asynchronous operation against a managed entity(ies)
     *
     * @param operID     is the name of the operation
     * @param selector   specifies the entity instance
     * @param operParams are the operation input parameters
     * @param listener   is a listener for state changes related to the operation instance. This
     *                   parameter can be null and a listener can also register for the operation at
     *                   a later time
     * @param refID      is an ID understood by the caller
     * @return operation unique instance ID which can be used to refer to the operation
     */
    @Override
    public String start(String operID,
                        DataObject selector,
                        DataObject operParams,
                        OperationListener listener,
                        String refID) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("start(%s, %s, %s, %s, %s)", operID, objectDump(selector),
                    objectDump(operParams), listener, refID));
        }
        return null;
    }

    /**
     * ErrorNotifier Interface
     * Errors are reported from SampleEventThread below
     * and in generateCIChangeEvents, which is called from the thread below.
     */
    @Override
    public String subscribeToErrors(String callbackRefID, ErrorListener listener)
            throws UCFException {
        LOG.debug(String.format("subscribeToErrors(%s, %s)", callbackRefID, listener));
        return errorNotifier.subscribeToErrors(callbackRefID, listener);
    }

    @Override
    public void unsubscribeFromErrors(String subscriptionId) throws UCFException {
        LOG.debug(String.format("unsubscribeFromErrors(%s)", subscriptionId));
        errorNotifier.unsubscribeFromErrors(subscriptionId);
    }

    /**
     * Listens to ongoing operation state changes. The interface supports a single listener per
     * runner; if more are required, a simple multiplexer pattern can be used.
     *
     * @param operInstID is the unique instance ID of a running operation; it was obtained through
     *                   an initial call to "start"
     * @param refID      is an ID understood by the caller
     */
    @Override
    public void setOperationListener(String operInstID, String refID, OperationListener listener)
            throws UCFException {
        LOG.debug(String.format("setOperationListener(%s, %s, %s)", operInstID, refID, listener));
    }

    /**
     * Aborts operation
     *
     * @param operInstID is the unique instance ID of a running operation; it was obtained through
     *                   an initial call to "start"
     */
    @Override
    public void abort(String operInstID) throws UCFException {
        LOG.debug(String.format("abort(%s)", operInstID));
    }

    /**
     * Forgets operation.  Indicates to the underlying system that the caller is no longer
     * interested in the results, but that the operation does not necessarily need to be aborted.
     *
     * @param operInstID is the unique instance ID of a running operation; it was obtained through
     *                   an initial call to "start"
     */
    public void forget(String operInstID) throws UCFException {
        LOG.debug(String.format("forget(%s)", operInstID));
    }

    /**
     * Recovers operation.  This is equivalent to a restart of an operation from the last state
     * previously saved in a intermediateState() callback to the listener.
     * Some operations may simply restart, others may abort as a result (non idempotent operations),
     * others may be able to restart from that state.
     *
     * @param operInstID is the unique instance ID of a running operation; it was obtained through
     *                   an initial call to "start"
     * @param lastState  is the last known state
     * @param listener   observes state changes related to the operation instance. This parameter
     *                   can be null and a listener can also register for the operation at a later
     *                   time
     * @param refId      is an ID understood by the caller
     */
    public void recover(String operInstID,
                        DataObject lastState,
                        OperationListener listener,
                        String refId) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("recover(%s, %s, %s, %s)", objectDump(lastState), lastState, listener, refId));
        }
    }

    public DataObject create(DataObject config) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("create(%s)", objectDump(config)));
        }
        throw new NotImplementedException("This operation is not implemented.");
    }

    public DataObject update(DataObject config) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("update(%s)", objectDump(config)));
        }
        throw new NotImplementedException("This operation is not implemented.");
    }

    public void delete(DataObject selector) throws UCFException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("delete(%s)", objectDump(selector)));
        }
        throw new NotImplementedException("This operation is not implemented.");
    }
}
