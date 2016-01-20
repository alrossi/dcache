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
package org.dcache.resilience.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.dcache.resilience.data.MessageType;
import org.dcache.resilience.handlers.PnfsOperationHandler.Type;

/**
 * <p>For recording cumulative activity.</p>
 *
 * <p>Maintains maps of AtomicLongs.</p>
 *
 * Created by arossi on 1/30/15.
 */
public final class OperationStatistics {
    private static final Logger LOGGER = LoggerFactory.getLogger(
                    OperationStatistics.class);

    private static final String FORMAT_MSG      = "    %-26s %15s %9s\n";
    private static final String FORMAT_OPS      = "    %-26s %15s %9s %12s\n";
    private static final String FORMAT_TIMING   = "    %-26s %15s %15s\n";
    private static final String FORMAT_POOLS    = "%-24s %15s %15s %15s %12s   %15s %15s %12s\n";
    private static final String FORMAT_FILE     = "%-28s | %15s %9s %9s | %15s %9s %9s %12s | %15s\n";

    private static final String MSGS_TITLE
                    = String.format("%-30s %15s %9s\n",
                                    CounterType.MESSAGE.name(),
                                    "received", "msgs/sec");
    private static final String OPS_TITLE
                    = String.format("%-30s %15s %9s %12s\n",
                                    CounterType.OPERATION.name(),
                                    "completed", "ops/sec", "failed");
    private static final String TIMING_TITLE
                    = String.format("%-30s %15s %15s\n",
                        "PNFS Average Timing (secs)", "waiting", "running");

    private static final String POOLS_TITLE
                    = String.format(FORMAT_POOLS, "TRANSFERS BY POOL",
                                    "from", "to", "failed", "size",
                                    "removed", "failed", "size");

    private static final String LASTSTART = "Running since: %s\n";
    private static final String UPTIME    = "Uptime %s days, %s hours, %s minutes, %s seconds\n\n";
    private static final String LASTCHK   = "Last checkpoint at %s\n";
    private static final String LASTCHKD  = "Last checkpoint took %s seconds\n";
    private static final String LASTCHKCT = "Last checkpoint saved %s records\n\n";

    private static final String[] DIMENSION = { "b", "kb", "mb", "gb", "tb",
                                                "pb", "eb" };

    private static final String[] MSGS      = {
                    MessageType.CLEAR_CACHE_LOCATION.name(),
                    MessageType.CORRUPT_FILE.name(),
                    MessageType.NEW_FILE_LOCATION.name(),
                    MessageType.POOL_STATUS_DOWN.name(),
                    MessageType.POOL_STATUS_RESTART.name() };

    private static final String[] OPS       = {
                    Operation.PNFSID.name(),
                    Operation.POOL_DOWN.name(),
                    Operation.POOL_RESTART.name() };

    private static String getDimension(long count) {
        int i = 0;
        long Q = 1;
        long D = count;
        while (true) {
            count = count / 1024;
            if (count == 0) {
                break;
            }
            ++i;
            Q *= 1024;
        }

        if (i > 0) {
            return String.format("%.2f %s", D / (double) Q, DIMENSION[i]);
        }

        return (String.format("%s %s", D, "b"));
    }

    private static String getRateChangeSinceLast(double current, double last) {
        if (last == 0) {
            return "?";
        }
        double delta = 100*(current - last)/last;
        return String.format("%.2f%%", delta);
    }

    public enum CounterType {
        MESSAGE, OPERATION, CPSRC, CPBYTES, CPTGT, RMCT, RMBYTES, CHCKPT
    }

    enum TagType {
        TOTAL, FAILED, CURRENT, LAST
    }

    private final Date started = new Date();

    private final Map<String, Map<String, AtomicLong>> counterMap
        = Collections.synchronizedMap(new HashMap<String, Map<String, AtomicLong>>());

    private final Set<String> pools = new HashSet<>();

    private long lastCheckpoint         = started.getTime();
    private long lastCheckpointDuration = 0;

    private long totalWait          = 0;
    private long totalRun           = 0;
    private long totalOps           = 0;

    private File statisticsPath;

    public long getCount(String category, String type, boolean failed) {
        String tag = failed ? TagType.FAILED.name() : TagType.TOTAL.name();
        AtomicLong counter = getCounter(category, type, tag);
        if (counter != null) {
            return counter.get();
        }
        return 0;
    }

    public void getCheckpointInfo(StringBuilder builder) {
        AtomicLong total = getCounter(CounterType.CHCKPT.name(),
                                      TagType.TOTAL.name());
        AtomicLong latest = getCounter(CounterType.CHCKPT.name(),
                                       TagType.CURRENT.name());
        builder.append(String.format(LASTCHK, new Date(lastCheckpoint)));
        builder.append(String.format(LASTCHKD,
                                     TimeUnit.MILLISECONDS.toSeconds(
                                                     lastCheckpointDuration)));
        builder.append(String.format(LASTCHKCT, latest.get()));
    }

    public void increment(String source, String target, Type type, long size) {
        String tag = TagType.TOTAL.name();
        AtomicLong count = null;
        AtomicLong bytes = null;

        switch (type) {
            case COPY:
                count = getPoolCounter(source, CounterType.CPSRC.name(), tag);
                if (count != null) {
                    count.incrementAndGet();
                }

                bytes = getPoolCounter(source, CounterType.CPBYTES.name(), tag);
                if (bytes != null) {
                    bytes.addAndGet(size);
                }

                count = getPoolCounter(target, CounterType.CPTGT.name(), tag);
                if (count != null) {
                    count.incrementAndGet();
                }

                bytes = getPoolCounter(target, CounterType.CPBYTES.name(), tag);
                if (bytes != null) {
                    bytes.addAndGet(size);
                }
                break;
            case REMOVE:
                count = getPoolCounter(target, CounterType.RMCT.name(), tag);
                if (count != null) {
                    count.incrementAndGet();
                }

                bytes = getPoolCounter(target, CounterType.RMBYTES.name(), tag);
                if (bytes != null) {
                    bytes.addAndGet(size);
                }
                break;
            default:
        }
    }

    public void incrementFailed(String pool, Type type) {
        AtomicLong count = null;
        switch (type) {
            case COPY:
                count = getPoolCounter(pool, CounterType.CPTGT.name(),
                                       TagType.FAILED.name());
                break;
            case REMOVE:
                count = getPoolCounter(pool, CounterType.RMCT.name(),
                                       TagType.FAILED.name());
                break;
            default:
        }

        if (count != null) {
            count.incrementAndGet();
        }
    }

    public void incrementMessage(String type) {
        increment(CounterType.MESSAGE.name(), type);
    }

    public void incrementOperation(String type) {
        increment(CounterType.OPERATION.name(), type);
    }

    public void incrementOperationFailed(String type) {
        AtomicLong counter = getCounter(CounterType.OPERATION.name(), type,
                                        TagType.FAILED.name());
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    public synchronized void incrementTimings(long inWait, long inRun) {
        totalWait += inWait;
        totalRun  += inRun;
        ++totalOps;
    }

    public void initialize() {
        for (String type : MSGS) {
            registerMessage(type);
        }

        for (String type : OPS) {
            registerOperation(type);
        }

        registerCheckpoint();

        initializeStatisticsFile();
    }

    public String print(String regex) {
        StringBuilder builder = new StringBuilder();

        getRunning(builder);
        getCheckpointInfo(builder);
        printSummary(builder);

        if (regex != null) {
            printPools(regex, builder);
        }

        return builder.toString();
    }

    public String readStatistics(Integer limit, boolean descending) {
        List<String> buffer = new ArrayList<>();

        try (BufferedReader fr = new BufferedReader(new FileReader(statisticsPath))) {
            /*
             *  Title line should always be there.
             */
            buffer.add(fr.readLine());
            int end = limit == null ? Integer.MAX_VALUE : limit+1;
            while (true) {
                String line = fr.readLine();
                if (line == null) {
                    break;
                }

                if (descending) {
                    buffer.add(1, line);
                } else if (buffer.size() < end) {
                    buffer.add(line);
                } else {
                    break;
                }

                if (buffer.size() > end) {
                    buffer.remove(end);
                }
            }
        } catch (FileNotFoundException e) {
            LOGGER.error("Unable to append to statistics file: {}",
                            e.getMessage());
        } catch (IOException e) {
            LOGGER.error("Unrecoverable error during append to "
                                            + "statistics file: {}",
                            e.getMessage());
        }

        StringBuilder builder = new StringBuilder();
        buffer.stream().forEach((l) -> builder.append(l).append("\n"));
        return builder.toString();
    }

    public void recordCheckpoint(long ended, long duration, long count) {
        printStatistics();
        lastCheckpoint = ended;
        lastCheckpointDuration = duration;
        AtomicLong total = getCounter(CounterType.CHCKPT.name(),
                                      TagType.TOTAL.name());
        AtomicLong latest = getCounter(CounterType.CHCKPT.name(),
                                       TagType.CURRENT.name());
        total.addAndGet(count);
        latest.set(count);
        resetLatestCounts();
    }

    public void registerPool(String pool) {
        pools.add(pool);
        counterMap.computeIfAbsent(pool, (p) -> {
            Map<String, AtomicLong> categories = new HashMap<>();
            categories.put(CounterType.CPSRC.name() + TagType.TOTAL,
                                                             new AtomicLong(0));
            categories.put(CounterType.CPTGT.name() + TagType.TOTAL,
                           new AtomicLong(0));
            categories.put(CounterType.CPTGT.name() + TagType.FAILED,
                           new AtomicLong(0));
            categories.put(CounterType.CPBYTES.name() + TagType.TOTAL,
                           new AtomicLong(0));
            categories.put(CounterType.RMCT.name() + TagType.TOTAL,
                           new AtomicLong(0));
            categories.put(CounterType.RMCT.name() + TagType.FAILED,
                           new AtomicLong(0));
            categories.put(CounterType.RMBYTES.name() + TagType.TOTAL,
                           new AtomicLong(0));
            return categories;
        });
    }

    public void setStatisticsPath(String statisticsPath) {
        this.statisticsPath = new File(statisticsPath);
    }

    private AtomicLong getCounter(String category, String tag) {
        return getCounter(category, "", tag);
    }

    private AtomicLong getCounter(String category, String type, String tag) {
        if (category == null) {
            return null;
        }

        Map<String, AtomicLong> map = counterMap.get(category);
        if (map != null) {
            return map.get(type + tag);
        }

        return null;
    }

    private long getRatePerSecond(long value) {
        long elapsed = System.currentTimeMillis() - lastCheckpoint;
        elapsed = TimeUnit.MILLISECONDS.toSeconds(elapsed);
        if (elapsed == 0) {
            return 0L;
        }

        return value/elapsed;
    }

    private AtomicLong getPoolCounter(String pool, String category,
                                      String tag) {
        return getCounter(pool, category, tag);
    }

    private void getRunning(StringBuilder builder) {
        long elapsed = (System.currentTimeMillis() - started.getTime()) / 1000;
        long seconds = elapsed % 60;
        elapsed = elapsed / 60;
        long minutes = elapsed % 60;
        elapsed = elapsed / 60;
        long hours = elapsed % 24;
        long days = elapsed / 24;

        builder.append(String.format(LASTSTART, started));
        builder.append(String.format(UPTIME, days, hours, minutes, seconds));
    }

    private void increment(String category, String type) {
        String total = TagType.TOTAL.name();
        String latest = TagType.CURRENT.name();
        AtomicLong counter = getCounter(category, type, total);
        if (counter != null) {
            counter.incrementAndGet();
        }
        counter = getCounter(category, type, latest);
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    private void initializeStatisticsFile() {
        if (!statisticsPath.exists()) {
            try (FileWriter fw = new FileWriter(statisticsPath, true)) {
                fw.write(String.format(FORMAT_FILE, "CHECKPOINT", "NEWLOC",
                                "HZ", "CHNG", "PNFSOP", "HZ", "CHNG", "FAILED",
                                "CHCKPTD"));
                fw.flush();
            } catch (FileNotFoundException e) {
                LOGGER.error("Unable to initialize statistics file: {}",
                                e.getMessage());
            } catch (IOException e) {
                LOGGER.error("Unrecoverable error during initialization of"
                                                + " statistics file: {}",
                                e.getMessage());
            }
        }
    }

    /**
     * @param regex not <code>null</code>.
     */
    private void printPools(String regex, StringBuilder builder) {

        builder.append(POOLS_TITLE);

        Pattern pattern = regex == null ? null : Pattern.compile(regex);
        String[] pools = this.pools.toArray(new String[0]);
        Arrays.sort(pools);

        long[] totals = new long[] { 0, 0, 0, 0, 0, 0, 0 };
        long[] counts;

        for (String pool : pools) {
            if (!pattern.matcher(pool).find()) {
                continue;
            }

            counts = new long[7];

            counts[0] = getPoolCounter(pool,
                                       CounterType.CPSRC.name(),
                                       TagType.TOTAL.name()).get();
            counts[1] = getPoolCounter(pool,
                                       CounterType.CPTGT.name(),
                                       TagType.TOTAL.name()).get();
            counts[2] = getPoolCounter(pool,
                                       CounterType.CPTGT.name(),
                                       TagType.FAILED.name()).get();
            counts[3] = getPoolCounter(pool,
                                       CounterType.CPBYTES.name(),
                                       TagType.TOTAL.name()).get();
            counts[4] = getPoolCounter(pool,
                                       CounterType.RMCT.name(),
                                       TagType.TOTAL.name()).get();
            counts[5] = getPoolCounter(pool,
                                       CounterType.RMCT.name(),
                                       TagType.FAILED.name()).get();
            counts[6] = getPoolCounter(pool,
                                       CounterType.RMBYTES.name(),
                                       TagType.TOTAL.name()).get();

            builder.append(String.format(FORMAT_POOLS, pool,
                                         counts[0],
                                         counts[1],
                                         counts[2],
                                         getDimension(counts[3]),
                                         counts[4],
                                         counts[5],
                                         getDimension(counts[6])));

            for (int i = 0; i < totals.length; i++) {
                totals[i] += counts[i];
            }
        }

        builder.append("\n");
        builder.append(String.format(FORMAT_POOLS, "TOTALS",
                                     totals[0],
                                     totals[1],
                                     totals[2],
                                     getDimension(totals[3]),
                                     totals[4],
                                     totals[5],
                                     getDimension(totals[6])));
    }

    /**
     * <p>Append summary line to file.</p>
     */
    private void printStatistics() {
        String[] category = {CounterType.MESSAGE.name(),
                             CounterType.OPERATION.name(),
                             CounterType.CHCKPT.name()};

        String[] type = {MessageType.NEW_FILE_LOCATION.name(),
                         Operation.PNFSID.name()};

        String[] tag = {TagType.TOTAL.name(),
                        TagType.CURRENT.name(),
                        TagType.LAST.name(),
                        TagType.FAILED.name()};

        long[] received = { getCounter(category[0], type[0], tag[0]).get(),
                            getCounter(category[1], type[1], tag[0]).get()};

        long[] current = {  getCounter(category[0], type[0], tag[1]).get(),
                            getCounter(category[1], type[1], tag[1]).get(),
                            getCounter(category[2], tag[1]).get()};

        long[] last    = {  getCounter(category[0], type[0], tag[2]).get(),
                            getCounter(category[1], type[1], tag[2]).get()};

        long failed =       getCounter(category[1], type[1], tag[3]).get();

        try (FileWriter fw = new FileWriter(statisticsPath, true)) {
            fw.write(String.format(FORMAT_FILE, new Date(lastCheckpoint),
                            received[0], getRatePerSecond(current[0]),
                            getRateChangeSinceLast(current[0], last[0]),
                            received[1], getRatePerSecond(current[1]),
                            getRateChangeSinceLast(current[1], last[1]), failed,
                            current[2]));
            fw.flush();
        } catch (FileNotFoundException e) {
            LOGGER.error("Unable to append to statistics file: {}",
                            e.getMessage());
        } catch (IOException e) {
            LOGGER.error("Unrecoverable error during append to "
                                            + "statistics file: {}",
                         e.getMessage());
        }
    }

    private void printSummary(StringBuilder builder) {
        long received = 0;
        long failed = 0;
        long current = 0;

        builder.append(MSGS_TITLE);
        String category = CounterType.MESSAGE.name();
        for (String t : MSGS) {
            received = getCounter(category, t, TagType.TOTAL.name()).get();
            current = getCounter(category, t, TagType.CURRENT.name()).get();
            builder.append(String.format(FORMAT_MSG, t, received,
                                         getRatePerSecond(current)));
        }

        builder.append("\n");

        category = CounterType.OPERATION.name();
        builder.append(OPS_TITLE);
        for (String t : OPS) {
            received = getCounter(category, t, TagType.TOTAL.name()).get();
            failed = getCounter(category, t, TagType.FAILED.name()).get();
            current = getCounter(category, t, TagType.CURRENT.name()).get();
            builder.append(String.format(FORMAT_OPS, t, received,
                                         getRatePerSecond(current),
                                         failed));
        }

        builder.append("\n");
        builder.append(TIMING_TITLE);

        if (totalOps == 0) {
            builder.append(String.format(FORMAT_TIMING, "0.00", "0.00"));
        } else {
            double Q = (double) totalOps;
            builder.append(String.format(FORMAT_TIMING,
                            String.format("%.2f %s", (double)totalWait/Q),
                            String.format("%.2f %s", (double)totalRun/Q)));
        }

        builder.append("\n");
    }

    private void registerCheckpoint() {
        Map<String, AtomicLong> map =
                        counterMap.computeIfAbsent(CounterType.CHCKPT.name(),
                                                   (m) -> {return new HashMap<>();});
        map.computeIfAbsent(TagType.TOTAL.name(), (t) -> {return new AtomicLong(0);});
        map.computeIfAbsent(TagType.CURRENT.name(), (t) -> {return new AtomicLong(0);});
        map.computeIfAbsent(TagType.LAST.name(), (t) -> {return new AtomicLong(0);});
    }

    private void registerMessage(String type) {
        Map<String, AtomicLong> map =
                        counterMap.computeIfAbsent(CounterType.MESSAGE.name(),
                                                   (m) -> {return new HashMap<>();});
        map.computeIfAbsent(type+TagType.TOTAL.name(), (t) -> {return new AtomicLong(0);});
        map.computeIfAbsent(type+TagType.CURRENT.name(), (t) -> {return new AtomicLong(0);});
        map.computeIfAbsent(type+TagType.LAST.name(), (t) -> {return new AtomicLong(0);});
    }

    private void registerOperation(String type) {
        Map<String, AtomicLong> map =
                        counterMap.computeIfAbsent(CounterType.OPERATION.name(),
                                                   (m) -> {return new HashMap<>();});
        map.computeIfAbsent(type+TagType.TOTAL.name(), (t) -> {return new AtomicLong(0);});
        map.computeIfAbsent(type+TagType.FAILED.name(), (t) -> {return new AtomicLong(0);});
        map.computeIfAbsent(type+TagType.CURRENT.name(), (t) -> {return new AtomicLong(0);});
        map.computeIfAbsent(type+TagType.LAST.name(), (t) -> {return new AtomicLong(0);});
    }

    private void resetLatestCounts() {
        String category = CounterType.MESSAGE.name();
        for (String t : MSGS) {
            AtomicLong current = getCounter(category, t, TagType.CURRENT.name());
            getCounter(category, t, TagType.LAST.name()).set(current.get());
            current.set(0);
        }
        category = CounterType.OPERATION.name();
        for (String t : OPS) {
            AtomicLong current = getCounter(category, t, TagType.CURRENT.name());
            getCounter(category, t, TagType.LAST.name()).set(current.get());
            current.set(0);
        }
    }
}
