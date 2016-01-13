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
package org.dcache.resilience.data;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;

import diskCacheV111.util.AccessLatency;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.FileNotFoundCacheException;
import diskCacheV111.util.PnfsId;
import org.dcache.resilience.db.LocalNamespaceAccess;
import org.dcache.resilience.db.NamespaceAccess;
import org.dcache.resilience.db.ScanSummary;
import org.dcache.resilience.handlers.PnfsOperationHandler;
import org.dcache.resilience.handlers.ResilienceMessageHandler;
import org.dcache.resilience.util.PoolSelectionUnitDecorator.SelectionAction;
import org.dcache.vehicles.FileAttributes;

/**
 * <p>A transient encapsulation of pertinent configuration data regarding
 * a pnfs location.</p>
 *
 * <p>Implements verification/validation methods to determine if the update
 * should be registered in the operation map or not.</p>
 *
 * @see PnfsOperationMap#register(PnfsUpdate)
 * @see PnfsOperationHandler#handleLocationUpdate(PnfsUpdate)
 * @see ResilienceMessageHandler#updatePnfsLocation(PnfsUpdate)
 * @see LocalNamespaceAccess#handleQuery(Connection,
 *  String, MessageType, SelectionAction, Integer, Integer, ScanSummary)
 *
 * Created by arossi on 09/11/2015.
 */
public final class PnfsUpdate {
    private static final Logger LOGGER = LoggerFactory.getLogger(
                    PnfsUpdate.class);

    /**
     * @return <code>null</code> if AccessLatency is not ONLINE; if the file
     * is not found or there are no locations for it and the
     * message being processed is for a clear cache location; otherwise
     * the file attribute set required to process resilience.
     */
    public static FileAttributes getAttributes(PnfsId pnfsId, String pool,
                                               MessageType messageType,
                                               NamespaceAccess namespace)
                    throws CacheException {
        try {
            FileAttributes attributes = namespace.getRequiredAttributes(pnfsId);
            if (attributes == null) {
                throw new FileNotFoundCacheException(String.format("No attributes "
                    + "returned for %s", pnfsId));
            }

            LOGGER.trace("Got required attributes for {}.", pnfsId);

            if (!attributes.getAccessLatency().equals(AccessLatency.ONLINE)) {
                LOGGER.trace("AccessLatency of {} is not ONLINE; ignoring ...",
                             pnfsId);
                return null;
            }

            if (attributes.getLocations().isEmpty()) {
                if (messageType == MessageType.CLEAR_CACHE_LOCATION) {
                    LOGGER.trace("ClearCacheLocationMessage for {}; "
                                                 + "no current locations; "
                                                 + "file probably deleted "
                                                 + "from namespace.",
                                 pnfsId);
                    return null;
                }

                /*
                 *  Due to a race between PnfsManager and the replication
                 *  system to process the message into/from the namespace.
                 *  We can assume here that this is a new file, so
                 *  we just add the originating location to the attribute
                 *  location list.
                 */
                LOGGER.trace("{} has no locations yet.", pnfsId);
                Collection<String> singleLoc = new ArrayList<>();
                singleLoc.add(pool);
                attributes.setLocations(singleLoc);
            }

            LOGGER.debug("After call to namespace, {} has locations {}.",
                         pnfsId,
                         attributes.getLocations());
            return attributes;
        } catch (FileNotFoundCacheException e) {
            /*
             * Most likely we received a PnfsClearCacheLocationMessage
             * as the result of a file deletion.
             */
            LOGGER.debug("{}; {} has likely been deleted from the namespace.",
                         e.getMessage(),
                         pnfsId);
            return null;
        }
    }

    public enum MessageType {
        CORRUPT_FILE,
        CLEAR_CACHE_LOCATION,
        NEW_FILE_LOCATION,
        POOL_STATUS_DOWN,
        POOL_STATUS_RESTART
    }

    public final PnfsId  pnfsId;
    public final String  pool;
    public final boolean isParent;
    public final MessageType     type;
    public final SelectionAction action;

    private Integer        group;
    private FileAttributes attributes;
    private int            poolIndex;
    private Integer        unitIndex;
    private Integer        count;
    private boolean        fromReload;

    @VisibleForTesting
    public PnfsUpdate(PnfsId pnfsId,
                           String pool,
                           MessageType type,
                           Integer poolIndex,
                           Integer groupIndex,
                           Integer unitIndex,
                           FileAttributes attributes) {
        this(pnfsId, pool, type, SelectionAction.NONE, groupIndex);
        this.poolIndex = poolIndex;
        this.unitIndex = unitIndex;
        this.attributes = attributes;
    }

    public PnfsUpdate(PnfsId pnfsId, String pool, MessageType type) {
        this(pnfsId, pool, type, SelectionAction.NONE, null);
    }

    /**
     * @param pnfsId of the file.
     * @param pool   either the source of the message, or the pool being scanned.
     * @param type   CORRUPT_FILE, CLEAR_CACHE_LOCATION, NEW_FILE_LOCATION,
     *               POOL_STATUS_DOWN, or POOL_STATUS_RESTART.
     * @param action from PoolSelectionUnit (ADD, REMOVE, MODIFY, NONE).
     * @param group  of the pool, if action is not NONE or MODIFY
     *               (can be <code>null</code>).
     */
    public PnfsUpdate(PnfsId pnfsId, String pool, MessageType type,
                      SelectionAction action, Integer group) {
        this.pnfsId = pnfsId;
        this.pool = pool;
        this.type = type;
        if (type == null) {
            isParent = false;
        } else {
            switch (type) {
                case POOL_STATUS_DOWN:
                case POOL_STATUS_RESTART:
                    isParent = true;
                    break;
                default:
                    isParent = false;
            }
        }
        this.action = action;
        this.group = group;
        fromReload = false;
    }

    public FileAttributes getAttributes() {
        return attributes;
    }

    public Short getCount() {
        return count.shortValue();
    }

    public Integer getGroup() {
        return group;
    }

    public int getPoolIndex() {
        return poolIndex;
    }

    public byte getSelectionAction() {
        return (byte) action.ordinal();
    }

    public long getSize() { return attributes.getSize(); }

    public Integer getUnitIndex() {
        return unitIndex;
    }

    public boolean isFromReload() {
        return fromReload;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public void setFromReload(boolean fromReload) {
        this.fromReload = fromReload;
    }

    public boolean shouldVerifySticky() {
        return !isFromReload() && type != MessageType.CLEAR_CACHE_LOCATION &&
                        (!isParent || action == SelectionAction.ADD);
    }

    public String toString() {
        return String.format(
                        "(%s)(%s)(%s)(parent %s)(psu action %s)(group %s)(count %s)",
                        pnfsId, pool, type, isParent, action, group, count);
    }

    public boolean validateAttributes(NamespaceAccess access)
                    throws CacheException {
        LOGGER.trace("validateAttributes for {}", this);
        attributes = getAttributes(pnfsId, pool, type, access);
        LOGGER.trace("validateAttributes, {}", attributes);
        return attributes != null;
    }

    public boolean validateForAction(Integer storageUnit,
                                     PoolInfoMap poolInfoMap) {
        /*
         * Storage unit is not recorded in checkpoint, so it should
         * be set here.
         */
        unitIndex = poolInfoMap.getStorageUnitIndex(attributes);

        LOGGER.trace("validateForAction {} got unit from attributes {}.",
                     pnfsId, unitIndex);

        if (unitIndex == null) {
            LOGGER.debug("Storage unit not defined for {}. Default replica "
                                         + "count is 1, nothing to do.",
                         pnfsId);
            return false;
        }

        /*
         *  Check to see if this is from a reload of the checkpoint record.
         *  If so, the operation count will be non-null.
         */
        if (fromReload) {
            LOGGER.debug("validateForAction, data was reloaded, restoredCount {}",
                         count);
            return count > 0;
        }

        StorageUnitConstraints constraints
                        = poolInfoMap.getStorageUnitConstraints(unitIndex);

        /*
         * Files may be in need of migration even if the correct number
         * exist.  Force the pnfsId operation into the table if the
         * storage unit matches the modified one.
         */
        if (action == SelectionAction.MODIFY && unitIndex == storageUnit) {
            /*
             * The maximum number of steps required to redistribute all files
             * would be required - 1 removes + required -1 copies.
             */
            count = 2 * (constraints.getRequired() - 1);
            return true;
        }

        Collection<String> locations = attributes.getLocations();

        /*
         * On removing a pool from a group, the pool must
         * be considered as if it were DOWN.
         */
        if (action == SelectionAction.REMOVE) {
            locations.remove(pool);
        }

        /*
         *  Check the constraints.
         */
        int readable = poolInfoMap.getReadableMemberLocations(group,
                                                              locations).size();
        count = constraints.getRequired() - readable;
        LOGGER.debug("validateForAction ({} needs {} replicas, locations {}, "
                                     + "{} readable; difference = {}.",
                     pnfsId, constraints.getRequired(),
                     locations, readable, count);

        if (count == 0) {
            LOGGER.debug("{}, requirements are already met.", pnfsId);
            return false;
        }

        /**
         * Multiple copies are handled only when the file is a new entry in
         * the namespace. Any given pool status change (= isParent) will
         * trigger only a single migration or single remove.
         */
        count = isParent ? 1 : Math.abs(count);

        LOGGER.trace("validateForAction, computed count as {}", count);
        return true;
    }

    public boolean verifyPoolGroup(PoolInfoMap poolInfoMap) {
        poolIndex = poolInfoMap.getPoolIndex(pool);
        if (group != null) {
            return poolInfoMap.isResilientGroup(group);
        }

        group = poolInfoMap.getResilientPoolGroup(poolIndex);
        return group != null;
    }
}
