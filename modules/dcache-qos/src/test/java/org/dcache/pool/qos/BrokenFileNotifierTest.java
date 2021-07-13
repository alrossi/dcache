package org.dcache.pool.qos;

import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import org.dcache.cells.CellStub;
import org.dcache.pool.repository.StateChangeEvent;

public class BrokenFileNotifierTest {
    private static final String poolName ="testPool";

    @Mock
    CellStub pool;

    @Mock
    CellStub corruptFileTopic;

    BrokenFileNotifier brokenFileNotifier;
    StateChangeEvent stateChangeEvent;

}
