package wind.gifexample.image;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.facebook.imagepipeline.core.PriorityThreadFactory;
import com.jakewharton.disklrucache.DiskLruCacheHelper;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import pl.droidsonroids.gif.GifDrawable;

/**
 * Created by wind on 2016/8/19.
 */
public class GifImageLoader {

    private static final String TAG = GifImageLoader.class.getSimpleName();

    private static GifImageLoader instance;

    public interface LoadListener {
        void onLoadComplete(GifDrawable gifDrawable);
    }

    LruCache<String, byte[]> lruCache;

    OkHttpClient okHttpClient;

    Executor taskExecutorNetwork;

    Executor taskExecutorDiskCache;

    Executor taskDistributor;

    DiskLruCacheHelper diskLruCache;

    boolean cacheInDisk = false;


    private final Map<Integer, String> cacheKeysForImage = Collections
            .synchronizedMap(new HashMap<Integer, String>());

    private final Map<String, ReentrantLock> uriLocks = new WeakHashMap<>();


    private Handler mHandler;

    public static GifImageLoader getInstance() {
        if (instance == null) {
            throw new RuntimeException("gif image loader hasn't init");
        }
        return instance;
    }

    public static void init(Context context) {
        instance = new GifImageLoader(context);
    }


    private GifImageLoader(Context context) {
        lruCache = new LruCache<String, byte[]>(1024 * 1024 * 10) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                return value.length;
            }
        };
        okHttpClient = new OkHttpClient.Builder()
                .dispatcher(new Dispatcher(Executors.newFixedThreadPool(10)))
                .build();
        taskExecutorNetwork = Executors.newFixedThreadPool(10);
        taskDistributor = Executors.newCachedThreadPool(new PriorityThreadFactory(Thread.NORM_PRIORITY));
        taskExecutorDiskCache = Executors.newFixedThreadPool(5);
        try {
            diskLruCache = new DiskLruCacheHelper(context, context.getCacheDir(), 1024 * 1024 * 40);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void disPlayGif(ImageView imageView, String url) {
        disPlayGif(imageView, url, null);
    }

    public void disPlayGif(final ImageView imageView, final String url, final LoadListener listener) {
        imageView.setImageDrawable(null);
        final WeakReference<ImageView> imageRef = new WeakReference<>(imageView);
        final Integer viewId = imageView.hashCode();
        taskDistributor.execute(new Runnable() {
            @Override
            public void run() {
                prepareLoadImage(viewId, url);
                byte[] bytes = lruCache.get(url);
                if (bytes != null) {
                    Log.d(TAG, "taskDistributor load from memory");
                    postResultDrawable(imageRef, url, viewId, newGifDrawable(bytes), listener);
                    cancelLoadImage(imageView.hashCode());
                } else if (cacheInDisk) {
                    DiskLoadTask task = new DiskLoadTask(url, imageRef, viewId, listener);
                    taskExecutorDiskCache.execute(task);
                } else {
                    NetworkLoadAndDisplayTask task = new NetworkLoadAndDisplayTask(imageRef, viewId, url, listener);
                    taskExecutorNetwork.execute(task);
                }
            }
        });

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


    void prepareLoadImage(Integer viewId, String url) {
        cacheKeysForImage.put(viewId, url);
    }

    void cancelLoadImage(Integer viewId) {
        cacheKeysForImage.remove(viewId);
    }


    String getImageLoadUri(Integer viewId) {
        return cacheKeysForImage.get(viewId);
    }

    class DiskLoadTask implements Runnable {

        String url;
        WeakReference<ImageView> imageRef;
        Integer viewId;
        LoadListener listener;

        public DiskLoadTask(String url, WeakReference<ImageView> imageRef, Integer viewId, LoadListener listener) {
            this.url = url;
            this.imageRef = imageRef;
            this.viewId = viewId;
            this.listener = listener;
        }

        @Override
        public void run() {
            byte[] bytes = diskLruCache.getAsBytes(url);
            if (bytes != null) {
                Log.d(TAG, "taskExecutorDiskCache load from disk");
                postResultDrawable(imageRef, url, viewId, newGifDrawable(bytes), listener);
                lruCache.put(url, bytes);
                cancelLoadImage(viewId);
            } else {
                NetworkLoadAndDisplayTask task = new NetworkLoadAndDisplayTask(imageRef, viewId, url, listener);
                taskExecutorNetwork.execute(task);
            }
        }
    }


    class NetworkLoadAndDisplayTask implements Runnable {

        String url;
        WeakReference<ImageView> imageRef;
        Integer viewId;
        LoadListener loadListener;

        public NetworkLoadAndDisplayTask(WeakReference<ImageView> imageRef, Integer viewId, String url, LoadListener listener) {
            this.url = url;
            this.viewId = viewId;
            loadListener = listener;
            this.imageRef = imageRef;
        }

        @Override
        public void run() {

            ReentrantLock lock = getUrlLock(url);
            if (lock.isLocked()) {
                Log.d(TAG, "url lock is locked, wait here");
            }
            lock.lock();


            try {
                byte[] bytes;  //前面加锁等待，再次判断缓存，因为可能已经加载完毕
                if ((bytes = lruCache.get(url)) != null) {
                    Log.d(TAG, "lock open,image already loaded in memory");
                    postResultDrawable(imageRef, url, viewId, newGifDrawable(bytes), loadListener);
                } else if (cacheInDisk && (bytes = diskLruCache.getAsBytes(url)) != null) {
                    Log.d(TAG, "lock open,image already loaded in disk");
                    postResultDrawable(imageRef, url, viewId, newGifDrawable(bytes), loadListener);
                } else {
                    Response response = okHttpClient.newCall(new Request.Builder()
                            .url(url)
                            .tag(url)
                            .get().build()).execute();

                    bytes = response.body().bytes();
                    Log.d(TAG, "load image from network success url=" + url);
                    postResultDrawable(imageRef, url, viewId, newGifDrawable(bytes), loadListener);
                    lruCache.put(url, bytes);
                    if (cacheInDisk) {
                        diskLruCache.put(url, bytes);
                        diskLruCache.flush();
                    }
                }
                cancelLoadImage(viewId);
            } catch (IOException e) {
                e.printStackTrace();
            }
            lock.unlock();
        }

    }

    boolean isViewReused(String url, Integer viewId) {
        return !url.equals(getImageLoadUri(viewId));
    }

    void postResultDrawable(final WeakReference<ImageView> imageRef, String url, Integer viewId,
                            final GifDrawable drawable, final LoadListener loadListener) {
        if (isViewReused(url, viewId)) {
            Log.d(TAG, "post result view reused");
            return;
        }
        Log.d(TAG, "post result");
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                ImageView imageView = imageRef.get();
                if (imageView != null) {
                    imageView.setImageDrawable(drawable);
                }
                if (loadListener != null) {
                    loadListener.onLoadComplete(drawable);
                }
            }
        });
    }

}
