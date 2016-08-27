package wind.gifexample.image;

import android.content.Context;
import android.support.annotation.DrawableRes;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.facebook.imagepipeline.core.PriorityThreadFactory;
import com.jakewharton.disklrucache.DiskLruCacheHelper;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import pl.droidsonroids.gif.GifDrawable;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.schedulers.Schedulers;

/**
 * Created by wind on 2016/8/19.
 */
public class GifImageLoader {

    private static final String TAG = GifImageLoader.class.getSimpleName();

    private static GifImageLoader instance;

    public interface LoadListener {
        void onLoadComplete(GifDrawable gifDrawable);
    }

    public static class Options {

        public boolean cacheOnDisk;
        public boolean clearDrawable;
        @DrawableRes
        public int imageOnLoading;

        public Options cacheOnDisk(boolean cacheOnDisk) {
            this.cacheOnDisk = cacheOnDisk;
            return this;
        }

        public Options clearDrawable(boolean clearDrawable) {
            this.clearDrawable = clearDrawable;
            return this;
        }

        public Options imageOnLoading(@DrawableRes int imageOnLoading) {
            this.imageOnLoading = imageOnLoading;
            return this;
        }


    }

    LruCache<String, byte[]> memoryLruCache;

    OkHttpClient okHttpClient;

    Executor taskExecutorNetwork;

    Executor taskExecutorDiskCache;

    Executor taskDistributor;

    DiskLruCacheHelper diskLruCache;

    public static Options defaultOptions = new Options()
            .cacheOnDisk(true)
            .clearDrawable(true);


    private final Map<Integer, String> cacheKeysForImage = new ConcurrentHashMap<>();

    private final Map<String, ReentrantLock> uriLocks = new WeakHashMap<>();


    public static GifImageLoader getInstance() {
        if (instance == null) {
            throw new RuntimeException("gif image loader hasn't init");
        }
        return instance;
    }

    public static void init(Context context) {
        instance = new GifImageLoader(context, 10, 50);
    }


    private GifImageLoader(Context context, int memoryCacheSize, int diskCacheSize) {

        memoryLruCache = new LruCache<String, byte[]>(memoryCacheSize * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                return value.length;
            }
        };
        okHttpClient = new OkHttpClient.Builder().build();
        taskExecutorNetwork = Executors.newFixedThreadPool(10);
        taskDistributor = Executors.newCachedThreadPool(new PriorityThreadFactory(Thread.NORM_PRIORITY));
        taskExecutorDiskCache = Executors.newFixedThreadPool(5);
        try {
            diskLruCache = new DiskLruCacheHelper(context, context.getCacheDir(), diskCacheSize * 1024 * 1024);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void clearMemoryCache() {
        memoryLruCache.evictAll();
    }


    GifDrawable newGifDrawable(byte[] bytes) {
        GifDrawable drawable = null;
        try {
            drawable = new GifDrawable(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return drawable;
    }

    ReentrantLock getUrlLock(String url) {
        ReentrantLock lock = uriLocks.get(url);
        if (lock == null) {
            lock = new ReentrantLock();
            uriLocks.put(url, lock);
        }
        return lock;
    }


    public void displayGif(final ImageView imageView, final String url) {
        displayGif(imageView, url, defaultOptions, null);
    }

    public void displayGif(final ImageView imageView, final String url, final Options options, final LoadListener listener) {
        if (options.clearDrawable) {
            imageView.setImageDrawable(null);
        }
        if (options.imageOnLoading > 0) {
            imageView.setImageResource(options.imageOnLoading);
        }
        final WeakReference<ImageView> imageRef = new WeakReference<>(imageView);
        final Integer viewId = imageView.hashCode();


        cacheKeysForImage.put(viewId, url);


        Observable.concat(loadFromMemory(url),
                options.cacheOnDisk ? loadFromDisk(url) : Observable.<byte[]>empty(),
                loadFromNetwork(url, options))
                .first(new Func1<byte[], Boolean>() {
                    @Override
                    public Boolean call(byte[] bytes) {
                        return bytes != null;
                    }
                }).map(new Func1<byte[], GifDrawable>() {
            @Override
            public GifDrawable call(byte[] bytes) {
                return newGifDrawable(bytes);
            }
        }).subscribeOn(Schedulers.from(taskDistributor))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Action1<GifDrawable>() {
                    @Override
                    public void call(GifDrawable gifDrawable) {
                        ImageView image = imageRef.get();
                        if (image != null && url.equals(cacheKeysForImage.get(viewId))) {
                            image.setImageDrawable(gifDrawable);
                            cacheKeysForImage.remove(viewId);
                            if (listener != null) {
                                listener.onLoadComplete(gifDrawable);
                            }
                        }
                    }
                });

    }

    Observable<byte[]> loadFromMemory(final String url) {
        return Observable.create(new Observable.OnSubscribe<byte[]>() {
            @Override
            public void call(Subscriber<? super byte[]> subscriber) {
                byte[] bytes = memoryLruCache.get(url);
                if (bytes != null) {
                    Log.d(TAG, "load from memory ");
                    subscriber.onNext(bytes);
                }
                subscriber.onCompleted();
            }
        }).subscribeOn(Schedulers.from(taskDistributor));
    }

    Observable<byte[]> loadFromDisk(final String url) {
        return Observable.create(new Observable.OnSubscribe<byte[]>() {
            @Override
            public void call(Subscriber<? super byte[]> subscriber) {
                byte[] bytes = diskLruCache.getAsBytes(url);
                if (bytes != null) {
                    Log.d(TAG, "load from disk ");
                    subscriber.onNext(bytes);
                    memoryLruCache.put(url, bytes);
                }
                subscriber.onCompleted();
            }
        }).subscribeOn(Schedulers.from(taskExecutorDiskCache));
    }

    Observable<byte[]> loadFromNetwork(final String url, final Options options) {
        return Observable.create(new Observable.OnSubscribe<byte[]>() {
            @Override
            public void call(Subscriber<? super byte[]> subscriber) {
                try {
                    ReentrantLock lock = getUrlLock(url);
                    if (lock.isLocked()) {
                        Log.d(TAG, "url locked , wait here");
                    }
                    lock.lock();

                    byte[] bytes;
                    if ((bytes = memoryLruCache.get(url)) != null) {
                        Log.d(TAG, "unlock , load from memory ");
                        subscriber.onNext(bytes);
                    } else if ((bytes = diskLruCache.getAsBytes(url)) != null) {
                        Log.d(TAG, "unlock , load from disk ");
                        subscriber.onNext(bytes);
                        memoryLruCache.put(url, bytes);
                    } else {
                        Response response = okHttpClient.newCall(new Request.Builder()
                                .url(url)
                                .get().build()).execute();
                        bytes = response.body().bytes();
                        Log.d(TAG, "load from network");
                        subscriber.onNext(bytes);
                        if (options.cacheOnDisk) {
                            diskLruCache.put(url, bytes);
                        }
                        memoryLruCache.put(url, bytes);
                    }

                    subscriber.onCompleted();
                    lock.unlock();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).subscribeOn(Schedulers.from(taskExecutorNetwork));
    }

}
