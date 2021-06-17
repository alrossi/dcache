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
package org.dcache.qos.remote.receivers;

import diskCacheV111.util.CacheException;
import diskCacheV111.util.PnfsId;
import diskCacheV111.vehicles.PnfsAddCacheLocationMessage;
import diskCacheV111.vehicles.PnfsClearCacheLocationMessage;
import org.dcache.qos.TestBase;
import org.dcache.qos.TestStub;
import org.dcache.qos.TestSynchronousExecutor.Mode;
import org.dcache.qos.data.FileQoSRequirements;
import org.dcache.qos.data.QoSAction;
import org.dcache.qos.data.QoSMessageType;
import org.dcache.qos.services.engine.handler.FileQoSStatusHandler;
import org.dcache.qos.services.engine.util.QoSEngineCounters;
import org.dcache.qos.util.MessageGuard;
import org.dcache.qos.util.MessageGuard.Status;
import org.dcache.vehicles.CorruptFileMessage;
import org.dcache.vehicles.FileAttributes;
import org.dcache.vehicles.qos.QoSActionCompleteMessage;
import org.dcache.vehicles.qos.QoSRequirementsModifiedMessage;
import org.junit.Before;
import org.junit.Test;

import static org.dcache.qos.services.engine.util.QoSEngineCounters.QOS_ACTION_COMPLETED;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <p>This test merely verifies that message handling calls are made.</p>
 */
public final class QoSRequirementsReceiverTest extends TestBase {
    QoSRequirementsReceiver receiver;
    QoSEngineCounters engineCounters;
    MessageGuard messageGuard;
    FileQoSStatusHandler fileStatusHandler;

    @Before
    public void setUp() throws CacheException {
        setUpBase();
        setShortExecutionMode(Mode.NOP);
        receiver = new QoSRequirementsReceiver();
        fileStatusHandler = new FileQoSStatusHandler();
        fileStatusHandler.setExecutor(shortJobExecutor);
        fileStatusHandler.setQosTransitionTopic(new TestStub());
        engineCounters = new QoSEngineCounters();
        engineCounters.initialize();
        fileStatusHandler.setQoSEngineCounters(engineCounters);
        receiver.setFileStatusHandler(fileStatusHandler);
        messageGuard = mock(MessageGuard.class);
        receiver.setMessageGuard(messageGuard);
    }

    @Test
    public void verifyCorruptFileMessage() throws CacheException {
        PnfsId pnfsId = new PnfsId("0000000000000000BCD");
        CorruptFileMessage message
            = new CorruptFileMessage("test-pool", pnfsId);
        when(messageGuard.getStatus("CorruptFileMessage",
            message)).thenReturn(Status.EXTERNAL);
        receiver.accept(message);
        /*
         *  Without artificially altering the call structure of the
         *  handler, verification of the actual FileOperationHandler method
         * call is not possible here as the message handler creates an
         * anonymous struct-type object on the fly to pass as parameter.
         */
        assertEquals(1,
            engineCounters.getCount(QoSMessageType.CORRUPT_FILE.name()));
    }

    @Test
    public void verifyPnfsAddCacheLocationMessage() throws CacheException {
        PnfsId pnfsId = new PnfsId("0000000000000000BCD");
        PnfsAddCacheLocationMessage message
            = new PnfsAddCacheLocationMessage(pnfsId,"test-pool");
        when(messageGuard.getStatus("PnfsAddCacheLocationMessage",
                                    message)).thenReturn(Status.EXTERNAL);
        receiver.accept(message);
        /*
         *  Without artificially altering the call structure of the
         *  handler, verification of the actual FileOperationHandler method
         * call is not possible here as the message handler creates an
         * anonymous struct-type object on the fly to pass as parameter.
         */
        assertEquals(1,
            engineCounters.getCount(QoSMessageType.ADD_CACHE_LOCATION.name()));
    }

    @Test
    public void verifyQoSOriginatingPnfsAddCacheLocationMessage() throws CacheException {
        PnfsId pnfsId = new PnfsId("0000000000000000BCD");
        PnfsAddCacheLocationMessage message
            = new PnfsAddCacheLocationMessage(pnfsId,"test-pool");
        when(messageGuard.getStatus("PnfsAddCacheLocationMessage",
            message)).thenReturn(Status.QOS);
        receiver.accept(message);
        /*
         *  Without artificially altering the call structure of the
         *  handler, verification of the actual FileOperationHandler method
         * call is not possible here as the message handler creates an
         * anonymous struct-type object on the fly to pass as parameter.
         */
        assertEquals(0,
            engineCounters.getCount(QoSMessageType.ADD_CACHE_LOCATION.name()));
    }

    @Test
    public void verifyPnfsClearCacheLocationMessage() {
        PnfsId pnfsId = new PnfsId("0000000000000000BCD");
        PnfsClearCacheLocationMessage message
            = new PnfsClearCacheLocationMessage(pnfsId, "test-pool");
        when(messageGuard.getStatus("PnfsClearCacheLocationMessage",
                                    message)).thenReturn(Status.EXTERNAL);
        receiver.accept(message);
        /*
         *  Without artificially altering the call structure of the
         *  handler, verification of the actual FileOperationHandler method
         * call is not possible here as the message handler creates an
         * anonymous struct-type object on the fly to pass as parameter.
         */
        assertEquals(1,
            engineCounters.getCount(QoSMessageType.CLEAR_CACHE_LOCATION.name()));
    }

    @Test
    public void verifyQoSRequirementsModifiedMessage() {
        QoSRequirementsModifiedMessage message
            = new QoSRequirementsModifiedMessage(new FileQoSRequirements(new PnfsId("0000000000000000BCD"),
                                                                         new FileAttributes()));
        when(messageGuard.getStatus("QoSRequirementsModifiedMessage",
            message)).thenReturn(Status.EXTERNAL);
        receiver.accept(message);
        /*
         *  Without artificially altering the call structure of the
         *  handler, verification of the actual FileOperationHandler method
         * call is not possible here as the message handler creates an
         * anonymous struct-type object on the fly to pass as parameter.
         */
        assertEquals(1,
            engineCounters.getCount(QoSMessageType.QOS_MODIFIED.name()));
    }

    @Test
    public void verifyBlockedQoSRequirementsModifiedMessage() {
        QoSRequirementsModifiedMessage message
            = new QoSRequirementsModifiedMessage(new FileQoSRequirements(new PnfsId("0000000000000000BCD"),
                                                                         new FileAttributes()));
        when(messageGuard.getStatus("QoSRequirementsModifiedMessage",
            message)).thenReturn(Status.DISABLED);
        receiver.accept(message);
        /*
         *  Without artificially altering the call structure of the
         *  handler, verification of the actual FileOperationHandler method
         * call is not possible here as the message handler creates an
         * anonymous struct-type object on the fly to pass as parameter.
         */
        assertEquals(0,
            engineCounters.getCount(QoSMessageType.QOS_MODIFIED.name()));
    }

    @Test
    public void verifyCompletedActionMessage() {
        QoSActionCompleteMessage message
         =  new QoSActionCompleteMessage(new PnfsId("0000000000000000BCD"),
                                         QoSAction.WAIT_FOR_STAGE, null);
        when(messageGuard.getStatus("QoSActionCompleteMessage",
            message)).thenReturn(Status.EXTERNAL);
        receiver.accept(message);
        /*
         *  Without artificially altering the call structure of the
         *  handler, verification of the actual method
         *  call is not possible here as the message handler creates an
         *  anonymous struct-type object on the fly to pass as parameter.
         */
        assertEquals(1,
            engineCounters.getCount(QOS_ACTION_COMPLETED));
    }
}
