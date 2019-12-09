package com.guichaguri.trackplayer.service.player;


import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.cache.CacheEvictor;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheSpan;
import com.guichaguri.trackplayer.service.Utils;

import android.util.Log;
import java.util.Comparator;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.NavigableSet;

/**
 * @author Drazail
 */

public final class Evictor implements CacheEvictor, Comparator<CacheSpan> {

    private final long maxBytes;
    private final TreeSet<CacheSpan> leastRecentlyUsed;

    private long currentSize;

    public Evictor(long maxBytes) {
        this.maxBytes = maxBytes;
        this.leastRecentlyUsed = new TreeSet<>(this);
    }

    @Override
    public boolean requiresCacheSpanTouches() {
        return true;
    }

    @Override
    public void onCacheInitialized() {
        Log.d(Utils.LOG, "cache initialized");
    }

    @Override
    public void onStartFile(Cache cache, String key, long position, long length) {
        Log.d(Utils.LOG, "cache onStartFile : Cache:"+cache+"/ key: "+key+"/ position: "+ position +"/ Length: "+ length + "//");
        if (length != C.LENGTH_UNSET) {
            evictCache(cache, length);
        }
    }

    @Override
    public void onSpanAdded(Cache cache, CacheSpan span) {
        Log.d(Utils.LOG, "cache onSpanAdded : Cache:"+cache+"/ CacheSpan: "+span+"//");
        leastRecentlyUsed.add(span);
        currentSize += span.length;
        evictCache(cache, 0);
    }

    @Override
    public void onSpanRemoved(Cache cache, CacheSpan span) {
        Log.d(Utils.LOG, "cache onSpanRemoved : Cache:"+cache+"/ CacheSpan: "+span+"//");
        leastRecentlyUsed.remove(span);
        currentSize -= span.length;
    }

    @Override
    public void onSpanTouched(Cache cache, CacheSpan oldSpan, CacheSpan newSpan) {
        Log.d(Utils.LOG, "cache onSpanTouched : Cache:"+cache+"/ oldSpan: "+oldSpan+"/ newSpan: "+newSpan+"//");
        onSpanRemoved(cache, oldSpan);
        onSpanAdded(cache, newSpan);
    }

    @Override
    public int compare(CacheSpan lhs, CacheSpan rhs) {
        long lastTouchTimestampDelta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp;
        if (lastTouchTimestampDelta == 0) {
            // Use the standard compareTo method as a tie-break.
            return lhs.compareTo(rhs);
        }
        return lhs.lastTouchTimestamp < rhs.lastTouchTimestamp ? -1 : 1;
    }

    private void evictCache(Cache cache, long requiredSpace) {
        Log.d(Utils.LOG, "cache evictCache : Cache:"+cache+"/ requiredSpace: "+requiredSpace+"//");

        while (currentSize + requiredSpace > maxBytes && !leastRecentlyUsed.isEmpty()) {
            try {
                cache.removeSpan(leastRecentlyUsed.first());
            } catch (Cache.CacheException e) {
                // do nothing.
            }
        }
    }

    public void evictSpans(Cache cache, String key) {
        Log.d(Utils.LOG, "cache evictSpans for : Cache:"+cache+"/ key: "+key+"//");
        NavigableSet<CacheSpan> spansToRemove = cache.getCachedSpans(key);
        Iterator<CacheSpan> itr = spansToRemove.iterator();

            try {
                while (itr.hasNext()) {
                    cache.removeSpan(itr.next());
                }
            } catch (Cache.CacheException e) {
                // do nothing.
            }
    }
}
