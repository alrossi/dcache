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
package org.dcache.resilience.handlers;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import diskCacheV111.pools.PoolV2Mode;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.PnfsId;
import diskCacheV111.vehicles.Message;
import diskCacheV111.vehicles.PoolStatusChangedMessage;
import org.dcache.pool.migration.Task;
import org.dcache.resilience.TestBase;
import org.dcache.resilience.TestMessageProcessor;
import org.dcache.resilience.TestSynchronousExecutor.Mode;
import org.dcache.resilience.data.PnfsOperation;
import org.dcache.resilience.data.PnfsUpdate;
import org.dcache.resilience.data.PnfsUpdate.MessageType;
import org.dcache.resilience.data.PoolStateUpdate;
import org.dcache.resilience.data.StorageUnitConstraints;
import org.dcache.resilience.util.InaccessibleFileHandler;
import org.dcache.resilience.util.PoolSelectionUnitDecorator.SelectionAction;
import org.dcache.resilience.util.ResilientFileTask;
import org.dcache.vehicles.FileAttributes;
import org.dcache.vehicles.resilience.RemoveReplicaMessage;
import org.dcache.vehicles.resilience.ResilienceStickyBitMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Created by arossi on 9/21/15.
 */
public final class PnfsOperationHandlerTest extends TestBase
                implements InaccessibleFileHandler,
                TestMessageProcessor {
    final Multimap<String, PnfsId> inaccessible = ArrayListMultimap.create();

    PnfsUpdate update;
    FileAttributes attributes;
    List<ResilienceStickyBitMessage> stickyBitMessages;
    RemoveReplicaMessage repRmMessage;
    ResilientFileTask task;
    String verifyType;
    String storageUnit;
    Integer originalTarget;
    Integer originalSource;
    boolean suppressAlarm = false;
    boolean pinMessageFailure = false;
    boolean rmMessageFailure = false;
    boolean unpinMessageFailure = false;

    @Override
    public void registerInaccessibleFile(String pool, PnfsId pnfsId) {
        inaccessible.put(pool, pnfsId);
    }

    @Override
    public void handleInaccessibleFilesIfExistOn(String pool) {
        // NOP
    }

    @Override
    public void processMessage(Message message) throws Exception {
         if (message instanceof ResilienceStickyBitMessage) {
            ResilienceStickyBitMessage stickyBitMessage = (ResilienceStickyBitMessage) message;
            if (stickyBitMessage.set && pinMessageFailure) {
                throw new Exception(FORCED_FAILURE);
            }

            if (!stickyBitMessage.set && unpinMessageFailure) {
                throw new Exception(FORCED_FAILURE);
            }

            if (stickyBitMessages == null) {
                stickyBitMessages = new ArrayList<>();
            }

            stickyBitMessages.add((ResilienceStickyBitMessage) message);
        } else if (message instanceof RemoveReplicaMessage) {
            if (rmMessageFailure) {
                throw new Exception(FORCED_FAILURE);
            }

            repRmMessage = (RemoveReplicaMessage) message;
        }
    }

    @Before
    public void setUp() throws CacheException, InterruptedException,
                    IOException {
        setUpTest();
    }

    @Test
    public void shouldCreateMigrationTaskWhenVerifyResultIsCopy()
                    throws CacheException {
        givenAPnfsUpdateForANewFileOnAPoolWithHostAndRackTags();
        whenHandleUpdateIsCalled();
        whenSourceAndTargetAreSelected();
        whenTaskIsCreatedAndCalled();
        assertThatCorrectMigrationTaskWasCreated();
    }

    @Test
    public void shouldFailOnFatalFailure() throws CacheException, IOException {
        givenAPnfsUpdateForANewFileOnAPoolWithNoTags();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        whenOperationFailsFatally();
        assertNull(pnfsOperationMap.getOperation(update.pnfsId));
    }

    @Test
    public void shouldFailOnNewLocFailureWhereThereIsNoOtherTarget()
                    throws CacheException, InterruptedException, IOException {
        givenAPnfsUpdateForANewFileOnAPoolWithNoTags();
        givenUpdateHasBeenAddedToMapWithCountOf((short) 1);
        whenVerifyIsRun();
        afterInspectingSourceAndTarget();
        givenAllPoolsOfflineExceptSourceAndTarget();
        whenOperationFailsWithNewTargetError();
        whenVerifyIsRun();
        whenScanIsRun();
        assertNull(pnfsOperationMap.getOperation(update.pnfsId));
    }

    @Test
    public void shouldNotProcessUpdateWhenClearCacheLocationWithNoLocations()
                    throws CacheException {
        givenAPnfsUpdateClearCacheLocationForAFileWithNoLocationsInNamespace();
        whenHandleUpdateIsCalled();
        assertTrue(noOperationHasBeenAdded());
    }

    @Test
    public void shouldNotProcessUpdateWhenFileDeletedFromNamespace()
                    throws CacheException {
        givenAPnfsUpdateForAFileDeletedFromNamespace();
        whenHandleUpdateIsCalled();
        assertTrue(noOperationHasBeenAdded());
    }

    @Test
    public void shouldNotProcessUpdateWhenPoolNotResilient()
                    throws CacheException {
        givenAPnfsUpdateForANewFileOnNonResilientPool();
        whenHandleUpdateIsCalled();
        assertTrue(noOperationHasBeenAdded());
    }

    @Test
    public void shouldNotProcessUpdateWhenStorageGroupNotResilient()
                    throws CacheException {
        givenAPnfsUpdateForANewFileResilientPoolButRequiringASingleCopy();
        whenHandleUpdateIsCalled();
        assertTrue(noOperationHasBeenAdded());
    }

    @Test
    public void shouldNotReportInaccessibleIfPoolRemovedFromGroup()
                    throws CacheException {
        givenAPnfsUpdateFromAPoolScan();
        givenAPnfsUpdateFromAPoolScanForPoolRemovedFromGroup();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        assertFalse(inaccessible.containsEntry(update.pool, update.pnfsId));
    }

    @Test
    public void shouldNotReportInaccessibleIfRetentionPolicyIsCustodial()
                    throws CacheException {
        givenACustodialPnfsUpdateFromAPoolScan();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        assertFalse(inaccessible.containsEntry(update.pool, update.pnfsId));
    }

    @Test
    public void shouldNotSendSetStickyMessageOnScan() throws CacheException {
        givenAPnfsUpdateFromAPoolScan();
        whenHandleUpdateIsCalled();
        assertFalse(update.shouldVerifySticky());
    }

    @Test
    public void shouldPinAllReplicasOnRemove() throws CacheException {
        givenAPnfsUpdateForAFileWithExcessLocations();
        whenHandleUpdateIsCalled();
        whenTaskIsCreatedAndCalled();
        assertEquals(theNumberOfPinMessages(), 15);
    }

    @Test
    public void shouldReportInaccessibleIfOnlyCopyOnDownPool()
                    throws CacheException {
        givenAPnfsUpdateFromAPoolScan();
        givenSourcePoolIsDown();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        assertTrue(inaccessible.containsEntry(update.pool, update.pnfsId));
    }

    @Test
    public void shouldReturnCopyWhenRequiredCopyMissing()
                    throws CacheException {
        givenAPnfsUpdateForANewFileOnAPoolWithNoTags();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        assertEquals("COPY", verifyType);
    }

    @Test
    public void shouldReturnRemoveWhenExcessCopiesExist()
                    throws CacheException {
        givenAPnfsUpdateForAFileWithExcessLocations();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        assertEquals("REMOVE", verifyType);
    }

    @Test
    public void shouldReturnRemoveWhenTagChangeRequiresRedistribution()
                    throws CacheException {
        givenAPnfsUpdateForAFileWithExcessLocations();
        givenANewTagRequirementForFileStorageUnit();
        givenUpdateHasBeenAddedToMapWithCountOf((short) 1);
        whenVerifyIsRun();
        assertEquals("REMOVE", verifyType);
        assertEquals((short)2,
                     pnfsOperationMap.getOperation(update.pnfsId).getOpCount());
    }

    @Test
    public void shouldReturnVoidWhenNothingToDo() throws CacheException {
        givenAPnfsUpdateForAFileWithRequiredLocations();
        givenUpdateHasBeenAddedToMapWithCountOf((short) 1);
        whenVerifyIsRun();
        assertEquals("VOID", verifyType);
    }

    @Test
    public void shouldSendAlarmWhenUnpinFails() throws CacheException {
        givenAPnfsUpdateForAFileWithExcessLocations();
        whenHandleUpdateIsCalled();
        givenUnpinWillFail();
        whenTaskIsCreatedAndCalled();
        /*
         *  This test should just show the alarm in the logging output.
         */
    }

    @Test
    public void shouldSendRemoveRequestOnExcessLocation()
                    throws CacheException {
        givenAPnfsUpdateForAFileWithExcessLocations();
        whenHandleUpdateIsCalled();
        whenTaskIsCreatedAndCalled();
        assertNotNull(repRmMessage);
    }

    @Test
    public void shouldSendSetStickyMessageOnNewLocation()
                    throws CacheException {
        givenAPnfsUpdateForANewFileOnAPoolWithHostAndRackTags();
        whenHandleUpdateIsCalled();
        assertTrue(update.shouldVerifySticky());
    }

    @Test
    public void shouldSendSetStickyMessageOnPoolAddedToGroup()
                    throws CacheException {
        givenAPnfsUpdateFromAPoolScanForPoolAddedToGroup();
        whenHandleUpdateIsCalled();
        assertTrue(update.shouldVerifySticky());
    }

    @Test
    public void shouldTryAnotherSourceOnBrokenFile() throws CacheException, IOException {
        givenAPnfsUpdateForAFileWithOneLocationOffline();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        afterInspectingSourceAndTarget();
        whenOperationFailsWithBrokenFileError();
        whenVerifyIsRun();
        assertTrue(theNewSourceIsDifferent());
    }

    @Test
    public void shouldTryAnotherSourceOnSourceError() throws CacheException, IOException {
        givenAPnfsUpdateForAFileWithOneLocationOffline();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        afterInspectingSourceAndTarget();
        whenOperationFailsWithSourceError();
        whenVerifyIsRun();
        assertTrue(theNewSourceIsDifferent());
    }

    @Test
    public void shouldTryAnotherTargetOnNewTargetFailure()
                    throws CacheException, IOException {
        givenAPnfsUpdateForAFileWithOneLocationOffline();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        afterInspectingSourceAndTarget();
        whenOperationFailsWithNewTargetError();
        whenVerifyIsRun();
        assertTrue(theNewTargetIsDifferent());
    }

    @Test
    public void shouldTryAnotherTargetOnRetryMaxReached()
                    throws CacheException, IOException {
        givenAPnfsUpdateForAFileWithOneLocationOffline();
        whenHandleUpdateIsCalled();
        whenVerifyIsRun();
        afterInspectingSourceAndTarget();
        whenOperationFailsWithRetriableError();
        whenVerifyIsRun();
        whenOperationFailsWithRetriableError();
        whenVerifyIsRun();
        assertTrue(theNewTargetIsDifferent());
    }

    @Test
    public void shouldUnpinAllWhenPinFails() throws CacheException {
        givenAPnfsUpdateForAFileWithExcessLocations();
        whenHandleUpdateIsCalled();
        givenPinWillFail();
        whenTaskIsCreatedAndCalled();
        assertEquals(theNumberOfUnPinMessages(), 15);
    }

    @Test
    public void shouldUnpinAllWhenRemoveFails() throws CacheException {
        givenAPnfsUpdateForAFileWithExcessLocations();
        whenHandleUpdateIsCalled();
        givenRemoveWillFail();
        whenTaskIsCreatedAndCalled();
        assertEquals(theNumberOfUnPinMessages(), 15);
    }

    @Test
    public void shouldUnpinRemainingReplicasAfterRemove()
                    throws CacheException {
        givenAPnfsUpdateForAFileWithExcessLocations();
        whenHandleUpdateIsCalled();
        whenTaskIsCreatedAndCalled();
        assertEquals(theNumberOfUnPinMessages(), 14);
    }

    @Test
    public void shouldUpdateCountWhenNewLocationArrives()
                    throws CacheException {
        givenAPnfsUpdateForANewFileOnAPoolWithHostAndRackTags();
        givenUpdateHasBeenAddedToMapWithCountOf((short) 1);
        givenANewLocationForFile();
        whenHandleUpdateIsCalled();
        assertTrue(theOperationCountIs((short) 2));
    }

    @Test
    public void shouldUpdateWhenLocationArrivesWithPredefinedGroup()
                    throws CacheException {
        givenAPnfsUpdateFromAPoolScan();
        whenHandleUpdateIsCalled();
        assertTrue(theOperationCountIs((short) 1));
    }

    @Test
    public void shouldUpdateWhenNewLocationArrivesWithNoLocations()
                    throws CacheException {
        givenAPnfsUpdateForAFileWithNoLocationsYetInAttributes();
        whenHandleUpdateIsCalled();
        assertTrue(theOperationCountIs((short) 1));
    }

    @Test
    public void shouldReloadOperationOnCrashAndReboot()
                    throws CacheException, InterruptedException, IOException {
        givenAPnfsUpdateForANewFileOnAPoolWithHostAndRackTags();
        givenCheckpointingIsOn();
        whenHandleUpdateIsCalled();
        whenTaskIsCreatedAndCalled();
        whenScanIsRun();
        whenResilienceRebootsWithNewFilesOnPoolsWithHostAndRackTags();
        assertTrue(theOperationHasBeenReloaded());
    }

    private void afterInspectingSourceAndTarget() {
        PnfsOperation operation = pnfsOperationMap.getOperation(update.pnfsId);
        originalSource = operation.getSource();
        originalTarget = operation.getTarget();
    }

    private void assertThatCorrectMigrationTaskWasCreated() {
        Task inner = task.getMigrationTask();
        assertNotNull(inner);
        assertEquals(inner.getPnfsId(), update.pnfsId);
    }

    private void givenACustodialPnfsUpdateFromAPoolScan()
                    throws CacheException {
        loadNewFilesOnPoolsWithHostAndRackTags();
        setUpdateWithGroup(aCustodialOnlineFile(), MessageType.POOL_STATUS_DOWN,
                           SelectionAction.NONE);
    }

    private void givenANewLocationForFile() throws CacheException {
        Integer pool = poolInfoMap.getPoolIndex(update.pool);
        Integer group = poolInfoMap.getResilientPoolGroup(pool);
        Collection<Integer> pools = poolInfoMap.getPoolsOfGroup(group);
        for (Integer p : pools) {
            if (p != pool) {
                attributes.getLocations().add(poolInfoMap.getPool(p));
                setUpdate(attributes, MessageType.NEW_FILE_LOCATION);
                break;
            }
        }
    }

    private void givenANewTagRequirementForFileStorageUnit() {
        Integer unit = poolInfoMap.getStorageUnitIndex(attributes);
        StorageUnitConstraints constraints
                        = poolInfoMap.getStorageUnitConstraints(unit);
        short req = constraints.getRequired();
        storageUnit = poolInfoMap.getGroupName(unit);
        poolInfoMap.setGroupConstraints(storageUnit, req, "host,rack");

    }

    private void givenAPnfsUpdateClearCacheLocationForAFileWithNoLocationsInNamespace()
                    throws CacheException {
        loadNewFilesOnPoolsWithHostAndRackTags();
        setUpdate(aReplicaOnlineFileWithBothTagsButNoLocations(),
                  MessageType.CLEAR_CACHE_LOCATION);
    }

    private void givenAPnfsUpdateForAFileDeletedFromNamespace()
                    throws CacheException {
        loadNewFilesOnPoolsWithHostAndRackTags();
        setUpdate(aDeletedReplicaOnlineFileWithBothTags(),
                  MessageType.CLEAR_CACHE_LOCATION);
    }

    private void givenAPnfsUpdateForAFileWithExcessLocations()
                    throws CacheException {
        loadFilesWithExcessLocations();
        setUpdate(aFileWithAReplicaOnAllResilientPools(),
                        MessageType.POOL_STATUS_RESTART);
    }

    private void givenAPnfsUpdateForAFileWithNoLocationsYetInAttributes()
                    throws CacheException {
        loadNewFilesOnPoolsWithHostAndRackTags();
        setUpdate(aReplicaOnlineFileWithBothTags(),
                  MessageType.NEW_FILE_LOCATION);
        attributes.getLocations().clear();
    }

    private void givenAPnfsUpdateForAFileWithOneLocationOffline()
                    throws CacheException {
        loadFilesWithRequiredLocations();
        setUpdate(aReplicaOnlineFileWithNoTags(), MessageType.POOL_STATUS_DOWN);
        givenSourcePoolIsDown();
    }

    private void givenAPnfsUpdateForAFileWithRequiredLocations()
                    throws CacheException {
        loadFilesWithRequiredLocations();
        setUpdate(aReplicaOnlineFileWithNoTags(),
                        MessageType.POOL_STATUS_RESTART);
    }

    private void givenAPnfsUpdateForANewFileOnAPoolWithHostAndRackTags()
                    throws CacheException {
        loadNewFilesOnPoolsWithNoTags();
        setUpdate(aReplicaOnlineFileWithNoTags(),
                  MessageType.NEW_FILE_LOCATION);
    }

    private void givenAPnfsUpdateForANewFileOnAPoolWithNoTags()
                    throws CacheException {
        loadNewFilesOnPoolsWithNoTags();
        setUpdate(aReplicaOnlineFileWithNoTags(),
                  MessageType.NEW_FILE_LOCATION);
    }

    private void givenAPnfsUpdateForANewFileOnNonResilientPool()
                    throws CacheException {
        loadNonResilientFiles();
        setUpdate(aCustodialNearlineFile(), MessageType.NEW_FILE_LOCATION);
    }

    private void givenAPnfsUpdateForANewFileResilientPoolButRequiringASingleCopy()
                    throws CacheException {
        loadNewFilesOnPoolsWithHostAndRackTags();
        setUpdate(aReplicaOnlineFileWithBothTags(),
                        MessageType.NEW_FILE_LOCATION);
        String key = attributes.getStorageClass() + "@" + attributes.getHsm();
        makeNonResilient(key);
    }

    private void givenAPnfsUpdateFromAPoolScan() throws CacheException {
        loadNewFilesOnPoolsWithHostAndRackTags();
        setUpdateWithGroup(aReplicaOnlineFileWithBothTags(),
                           MessageType.POOL_STATUS_DOWN, SelectionAction.NONE);
    }

    private void givenAPnfsUpdateFromAPoolScanForPoolAddedToGroup()
                    throws CacheException {
        loadNewFilesOnPoolsWithHostAndRackTags();
        setUpdateWithGroup(aReplicaOnlineFileWithBothTags(),
                           MessageType.POOL_STATUS_RESTART, SelectionAction.ADD);
    }

    private void givenAPnfsUpdateFromAPoolScanForPoolRemovedFromGroup()
                    throws CacheException {
        loadNewFilesOnPoolsWithHostAndRackTags();
        setUpdateWithGroup(aReplicaOnlineFileWithBothTags(),
                        MessageType.POOL_STATUS_DOWN, SelectionAction.REMOVE);
    }

    private void givenAllPoolsOfflineExceptSourceAndTarget()
                    throws InterruptedException {
        poolInfoMap.getResilientPools().stream().forEach((p) -> {
            Integer index = poolInfoMap.getPoolIndex(p);
            if (index != originalSource && index != originalTarget) {
                shutPoolDown(p);
            }
        });
    }

    private void givenCheckpointingIsOn() {
        pnfsOperationMap.setCheckpointExpiry(0);
    }

    private void givenPinWillFail() {
        pinMessageFailure = true;
    }

    private void givenRemoveWillFail() {
        rmMessageFailure = true;
    }

    private void givenUpdateHasBeenAddedToMapWithCountOf(int count)
                    throws CacheException {
        Integer pool = poolInfoMap.getPoolIndex(update.pool);
        Integer group = update.getGroup();
        if (group == null) {
            group = poolInfoMap.getResilientPoolGroup(pool);
        }
        Integer unit = poolInfoMap.getStorageUnitIndex(attributes);
        update = new PnfsUpdate(update.pnfsId, update.pool, update.type,
                                pool, group, unit, attributes);
        update.setCount(count);
        pnfsOperationMap.register(update);
    }

    private void givenSourcePoolIsDown() {
        shutPoolDown(update.pool);
    }

    private void givenUnpinWillFail() {
        unpinMessageFailure = true;
    }

    private boolean noOperationHasBeenAdded() {
        return pnfsOperationMap.getOperation(update.pnfsId) == null;
    }

    private void setUpTest()
                    throws InterruptedException, CacheException, IOException {
        setUpBase();
        setPoolMessageProcessor(this);
        setShortExecutionMode(Mode.NOP);
        setLongExecutionMode(Mode.NOP);
        setScheduledExecutionMode(Mode.NOP);
        setInaccessibleFileHandler(this);
        createCounters();
        createPoolOperationHandler();
        createPoolOperationMap();
        createPnfsOperationHandler();
        createPnfsOperationMap();
        initializeCounters();
        wirePoolOperationMap();
        wirePoolOperationHandler();
        wirePnfsOperationMap();
        wirePnfsOperationHandler();
        testNamespaceAccess.setHandler(pnfsOperationHandler);
        poolOperationMap.setRescanWindow(Integer.MAX_VALUE);
        poolOperationMap.setDownGracePeriod(0);
        poolOperationMap.loadPools();
        pnfsOperationMap.reload();
    }

    private void setUpdate(FileAttributes attributes, MessageType type) {
        this.attributes = attributes;
        PnfsId pnfsId = attributes.getPnfsId();
        Iterator<String> iterator = attributes.getLocations().iterator();
        if (iterator.hasNext()) {
            update = new PnfsUpdate(pnfsId, iterator.next(), type);
        } else {
            update = new PnfsUpdate(pnfsId, null, type);
        }
    }

    private void setUpdateWithGroup(FileAttributes attributes, MessageType type,
                    SelectionAction action) {
        this.attributes = attributes;
        PnfsId pnfsId = attributes.getPnfsId();
        Iterator<String> iterator = attributes.getLocations().iterator();
        if (iterator.hasNext()) {
            String pool = iterator.next();
            Integer group = poolInfoMap.getResilientPoolGroup(
                            poolInfoMap.getPoolIndex(pool));
            update = new PnfsUpdate(pnfsId, pool, type, action, group);
        } else {
            update = new PnfsUpdate(pnfsId, null, type);
        }
        suppressAlarm = action == SelectionAction.REMOVE;
    }

    private void shutPoolDown(String pool) {
        PoolStatusChangedMessage message = new PoolStatusChangedMessage(pool,
                        PoolStatusChangedMessage.DOWN);
        message.setPoolMode(new PoolV2Mode(PoolV2Mode.DISABLED_DEAD));
        PoolStateUpdate update = new PoolStateUpdate(message);
        poolInfoMap.updatePoolStatus(update);
    }

    private boolean theNewSourceIsDifferent() {
        PnfsOperation operation = pnfsOperationMap.getOperation(update.pnfsId);
        return operation.getSource() != originalSource;
    }

    private boolean theNewTargetIsDifferent() {
        PnfsOperation operation = pnfsOperationMap.getOperation(update.pnfsId);
        return operation.getTarget() != originalTarget;
    }

    private int theNumberOfPinMessages() {
        if (stickyBitMessages == null) {
            return 0;
        }
        return stickyBitMessages.stream().filter((m) -> m.set).collect(
                        Collectors.toList()).size();
    }

    private int theNumberOfUnPinMessages() {
        if (stickyBitMessages == null) {
            return 0;
        }
        return stickyBitMessages.stream().filter((m) -> !m.set).collect(
                        Collectors.toList()).size();
    }

    private boolean theOperationCountIs(short count) {
        PnfsOperation operation = pnfsOperationMap.getOperation(
                        attributes.getPnfsId());
        return operation != null && operation.getOpCount() == count;
    }

    private boolean theOperationHasBeenReloaded() {
       return pnfsOperationMap.getOperation(attributes.getPnfsId()) != null;
    }

    private void whenHandleUpdateIsCalled() throws CacheException {
        pnfsOperationHandler.handleLocationUpdate(update);
    }

    private void whenOperationFailsFatally() throws IOException {
        pnfsOperationMap.updateOperation(update.pnfsId,
                        new CacheException(CacheException.DEFAULT_ERROR_CODE,
                                        FORCED_FAILURE.toString()));
        pnfsOperationMap.scan();
    }

    private void whenOperationFailsWithBrokenFileError() throws IOException {
        pnfsOperationMap.updateOperation(update.pnfsId,
                        new CacheException(CacheException.BROKEN_ON_TAPE,
                                        "broken"));
        pnfsOperationMap.scan();
    }

    private void whenOperationFailsWithNewTargetError() throws IOException {
        pnfsOperationMap.updateOperation(update.pnfsId,
                        new CacheException(CacheException.FILE_NOT_FOUND,
                                        FORCED_FAILURE.toString()));
        pnfsOperationMap.scan();
    }

    private void whenOperationFailsWithRetriableError() throws IOException {
        pnfsOperationMap.updateOperation(update.pnfsId,
                                         new CacheException(
                                                         CacheException.HSM_DELAY_ERROR,
                                                         FORCED_FAILURE.toString()));
        pnfsOperationMap.scan();
    }

    private void whenOperationFailsWithSourceError() throws IOException {
        pnfsOperationMap.updateOperation(update.pnfsId,
                                         new CacheException(
                                                         CacheException.SELECTED_POOL_FAILED,
                                                         "Source pool failed"));
        pnfsOperationMap.scan();
    }

    private void whenResilienceRebootsWithNewFilesOnPoolsWithHostAndRackTags()
                    throws CacheException, InterruptedException, IOException {
        clearInMemory();
        setUpTest();
        loadNewFilesOnPoolsWithNoTags();
    }

    private void whenScanIsRun() throws IOException{
        pnfsOperationMap.scan();
        /*
         *  Also force a save.
         */
        pnfsOperationMap.runCheckpointNow();
    }

    private void whenSourceAndTargetAreSelected() {
        PnfsOperation op = pnfsOperationMap.getOperation(update.pnfsId);
        op.setSource(4);
        op.setTarget(5);
    }

    private void whenTaskIsCreatedAndCalled() {
        task = new ResilientFileTask(update.pnfsId, false,
                                     pnfsOperationHandler);
        task.call();
    }

    private void whenVerifyIsRun() {
        verifyType = pnfsOperationHandler.handleVerification(attributes,
                        suppressAlarm).name();
    }
}
