/*
 * Drizzle JDBC
 *
 * Copyright (C) 2009 Marcus Eriksson (krummas@gmail.com)
 * All rights reserved.
 *
 * Use and distribution licensed under the BSD license.
 */

package org.drizzle.jdbc.internal.mysql;

import org.drizzle.jdbc.internal.SQLExceptionMapper;
import org.drizzle.jdbc.internal.common.BinlogDumpException;
import org.drizzle.jdbc.internal.common.ColumnInformation;
import org.drizzle.jdbc.internal.common.PacketFetcher;
import org.drizzle.jdbc.internal.common.Protocol;
import org.drizzle.jdbc.internal.common.QueryException;
import org.drizzle.jdbc.internal.common.SupportedDatabases;
import org.drizzle.jdbc.internal.common.ValueObject;
import org.drizzle.jdbc.internal.common.packet.EOFPacket;
import org.drizzle.jdbc.internal.common.packet.ErrorPacket;
import org.drizzle.jdbc.internal.common.packet.OKPacket;
import org.drizzle.jdbc.internal.common.packet.RawPacket;
import org.drizzle.jdbc.internal.common.packet.ResultPacket;
import org.drizzle.jdbc.internal.common.packet.ResultPacketFactory;
import org.drizzle.jdbc.internal.common.packet.ResultSetPacket;
import org.drizzle.jdbc.internal.common.packet.SyncPacketFetcher;
import org.drizzle.jdbc.internal.common.packet.buffer.ReadUtil;
import org.drizzle.jdbc.internal.common.packet.commands.ClosePacket;
import org.drizzle.jdbc.internal.common.packet.commands.SelectDBPacket;
import org.drizzle.jdbc.internal.common.packet.commands.StreamedQueryPacket;
import org.drizzle.jdbc.internal.common.query.DrizzleQuery;
import org.drizzle.jdbc.internal.common.query.Query;
import org.drizzle.jdbc.internal.common.queryresults.DrizzleQueryResult;
import org.drizzle.jdbc.internal.common.queryresults.DrizzleUpdateResult;
import org.drizzle.jdbc.internal.common.queryresults.NoSuchColumnException;
import org.drizzle.jdbc.internal.common.queryresults.QueryResult;
import org.drizzle.jdbc.internal.mysql.packet.MySQLFieldPacket;
import org.drizzle.jdbc.internal.mysql.packet.MySQLGreetingReadPacket;
import org.drizzle.jdbc.internal.mysql.packet.MySQLRowPacket;
import org.drizzle.jdbc.internal.mysql.packet.commands.MySQLBinlogDumpPacket;
import org.drizzle.jdbc.internal.mysql.packet.commands.MySQLClientAuthPacket;
import org.drizzle.jdbc.internal.mysql.packet.commands.MySQLPingPacket;

import javax.net.SocketFactory;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

import static org.drizzle.jdbc.internal.common.packet.buffer.WriteBuffer.intToByteArray;

/**
 * TODO: refactor, clean up TODO: when should i read up the resultset? TODO: thread safety? TODO: exception handling
 * User: marcuse Date: Jan 14, 2009 Time: 4:06:26 PM
 */
public class MySQLProtocol implements Protocol {
    private final static Logger log = Logger.getLogger(MySQLProtocol.class.getName());
    private boolean connected = false;
    private final Socket socket;
    private final BufferedOutputStream writer;
    private final String version;
    private boolean readOnly = false;
    private final String host;
    private final int port;
    private String database;
    private final String username;
    private final String password;
    private final List<Query> batchList;
    private final PacketFetcher packetFetcher;
    private final Properties info;

    /**
     * Get a protocol instance
     *
     * @param host     the host to connect to
     * @param port     the port to connect to
     * @param database the initial database
     * @param username the username
     * @param password the password
     * @param info
     * @throws org.drizzle.jdbc.internal.common.QueryException
     *          if there is a problem reading / sending the packets
     */
    public MySQLProtocol(final String host,
                         final int port,
                         final String database,
                         final String username,
                         final String password,
                         Properties info)
            throws QueryException {
        this.info = info;
        this.host = host;
        this.port = port;
        this.database = (database == null ? "" : database);
        this.username = (username == null ? "" : username);
        this.password = (password == null ? "" : password);

        final SocketFactory socketFactory = SocketFactory.getDefault();
        try {
            socket = socketFactory.createSocket(host, port);
        } catch (IOException e) {
            throw new QueryException("Could not connect: " + e.getMessage(),
                    -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState(),
                    e);
        }
        batchList = new ArrayList<Query>();
        try {
            final BufferedInputStream reader = new BufferedInputStream(socket.getInputStream(), 32768);
            packetFetcher = new SyncPacketFetcher(reader);
            writer = new BufferedOutputStream(socket.getOutputStream(), 32768);
            final MySQLGreetingReadPacket greetingPacket = new MySQLGreetingReadPacket(packetFetcher.getRawPacket());
            log.finest("Got greeting packet");
            this.version = greetingPacket.getServerVersion();
            
            final Set<MySQLServerCapabilities> capabilities = EnumSet.of(MySQLServerCapabilities.LONG_PASSWORD,
                    MySQLServerCapabilities.IGNORE_SPACE,
                    MySQLServerCapabilities.CLIENT_PROTOCOL_41,
                    MySQLServerCapabilities.TRANSACTIONS,
                    MySQLServerCapabilities.SECURE_CONNECTION,
                    MySQLServerCapabilities.LOCAL_FILES);

            // If a database is given, but createDB is not defined or is false,
            // then just try to connect to the given database
            if(this.database != null && !createDB())
                capabilities.add(MySQLServerCapabilities.CONNECT_WITH_DB);

            final MySQLClientAuthPacket cap = new MySQLClientAuthPacket(this.username,
                    this.password,
                    this.database,
                    capabilities,
                    greetingPacket.getSeed());
            cap.send(writer);
            log.finest("Sending auth packet");

            final RawPacket rp = packetFetcher.getRawPacket();
            final ResultPacket resultPacket = ResultPacketFactory.createResultPacket(rp);
            if (resultPacket.getResultType() == ResultPacket.ResultType.ERROR) {
                final ErrorPacket ep = (ErrorPacket) resultPacket;
                final String message = ep.getMessage();
                throw new QueryException("Could not connect: " + message);
            }

            // At this point, the driver is connected to the database, if createDB is true, 
            // then just try to create the database and to use it
            if(createDB())
            {
                // Try to create the database if it does not exist
                executeQuery(new DrizzleQuery("CREATE DATABASE IF NOT EXISTS " + this.database));
                // and switch to this database
                executeQuery(new DrizzleQuery("USE " + this.database));
            }

            connected = true;
        } catch (IOException e) {
            throw new QueryException("Could not connect: " + e.getMessage(),
                    -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState(),
                    e);
        }
    }

    /**
     * Closes socket and stream readers/writers
     *
     * @throws org.drizzle.jdbc.internal.common.QueryException
     *          if the socket or readers/writes cannot be closed
     */
    public void close() throws QueryException {
        try {
            final ClosePacket closePacket = new ClosePacket();
            closePacket.send(writer);
            writer.close();
            packetFetcher.close();
        } catch (IOException e) {
            throw new QueryException("Could not close connection: " + e.getMessage(),
                    -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState(),
                    e);
        } finally {
            try {
                this.connected = false;
                socket.close();
            } catch (IOException e) {
                log.warning("Could not close socket");
            }
        }
        this.connected = false;
    }

    /**
     * @return true if the connection is closed
     */
    public boolean isClosed() {
        return !this.connected;
    }

    /**
     * create a DrizzleQueryResult - precondition is that a result set packet has been read
     *
     * @param packet the result set packet from the server
     * @return a DrizzleQueryResult
     * @throws java.io.IOException when something goes wrong while reading/writing from the server
     */
    private QueryResult createDrizzleQueryResult(final ResultSetPacket packet) throws IOException, QueryException {
        final List<ColumnInformation> columnInformation = new ArrayList<ColumnInformation>();
        for (int i = 0; i < packet.getFieldCount(); i++) {
            final RawPacket rawPacket = packetFetcher.getRawPacket();
            final ColumnInformation columnInfo = MySQLFieldPacket.columnInformationFactory(rawPacket);
            columnInformation.add(columnInfo);
        } packetFetcher.getRawPacket();
        final List<List<ValueObject>> valueObjects = new ArrayList<List<ValueObject>>();

        while (true) {
            final RawPacket rawPacket = packetFetcher.getRawPacket();

            if(ReadUtil.isErrorPacket(rawPacket)) {
                ErrorPacket errorPacket = (ErrorPacket) ResultPacketFactory.createResultPacket(rawPacket);
                throw new QueryException(errorPacket.getMessage(), errorPacket.getErrorNumber(),errorPacket.getSqlState());
            }
            if (ReadUtil.eofIsNext(rawPacket)) {
                final EOFPacket eofPacket = (EOFPacket) ResultPacketFactory.createResultPacket(rawPacket);
                return new DrizzleQueryResult(columnInformation, valueObjects, eofPacket.getWarningCount());
            }
            final MySQLRowPacket rowPacket = new MySQLRowPacket(rawPacket, columnInformation);
            valueObjects.add(rowPacket.getRow());
        }
    }

    public void selectDB(final String database) throws QueryException {
        log.finest("Selecting db " + database);
        final SelectDBPacket packet = new SelectDBPacket(database);
        try {
            packet.send(writer);
            final RawPacket rawPacket = packetFetcher.getRawPacket();
            ResultPacketFactory.createResultPacket(rawPacket);
        } catch (IOException e) {
            throw new QueryException("Could not select database: " + e.getMessage(),
                    -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState(),
                    e);
        }
        this.database = database;
    }

    public String getServerVersion() {
        return version;
    }

    public void setReadonly(final boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean getReadonly() {
        return readOnly;
    }

    public void commit() throws QueryException {
        log.finest("commiting transaction");
        executeQuery(new DrizzleQuery("COMMIT"));
    }

    public void rollback() throws QueryException {
        log.finest("rolling transaction back");
        executeQuery(new DrizzleQuery("ROLLBACK"));
    }

    public void rollback(final String savepoint) throws QueryException {
        log.finest("rolling back to savepoint " + savepoint);
        executeQuery(new DrizzleQuery("ROLLBACK TO SAVEPOINT " + savepoint));
    }

    public void setSavepoint(final String savepoint) throws QueryException {
        executeQuery(new DrizzleQuery("SAVEPOINT " + savepoint));
    }

    public void releaseSavepoint(final String savepoint) throws QueryException {
        executeQuery(new DrizzleQuery("RELEASE SAVEPOINT " + savepoint));
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean ping() throws QueryException {
        final MySQLPingPacket pingPacket = new MySQLPingPacket();
        try {
            pingPacket.send(writer);
            log.finest("Sent ping packet");
            final RawPacket rawPacket = packetFetcher.getRawPacket();
            return ResultPacketFactory.createResultPacket(rawPacket).getResultType() == ResultPacket.ResultType.OK;
        } catch (IOException e) {
            throw new QueryException("Could not ping: " + e.getMessage(),
                    -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState(),
                    e);
        }
    }

    public QueryResult executeQuery(final Query dQuery) throws QueryException {
        log.finest("Executing streamed query: " + dQuery);
        final StreamedQueryPacket packet = new StreamedQueryPacket(dQuery);

        try {
            packet.send(writer);
        } catch (IOException e) {
            throw new QueryException("Could not send query: " + e.getMessage(),
                    -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState(),
                    e);
        }

        final RawPacket rawPacket;
        final ResultPacket resultPacket;
        try {
            rawPacket = packetFetcher.getRawPacket();
            resultPacket = ResultPacketFactory.createResultPacket(rawPacket);
        } catch (IOException e) {
            throw new QueryException("Could not read resultset: " + e.getMessage(),
                    -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState(),
                    e);
        }


        switch (resultPacket.getResultType()) {
            case ERROR:
                final ErrorPacket ep = (ErrorPacket) resultPacket;
                log.warning("Could not execute query " + dQuery + ": " + ((ErrorPacket) resultPacket).getMessage());
                throw new QueryException(ep.getMessage(),
                        ep.getErrorNumber(),
                        ep.getSqlState());
            case OK:
                final OKPacket okpacket = (OKPacket) resultPacket;
                final QueryResult updateResult = new DrizzleUpdateResult(okpacket.getAffectedRows(),
                        okpacket.getWarnings(),
                        okpacket.getMessage(),
                        okpacket.getInsertId());
                log.fine("OK, " + okpacket.getAffectedRows());
                return updateResult;
            case RESULTSET:
                log.fine("SELECT executed, fetching result set");
                try {
                    return this.createDrizzleQueryResult((ResultSetPacket) resultPacket);
                } catch (IOException e) {
                    throw new QueryException("Could not read result set: " + e.getMessage(),
                            -1,
                            SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION.getSqlState(),
                            e);
                }
            default:
                log.severe("Could not parse result...");
                throw new QueryException("Could not parse result");
        }

    }

    public void addToBatch(final Query dQuery) {
        batchList.add(dQuery);
    }

    public List<QueryResult> executeBatch() throws QueryException {
        final List<QueryResult> retList = new ArrayList<QueryResult>(batchList.size());

        for (final Query query : batchList) {
            retList.add(executeQuery(query));
        }
        clearBatch();
        return retList;

    }

    public void clearBatch() {
        batchList.clear();
    }

    public List<RawPacket> startBinlogDump(final int startPos, final String filename) throws BinlogDumpException {
        final MySQLBinlogDumpPacket mbdp = new MySQLBinlogDumpPacket(startPos, filename);
        try {
            mbdp.send(writer);
            final List<RawPacket> rpList = new LinkedList<RawPacket>();
            while (true) {
                final RawPacket rp = this.packetFetcher.getRawPacket();
                if (ReadUtil.eofIsNext(rp)) {
                    return rpList;
                }
                rpList.add(rp);
            }
        } catch (IOException e) {
            throw new BinlogDumpException("Could not read binlog", e);
        }
    }

    public SupportedDatabases getDatabaseType() {
        return SupportedDatabases.fromVersionString(version);
    }

    public boolean supportsPBMS() {
        return info != null && info.getProperty("enableBlobStreaming","").equalsIgnoreCase("true");
    }

    public String getServerVariable(String variable) throws QueryException {
        DrizzleQueryResult qr = (DrizzleQueryResult) executeQuery(new DrizzleQuery("select @@"+variable));
        if(!qr.next()) {
            throw new QueryException("Could not get variable: "+variable);
        }

        try {
            String value = qr.getValueObject(0).getString();
            return value;
        } catch (NoSuchColumnException e) {
            throw new QueryException("Could not get variable: "+variable);
        }
    }
    @Override
    public QueryResult executeQuery(Query dQuery,
            FileInputStream fileInputStream) throws QueryException
    {
        int packIndex = 0;
        
        log.finest("Executing streamed query: " + dQuery);
        final StreamedQueryPacket packet = new StreamedQueryPacket(dQuery);

        try
        {
            packIndex = packet.send(writer);
            packIndex++;
        }
        catch (IOException e)
        {
            throw new QueryException("Could not send query: " + e.getMessage(),
                    -1, SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION
                            .getSqlState(), e);
        }

        RawPacket rawPacket;
        ResultPacket resultPacket;

        try
        {
            rawPacket = packetFetcher.getRawPacket();
            resultPacket = ResultPacketFactory.createResultPacket(rawPacket);
        }
        catch (IOException e)
        {
            throw new QueryException("Could not read resultset: "
                    + e.getMessage(), -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION
                            .getSqlState(), e);
        }
        
        if(rawPacket.getPacketSeq() != packIndex)
            throw new QueryException("Got out of order packet ", -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION
                            .getSqlState(), null);

        switch (resultPacket.getResultType())
        {
            case ERROR :
                final ErrorPacket ep = (ErrorPacket) resultPacket;
                log.warning("Could not execute query " + dQuery + ": "
                        + ((ErrorPacket) resultPacket).getMessage());
                throw new QueryException(ep.getMessage(), ep.getErrorNumber(),
                        ep.getSqlState());
            case OK :
                break;
            case RESULTSET :
                break;
            default :
                log.severe("Could not parse result...");
                throw new QueryException("Could not parse result");
        }

        packIndex++;
        return sendFile(dQuery, fileInputStream, packIndex);
    }
    
    @Override
    public boolean createDB() {
        return info != null
               && info.getProperty("createDB", "").equalsIgnoreCase("true");
    }
    
    
    /**
     * Send the given file to the server starting with packet number packIndex
     * 
     * @param dQuery the query that was first issued
     * @param fileInputStream input stream used to read the file
     * @param packIndex Starting index, which will be used for sending packets
     * @return the result of the query execution
     * @throws QueryException if something wrong happens
     */
    private QueryResult sendFile(Query dQuery, FileInputStream fileInputStream,
            int packIndex) throws QueryException
    {
        byte[] emptyHeader =  Arrays.copyOf(intToByteArray(0), 4);
        RawPacket rawPacket;
        ResultPacket resultPacket;
        
        BufferedInputStream bufferedInputStream = new BufferedInputStream(
                fileInputStream);

        ByteArrayOutputStream bOS = new ByteArrayOutputStream();

        try
        {
            while (true)
            {
                int data = bufferedInputStream.read();
                if (data == -1)
                {
                    // Send the last packet
                    byte[] data1 = bOS.toByteArray();
                    byte[] byteHeader = Arrays.copyOf(
                            intToByteArray(data1.length), 4);
                    byteHeader[3] = (byte) packIndex;

                    log.finest("Sending : " + MySQLProtocol.hexdump(byteHeader, 0) + " - data length = " + data1.length);

                    // Send the packet
                    writer.write(byteHeader);
                    writer.write(data1);
                    writer.flush();
                    packIndex++;
                    break;
                }
                
                // Add data into buffer
                bOS.write(data);
                
                if(bOS.size() >= 0xffffff)
                {
                    byte[] byteHeader = Arrays.copyOf(intToByteArray(bOS.size()), 4);
                    byteHeader[3] = (byte) packIndex;
                    
                    log.finest("Sending : " + MySQLProtocol.hexdump(byteHeader, 0));
                    // Send the packet
                    writer.write(byteHeader);
                    
                    bOS.writeTo(writer);
                    writer.flush();
                    packIndex++;                    
                    break;

                }
            }
        }
        catch (IOException e)
        {
            throw new QueryException("Could not send query: " + e.getMessage(),
                    -1, SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION
                            .getSqlState(), e);
        }
        try
        {
            emptyHeader[3] = (byte) packIndex;
            writer.write(emptyHeader);
            log.finest("Sending : " + MySQLProtocol.hexdump(emptyHeader, 0));
            writer.flush();
        }
        catch (IOException e)
        {
            throw new QueryException("Could not send query: " + e.getMessage(),
                    -1, SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION
                            .getSqlState(), e);
        }

        try
        {
            rawPacket = packetFetcher.getRawPacket();
            resultPacket = ResultPacketFactory.createResultPacket(rawPacket);
        }
        catch (IOException e)
        {
            throw new QueryException("Could not read resultset: "
                    + e.getMessage(), -1,
                    SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION
                            .getSqlState(), e);
        }

        switch (resultPacket.getResultType())
        {
            case ERROR :
                final ErrorPacket ep = (ErrorPacket) resultPacket;
                throw new QueryException(ep.getMessage(), ep.getErrorNumber(),
                        ep.getSqlState());
            case OK :
                final OKPacket okpacket = (OKPacket) resultPacket;
                final QueryResult updateResult = new DrizzleUpdateResult(
                        okpacket.getAffectedRows(), okpacket.getWarnings(),
                        okpacket.getMessage(), okpacket.getInsertId());
                log.fine("OK, " + okpacket.getAffectedRows());
                return updateResult;
            case RESULTSET :
                log.fine("SELECT executed, fetching result set");
                try
                {
                    return this
                            .createDrizzleQueryResult((ResultSetPacket) resultPacket);
                }
                catch (IOException e)
                {
                    throw new QueryException("Could not read result set: "
                            + e.getMessage(), -1,
                            SQLExceptionMapper.SQLStates.CONNECTION_EXCEPTION
                                    .getSqlState(), e);
                }
            default :
                log.severe("Could not parse result...");
                throw new QueryException("Could not parse result");
        }
    }
    public static String hexdump(byte[] buffer, int offset)
    {
        StringBuffer dump = new StringBuffer();
        if ((buffer.length - offset) > 0)
        {
            dump.append(String.format("%02x", buffer[offset]));
            for (int i = offset + 1; i < buffer.length; i++)
            {
                dump.append("_");
                dump.append(String.format("%02x", buffer[i]));
            }
        }
        return dump.toString();
    }
    public static String hexdump(ByteBuffer bb, int offset) {
        byte [] b = new byte[bb.capacity()];
        bb.mark();
        bb.get(b);
        bb.reset();
        return hexdump(b,offset);
    }
}
