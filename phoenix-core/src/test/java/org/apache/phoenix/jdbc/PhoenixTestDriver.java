/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import org.apache.phoenix.end2end.ConnectionQueryServicesTestImpl;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.query.ConnectionlessQueryServicesImpl;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesTestImpl;
import org.apache.phoenix.util.ReadOnlyProps;



/**
 * 
 * JDBC Driver implementation of Phoenix for testing.
 * To use this driver, specify test=true in url.
 * 
 * 
 * @since 0.1
 */
@ThreadSafe
public class PhoenixTestDriver extends PhoenixEmbeddedDriver {

    private final ReadOnlyProps overrideProps;
    
    @GuardedBy("this")
    private final QueryServices queryServices;
    
    @GuardedBy("this")
    private boolean closed = false;

    @GuardedBy("this")
    private final Map<ConnectionInfo, ConnectionQueryServices>
            connectionQueryServicesMap = new HashMap<>();

    public PhoenixTestDriver() {
        this(ReadOnlyProps.EMPTY_PROPS);
    }

    // For tests to override the default configuration
    public PhoenixTestDriver(ReadOnlyProps props) {
        overrideProps = props;
        queryServices = new QueryServicesTestImpl(getDefaultProps(), overrideProps);
    }

    @Override
    public synchronized QueryServices getQueryServices() {
        checkClosed();
        return queryServices;
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        // Accept the url only if test=true attribute set
        return super.acceptsURL(url) && isTestUrl(url);
    }
    
    @Override
    public synchronized Connection connect(String url, Properties info) throws SQLException {
        checkClosed();
        return super.connect(url, info);
    }
    
    @Override // public for testing
    public synchronized ConnectionQueryServices getConnectionQueryServices(String url, Properties info) throws SQLException {
        checkClosed();
        ConnectionInfo connInfo = ConnectionInfo.create(url);
        ConnectionQueryServices connectionQueryServices = connectionQueryServicesMap.get(connInfo);
        if (connectionQueryServices != null) {
            return connectionQueryServices;
        }
        if (info == null) {
            info = new Properties();
        }
        if (connInfo.isConnectionless()) {
            connectionQueryServices = new ConnectionlessQueryServicesImpl(queryServices, connInfo, info);
        } else {
            connectionQueryServices = new ConnectionQueryServicesTestImpl(queryServices, connInfo, info);
        }
        connectionQueryServices.init(url, info);
        connectionQueryServicesMap.put(connInfo, connectionQueryServices);
        return connectionQueryServices;
    }
    
    private synchronized void checkClosed() {
        if (closed) {
            throw new IllegalStateException("The Phoenix jdbc test driver has been closed.");
        }
    }
    
    @Override
    public synchronized void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            for (ConnectionQueryServices cqs : connectionQueryServicesMap.values()) {
                cqs.close();
            }
        } finally {
            ThreadPoolExecutor executor = queryServices.getExecutor();
            try {
                queryServices.close();
            } finally {
                if (executor != null) executor.shutdownNow();
                connectionQueryServicesMap.clear();;
            }
        }
    }
}
