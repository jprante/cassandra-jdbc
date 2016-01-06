/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cassandra.cql.jdbc;

import org.apache.cassandra.thrift.CqlPreparedResult;
import org.apache.cassandra.thrift.CqlResult;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.SchemaDisagreementException;
import org.apache.cassandra.thrift.TimedOutException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.cassandra.cql.jdbc.Utils.NO_RESULTSET;
import static org.apache.cassandra.cql.jdbc.Utils.NO_SERVER;
import static org.apache.cassandra.cql.jdbc.Utils.NO_UPDATE_COUNT;
import static org.apache.cassandra.cql.jdbc.Utils.SCHEMA_MISMATCH;

class CassandraPreparedStatement extends CassandraStatement implements PreparedStatement {
    private static final Logger LOG = LoggerFactory.getLogger(CassandraPreparedStatement.class);

    /**
     * the key token passed back from server-side to identify the prepared statement
     */
    private int itemId;

    /**
     * the count of bound variable markers (?) encountered in the parse o the CQL server-side
     */
    private int count;

    /**
     * a Map of the current bound values encountered in setXXX methods
     */
    private Map<Integer, ByteBuffer> bindValues = new LinkedHashMap<Integer, ByteBuffer>();


    CassandraPreparedStatement(CassandraConnection con, String cql) throws SQLException {
        this(con, cql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }


    CassandraPreparedStatement(CassandraConnection con,
                               String cql,
                               int rsType,
                               int rsConcurrency,
                               int rsHoldability
    ) throws SQLException {
        super(con, cql, rsType, rsConcurrency, rsHoldability);
        if (LOG.isTraceEnabled()) {
            LOG.trace("CQL: " + this.cql);
        }
        try {
            CqlPreparedResult result = con.prepare(cql);

            itemId = result.itemId;
            count = result.count;
        } catch (InvalidRequestException e) {
            throw new SQLSyntaxErrorException(e);
        } catch (TException e) {
            throw new SQLNonTransientConnectionException(e);
        }
    }

    String getCql() {
        return cql;
    }

    private final void checkIndex(int index) throws SQLException {
        if (index > count) {
            throw new SQLRecoverableException(String.format("the column index : %d is greater than the count of bound variable markers in the CQL: %d",
                    index,
                    count));
        }
        if (index < 1) {
            throw new SQLRecoverableException(String.format("the column index must be a positive number : %d", index));
        }
    }

    private List<ByteBuffer> getBindValues() throws SQLException {
        List<ByteBuffer> values = new ArrayList<ByteBuffer>();
        if (bindValues.size() != count) {
            throw new SQLRecoverableException(
                    String.format("the number of bound variables: %d must match the count of bound variable markers in the CQL: %d",
                            bindValues.size(),
                            count));
        }

        for (int i = 1; i <= count; i++) {
            ByteBuffer value = bindValues.get(i);
            if (value == null) {
                throw new SQLRecoverableException(String.format("the bound value for index: %d was not set", i));
            }
            values.add(value);
        }
        return values;
    }


    public void close() {
        try {
            connection.removeStatement(this);
        } catch (Exception e) {

        }

        connection = null;
    }

    private void doExecute() throws SQLException {
        if (LOG.isTraceEnabled()) {
            LOG.trace("CQL: " + cql);
        }
        try {
            resetResults();
            CqlResult result = connection.execute(itemId, getBindValues(), consistencyLevel);

            switch (result.getType()) {
                case ROWS:
                    currentResultSet = new CassandraResultSet(this, result);
                    break;
                case INT:
                    updateCount = result.getNum();
                    break;
                case VOID:
                    updateCount = 0;
                    break;
            }
        } catch (InvalidRequestException e) {
            throw new SQLSyntaxErrorException(e.getWhy() + "\n'" + cql + "'", e);
        } catch (UnavailableException e) {
            try {
                this.connection.disconnect();
            } catch (Exception e1) {

            }
            throw new SQLNonTransientConnectionException(NO_SERVER, e);
        } catch (TimedOutException e) {
            throw new SQLTransientConnectionException(e);
        } catch (SchemaDisagreementException e) {
            throw new SQLRecoverableException(SCHEMA_MISMATCH);
        } catch (TException e) {
            try {
                this.connection.disconnect();
            } catch (Exception e1) {

            }
            throw new SQLNonTransientConnectionException(e);
        }
    }

    public void addBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException(NOT_SUPPORTED);
    }


    public void clearParameters() throws SQLException {
        checkNotClosed();
        bindValues.clear();
    }


    public boolean execute() throws SQLException {
        checkNotClosed();
        doExecute();
        return !(currentResultSet == null);
    }


    public ResultSet executeQuery() throws SQLException {
        checkNotClosed();
        doExecute();
        if (currentResultSet == null) {
            throw new SQLNonTransientException(NO_RESULTSET);
        }
        return currentResultSet;
    }


    public int executeUpdate() throws SQLException {
        checkNotClosed();
        doExecute();
        if (currentResultSet != null) {
            throw new SQLNonTransientException(NO_UPDATE_COUNT);
        }
        return updateCount;
    }


    public ResultSetMetaData getMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException(NOT_SUPPORTED);
    }


    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw new SQLFeatureNotSupportedException(NOT_SUPPORTED);
    }


    public void setBigDecimal(int parameterIndex, BigDecimal decimal) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, decimal == null ? ByteBufferUtil.EMPTY_BYTE_BUFFER : JdbcDecimal.instance.decompose(decimal));
    }


    public void setBoolean(int parameterIndex, boolean truth) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, JdbcBoolean.instance.decompose(truth));
    }


    public void setByte(int parameterIndex, byte b) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, JdbcInteger.instance.decompose(BigInteger.valueOf(b)));
    }


    public void setBytes(int parameterIndex, byte[] bytes) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, bytes == null ? ByteBufferUtil.EMPTY_BYTE_BUFFER : ByteBuffer.wrap(bytes));
    }


    public void setDate(int parameterIndex, Date value) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        // date type data is handled as an 8 byte Long value of milliseconds since the epoch (handled in decompose() )
        bindValues.put(parameterIndex, value == null ? ByteBufferUtil.EMPTY_BYTE_BUFFER : JdbcDate.instance.decompose(value));
    }


    public void setDate(int parameterIndex, Date date, Calendar cal) throws SQLException {
        // silently ignore the calendar argument it is not useful for the Cassandra implementation
        setDate(parameterIndex, date);
    }


    public void setDouble(int parameterIndex, double decimal) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, JdbcDouble.instance.decompose(decimal));
    }


    public void setFloat(int parameterIndex, float decimal) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, JdbcFloat.instance.decompose(decimal));
    }


    public void setInt(int parameterIndex, int integer) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, JdbcInt32.instance.decompose(integer));
    }


    public void setLong(int parameterIndex, long bigint) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, JdbcLong.instance.decompose(bigint));
    }


    public void setNString(int parameterIndex, String value) throws SQLException {
        // treat like a String
        setString(parameterIndex, value);
    }


    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        // silently ignore type for cassandra... just store an empty String
        bindValues.put(parameterIndex, ByteBufferUtil.EMPTY_BYTE_BUFFER);
    }


    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        // silently ignore type and type name for cassandra... just store an empty BB
        setNull(parameterIndex, sqlType);
    }


    public void setObject(int parameterIndex, Object object) throws SQLException {
        // For now all objects are forced to String type
        setObject(parameterIndex, object, Types.VARCHAR, 0);
    }

    public void setObject(int parameterIndex, Object object, int targetSqlType) throws SQLException {
        setObject(parameterIndex, object, targetSqlType, 0);
    }

    public final void setObject(int parameterIndex, Object object, int targetSqlType, int scaleOrLength) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);

        ByteBuffer variable = HandleObjects.makeBytes(object, targetSqlType, scaleOrLength);

        if (variable == null) {
            throw new SQLNonTransientException("Problem mapping object to JDBC Type");
        }

        bindValues.put(parameterIndex, variable);
    }

    public void setRowId(int parameterIndex, RowId value) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, ByteBuffer.wrap(value.getBytes()));
    }


    public void setShort(int parameterIndex, short smallint) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, JdbcInteger.instance.decompose(BigInteger.valueOf(smallint)));
    }


    public void setString(int parameterIndex, String value) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        bindValues.put(parameterIndex, value == null ? ByteBufferUtil.EMPTY_BYTE_BUFFER : ByteBufferUtil.bytes(value));
    }


    public void setTime(int parameterIndex, Time value) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        // time type data is handled as an 8 byte Long value of milliseconds since the epoch
        bindValues.put(parameterIndex,
                value == null ? ByteBufferUtil.EMPTY_BYTE_BUFFER : JdbcLong.instance.decompose(Long.valueOf(value.getTime())));
    }


    public void setTime(int parameterIndex, Time value, Calendar cal) throws SQLException {
        // silently ignore the calendar argument it is not useful for the Cassandra implementation
        setTime(parameterIndex, value);
    }


    public void setTimestamp(int parameterIndex, Timestamp value) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        // timestamp type data is handled as an 8 byte Long value of milliseconds since the epoch. Nanos are not supported and are ignored
        bindValues.put(parameterIndex,
                value == null ? ByteBufferUtil.EMPTY_BYTE_BUFFER : JdbcLong.instance.decompose(Long.valueOf(value.getTime())));
    }


    public void setTimestamp(int parameterIndex, Timestamp value, Calendar cal) throws SQLException {
        // silently ignore the calendar argument it is not useful for the Cassandra implementation
        setTimestamp(parameterIndex, value);
    }


    public void setURL(int parameterIndex, URL value) throws SQLException {
        checkNotClosed();
        checkIndex(parameterIndex);
        // URl type data is handled as an string
        String url = value.toString();
        bindValues.put(parameterIndex, value == null ? ByteBufferUtil.EMPTY_BYTE_BUFFER : ByteBufferUtil.bytes(url));
    }
}
