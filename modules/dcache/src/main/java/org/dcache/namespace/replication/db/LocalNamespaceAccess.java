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
package org.dcache.namespace.replication.db;

import com.google.common.collect.Range;

import javax.security.auth.Subject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;

import diskCacheV111.namespace.NameSpaceProvider;
import diskCacheV111.util.AccessLatency;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.FsPath;
import diskCacheV111.util.PnfsId;
import diskCacheV111.util.RetentionPolicy;
import org.dcache.chimera.BackEndErrorHimeraFsException;
import org.dcache.chimera.IOHimeraFsException;
import org.dcache.commons.util.SqlHelper;
import org.dcache.namespace.CreateOption;
import org.dcache.namespace.FileAttribute;
import org.dcache.namespace.ListHandler;
import org.dcache.namespace.replication.ReplicationHub;
import org.dcache.namespace.replication.data.PnfsIdInfo;
import org.dcache.util.ChecksumType;
import org.dcache.util.Glob;
import org.dcache.vehicles.FileAttributes;

import static org.dcache.commons.util.SqlHelper.tryToClose;

/**
 * Wraps both the standard namespace provider as well as a few specialized
 * queries requiring direct access to the underlying database.
 * <p/>
 * The extra queries return the pnfsids for a given location, and
 * optionally counts for them representing total number of replicas.
 * <p/>
 * Also implements query which calls back to a
 * {@link org.dcache.namespace.replication.tasks.PnfsIdProcessor}.
 *
 * Created by arossi on 1/31/15.
 */
public final class LocalNamespaceAccess implements NameSpaceProvider {
    static final String SQL_GET_LOCATION_COUNTS =
                    "SELECT t1.ipnfsid, count(*) "
                                    + "FROM t_locationinfo t1, t_locationinfo t2 "
                                    + "WHERE t1.ipnfsid = t2.ipnfsid "
                                    + "AND t1.itype = 1 AND t2.itype = 1 "
                                    + "AND t2.ilocation = ? "
                                    + "GROUP BY t1.ipnfsid";

    static final String SQL_GET_PNFSIDS_FOR_LOCATION =
                    "SELECT t1.ipnfsid "
                                    + "FROM t_locationinfo t1 "
                                    + "WHERE t1.itype = 1 "
                                    + "AND t1.ilocation = ? "
                                    + "ORDER BY t1.ipnfsid";

    /**
     * Database connection pool for queries returning multiple pnfsid
     * location info.
     */
    private DataSource connectionPool;

    /**
     * Delegate service used to extract file attributes and
     * single pnfsId location info.
     */
    private NameSpaceProvider namespace;

    private ReplicationHub hub;

    /**
     * For cursor used when running pool-based queries.
     */
    private int fetchSize;

    /**
     * @param location pool name.
     * @return set of all pnfsids on that pool.
     * @throws CacheException
     */
    public Collection<String> getAllPnfsidsFor(String location)
                    throws CacheException {
        String sql = SQL_GET_PNFSIDS_FOR_LOCATION;
        try {
            Connection dbConnection = getConnection();
            try {
                Map<String, Integer> results = new TreeMap<>();
                getResult(dbConnection, sql, location, results);
                return results.keySet();
            } catch (SQLException e) {
                throw new IOHimeraFsException(e.getMessage());
            } finally {
                tryToClose(dbConnection);
            }
        } catch (IOHimeraFsException e) {
            throw new CacheException(CacheException.RESOURCE,
                            String.format("Could not load cache"
                                           + " locations for %s.",
                                            location),
                            e);
        }
    }

    /**
     * @param location pool name.
     * @return map of pnfsid, replica count entries.
     * @throws CacheException
     */
    public Map<String, Integer> getPnfsidCountsFor(String location)
                    throws CacheException {
        String sql = SQL_GET_LOCATION_COUNTS + " ORDER BY count(*)";
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
                            String.format("Could not load cache "
                                                            + "locations for %s.",
                                            location),
                            e);
        }
    }

    /**
     * @param location pool name.
     * @param filter integer inequality such as >= 3, < 2, = 1.
     * @return map of pnfsid, replica count entries.
     * @throws CacheException
     */
    public Map<String, Integer> getPnfsidCountsFor(String location,
                                                   String filter)
                    throws CacheException, ParseException {
        String sql = String.format(SQL_GET_LOCATION_COUNTS
                        + " HAVING count(*) %s",
                        PnfsInfoQuery.validateInequality(filter));
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
                            String.format("Could not load cache "
                                                            + "locations for %s.",
                                            location),
                            e);
        }
    }

    /**
     * @param query encapsulates pool-related data for processing
     *              replicas; pnfsIds which have too few locations
     *              are called back for copying, with too many are
     *              called back for removal, and those with no
     *              available sources for copying raise an alarm.
     * @throws CacheException
     */
    public void handlePnfsidsForPool(PnfsInfoQuery query)
                    throws CacheException {
        try {
            Connection connection = getConnection();
            try {
                handleQuery(connection, query);
            } catch (SQLException | ExecutionException
                            | InterruptedException | CacheException  e ) {
                throw new IOHimeraFsException(e.getMessage());
            } finally {
                tryToClose(connection);
            }
        } catch (IOHimeraFsException e) {
            throw new CacheException(CacheException.RESOURCE,
                            String.format("Could not handle pnfsids for %s.",
                                            query.poolName),
                            e);
        }
    }

    public void setConnectionPool(DataSource connectionPool) {
        this.connectionPool = connectionPool;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public void setHub(ReplicationHub hub) {
        this.hub = hub;
    }

    public void setNamespace(NameSpaceProvider namespace) {
        this.namespace = namespace;
    }

    /*
     * ********************* NamespaceProvider API **************************
     */

    @Override
    public FileAttributes createFile(Subject subject, String path, int uid,
                    int gid, int mode, Set<FileAttribute> requestedAttributes)
                    throws CacheException {
        return namespace.createFile(subject, path, uid, gid, mode,
                        requestedAttributes);
    }

    @Override
    public PnfsId createDirectory(Subject subject, String path, int uid,
                    int gid, int mode) throws CacheException {
        return namespace.createDirectory(subject, path, uid, gid, mode);
    }

    @Override
    public PnfsId createSymLink(Subject subject, String path, String dest,
                    int uid, int gid) throws CacheException {
        return namespace.createSymLink(subject, path, dest, uid, gid);
    }

    @Override public void deleteEntry(Subject subject, PnfsId pnfsId)
                    throws CacheException {
        namespace.deleteEntry(subject, pnfsId);
    }

    @Override public void deleteEntry(Subject subject, String path)
                    throws CacheException {
        namespace.deleteEntry(subject, path);
    }

    @Override
    public void renameEntry(Subject subject, PnfsId pnfsId, String newName,
                    boolean overwrite) throws CacheException {
        namespace.renameEntry(subject, pnfsId, newName, overwrite);
    }

    @Override public String pnfsidToPath(Subject subject, PnfsId pnfsId)
                    throws CacheException {
        return namespace.pnfsidToPath(subject, pnfsId);
    }

    @Override public PnfsId pathToPnfsid(Subject subject, String path,
                    boolean followLinks) throws CacheException {
        return namespace.pathToPnfsid(subject, path, followLinks);
    }

    @Override public PnfsId getParentOf(Subject subject, PnfsId pnfsId)
                    throws CacheException {
        return namespace.getParentOf(subject, pnfsId);
    }

    @Override public void removeFileAttribute(Subject subject, PnfsId pnfsId,
                    String attribute) throws CacheException {
        namespace.removeFileAttribute(subject, pnfsId, attribute);
    }

    @Override public void removeChecksum(Subject subject, PnfsId pnfsId,
                    ChecksumType type) throws CacheException {
        namespace.removeChecksum(subject, pnfsId, type);
    }

    @Override public void addCacheLocation(Subject subject, PnfsId pnfsId,
                    String cacheLocation) throws CacheException {
        namespace.addCacheLocation(subject, pnfsId, cacheLocation);
    }

    @Override
    public List<String> getCacheLocation(Subject subject, PnfsId pnfsId)
                    throws CacheException {
        return namespace.getCacheLocation(subject, pnfsId);
    }

    @Override public void clearCacheLocation(Subject subject, PnfsId pnfsId,
                    String cacheLocation, boolean removeIfLast)
                    throws CacheException {
        namespace.clearCacheLocation(subject, pnfsId, cacheLocation,
                        removeIfLast);
    }

    @Override
    public FileAttributes getFileAttributes(Subject subject, PnfsId pnfsId,
                    Set<FileAttribute> attr) throws CacheException {
        return namespace.getFileAttributes(subject, pnfsId, attr);
    }

    @Override
    public FileAttributes setFileAttributes(Subject subject, PnfsId pnfsId,
                    FileAttributes attr, Set<FileAttribute> fetch)
                    throws CacheException {
        return namespace.setFileAttributes(subject, pnfsId, attr, fetch);
    }

    @Override public void list(Subject subject, String path, Glob glob,
                    Range<Integer> range, Set<FileAttribute> attrs,
                    ListHandler handler) throws CacheException {
        namespace.list(subject, path, glob, range, attrs, handler);
    }

    @Override public FsPath createUploadPath(Subject subject, FsPath path,
                    FsPath rootPath, int uid, int gid, int mode, Long size,
                    AccessLatency al, RetentionPolicy rp, String spaceToken,
                    Set<CreateOption> options) throws CacheException {
        return namespace.createUploadPath(subject, path, rootPath, uid, gid,
                        mode, size, al, rp, spaceToken, options);
    }

    @Override
    public PnfsId commitUpload(Subject subject, FsPath uploadPath, FsPath path,
                    Set<CreateOption> options) throws CacheException {
        return namespace.commitUpload(subject, uploadPath, path, options);
    }

    @Override
    public void cancelUpload(Subject subject, FsPath uploadPath, FsPath path)
                    throws CacheException {
        namespace.cancelUpload(subject, uploadPath, path);
    }

    /*
     * ********************** Replication Handling **************************
     */

    private Connection getConnection() throws IOHimeraFsException {
        try {
            Connection dbConnection = connectionPool.getConnection();
            dbConnection.setAutoCommit(true);
            return dbConnection;
        } catch (SQLException e) {
            throw new BackEndErrorHimeraFsException(e.getMessage());
        }
    }

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

    /*
     *  On down pools, the query asks for files with a location count less than
     *  the highest minimum + 1, for restarts, files with a location count
     *  greater than the lowest maximum for a pool group.
     *
     *  Note that these are heuristics and will not really give us the
     *  true number of qualifying pnfsids because the counts are all
     *  registered locations regardless of whether they are active.
     *  That is why we vet these results by storage group constraints
     *  and exclude inactive locations in memory.
     *
     *  Iterates over the result set (which uses a cursor), caching
     *  the info, and checking storage group constraints against active
     *  locations, then discarding the pnfsid if the current count meets them.
     *  Otherwise, the pnfsId is added to the list of pnfsIds.
     */
    private void handleQuery(Connection connection, PnfsInfoQuery query)
                    throws SQLException, ExecutionException,
                           CacheException, InterruptedException {
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(query.getSql());
            statement.setString(1, query.poolName);
            statement.setFetchSize(fetchSize);
            resultSet = statement.executeQuery();
            Collection<String> active
                            = hub.getPoolInfoCache().findAllActivePools();

            while(resultSet.next()) {
                String pnfsid = resultSet.getString(1);
                PnfsId pnfsId = new PnfsId(pnfsid);
                PnfsIdInfo info = new PnfsIdInfo(pnfsId);
                info.getAttributes();

                for (Iterator<String> it = info.getLocations().iterator();
                                            it.hasNext(); ) {
                    String location = it.next();
                    if (!active.contains(location)) {
                        it.remove();
                    } else if (query.excludeThisPool
                                    && query.poolName.equals(location)) {
                        it.remove();
                    }
                }

                try {
                    int count = info.getLocations().size();
                    if (count == 0) {
                        query.callback.processAlarm(info);
                    }

                    info.setConstraints(query.poolGroupInfo);

                    if (count < info.getMinimum()) {
                        query.callback.processCopy(info);
                    } else if (count > info.getMaximum()) {
                        query.callback.processRemove(info, count);
                    }
                } catch (InterruptedException e) {
                    /*
                     *  The task to which we callback here
                     *  has failed somewhere else or has been interrupted.
                     */
                    break;
                }
            }
        } finally {
            SqlHelper.tryToClose(resultSet);
            SqlHelper.tryToClose(statement);
        }
    }
}
