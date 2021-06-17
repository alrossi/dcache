package org.dcache.mock;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;

import diskCacheV111.util.PnfsId;

import org.dcache.pool.repository.CacheEntry;
import org.dcache.pool.repository.ReplicaState;
import org.dcache.pool.repository.StickyRecord;
import org.dcache.vehicles.FileAttributes;

public class CacheEntryBuilder {
    public static CacheEntryBuilder aCacheEntry()
    {
        return new CacheEntryBuilder();
    }

    private PnfsId pnfsId;
    private long replicaSize;
    private long creationTime;
    private long lastAccessTime;
    private int linkCount;
    private boolean sticky;

    FileAttributes           fileAttributes;
    ReplicaState             replicaState;

    final Collection<StickyRecord> stickyRecords = new HashSet<>();

    public CacheEntryBuilder withPnfsId(String pnfsId)
    {
        this.pnfsId = new PnfsId(pnfsId);
        return this;
    }

    public CacheEntryBuilder withReplicaSize(long replicaSize)
    {
        this.replicaSize = replicaSize;
        return this;
    }

    public CacheEntryBuilder withCreationTime(Instant creationTime)
    {
        this.creationTime = creationTime.toEpochMilli();
        return this;
    }

    public CacheEntryBuilder withLastAccessTime(Instant lastAccessTime)
    {
        this.lastAccessTime = lastAccessTime.toEpochMilli();
        return this;
    }

    public CacheEntryBuilder withLinkCount(int linkCount)
    {
        this.linkCount = linkCount;
        return this;
    }

    public CacheEntryBuilder whichIsSticky()
    {
        this.sticky = true;
        return this;
    }

    public CacheEntryBuilder whichIsNotSticky()
    {
        this.sticky = false;
        return this;
    }

    public CacheEntryBuilder withStickyRecord(String owner, long expiration) {
        stickyRecords.add(new StickyRecord(owner, expiration));
        return this;
    }

    CacheEntry entry;

}
