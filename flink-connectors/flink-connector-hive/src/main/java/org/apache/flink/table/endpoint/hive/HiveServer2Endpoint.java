/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.endpoint.hive;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.table.api.SqlDialect;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.catalog.hive.HiveCatalog;
import org.apache.flink.table.catalog.hive.client.HiveShimLoader;
import org.apache.flink.table.gateway.api.SqlGatewayService;
import org.apache.flink.table.gateway.api.endpoint.SqlGatewayEndpoint;
import org.apache.flink.table.gateway.api.session.SessionEnvironment;
import org.apache.flink.table.gateway.api.session.SessionHandle;
import org.apache.flink.table.gateway.api.utils.SqlGatewayException;
import org.apache.flink.table.gateway.api.utils.ThreadUtils;
import org.apache.flink.table.module.Module;
import org.apache.flink.table.module.hive.HiveModule;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hive.service.rpc.thrift.TCLIService;
import org.apache.hive.service.rpc.thrift.TCancelDelegationTokenReq;
import org.apache.hive.service.rpc.thrift.TCancelDelegationTokenResp;
import org.apache.hive.service.rpc.thrift.TCancelOperationReq;
import org.apache.hive.service.rpc.thrift.TCancelOperationResp;
import org.apache.hive.service.rpc.thrift.TCloseOperationReq;
import org.apache.hive.service.rpc.thrift.TCloseOperationResp;
import org.apache.hive.service.rpc.thrift.TCloseSessionReq;
import org.apache.hive.service.rpc.thrift.TCloseSessionResp;
import org.apache.hive.service.rpc.thrift.TExecuteStatementReq;
import org.apache.hive.service.rpc.thrift.TExecuteStatementResp;
import org.apache.hive.service.rpc.thrift.TFetchResultsReq;
import org.apache.hive.service.rpc.thrift.TFetchResultsResp;
import org.apache.hive.service.rpc.thrift.TGetCatalogsReq;
import org.apache.hive.service.rpc.thrift.TGetCatalogsResp;
import org.apache.hive.service.rpc.thrift.TGetColumnsReq;
import org.apache.hive.service.rpc.thrift.TGetColumnsResp;
import org.apache.hive.service.rpc.thrift.TGetCrossReferenceReq;
import org.apache.hive.service.rpc.thrift.TGetCrossReferenceResp;
import org.apache.hive.service.rpc.thrift.TGetDelegationTokenReq;
import org.apache.hive.service.rpc.thrift.TGetDelegationTokenResp;
import org.apache.hive.service.rpc.thrift.TGetFunctionsReq;
import org.apache.hive.service.rpc.thrift.TGetFunctionsResp;
import org.apache.hive.service.rpc.thrift.TGetInfoReq;
import org.apache.hive.service.rpc.thrift.TGetInfoResp;
import org.apache.hive.service.rpc.thrift.TGetOperationStatusReq;
import org.apache.hive.service.rpc.thrift.TGetOperationStatusResp;
import org.apache.hive.service.rpc.thrift.TGetPrimaryKeysReq;
import org.apache.hive.service.rpc.thrift.TGetPrimaryKeysResp;
import org.apache.hive.service.rpc.thrift.TGetResultSetMetadataReq;
import org.apache.hive.service.rpc.thrift.TGetResultSetMetadataResp;
import org.apache.hive.service.rpc.thrift.TGetSchemasReq;
import org.apache.hive.service.rpc.thrift.TGetSchemasResp;
import org.apache.hive.service.rpc.thrift.TGetTableTypesReq;
import org.apache.hive.service.rpc.thrift.TGetTableTypesResp;
import org.apache.hive.service.rpc.thrift.TGetTablesReq;
import org.apache.hive.service.rpc.thrift.TGetTablesResp;
import org.apache.hive.service.rpc.thrift.TGetTypeInfoReq;
import org.apache.hive.service.rpc.thrift.TGetTypeInfoResp;
import org.apache.hive.service.rpc.thrift.TOpenSessionReq;
import org.apache.hive.service.rpc.thrift.TOpenSessionResp;
import org.apache.hive.service.rpc.thrift.TProtocolVersion;
import org.apache.hive.service.rpc.thrift.TRenewDelegationTokenReq;
import org.apache.hive.service.rpc.thrift.TRenewDelegationTokenResp;
import org.apache.hive.service.rpc.thrift.TStatus;
import org.apache.hive.service.rpc.thrift.TStatusCode;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.table.api.config.TableConfigOptions.TABLE_SQL_DIALECT;
import static org.apache.flink.table.endpoint.hive.HiveServer2EndpointVersion.HIVE_CLI_SERVICE_PROTOCOL_V10;
import static org.apache.flink.table.endpoint.hive.util.HiveJdbcParameterUtils.getUsedDefaultDatabase;
import static org.apache.flink.table.endpoint.hive.util.HiveJdbcParameterUtils.validateAndNormalize;
import static org.apache.flink.table.endpoint.hive.util.ThriftObjectConversions.toSessionHandle;
import static org.apache.flink.table.endpoint.hive.util.ThriftObjectConversions.toTSessionHandle;
import static org.apache.flink.table.endpoint.hive.util.ThriftObjectConversions.toTStatus;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * HiveServer2 Endpoint that allows to accept the request from the hive client, e.g. Hive JDBC, Hive
 * Beeline.
 */
public class HiveServer2Endpoint implements TCLIService.Iface, SqlGatewayEndpoint, Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(HiveServer2Endpoint.class);
    private static final HiveServer2EndpointVersion SERVER_VERSION = HIVE_CLI_SERVICE_PROTOCOL_V10;
    private static final TStatus OK_STATUS = new TStatus(TStatusCode.SUCCESS_STATUS);
    private static final String ERROR_MESSAGE =
            "The HiveServer2 Endpoint currently doesn't support this API.";

    // --------------------------------------------------------------------------------------------
    // Server attributes
    // --------------------------------------------------------------------------------------------

    private final SqlGatewayService service;
    private final int minWorkerThreads;
    private final int maxWorkerThreads;
    private final Duration workerKeepAliveTime;
    private final int requestTimeoutMs;
    private final int backOffSlotLengthMs;
    private final long maxMessageSize;
    private final int port;

    private final Thread serverThread = new Thread(this, "HiveServer2 Endpoint");
    private ThreadPoolExecutor executor;
    private TThreadPoolServer server;

    // --------------------------------------------------------------------------------------------
    // Catalog attributes
    // --------------------------------------------------------------------------------------------

    private final String catalogName;
    @Nullable private final String defaultDatabase;
    @Nullable private final String hiveConfPath;
    private final boolean allowEmbedded;

    // --------------------------------------------------------------------------------------------
    // Module attributes
    // --------------------------------------------------------------------------------------------

    private final String moduleName;

    public HiveServer2Endpoint(
            SqlGatewayService service,
            int port,
            long maxMessageSize,
            int requestTimeoutMs,
            int backOffSlotLengthMs,
            int minWorkerThreads,
            int maxWorkerThreads,
            Duration workerKeepAliveTime,
            String catalogName,
            @Nullable String hiveConfPath,
            @Nullable String defaultDatabase,
            String moduleName) {
        this(
                service,
                port,
                maxMessageSize,
                requestTimeoutMs,
                backOffSlotLengthMs,
                minWorkerThreads,
                maxWorkerThreads,
                workerKeepAliveTime,
                catalogName,
                hiveConfPath,
                defaultDatabase,
                moduleName,
                false);
    }

    @VisibleForTesting
    public HiveServer2Endpoint(
            SqlGatewayService service,
            int port,
            long maxMessageSize,
            int requestTimeoutMs,
            int backOffSlotLengthMs,
            int minWorkerThreads,
            int maxWorkerThreads,
            Duration workerKeepAliveTime,
            String catalogName,
            @Nullable String hiveConfPath,
            @Nullable String defaultDatabase,
            String moduleName,
            boolean allowEmbedded) {
        this.service = service;

        this.port = port;
        this.maxMessageSize = maxMessageSize;
        this.requestTimeoutMs = requestTimeoutMs;
        this.backOffSlotLengthMs = backOffSlotLengthMs;
        this.minWorkerThreads = minWorkerThreads;
        this.maxWorkerThreads = maxWorkerThreads;
        this.workerKeepAliveTime = checkNotNull(workerKeepAliveTime);

        this.catalogName = checkNotNull(catalogName);
        this.hiveConfPath = hiveConfPath;
        this.defaultDatabase = defaultDatabase;
        this.allowEmbedded = allowEmbedded;

        this.moduleName = moduleName;
    }

    @Override
    public void start() throws Exception {
        buildTThreadPoolServer();
        serverThread.start();
    }

    @Override
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
        }

        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public TOpenSessionResp OpenSession(TOpenSessionReq tOpenSessionReq) throws TException {
        LOG.debug("Client protocol version: {}.", tOpenSessionReq.getClient_protocol());
        TOpenSessionResp resp = new TOpenSessionResp();
        try {
            // negotiate connection protocol
            TProtocolVersion clientProtocol = tOpenSessionReq.getClient_protocol();
            // the session version is not larger than the server version because of the
            // min(server_version, ...)
            HiveServer2EndpointVersion sessionVersion =
                    HiveServer2EndpointVersion.valueOf(
                            TProtocolVersion.findByValue(
                                    Math.min(
                                            clientProtocol.getValue(),
                                            SERVER_VERSION.getVersion().getValue())));

            // prepare session environment
            Map<String, String> originSessionConf =
                    tOpenSessionReq.getConfiguration() == null
                            ? Collections.emptyMap()
                            : tOpenSessionReq.getConfiguration();
            Map<String, String> sessionConfig = new HashMap<>();
            sessionConfig.put(TABLE_SQL_DIALECT.key(), SqlDialect.HIVE.name());
            sessionConfig.putAll(validateAndNormalize(originSessionConf));

            HiveConf conf = HiveCatalog.createHiveConf(hiveConfPath, null);
            sessionConfig.forEach(conf::set);
            Catalog hiveCatalog =
                    new HiveCatalog(
                            catalogName,
                            defaultDatabase,
                            conf,
                            HiveShimLoader.getHiveVersion(),
                            allowEmbedded);
            Module hiveModule = new HiveModule();
            SessionHandle sessionHandle =
                    service.openSession(
                            SessionEnvironment.newBuilder()
                                    .setSessionEndpointVersion(sessionVersion)
                                    .registerCatalog(catalogName, hiveCatalog)
                                    .registerModule(moduleName, hiveModule)
                                    .setDefaultCatalog(catalogName)
                                    .setDefaultDatabase(
                                            getUsedDefaultDatabase(originSessionConf).orElse(null))
                                    .addSessionConfig(sessionConfig)
                                    .build());
            // response
            resp.setStatus(OK_STATUS);
            resp.setServerProtocolVersion(sessionVersion.getVersion());
            resp.setSessionHandle(toTSessionHandle(sessionHandle));
            resp.setConfiguration(service.getSessionConfig(sessionHandle));
        } catch (Exception e) {
            LOG.error("Failed to openSession.", e);
            resp.setStatus(toTStatus(e));
        }
        return resp;
    }

    @Override
    public TCloseSessionResp CloseSession(TCloseSessionReq tCloseSessionReq) throws TException {
        TCloseSessionResp resp = new TCloseSessionResp();
        try {
            SessionHandle sessionHandle = toSessionHandle(tCloseSessionReq.getSessionHandle());
            service.closeSession(sessionHandle);
            resp.setStatus(OK_STATUS);
        } catch (Throwable t) {
            LOG.error("Failed to closeSession.", t);
            resp.setStatus(toTStatus(t));
        }
        return resp;
    }

    @Override
    public TGetInfoResp GetInfo(TGetInfoReq tGetInfoReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TExecuteStatementResp ExecuteStatement(TExecuteStatementReq tExecuteStatementReq)
            throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetTypeInfoResp GetTypeInfo(TGetTypeInfoReq tGetTypeInfoReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetCatalogsResp GetCatalogs(TGetCatalogsReq tGetCatalogsReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetSchemasResp GetSchemas(TGetSchemasReq tGetSchemasReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetTablesResp GetTables(TGetTablesReq tGetTablesReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetTableTypesResp GetTableTypes(TGetTableTypesReq tGetTableTypesReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetColumnsResp GetColumns(TGetColumnsReq tGetColumnsReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetFunctionsResp GetFunctions(TGetFunctionsReq tGetFunctionsReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetPrimaryKeysResp GetPrimaryKeys(TGetPrimaryKeysReq tGetPrimaryKeysReq)
            throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetCrossReferenceResp GetCrossReference(TGetCrossReferenceReq tGetCrossReferenceReq)
            throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetOperationStatusResp GetOperationStatus(TGetOperationStatusReq tGetOperationStatusReq)
            throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TCancelOperationResp CancelOperation(TCancelOperationReq tCancelOperationReq)
            throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TCloseOperationResp CloseOperation(TCloseOperationReq tCloseOperationReq)
            throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetResultSetMetadataResp GetResultSetMetadata(
            TGetResultSetMetadataReq tGetResultSetMetadataReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TFetchResultsResp FetchResults(TFetchResultsReq tFetchResultsReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TGetDelegationTokenResp GetDelegationToken(TGetDelegationTokenReq tGetDelegationTokenReq)
            throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TCancelDelegationTokenResp CancelDelegationToken(
            TCancelDelegationTokenReq tCancelDelegationTokenReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public TRenewDelegationTokenResp RenewDelegationToken(
            TRenewDelegationTokenReq tRenewDelegationTokenReq) throws TException {
        throw new UnsupportedOperationException(ERROR_MESSAGE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HiveServer2Endpoint)) {
            return false;
        }
        HiveServer2Endpoint that = (HiveServer2Endpoint) o;

        return minWorkerThreads == that.minWorkerThreads
                && maxWorkerThreads == that.maxWorkerThreads
                && requestTimeoutMs == that.requestTimeoutMs
                && backOffSlotLengthMs == that.backOffSlotLengthMs
                && maxMessageSize == that.maxMessageSize
                && port == that.port
                && Objects.equals(workerKeepAliveTime, that.workerKeepAliveTime)
                && Objects.equals(catalogName, that.catalogName)
                && Objects.equals(defaultDatabase, that.defaultDatabase)
                && Objects.equals(hiveConfPath, that.hiveConfPath)
                && Objects.equals(allowEmbedded, that.allowEmbedded)
                && Objects.equals(moduleName, that.moduleName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                minWorkerThreads,
                maxWorkerThreads,
                workerKeepAliveTime,
                requestTimeoutMs,
                backOffSlotLengthMs,
                maxMessageSize,
                port,
                catalogName,
                defaultDatabase,
                hiveConfPath,
                allowEmbedded,
                moduleName);
    }

    @Override
    public void run() {
        try {
            LOG.info("HiveServer2 Endpoint begins to listen on {}.", port);
            server.serve();
        } catch (Throwable t) {
            LOG.error("Exception caught by " + getClass().getSimpleName() + ". Exiting.", t);
        }
    }

    private void buildTThreadPoolServer() {
        executor =
                ThreadUtils.newThreadPool(
                        minWorkerThreads,
                        maxWorkerThreads,
                        workerKeepAliveTime.toMillis(),
                        "hiveserver2-endpoint-thread-pool");
        try {
            server =
                    new TThreadPoolServer(
                            new TThreadPoolServer.Args(
                                            new TServerSocket(
                                                    new ServerSocket(
                                                            port, -1, InetAddress.getByName(null))))
                                    .processorFactory(
                                            new TProcessorFactory(
                                                    new TCLIService.Processor<>(this)))
                                    .transportFactory(new TTransportFactory())
                                    // Currently, only support binary mode.
                                    .protocolFactory(new TBinaryProtocol.Factory())
                                    .inputProtocolFactory(
                                            new TBinaryProtocol.Factory(
                                                    true, true, maxMessageSize, maxMessageSize))
                                    .requestTimeout(requestTimeoutMs)
                                    .requestTimeoutUnit(TimeUnit.MILLISECONDS)
                                    .beBackoffSlotLength(backOffSlotLengthMs)
                                    .beBackoffSlotLengthUnit(TimeUnit.MILLISECONDS)
                                    .executorService(executor));
        } catch (Exception e) {
            throw new SqlGatewayException("Failed to build the server.", e);
        }
    }
}
