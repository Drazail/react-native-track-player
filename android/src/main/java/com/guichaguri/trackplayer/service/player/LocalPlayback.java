package com.guichaguri.trackplayer.service.player;

import android.content.Context;
import android.util.Log;
import com.facebook.react.bridge.Promise;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.database.DatabaseProvider;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.guichaguri.trackplayer.service.MusicManager;
import com.guichaguri.trackplayer.service.MusicService;
import com.guichaguri.trackplayer.service.Utils;
import com.guichaguri.trackplayer.service.models.Track;
import java.io.File;
import java.util.*;

/**
 * @author Drazail
 */
public class LocalPlayback extends ExoPlayback<SimpleExoPlayer> {

    private final long cacheMaxSize;

    private SimpleCache cache;
    private ConcatenatingMediaSource source;
    private boolean prepared = false;
    
    private final MusicService service;

    public LocalPlayback(MusicService service, Context context, MusicManager manager, SimpleExoPlayer player, long maxCacheSize) {
        super(context, manager, player);
        this.cacheMaxSize = maxCacheSize;
        this.service = service;
    }

    @Override
    public void initialize() {

        if(cacheMaxSize > 0) {
            File cacheDir = new File(context.getCacheDir(), "TrackPlayer");
            DatabaseProvider db = new ExoDatabaseProvider(context);
            cache = new SimpleCache(cacheDir, new LeastRecentlyUsedCacheEvictor(cacheMaxSize), db);
            Log.d(Utils.LOG, "cache: LeastRecentlyUsedCacheEvictor");
        } else if (cacheMaxSize == 0) {
            File cacheDir = new File(context.getFilesDir(), "TrackPlayerPersisting");
            DatabaseProvider db = new ExoDatabaseProvider(context);
            NoOpCacheEvictor NoOpEvictor = new NoOpCacheEvictor();
            cache = new SimpleCache(cacheDir, NoOpEvictor, db);
            Log.d(Utils.LOG, "cache: NoOpEvictor");
        } else if (cacheMaxSize < 0) {
            File cacheDir = new File(context.getFilesDir(), "TrackPlayerCustomEvictor");
            DatabaseProvider db = new ExoDatabaseProvider(context);
            cache = new SimpleCache(cacheDir, new Evictor(service, -cacheMaxSize),db);
            Log.d(Utils.LOG, "cache: Evictor");

        } else {
            cache = null;
        }

        super.initialize();

        resetQueue();
    } 

    public DataSource.Factory enableCaching(DataSource.Factory ds) {
        if(cache == null ) return ds;

        return new CacheDataSourceFactory(cache, ds);
    }

    private void prepare() {
        if(!prepared) {
            Log.d(Utils.LOG, "Preparing the media source...");
            player.prepare(source, false, false);
            prepared = true;
        }
    }

    @Override
    public void add(Track track, int index, Promise promise) {
        queue.add(index, track);
        MediaSource trackSource = track.toMediaSource(context, this);
        source.addMediaSource(index, trackSource, manager.getHandler(), Utils.toRunnable(promise));

        prepare();
    }

    @Override
    public void updateTrackObject (Track track, int index, Promise promise){
        try {
            int currentIndex = player.getCurrentWindowIndex();
            if (index < 0 || index > queue.size()) {
                promise.resolve(null);
            } else {
    
                queue.set(index, track);
                MediaSource trackSource = track.toMediaSource(context, this);
                source.removeMediaSource(index);
                source.addMediaSource(index, trackSource, manager.getHandler(), Utils.toRunnable(promise));
    
                prepare();
                }
            }catch(Exception ex) {
            Log.w(Utils.LOG, "Couldnt update Track", ex);
            }
        }

    @Override
    public void add(Collection<Track> tracks, int index, Promise promise) {
        List<MediaSource> trackList = new ArrayList<>();

        for(Track track : tracks) {
            trackList.add(track.toMediaSource(context, this));
        }

        queue.addAll(index, tracks);
        source.addMediaSources(index, trackList, manager.getHandler(), Utils.toRunnable(promise));

        prepare();
    }

    @Override
    public void remove(List<Integer> indexes, Promise promise) {
        int currentIndex = player.getCurrentWindowIndex();

        // Sort the list so we can loop through sequentially
        Collections.sort(indexes);

        for(int i = indexes.size() - 1; i >= 0; i--) {
            int index = indexes.get(i);

            // Skip indexes that are the current track or are out of bounds
            if(index == currentIndex || index < 0 || index >= queue.size()) {
                // Resolve the promise when the last index is invalid
                if(i == 0) promise.resolve(null);
                continue;
            }

            queue.remove(index);

            if(i == 0) {
                source.removeMediaSource(index, manager.getHandler(), Utils.toRunnable(promise));
            } else {
                source.removeMediaSource(index);
            }
        }
    }

    @Override
    public void move(int index, int newIndex, Promise promise) {
        queue.add(newIndex, queue.remove(index));
        source.moveMediaSource(index, newIndex, manager.getHandler(), Utils.toRunnable(promise));
    }

    @Override
    public void shuffle(final Promise promise) {
        Random rand = new Random();
        int length = queue.size();

        // Fisher-Yates shuffle
        for (int i = 0; i < length; i++) {
            int swapIndex = rand.nextInt(i + 1);

            queue.add(swapIndex, queue.remove(i));

            if (length - 1 == i) {
                // Resolve the promise after the last move command
                source.moveMediaSource(i, swapIndex, manager.getHandler(), Utils.toRunnable(promise));
            } else {
                source.moveMediaSource(i, swapIndex);
            }
        }
    }

    @Override
    public void shuffleFromIndex(final int index,  Promise promise) {
        Random rand = new Random();
        int length = queue.size();

        // Fisher-Yates shuffle
        for (int i = index+1; i < length; i++) {

            int swapIndex = rand.nextInt(length - i)+i;
            queue.add(swapIndex, queue.remove(i));

            if (length - 1 == i) {
                // Resolve the promise after the last move command
                source.moveMediaSource(i, swapIndex, manager.getHandler(), Utils.toRunnable(promise));
            } else {
                source.moveMediaSource(i, swapIndex);
            }
        }
    }

    @Override
    public void setRepeatMode(int repeatMode) {
        player.setRepeatMode(repeatMode);
    }
    
    @Override
    public int getRepeatMode() {
        return player.getRepeatMode();
    }


    @Override
    public void removeUpcomingTracks() {
        int currentIndex = player.getCurrentWindowIndex();
        if (currentIndex == C.INDEX_UNSET) return;

        for (int i = queue.size() - 1; i > currentIndex; i--) {
            queue.remove(i);
            source.removeMediaSource(i);
        }
    }

    private void resetQueue() {
        queue.clear();

        source = new ConcatenatingMediaSource();
        player.prepare(source, true, true);
        prepared = false; // We set it to false as the queue is now empty

        lastKnownWindow = C.INDEX_UNSET;
        lastKnownPosition = C.POSITION_UNSET;

        manager.onReset();
    }

    @Override
    public void play() {
        prepare();
        super.play();
    }

    @Override
    public void stop() {
        super.stop();
        prepared = false;
    }

    @Override
    public void seekTo(long time) {
        prepare();
        super.seekTo(time);
    }

    @Override
    public void reset() {
        Track track = getCurrentTrack();
        long position = player.getCurrentPosition();

        super.reset();
        resetQueue();

        manager.onTrackUpdate(track, position, null);
    }

    @Override
    public float getPlayerVolume() {
        return player.getVolume();
    }

    @Override
    public void setPlayerVolume(float volume) {
        player.setVolume(volume);
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if(playbackState == Player.STATE_ENDED) {
            prepared = false;
        }

        super.onPlayerStateChanged(playWhenReady, playbackState);
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        prepared = false;
        super.onPlayerError(error);
    }

    @Override
    public void destroy() {
        super.destroy();

        if(cache != null) {
            try {
                cache.release();
                cache = null;
            } catch(Exception ex) {
                Log.w(Utils.LOG, "Couldn't release the cache properly", ex);
            }
        }
    }

}
