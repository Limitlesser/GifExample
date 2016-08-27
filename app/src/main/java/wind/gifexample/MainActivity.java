package wind.gifexample;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.rockerhieu.rvadapter.endless.EndlessRecyclerViewAdapter;

import java.util.List;
import java.util.Locale;

import pl.droidsonroids.gif.GifImageView;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.schedulers.Schedulers;
import wind.gifexample.bean.GifResult;
import wind.gifexample.image.GifImageLoader;
import wind.gifexample.net.RetrofitClient;

public class MainActivity extends AppCompatActivity {

    RecyclerView recyclerView;

    GifAdapter mAdapter;

    Toolbar toolbar;

    EditText editText;

    EndlessRecyclerViewAdapter endlessAdapter;

    int offset = 0;

    int limit = 30;

    String keyword;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = (Toolbar) findViewById(R.id.toolbar);
        editText = (EditText) findViewById(R.id.editText);

        setSupportActionBar(toolbar);

        recyclerView = (RecyclerView) findViewById(R.id.recyclerView);
        GridLayoutManager layoutManager = new GridLayoutManager(this, 3);
        recyclerView.setLayoutManager(layoutManager);
        mAdapter = new GifAdapter();
        endlessAdapter = new EndlessRecyclerViewAdapter(this, mAdapter, new EndlessRecyclerViewAdapter.RequestToLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                loadMore();
            }
        }, R.layout.item_loading, false);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return endlessAdapter.getItemViewType(position) == EndlessRecyclerViewAdapter.TYPE_PENDING ? 3 : 1;
            }
        });
        recyclerView.setAdapter(endlessAdapter);

        search("hello");
    }

    private void search(String keyword) {
        RetrofitClient.getTenorApi()
                .searchGif(this.keyword = keyword, offset = 0, limit, Locale.getDefault().getCountry(), Locale.getDefault().getLanguage())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Action1<GifResult>() {
                    @Override
                    public void call(GifResult gifResult) {
                        mAdapter.setGifResult(gifResult.getResults());
                        endlessAdapter.restartAppending();
                    }
                });
    }

    private void loadMore() {
        RetrofitClient.getTenorApi()
                .searchGif(keyword, offset += limit, limit, Locale.getDefault().getCountry(), Locale.getDefault().getLanguage())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Action1<GifResult>() {
                    @Override
                    public void call(GifResult gifResult) {
                        mAdapter.addGifReslut(gifResult.getResults());
                        if (gifResult.getResults().size() > 0) {
                            endlessAdapter.onDataReady(true);
                        } else {
                            endlessAdapter.onDataReady(false);
                        }
                    }
                });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.search:
                search(editText.getText().toString());
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public class GifAdapter extends RecyclerView.Adapter<GifAdapter.ViewHolder> {

        List<GifResult.ResultsBean> results;

        public void setGifResult(List<GifResult.ResultsBean> results) {
            this.results = results;
            notifyDataSetChanged();
        }

        public void addGifReslut(List<GifResult.ResultsBean> results) {
            int size = this.results.size();
            this.results.addAll(results);
            notifyItemRangeInserted(size, results.size());
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gif, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            holder.bind(results.get(position));
        }

        @Override
        public int getItemCount() {
            return results != null ? results.size() : 0;
        }

        public class ViewHolder extends RecyclerView.ViewHolder {

            //            SimpleDraweeView imageView;
            GifImageView imageView;

            public ViewHolder(View itemView) {
                super(itemView);
//                imageView = (SimpleDraweeView) itemView.findViewById(R.id.imageView);
                imageView = (GifImageView) itemView.findViewById(R.id.imageView);

            }

            public void bind(GifResult.ResultsBean resultsBean) {
                String url = resultsBean.getMedia().get(0).getTinygif().getUrl();
//                DraweeController controller = Fresco.newDraweeControllerBuilder()
//                        .setUri(url)
//                        .setAutoPlayAnimations(true)
//                .build();
//                imageView.setController(controller);
                GifImageLoader.getInstance().displayGif(imageView, url);
            }


        }

    }


}
