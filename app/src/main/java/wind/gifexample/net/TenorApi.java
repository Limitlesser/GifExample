package wind.gifexample.net;

import retrofit2.http.GET;
import retrofit2.http.Query;
import rx.Observable;
import wind.gifexample.bean.GifResult;

/**
 * Created by wind on 2016/8/24.
 */
public interface TenorApi {

    String BASE_URL = "https://api.tenor.co/v1/";

    String API_KEY = "key";

    String API_KEY_VALUE = "LIVDSRZULELA";


    /**
     * country - 国家代码
     * tag - 搜索关键字
     * pos - 起始位置
     * limit - 请求条数
     * locale - 国家 如 zh_CN
     *
     * @return
     */
    @GET("search")
    Observable<GifResult> searchGif(@Query("tag") String tag,
                                    @Query("pos") Integer pos,
                                    @Query("limit") Integer limit,
                                    @Query("country") String country,
                                    @Query("locale") String locale);


}
