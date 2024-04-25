package com.clickhouse.client.api;

import com.clickhouse.client.*;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.insert.POJOSerializer;
import com.clickhouse.client.api.internal.SerializerUtils;
import com.clickhouse.client.api.internal.SettingsConverter;
import com.clickhouse.client.api.internal.ValidationUtils;
import com.clickhouse.data.ClickHouseColumn;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.SocketException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.clickhouse.client.api.metadata.TableSchema;
import com.clickhouse.client.api.internal.TableSchemaParser;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseDataStreamFactory;
import com.clickhouse.data.ClickHouseFormat;
import com.clickhouse.data.ClickHousePipedOutputStream;
import com.clickhouse.data.format.BinaryStreamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static java.time.temporal.ChronoUnit.SECONDS;

public class Client {
    public static final int TIMEOUT = 30_000;
    private Set<String> endpoints;
    private Map<String, String> configuration;
    private List<ClickHouseNode> serverNodes = new ArrayList<>();
    private Map<Class<?>, Map<ClickHouseColumn, POJOSerializer>> serializers;
    private Map<Class<?>, Map<String, Method>> getters;
    private static final  Logger LOG = LoggerFactory.getLogger(Client.class);

    private Client(Set<String> endpoints, Map<String,String> configuration) {
        this.endpoints = endpoints;
        this.configuration = configuration;
        this.endpoints.forEach(endpoint -> {
            this.serverNodes.add(ClickHouseNode.of(endpoint, this.configuration));
        });
        this.serializers = new HashMap<>();
        this.getters = new HashMap<>();
    }

    public static class Builder {
        private Set<String> endpoints;
        private Map<String, String> configuration;

        public Builder() {
            this.endpoints = new HashSet<>();
            this.configuration = new HashMap<String, String>();
            // TODO: set defaults configuration values
            this.setConnectTimeout(30, SECONDS)
                .setSocketTimeout(2, SECONDS)
                .setSocketRcvbuf(804800)
                .setSocketSndbuf(804800);
        }

        public Builder addEndpoint(String endpoint) {
            // TODO: validate endpoint
            this.endpoints.add(endpoint);
            return this;
        }

        public Builder addEndpoint(Protocol protocol, String host, int port) {
            String endpoint = String.format("%s://%s:%d", protocol.toString().toLowerCase(), host, port);
            this.addEndpoint(endpoint);
            return this;
        }

        public Builder addConfiguration(String key, String value) {
            this.configuration.put(key, value);
            return this;
        }

        public Builder addUsername(String username) {
            this.configuration.put("user", username);
            return this;
        }

        public Builder addPassword(String password) {
            this.configuration.put("password", password);
            return this;
        }
        // SOCKET SETTINGS
        public Builder setConnectTimeout(long size) {
            this.configuration.put("connect_timeout", String.valueOf(size));
            return this;
        }
        public Builder setConnectTimeout(long amount, ChronoUnit unit) {
            this.setConnectTimeout(Duration.of(amount, unit).toMillis());
            return this;
        }

        public Builder setSocketTimeout(long size) {
            this.configuration.put("socket_timeout", String.valueOf(size));
            return this;
        }
        public Builder setSocketTimeout(long amount, ChronoUnit unit) {
            this.setSocketTimeout(Duration.of(amount, unit).toMillis());
            return this;
        }
        public Builder setSocketRcvbuf(long size) {
            this.configuration.put("socket_rcvbuf", String.valueOf(size));
            return this;
        }
        public Builder setSocketSndbuf(long size) {
            this.configuration.put("socket_sndbuf", String.valueOf(size));
            return this;
        }
        public Builder setSocketReuseaddr(boolean value) {
            this.configuration.put("socket_reuseaddr", String.valueOf(value));
            return this;
        }
        public Builder setSocketKeepalive(boolean value) {
            this.configuration.put("socket_keepalive", String.valueOf(value));
            return this;
        }
        public Builder setSocketTcpNodelay(boolean value) {
            this.configuration.put("socket_tcp_nodelay", String.valueOf(value));
            return this;
        }
        public Builder setSocketLinger(int secondsToWait) {
            this.configuration.put("socket_linger", String.valueOf(secondsToWait));
            return this;
        }
        public Client build() {
            // check if endpoint are empty. so can not initiate client
            if (this.endpoints.isEmpty()) {
                throw new IllegalArgumentException("At least one endpoint is required");
            }
            // check if username and password are empty. so can not initiate client?
            return new Client(this.endpoints, this.configuration);
        }
    }

    private ClickHouseNode getServerNode() {
        // TODO: implement load balancing using existing logic
        return this.serverNodes.get(0);
    }

    /**
     * Ping the server to check if it is alive
     * @return true if the server is alive, false otherwise
     */
    public boolean ping() {
        return ping(Client.TIMEOUT);
    }

    /**
     * Ping the server to check if it is alive
     * @param timeout timeout in milliseconds
     * @return true if the server is alive, false otherwise
     */
    public boolean ping(int timeout) {
        ClickHouseClient clientPing = ClickHouseClient.newInstance(ClickHouseProtocol.HTTP);
        return clientPing.ping(getServerNode(), timeout);
    }

    /**
     * Register the POJO
     */
    public void register(Class<?> clazz, TableSchema schema) {
        //Create a new POJOSerializer with static .serialize(object, columns) methods
        Map<String, Method> getters = new HashMap<>();
        Map<ClickHouseColumn, POJOSerializer> serializers = new HashMap<>();

        //Retrieve all methods
        for (Method method: clazz.getMethods()) {
            String methodName = method.getName();
            if (method.getName().startsWith("get") &&
                    method.getParameterCount() == 0 &&
                    method.getReturnType() != void.class) {//Make sure they're getter methods
                String fieldName = methodName.substring(3);//Get the field name
                ClickHouseColumn column = schema.getColumnByName(fieldName);
                if(column != null) {//Check if the field is in the schema
                    getters.put(fieldName, method);
                    serializers.put(column, (obj, stream) -> {//Create the field serializer
                        Method getter = this.getters.get(clazz).get(fieldName);
                        Object value = getter.invoke(obj);

                        //Handle null values
                        if (value == null) {
                            BinaryStreamUtils.writeNull(stream);
                            return;
                        } else {//If nullable, we have to specify that the value is not null
                            if (column.isNullable()) {
                                BinaryStreamUtils.writeNonNull(stream);
                            }
                        }

                        //Handle the different types
                        SerializerUtils.serializeData(stream, value, column);
                    });
                }
            }
        }

        this.serializers.put(clazz, serializers);
        this.getters.put(clazz, getters);
    }

    /**
     * Insert data into ClickHouse using a POJO
     */
    public Future<InsertResponse> insert(String tableName,
                                         List<Object> data,
                                         InsertSettings settings) throws ClickHouseException, IOException, InvocationTargetException, IllegalAccessException {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Data cannot be empty");
        }

        CompletableFuture<InsertResponse> future = new CompletableFuture<>();
        try (ClickHouseClient client = createClient()) {
            ClickHouseRequest.Mutation request = client.write(getServerNode());
            if (settings != null) {
                if (settings.getSetting("query_id") != null) {
                    request.table(tableName, settings.getSetting("query_id").toString());
                } else {
                    request.table(tableName);
                }

                if (settings.getSetting("insert_deduplication_token") != null) {
                    request.set("insert_deduplication_token", settings.getSetting("insert_deduplication_token").toString());
                }
            }

            try(ClickHousePipedOutputStream stream = ClickHouseDataStreamFactory.getInstance().createPipedOutputStream(new ClickHouseConfig())) {
                //Lookup the Serializer for the POJO
                //Call the static .serialize method on the POJOSerializer for each object in the list
                Map<ClickHouseColumn, POJOSerializer> serializers = this.serializers.get(data.get(0).getClass());
                if (serializers == null || serializers.isEmpty()) {
                    throw new IllegalArgumentException("No serializer found for the given class. Please register() before calling this method.");
                }

                future = CompletableFuture.completedFuture(new InsertResponse(client, request.data(stream.getInputStream()).execute()));
                for (Object obj : data) {
                    for (POJOSerializer serializer : serializers.values()) {
                        serializer.serialize(obj, stream);
                    }
                }
            }
        }

        return future;
    }

    /**
     * Insert data into ClickHouse using a binary stream
     */
    public Future<InsertResponse> insert(String tableName,
                                     InputStream data,
                                     InsertSettings settings) throws ClickHouseException, SocketException {
        return null;//This is just a placeholder
    }


    /**
     * Sends data query to the server and returns a reference to a result descriptor.
     * Control is returned when server accepted the query and started processing it.
     * <br/>
     * The caller should use {@link ClickHouseParameterizedQuery} to render the `sqlQuery` with parameters.
     *
     *
     * @param sqlQuery - complete SQL query.
     * @param settings
     * @return
     */
    public Future<QueryResponse> query(String sqlQuery, Map<String, Object> qparams, QuerySettings settings) {
        ClickHouseClient client = createClient();
        ClickHouseRequest<?> request = client.read(getServerNode());
        request.options(SettingsConverter.toRequestOptions(settings.getAllSettings()));
        request.settings(SettingsConverter.toRequestSettings(settings.getAllSettings()));
        request.query(sqlQuery, settings.getQueryID());
        request.format(ClickHouseFormat.valueOf(settings.getFormat()));
        if (qparams != null && !qparams.isEmpty()) {
            request.params(qparams);
        }
        MDC.put("queryId", settings.getQueryID());
        LOG.debug("Executing request: {}", request);
        return CompletableFuture.completedFuture(new QueryResponse(client, request.execute()));
    }

    public TableSchema getTableSchema(String table, String database) {
        try (ClickHouseClient clientQuery = createClient()) {
            ClickHouseRequest request = clientQuery.read(getServerNode());
            // XML - because java has a built-in XML parser. Will consider CSV later.
            request.query("DESCRIBE TABLE " + table + " FORMAT " + ClickHouseFormat.TSKV.name());
            TableSchema tableSchema = new TableSchema();
            try {
                return new TableSchemaParser().createFromBinaryResponse(clientQuery.execute(request).get(), table, database);
            } catch (Exception e) {
                throw new RuntimeException("Failed to get table schema", e);
            }
        }
    }


    private ClickHouseClient createClient() {
        ClickHouseConfig clientConfig = new ClickHouseConfig();
        return ClickHouseClient.builder().config(clientConfig)
                .nodeSelector(ClickHouseNodeSelector.of(ClickHouseProtocol.HTTP))
                .build();
    }

    private static final Set<String> COMPRESS_ALGORITHMS = ValidationUtils.whiteList("LZ4", "LZ4HC", "ZSTD", "ZSTDHC", "NONE");

    public static Set<String> getCompressAlgorithms() {
        return COMPRESS_ALGORITHMS;
    }

    private static final Set<String> OUTPUT_FORMATS = createFormatWhitelist("output");

    private static final Set<String> INPUT_FORMATS = createFormatWhitelist("input");

    public static Set<String> getOutputFormats() {
        return OUTPUT_FORMATS;
    }

    private static Set<String> createFormatWhitelist(String shouldSupport) {
        Set<String> formats = new HashSet<>();
        boolean supportOutput = "output".equals(shouldSupport);
        boolean supportInput = "input".equals(shouldSupport);
        boolean supportBoth = "both".equals(shouldSupport);
        for (ClickHouseFormat format : ClickHouseFormat.values()) {
            if ((supportOutput && format.supportsOutput()) || (supportInput && format.supportsInput()) || (supportBoth)) {
                formats.add(format.name());
            }
        }
        return Collections.unmodifiableSet(formats);
    }
}
