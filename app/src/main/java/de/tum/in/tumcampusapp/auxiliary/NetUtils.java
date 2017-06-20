package de.tum.in.tumcampusapp.auxiliary;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Base64;
import android.widget.ImageView;

import com.google.common.base.Optional;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import de.tum.in.tumcampusapp.api.Helper;
import de.tum.in.tumcampusapp.managers.CacheManager;
import de.tum.in.tumcampusapp.tumonline.TUMFacilityLocatorRequest;
import de.tum.in.tumcampusapp.tumonline.TUMRoomFinderRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class NetUtils {
    private final Context mContext;
    private final CacheManager cacheManager;
    private final OkHttpClient client;

    public NetUtils(Context context) {
        //Manager caches all requests
        mContext = context;
        cacheManager = new CacheManager(mContext);

        //Set our max wait time for each request
        client = Helper.getOkClient(context);
    }

    public static Optional<JSONObject> downloadJson(Context context, String url) {
        return new NetUtils(context).downloadJson(url);
    }

    /**
     * Check if a network connection is available or can be available soon
     *
     * @return true if available
     */
    public static boolean isConnected(Context con) {
        ConnectivityManager cm = (ConnectivityManager) con
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnectedOrConnecting();
    }

    /**
     * Check if a network connection is available or can be available soon
     * and if the available connection is a mobile internet connection
     *
     * @return true if available
     */
    public static boolean isConnectedMobileData(Context con) {
        ConnectivityManager cm = (ConnectivityManager) con
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnectedOrConnecting() && netInfo.getType() == ConnectivityManager.TYPE_MOBILE;
    }

    /**
     * Check if a network connection is available or can be available soon
     * and if the available connection is a wifi internet connection
     *
     * @return true if available
     */
    public static boolean isConnectedWifi(Context con) {
        ConnectivityManager cm = (ConnectivityManager) con.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnectedOrConnecting() && netInfo.getType() == ConnectivityManager.TYPE_WIFI;
    }

    private Optional<ResponseBody> getOkHttpResponse(String url) throws IOException {
        // if we are not online, fetch makes no sense
        boolean isOnline = isConnected(mContext);
        if (!isOnline || url == null) {
            return Optional.absent();
        }

        Utils.logv("Download URL: " + url);
        Request.Builder builder = new Request.Builder().url(url);

        //Execute the request
        Response res = client.newCall(builder.build()).execute();
        return Optional.of(res.body());
    }

    /**
     * Downloads the content of a HTTP URL as String
     *
     * @param url Download URL location
     * @return The content string
     * @throws IOException
     */
    public Optional<String> downloadStringHttp(String url) throws IOException {
        Optional<ResponseBody> body = getOkHttpResponse(url);
        if (body.isPresent()) {
            return Optional.of(body.get().string());
        }
        return Optional.absent();
    }

    public Optional<String> downloadStringAndCache(String url, int validity, boolean force) {
        try {
            Optional<String> content;
            if (!force) {
                content = cacheManager.getFromCache(url);
                if (content.isPresent()) {
                    return content;
                }
            }

            content = downloadStringHttp(url);
            if (content.isPresent()) {
                cacheManager.addToCache(url, content.get(), validity, CacheManager.CACHE_TYP_DATA);
                return content;
            }
            return Optional.absent();
        } catch (IOException e) {
            Utils.log(e);
            return Optional.absent();
        }

    }

    /**
     * Download a file in the same thread.
     * If file already exists the method returns immediately
     * without downloading anything
     *
     * @param url    Download location
     * @param target Target filename in local file system
     * @throws IOException When the download failed
     */
    public void downloadToFile(String url, String target) throws IOException {
        File f = new File(target);
        if (f.exists()) {
            return;
        }

        File file = new File(target);
        FileOutputStream out = new FileOutputStream(file);
        try {
            Optional<ResponseBody> body = getOkHttpResponse(url);
            if (!body.isPresent()) {
                file.delete();
                throw new IOException();
            }
            byte[] buffer = body.get().bytes();
            out.write(buffer, 0, buffer.length);
            out.flush();
        } finally {
            out.close();
        }
    }

    /**
     * Downloads an image synchronously from the given url
     *
     * @param pUrl Image url
     * @return Downloaded image as {@link Bitmap}
     */
    public Optional<File> downloadImage(String pUrl) {
        try {
            String url = pUrl.replaceAll(" ", "%20");

            Optional<String> file = cacheManager.getFromCache(url);
            if (file.isPresent()) {
                File result = new File(file.get());

                // TODO: remove this check when #391 is fixed
                // The cache could have been cleaned manually, so we need an existence check
                if (result.exists()) {
                    return Optional.of(result);
                }
            }

            file = Optional.of(mContext.getCacheDir().getAbsolutePath() + '/' + Utils.md5(url) + ".jpg");
            File f = new File(file.get());
            downloadToFile(url, file.get());

            // At this point, we are certain, that the file really has been downloaded and can safely be added to the cache
            cacheManager.addToCache(url, file.get(), CacheManager.VALIDITY_TEN_DAYS, CacheManager.CACHE_TYP_IMAGE);
            return Optional.of(f);
        } catch (IOException e) {
            Utils.log(e, pUrl);
            return Optional.absent();
        }
    }

    public Optional<File> saveCurrentLocationImage(String encodedImage){
        try{
            Optional<String> file=Optional.of(mContext.getCacheDir().getAbsolutePath() + '/'+ "current_location_map.jpg");
            File f = new File(file.get());
            byte[] imageData= Base64.decode(encodedImage,Base64.DEFAULT);
            BufferedOutputStream writer = new BufferedOutputStream(new FileOutputStream(f));
            writer.write(imageData);
            writer.flush();
            writer.close();
            return Optional.of(f);
        }
        catch (IOException e){
            Utils.log(e, "Could not save the current location map image");
            return Optional.absent();
        }
    }

    public Optional<File> getFacilityMapImage(String facilityName, double longitude, double latitude){
        try{

            String map= TUMFacilityLocatorRequest.getMapWithLocation(longitude, latitude);

            if(map==null){
                return Optional.absent();
            }

            Optional<String> file = cacheManager.getFromCache(facilityName);
            if (file.isPresent()) {
                File result = new File(file.get());

                // TODO: remove this check when #391 is fixed
                // The cache could have been cleaned manually, so we need an existence check
                if (result.exists()) {
                    return Optional.of(result);
                }
            }

            file=Optional.of(mContext.getCacheDir().getAbsolutePath() + '/'+ facilityName+".jpg");
            File f = new File(file.get());
            byte[] imageData= Base64.decode(map,Base64.DEFAULT);
            BufferedOutputStream writer = new BufferedOutputStream(new FileOutputStream(f));
            writer.write(imageData);
            writer.flush();
            writer.close();

            cacheManager.addToCache(facilityName, file.get(), CacheManager.VALIDITY_TEN_DAYS, CacheManager.CACHE_TYP_IMAGE);
            return Optional.of(f);
        }
        catch (IOException e){
            Utils.log(e, "Could not save the current location map image");
            return Optional.absent();
        }
    }


    /**
     * Downloads an image synchronously from the given url
     *
     * @param url Image url
     * @return Downloaded image as {@link Bitmap}
     */
    public Optional<Bitmap> downloadImageToBitmap(@NonNull String url) {
        Optional<File> f = downloadImage(url);
        if (f.isPresent()) {
            return Optional.fromNullable(BitmapFactory.decodeFile(f.get().getAbsolutePath()));
        }
        return Optional.absent();
    }

    /**
     * Download an image in background and sets the image to the image view
     *
     * @param url       URL
     * @param imageView Image
     */
    public void loadAndSetImage(final String url, final ImageView imageView) {
        synchronized (CacheManager.BITMAP_CACHE) {
            Bitmap bmp = CacheManager.BITMAP_CACHE.get(url);
            if (bmp != null) {
                imageView.setImageBitmap(bmp);
                return;
            }
        }
        new AsyncTask<Void, Void, Optional<Bitmap>>() {
            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                CacheManager.IMAGE_VIEWS.put(imageView, url);
                imageView.setImageBitmap(null);
            }

            @Override
            protected Optional<Bitmap> doInBackground(Void... voids) {
                return downloadImageToBitmap(url);
            }

            @Override
            protected void onPostExecute(Optional<Bitmap> bitmap) {
                if (!bitmap.isPresent()) {
                    return;
                }
                synchronized (CacheManager.BITMAP_CACHE) {
                    CacheManager.BITMAP_CACHE.put(url, bitmap.get());
                }
                String tag = CacheManager.IMAGE_VIEWS.get(imageView);
                if (tag != null && tag.equals(url)) {
                    imageView.setImageBitmap(bitmap.get());
                }
            }
        }.execute();
    }

    /**
     * Download a JSON stream from a URL
     *
     * @param url Valid URL
     * @return JSONObject
     */
    public Optional<JSONObject> downloadJson(String url) {
        try {
            Optional<String> data = downloadStringHttp(url);
            if (data.isPresent()) {
                Utils.logv("downloadJson " + data);
                return Optional.of(new JSONObject(data.get()));
            }
        } catch (IOException | JSONException e) {
            Utils.log(e);
        }
        return Optional.absent();
    }

    /**
     * Download a JSON stream from a URL or load it from cache
     *
     * @param url   Valid URL
     * @param force Load data anyway and fill cache, even if valid cached version exists
     * @return JSONObject
     */
    public Optional<JSONArray> downloadJsonArray(String url, int validity, boolean force) {
        Optional<String> download = downloadStringAndCache(url, validity, force);
        JSONArray result = null;
        if (download.isPresent()) {
            try {
                result = new JSONArray(download.get());
            } catch (JSONException e) {
                Utils.log(e);
            }
        }
        return Optional.fromNullable(result);
    }

    /**
     * Download a JSON stream from a URL or load it from cache
     *
     * @param url   Valid URL
     * @param force Load data anyway and fill cache, even if valid cached version exists
     * @return JSONObject
     */
    public Optional<JSONObject> downloadJsonObject(String url, int validity, boolean force) {
        Optional<String> download = downloadStringAndCache(url, validity, force);
        JSONObject result = null;
        if (download.isPresent()) {
            try {
                result = new JSONObject(download.get());
            } catch (JSONException e) {
                Utils.log(e);
            }
        }
        return Optional.fromNullable(result);
    }
}
