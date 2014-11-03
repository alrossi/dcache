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
package org.dcache.replication.v3.namespace.handlers.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

import diskCacheV111.util.PnfsId;
import diskCacheV111.vehicles.PnfsClearCacheLocationMessage;

import dmg.cells.nucleus.CellMessageReceiver;

import org.dcache.replication.v3.CDCFixedPoolTaskExecutor;
import org.dcache.replication.v3.namespace.ResilientInfoCache;
import org.dcache.replication.v3.namespace.handlers.task.PoolGroupInfoTaskCompletionHandler;
import org.dcache.replication.v3.namespace.tasks.PoolGroupInfoTask;
import org.dcache.vehicles.PnfsSetFileAttributes;

/**
 * Receiver which is responsible for initiating the process maintaing
 * the proper number of replicas for a single pnfsid in a resilient
 * pool group.
 * <p>
 * The same procedure is used when either an attribute update message or
 * a clear cache location message is intercepted.  A check is first done
 * to make sure the source pool belongs to a resilient group.  The
 * pool group information is retrieved via a cache which periodically
 * refreshes the pool monitor. The task is executed on a separate thread.
 *
 * @author arossi
 */
public final class PnfsCacheLocationHandler implements CellMessageReceiver {
    private static final Logger LOGGER
        = LoggerFactory.getLogger(PnfsCacheLocationHandler.class);

    private CDCFixedPoolTaskExecutor executor;
    private ResilientInfoCache cache;
    private MessageGuard guard;
    private PoolGroupInfoTaskCompletionHandler completionHandler;

    public void messageArrived(PnfsClearCacheLocationMessage message) {
        /*
         * Guard check is done on the message queue thread
         * (there should be little overhead).
         */
        if (!guard.acceptMessage("Clear Cache Location", message)) {
            return;
        }

        PnfsId pnfsId = message.getPnfsId();
        String pool = message.getPoolName();

        executor.execute(new PoolGroupInfoTask(pnfsId,
                                               pool,
                                               cache,
                                               completionHandler));
        LOGGER.debug("executed PoolGroupInfoTask for {}.", pool);
    }

    public void messageArrived(PnfsSetFileAttributes message) {
        /*
         * Guard check is done on the message queue thread
         * (there should be little overhead).
         */
        if (!guard.acceptMessage("Set File Attributes", message)) {
            return;
        }

        PnfsId pnfsId = message.getPnfsId();
        Collection<String> locations = message.getFileAttributes().getLocations();

        /*
         * We are only interested in attribute updates where a single new
         * location is added.
         */
        if (locations.size() != 1) {
            LOGGER.debug("Message for {} contains {} locations ({}): "
                            + "irrelevant to replication; " + "discarding.",
                            pnfsId, locations.size(), locations);
            return;
        }

        /*
         * Offload request for resilient pool information onto separate thread.
         * Results processed by PoolGroupInfoHandler#handleDone.
         */
        String pool = locations.iterator().next();
        executor.execute(new PoolGroupInfoTask(pnfsId,
                                               pool,
                                               cache,
                                               completionHandler));
        LOGGER.debug("executed PoolGroupInfoTask for {}.", pool);
    }

    public void setCache(ResilientInfoCache cache) {
        this.cache = cache;
    }

    public void setGuard(MessageGuard guard) {
        this.guard = guard;
    }

    public void setPoolInfoTaskExecutor(
                    CDCFixedPoolTaskExecutor poolInfoTaskExecutor) {
        this.executor = poolInfoTaskExecutor;
    }

    public PoolGroupInfoTaskCompletionHandler getPoolGroupInfoTaskHandler() {
        return completionHandler;
    }

    public void setPoolGroupInfoTaskHandler(PoolGroupInfoTaskCompletionHandler
                                            completionHandler) {
        this.completionHandler = completionHandler;
    }
}
