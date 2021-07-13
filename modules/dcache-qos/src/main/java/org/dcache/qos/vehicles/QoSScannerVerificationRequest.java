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
package org.dcache.qos.vehicles;

import diskCacheV111.util.PnfsId;
import java.io.Serializable;
import java.util.List;
import org.dcache.qos.data.QoSMessageType;

/**
 * This is a batched PnfsId request from the scanner.
 * <p/>
 * When the scanner looks at all the files of either a location/pool or segment of the
 * namespace inodes (system scan), it groups them in batches of a predetermined size and
 * sends them off to the verifier. The verifier will keep track of which pnfsids it is checking
 * for which id.
 */
public class QoSScannerVerificationRequest implements Serializable {
  private static final long serialVersionUID = 5803464448479347602L;
  private final List<PnfsId> replicas;

  /**
   * Can be the pool name or a UUID string for a system scan representing a segment of
   * the inode space.
   */
  private final String id;

  /**
   * For this message, only the values POOL_STATUS_DOWN or POOL_STATUS_UP pertain.
   */
  private final QoSMessageType type;

  /**
   * These fields are only true when specific changes occur through
   * the Pool Selection Unit to either a pool group or the requirements on a storage unit.
   */
  private final String storageUnit;
  private final String group;

  /**
   * True means this is a "forced scan" (from the admin command) or
   * a periodic one.  This is used largely to indicate whether
   * more than one action for each pnfsid may be required.
   */
  private final boolean forced;

  public QoSScannerVerificationRequest(String id,
                                       List<PnfsId> replicas,
                                       QoSMessageType type,
                                       String group,
                                       String storageUnit,
                                       boolean forced) {
    this.id = id;
    this.type = type;
    this.replicas = replicas;
    this.group = group;
    this.storageUnit = storageUnit;
    this.forced = forced;
  }

  public List<PnfsId> getReplicas() {
    return replicas;
  }

  public String getId() {
    return id;
  }

  public String getGroup() { return group; }

  public String getStorageUnit() {
    return storageUnit;
  }

  public QoSMessageType getType() {
    return type;
  }

  public boolean isForced() { return forced; }
}
