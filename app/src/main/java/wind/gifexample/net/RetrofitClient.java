package wind.gifexample.net;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.GsonConverterFactory;
import retrofit2.Retrofit;
import retrofit2.RxJavaCallAdapterFactory;
import wind.gifexample.BuildConfig;

/**
 * Created by wind on 2016/8/24.
 */
public class RetrofitClient {

    private static Retrofit retrofit;


    public static TenorApi getTenorApi() {
        return getRetrofit()
                .create(TenorApi.class);
    }

    private static Retrofit getRetrofit() {
        if (retrofit == null) {
            retrofit = new Retrofit.Builder()
                    .baseUrl(TenorApi.BASE_URL)
                    .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(getHttpClient())
                    .build();
        }
        return retrofit;
    }

    private static OkHttpClient getHttpClient() {
        return new OkHttpClient.Builder()
                .addInterceptor(getLogInterceptor())
                .addInterceptor(getApiKeyInterceptor())
                .build();
    }

    private static Interceptor getLogInterceptor() {
        HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
        interceptor.setLevel(BuildConfig.DEBUG ?
                HttpLoggingInterceptor.Level.BODY : HttpLoggingInterceptor.Level.NONE);
        return interceptor;
    }

    private static Interceptor getApiKeyInterceptor() {
        Interceptor interceptor = new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                Request request = chain.request();
                Request newRequest = request.newBuilder().url(
                        request.url()
                                .newBuilder().addQueryParameter(TenorApi.API_KEY, TenorApi.API_KEY_VALUE)
                                .build()
                ).build();
                return chain.proceed(newRequest);
            }
        };
        return interceptor;
    }

}
