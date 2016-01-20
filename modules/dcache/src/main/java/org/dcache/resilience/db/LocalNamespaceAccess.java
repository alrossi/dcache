/*
COPYRIGHT STATUS:
Dec 1st 2001, Fermi National Accelerator Laboratory (FNAL) documents and
software are sponsored by the U.S. Department of Energy under Contract No.
DE-AC02-76CH03000. Therefore, the U.S. Government retains a  world-wide
non-exclusive, royalty-free license to publish or reproduce these documents
and software for U.S. Government purposes.  All documents and software
available from this server are protected under the U.S. and Foreign
Copyright Laws, and FNAL reserves all rights.

Distribution of the software available from this server is free of
charge subject to the user following the terms of the Fermitools
Software Legal Information.

Redistribution and/or modification of the software shall be accompanied
by the Fermitools Software Legal Information  (including the copyright
notice).

The user is asked to feed back problems, benefits, and/or suggestions
about the software to the Fermilab Software Providers.

Neither the name of Fermilab, the  URA, nor the names of the contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

DISCLAIMER OF LIABILITY (BSD):

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED  WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED  WARRANTIES OF MERCHANTABILITY AND FITNESS
FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL FERMILAB,
OR THE URA, OR THE U.S. DEPARTMENT of ENERGY, OR CONTRIBUTORS BE LIABLE
FOR  ANY  DIRECT, INDIRECT,  INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
OF SUBSTITUTE  GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY  OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT  OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE  POSSIBILITY OF SUCH DAMAGE.

Liabilities of the Government:

This software is provided by URA, independent from its Prime Contract
with the U.S. Department of Energy. URA is acting independently from
the Government and in its own private capacity and is not acting on
behalf of the U.S. Government, nor as its contractor nor its agent.
Correspondingly, it is understood and agreed that the U.S. Government
has no connection to this software and in no manner whatsoever shall
be liable for nor assume any responsibility or obligation for any claim,
cost, or damages arising out of or resulting from the use of the software
available from this server.

Export Control:

All documents and software available from this server are subject to U.S.
export control laws.  Anyone downloading information from this server is
obligated to secure any necessary Government licenses before exporting
documents or software obtained from this server.
 */
package org.dcache.resilience.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

import diskCacheV111.namespace.NameSpaceProvider;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.PnfsId;
import org.dcache.auth.Subjects;
import org.dcache.chimera.BackEndErrorHimeraFsException;
import org.dcache.chimera.IOHimeraFsException;
import org.dcache.commons.util.SqlHelper;
import org.dcache.resilience.data.PnfsOperationMap;
import org.dcache.resilience.data.PnfsUpdate;
import org.dcache.resilience.data.MessageType;
import org.dcache.resilience.handlers.PnfsOperationHandler;
import org.dcache.resilience.handlers.PoolOperationHandler;
import org.dcache.resilience.util.PoolSelectionUnitDecorator.SelectionAction;
import org.dcache.util.ExceptionMessage;
import org.dcache.vehicles.FileAttributes;

import static org.dcache.commons.util.SqlHelper.tryToClose;

/**
 * <p>Provides handling of specialized resilience-related queries which require
 *      direct access to the underlying database. These return pnfsids
 *      for a given location, and counts representing the total number
 *      of replicas.</p>
 *
 * <p>The former uses a callback to the {@link PnfsOperationHandler} to add
 *    an entry in the pnfsid operation tables for each pnfsid.</p>
 *
 * <p>Class is not marked final so that a test version can be
 * implemented by extension.</p>
 *
 * <p>Class is not marked final for the purpose of test extension.</p>
 *
 * Created by arossi on 1/31/15.
 */
public class LocalNamespaceAccess implements NamespaceAccess {
    static final String SQL_GET_ONLINE_FOR_LOCATION
                    = "SELECT l.ipnfsid FROM t_locationinfo l, t_inodes n "
                                    + "WHERE l.ipnfsid = n.ipnfsid "
                                    + "AND l.itype = 1 AND n.iaccess_latency = 1 "
                                    + "AND l.ilocation = ?";

    static final String SQL_ADMIN_GET_COUNTS
                    = "SELECT t1.ipnfsid, count(*) "
                    + "FROM t_locationinfo t1, t_locationinfo t2 "
                    + "WHERE t1.ipnfsid = t2.ipnfsid "
                    + "AND t1.itype = 1 AND t2.itype = 1 "
                    + "AND t2.ilocation = ? "
                    + "GROUP BY t1.ipnfsid ";

    static final String SQL_ADMIN_GET_COUNTS_ORDERED
                    = SQL_ADMIN_GET_COUNTS
                    + "ORDER BY count(*)";

    static final String SQL_ADMIN_GET_COUNTS_HAVING
                    = SQL_ADMIN_GET_COUNTS
                    + "HAVING count(*) %s";

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalNamespaceAccess.class);

    private static final String SQL_INVALID_INEQUALITY
                    = "%s is not a valid inequality.";

    /**
     * <p>Checks that the expression represents a valid inequality for use
     * with the count queries.</p>
     *
     * @return the expression if valid.
     * @throws ParseException if the expression is invalid.
     */
    private static String validateInequality(String expression)
                    throws ParseException {
        if (expression.startsWith("\\")) {
            expression = expression.substring(1);
        }

        String validate = expression.trim();

        if (validate.length() < 1) {
            throw new ParseException(String.format(SQL_INVALID_INEQUALITY,
                                                   expression), 0);
        }

        switch (validate.charAt(0)) {
            case '=':
            case '<':
            case '>':
                validate = validate.substring(1).trim();
                break;

            default:
                throw new ParseException(String.format(SQL_INVALID_INEQUALITY,
                                                       expression), 0);
        }

        if (validate.length() < 1) {
            throw new ParseException(String.format(SQL_INVALID_INEQUALITY,
                                                   expression), 1);
        }

        if (validate.charAt(0) == '=') {
            validate = validate.substring(1).trim();
        }

        if (validate.length() < 1) {
            throw new ParseException(String.format(SQL_INVALID_INEQUALITY,
                                                   expression), 2);
        }

        try {
            Integer.parseInt(validate);
        } catch (NumberFormatException e) {
            throw new ParseException(String.format("%s is not an integer.",
                                                   expression), 2);
        }

        return expression;
    }

    /**
     * Handler for processing pnfs operations.
     */
    protected PnfsOperationHandler handler;

    /**
     * Database connection pool for queries returning multiple pnfsid
     * info.  This may be independent of the main pool
     * for the namespace (in embedded mode), or may be shared
     * (in standalone mode).
     */
    private DataSource connectionPool;

    /**
     * Delegate service used to extract file attributes.
     */
    private NameSpaceProvider namespace;

    /**
     * For cursor buffer used when running pool-based queries.
     */
    private int fetchSize;

    @Override
    public Map<String, Integer> getPnfsidCountsFor(String location)
                    throws CacheException {
        String sql = SQL_ADMIN_GET_COUNTS_ORDERED;
        try {
            Connection dbConnection = getConnection();
            try {
                Map<String, Integer> results = new TreeMap<>();
                getResult(dbConnection, sql, location, results);
                return results;
            } catch (SQLException e) {
                throw new IOHimeraFsException(e.getMessage());
            } finally {
                tryToClose(dbConnection);
            }
        } catch (IOHimeraFsException e) {
            throw new CacheException(CacheException.RESOURCE,
                                     String.format("Could not load cache locations for %s.",
                                                   location),
                                     e);
        }
    }

    @Override
    public Map<String, Integer> getPnfsidCountsFor(String location,
                                                   String filter)
                    throws CacheException, ParseException {
        String sql = String.format(SQL_ADMIN_GET_COUNTS_HAVING,
                                   validateInequality(filter));
        try {
            Connection dbConnection = getConnection();
            try {
                Map<String, Integer> results = new TreeMap<>();
                getResult(dbConnection, sql, location, results);
                return results;
            } catch (SQLException e) {
                throw new IOHimeraFsException(e.getMessage());
            } finally {
                tryToClose(dbConnection);
            }
        } catch (IOHimeraFsException e) {
            throw new CacheException(CacheException.RESOURCE,
                                     String.format("Could not load cache locations for %s.",
                                                   location),
                                     e);
        }
    }

    @Override
    public FileAttributes getRequiredAttributes(PnfsId pnfsId)
                    throws CacheException {
        return namespace.getFileAttributes(Subjects.ROOT,
                                           pnfsId,
                                           REQUIRED_ATTRIBUTES);
    }

    /**
     * Called by
     * {@link PoolOperationHandler
     *  #handlePoolScan(String, MessageType, SelectionAction, Integer, Integer)}.
     */
    @Override
    public void handlePnfsidsForPool(ScanSummary scan)
                    throws CacheException {
        try {
            Connection connection = getConnection();
            try {
                handleQuery(connection, scan);
            } catch (SQLException | CacheException e) {
                throw new IOHimeraFsException(e.getMessage());
            } finally {
                tryToClose(connection);
            }
        } catch (IOHimeraFsException e) {
            throw new CacheException(CacheException.RESOURCE,
                                     String.format("Could not handle pnfsids for %s",
                                                   scan.getPool()), e);
        }
    }

    @Override
    public void refreshLocations(FileAttributes attributes)
                    throws CacheException {
        FileAttributes refreshed =
                        namespace.getFileAttributes(Subjects.ROOT,
                                                    attributes.getPnfsId(),
                                                    REFRESHABLE_ATTRIBUTES);
        attributes.setLocations(refreshed.getLocations());
        attributes.setAccessTime(refreshed.getAccessTime());
    }

    @Override
    public void setConnectionPool(DataSource connectionPool) {
        this.connectionPool = connectionPool;
    }

    @Override
    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public void setHandler(PnfsOperationHandler handler) {
        this.handler = handler;
    }

    @Override
    public void setNamespace(NameSpaceProvider namespace) {
        this.namespace = namespace;
    }

    private Connection getConnection() throws IOHimeraFsException {
        try {
            Connection dbConnection = connectionPool.getConnection();
            dbConnection.setAutoCommit(true);
            return dbConnection;
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }
    }

    /**
     * Used by the queries which deliver replica counts.
     */
    private void getResult(Connection connection,
                           String query,
                           String location,
                           Map<String, Integer> results)
                    throws SQLException {
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.prepareStatement(query);
            statement.setString(1, location);
            resultSet = statement.executeQuery();
            boolean addCounts = resultSet.getMetaData().getColumnCount() == 2;
            while (resultSet.next()) {
                String pnfsId = resultSet.getString(1);
                Integer count = null;
                if (addCounts) {
                    count = resultSet.getInt(2);
                }
                results.put(pnfsId, count);
            }
        } finally {
            SqlHelper.tryToClose(resultSet);
            SqlHelper.tryToClose(statement);
        }
    }

    /**
     * <p>The query processes all pnfsids for the given location which
     * have access latency = ONLINE.  These are sent one-by-one to the
     * {@link PnfsOperationHandler} to either create or update a corresponding
     * entry in the {@link PnfsOperationMap}.</p>
     */
    private void handleQuery(Connection connection, ScanSummary scan)
                    throws SQLException, CacheException {
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        String pool = scan.getPool();
        MessageType type = scan.getType();
        SelectionAction action = scan.getAction();
        Integer group = scan.getGroup();
        Integer storageUnit = scan.getStorageUnit();

        try {
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(SQL_GET_ONLINE_FOR_LOCATION);
            statement.setString(1, pool);
            statement.setFetchSize(fetchSize);
            if (scan.isCancelled()) {
                return;
            }

            resultSet = statement.executeQuery();

            while (resultSet.next() && !scan.isCancelled()) {
                PnfsId pnfsId = new PnfsId(resultSet.getString(1));
                PnfsUpdate data = new PnfsUpdate(pnfsId, pool, type, action,
                                                 group);
                try {
                    if (handler.handleScannedLocation(data, storageUnit)) {
                        scan.incrementCount();
                        scan.incrementBytes(data.getSize());
                    }
                } catch (CacheException e) {
                    LOGGER.debug("{}: {}", data, new ExceptionMessage(e));
                }
            }
        } finally {
            SqlHelper.tryToClose(resultSet);
            SqlHelper.tryToClose(statement);
        }
    }
}
