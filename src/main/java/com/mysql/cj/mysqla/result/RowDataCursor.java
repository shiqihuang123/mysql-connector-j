/*
  Copyright (c) 2002, 2016, Oracle and/or its affiliates. All rights reserved.

  The MySQL Connector/J is licensed under the terms of the GPLv2
  <http://www.gnu.org/licenses/old-licenses/gpl-2.0.html>, like most MySQL Connectors.
  There are special exceptions to the terms and conditions of the GPLv2 as it is applied to
  this software, see the FOSS License Exception
  <http://www.mysql.com/about/legal/licensing/foss-exception.html>.

  This program is free software; you can redistribute it and/or modify it under the terms
  of the GNU General Public License as published by the Free Software Foundation; version 2
  of the License.

  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with this
  program; if not, write to the Free Software Foundation, Inc., 51 Franklin St, Fifth
  Floor, Boston, MA 02110-1301  USA

 */

package com.mysql.cj.mysqla.result;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import com.mysql.cj.api.io.ServerSession;
import com.mysql.cj.api.jdbc.result.ResultSetInternalMethods;
import com.mysql.cj.api.mysqla.io.NativeProtocol.IntegerDataType;
import com.mysql.cj.api.mysqla.io.PacketPayload;
import com.mysql.cj.api.mysqla.result.RowData;
import com.mysql.cj.core.Messages;
import com.mysql.cj.core.exceptions.ExceptionFactory;
import com.mysql.cj.core.result.Field;
import com.mysql.cj.jdbc.ServerPreparedStatement;
import com.mysql.cj.jdbc.result.ResultSetImpl;
import com.mysql.cj.mysqla.MysqlaConstants;
import com.mysql.cj.mysqla.io.MysqlaProtocol;

/**
 * Model for result set data backed by a cursor. Only works for forward-only result sets (but still works with updatable concurrency).
 */
public class RowDataCursor implements RowData {

    private final static int BEFORE_START_OF_ROWS = -1;

    /**
     * The cache of rows we have retrieved from the server.
     */
    private List<ResultSetRow> fetchedRows;

    /**
     * Where we are positionaly in the entire result set, used mostly to
     * facilitate easy 'isBeforeFirst()' and 'isFirst()' methods.
     */
    private int currentPositionInEntireResult = BEFORE_START_OF_ROWS;

    /**
     * Position in cache of rows, used to determine if we need to fetch more
     * rows from the server to satisfy a request for the next row.
     */
    private int currentPositionInFetchedRows = BEFORE_START_OF_ROWS;

    /**
     * The result set that we 'belong' to.
     */
    private ResultSetImpl owner;

    /**
     * Have we been told from the server that we have seen the last row?
     */
    private boolean lastRowFetched = false;

    /**
     * Field-level metadata from the server. We need this, because it is not
     * sent for each batch of rows, but we need the metadata to unpack the
     * results for each field.
     */
    private Field[] metadata;

    private ServerSession serverSession;

    /**
     * Communications channel to the server
     */
    private MysqlaProtocol protocol;

    /**
     * Identifier for the statement that created this cursor.
     */
    private long statementIdOnServer;

    /**
     * The prepared statement that created this cursor.
     */
    private ServerPreparedStatement prepStmt;

    /**
     * Have we attempted to fetch any rows yet?
     */
    private boolean firstFetchCompleted = false;

    private boolean wasEmpty = false;

    /**
     * Creates a new cursor-backed row provider.
     * 
     * @param serverSession
     *            session state
     * @param ioChannel
     *            connection to the server.
     * @param creatingStatement
     *            statement that opened the cursor.
     * @param metadata
     *            field-level metadata for the results that this cursor covers.
     */
    public RowDataCursor(ServerSession serverSession, MysqlaProtocol ioChannel, ServerPreparedStatement creatingStatement, Field[] metadata) {
        this.serverSession = serverSession;
        this.currentPositionInEntireResult = BEFORE_START_OF_ROWS;
        this.metadata = metadata;
        this.protocol = ioChannel;
        this.statementIdOnServer = creatingStatement.getServerStatementId();
        this.prepStmt = creatingStatement;
    }

    public boolean isAfterLast() {
        return this.lastRowFetched && this.currentPositionInFetchedRows > this.fetchedRows.size();
    }

    public boolean isBeforeFirst() {
        return this.currentPositionInEntireResult < 0;
    }

    public int getCurrentRowNumber() {
        return this.currentPositionInEntireResult + 1;
    }

    public boolean isEmpty() {
        return this.isBeforeFirst() && this.isAfterLast();
    }

    public boolean isFirst() {
        return this.currentPositionInEntireResult == 0;
    }

    public boolean isLast() {
        return this.lastRowFetched && this.currentPositionInFetchedRows == (this.fetchedRows.size() - 1);
    }

    public void close() {

        this.metadata = null;
        this.owner = null;
    }

    public boolean hasNext() {

        if (this.fetchedRows != null && this.fetchedRows.size() == 0) {
            return false;
        }

        if (this.owner != null && this.owner.getOwningStatement() != null) {
            int maxRows = this.owner.getOwningStatement().maxRows;

            if (maxRows != -1 && this.currentPositionInEntireResult + 1 > maxRows) {
                return false;
            }
        }

        if (this.currentPositionInEntireResult != BEFORE_START_OF_ROWS) {
            // Case, we've fetched some rows, but are not at end of fetched block
            if (this.currentPositionInFetchedRows < (this.fetchedRows.size() - 1)) {
                return true;
            } else if (this.currentPositionInFetchedRows == this.fetchedRows.size() && this.lastRowFetched) {
                return false;
            } else {
                // need to fetch to determine
                fetchMoreRows();

                return (this.fetchedRows.size() > 0);
            }
        }

        // Okay, no rows _yet_, so fetch 'em

        fetchMoreRows();

        return this.fetchedRows.size() > 0;
    }

    public ResultSetRow next() {
        if (this.fetchedRows == null && this.currentPositionInEntireResult != BEFORE_START_OF_ROWS) {
            throw ExceptionFactory.createException(Messages.getString("ResultSet.Operation_not_allowed_after_ResultSet_closed_144"),
                    this.protocol.getExceptionInterceptor());
        }

        if (!hasNext()) {
            return null;
        }

        this.currentPositionInEntireResult++;
        this.currentPositionInFetchedRows++;

        // Catch the forced scroll-passed-end
        if (this.fetchedRows != null && this.fetchedRows.size() == 0) {
            return null;
        }

        if ((this.fetchedRows == null) || (this.currentPositionInFetchedRows > (this.fetchedRows.size() - 1))) {
            fetchMoreRows();
            this.currentPositionInFetchedRows = 0;
        }

        ResultSetRow row = this.fetchedRows.get(this.currentPositionInFetchedRows);

        row.setMetadata(this.metadata);

        return row;
    }

    private void fetchMoreRows() {
        if (this.lastRowFetched) {
            this.fetchedRows = new ArrayList<ResultSetRow>(0);
            return;
        }

        synchronized (this.owner.getConnection().getConnectionMutex()) {
            try {
                boolean oldFirstFetchCompleted = this.firstFetchCompleted;

                if (!this.firstFetchCompleted) {
                    this.firstFetchCompleted = true;
                }

                int numRowsToFetch = this.owner.getFetchSize();

                if (numRowsToFetch == 0) {
                    numRowsToFetch = this.prepStmt.getFetchSize();
                }

                if (numRowsToFetch == Integer.MIN_VALUE) {
                    // Handle the case where the user used 'old' streaming result sets

                    numRowsToFetch = 1;
                }

                if (this.fetchedRows == null) {
                    this.fetchedRows = new ArrayList<ResultSetRow>(numRowsToFetch);
                } else {
                    this.fetchedRows.clear();
                }

                PacketPayload sharedSendPacket = this.protocol.getSharedSendPacket();
                sharedSendPacket.setPosition(0);

                sharedSendPacket.writeInteger(IntegerDataType.INT1, MysqlaConstants.COM_STMT_FETCH);
                sharedSendPacket.writeInteger(IntegerDataType.INT4, this.statementIdOnServer);
                sharedSendPacket.writeInteger(IntegerDataType.INT4, numRowsToFetch);

                this.protocol.sendCommand(MysqlaConstants.COM_STMT_FETCH, null, sharedSendPacket, true, null, 0);

                ResultSetRow row = null;

                while ((row = this.protocol.getResultsHandler().nextRow(this.metadata, this.metadata.length, true, ResultSet.CONCUR_READ_ONLY,
                        false)) != null) {
                    this.fetchedRows.add(row);
                }

                this.currentPositionInFetchedRows = BEFORE_START_OF_ROWS;

                if (this.serverSession.isLastRowSent()) {
                    this.lastRowFetched = true;

                    if (!oldFirstFetchCompleted && this.fetchedRows.size() == 0) {
                        this.wasEmpty = true;
                    }
                }
            } catch (Exception ex) {
                throw ExceptionFactory.createException(ex.getMessage(), ex);
            }
        }
    }

    public void setOwner(ResultSetImpl rs) {
        this.owner = rs;
    }

    public ResultSetInternalMethods getOwner() {
        return this.owner;
    }

    public boolean wasEmpty() {
        return this.wasEmpty;
    }

    public void setMetadata(Field[] metadata) {
        this.metadata = metadata;
    }

}
