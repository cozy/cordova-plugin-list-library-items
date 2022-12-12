package io.cozy.plugins.listlibraryitems;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.content.pm.PackageManager;
import android.util.Base64;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Headers;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.Call;
import okio.BufferedSink;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;

public class ListLibraryItems extends CordovaPlugin {

    private Context mContext;
    private CallbackContext mCallbackContext;
    private static final String PERMISSION_ERROR = "Permission Denial: This application is not allowed to access Photo data.";
    private SimpleDateFormat mDateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        mContext = this.cordova.getActivity().getApplicationContext();
        mDateFormatter.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext)
            throws JSONException {
        mCallbackContext = callbackContext;

        if ("isAuthorized".equals(action)) {
            this.isAuthorized(callbackContext);
            return true;
        } else if ("requestReadAuthorization".equals(action)) {
            this.requestReadAuthorization(callbackContext);
            return true;
        } else if ("listItems".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        if (!cordova.hasPermission(READ_EXTERNAL_STORAGE)) {
                            callbackContext.error(PERMISSION_ERROR);
                        } else {
                            listItems(callbackContext, args.getBoolean(0), args.getBoolean(1), args.getBoolean(2));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                }
            });
            return true;
        } else if ("uploadItem".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        UploadFilePayload ufp = new UploadFilePayload();
                        ufp.mJSONObject = args.getJSONObject(0);
                        ufp.mCallbackContext = callbackContext;
                        new UploadFileTask().execute(ufp);
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                }
            });
            return true;
        }
        // No action matched
        return false;
    }

    private void isAuthorized(CallbackContext callbackContext) {
        boolean ok = cordova.hasPermission(READ_EXTERNAL_STORAGE);
        callbackContext.success(ok ? 1 : 0);
    }

    private void requestReadAuthorization(CallbackContext callbackContext) {
        try {
            if (!cordova.hasPermission(READ_EXTERNAL_STORAGE)) {
                List<String> permissions = new ArrayList<String>();
                permissions.add(READ_EXTERNAL_STORAGE);
                cordova.requestPermissions(this, 0, permissions.toArray(new String[0]));
            } else {
                callbackContext.success();
            }
        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(e.getMessage());
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults)
            throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                this.mCallbackContext.error("Permission denied");
                return;
            }
        }

        this.mCallbackContext.success();
    }

    private boolean listItems(CallbackContext callbackContext, boolean includePictures, boolean includeVideos,
                              boolean includeCloud) {
        try {
            // All columns here: https://developer.android.com/reference/android/provider/MediaStore.Images.ImageColumns.html,
            // https://developer.android.com/reference/android/provider/MediaStore.MediaColumns.html
            JSONObject columns = new JSONObject() {
                {
                    put("int.id", MediaStore.Images.Media._ID);
                    put("fileName", MediaStore.Images.ImageColumns.DISPLAY_NAME);
                    put("int.width", MediaStore.Images.ImageColumns.WIDTH);
                    put("int.height", MediaStore.Images.ImageColumns.HEIGHT);
                    put("libraryId", MediaStore.Images.ImageColumns.BUCKET_ID);
                    put("date.creationDate", MediaStore.Images.ImageColumns.DATE_TAKEN);
                    // put("float.latitude", MediaStore.Images.ImageColumns.LATITUDE);
                    // put("float.longitude", MediaStore.Images.ImageColumns.LONGITUDE);
                    put("filePath", MediaStore.MediaColumns.DATA);
                }
            };

            final ArrayList<JSONObject> queryResults = new ArrayList();

            if (includePictures) {
                queryResults
                        .addAll(queryContentProvider(mContext, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns));
                queryResults
                        .addAll(queryContentProvider(mContext, MediaStore.Images.Media.INTERNAL_CONTENT_URI, columns));
            }
            if (includeVideos) {
                queryResults
                        .addAll(queryContentProvider(mContext, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columns));
                queryResults
                        .addAll(queryContentProvider(mContext, MediaStore.Video.Media.INTERNAL_CONTENT_URI, columns));
            }

            JSONObject result = new JSONObject();
            result.put("count", queryResults.size());
            result.put("library", new JSONArray(queryResults));
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, result);
            callbackContext.sendPluginResult(pluginResult);
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            callbackContext.error(e.getMessage());
            return false;
        }
    }

    private ArrayList<JSONObject> queryContentProvider(Context context, Uri collection, JSONObject columns)
            throws Exception {

        // TODO: filter
        // https://stackoverflow.com/a/4495753

        final ArrayList<String> columnNames = new ArrayList<String>();
        final ArrayList<String> columnValues = new ArrayList<String>();

        Iterator<String> iteratorFields = columns.keys();

        while (iteratorFields.hasNext()) {
            String column = iteratorFields.next();
            columnNames.add(column);
            columnValues.add("" + columns.getString(column));
        }

        // https://stackoverflow.com/a/20429397
        final String selection = MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " = ?";
        final String[] selectionArgs = new String[] { "Camera" };

        final String sortOrder = MediaStore.Images.Media.DATE_TAKEN;

        final Cursor cursor = context.getContentResolver().query(collection,
                columnValues.toArray(new String[columns.length()]), selection, selectionArgs, sortOrder);

        final ArrayList<JSONObject> buffer = new ArrayList<JSONObject>();

        if (cursor.moveToFirst()) {
            do {
                JSONObject item = new JSONObject();

                for (String column : columnNames) {
                    int columnIndex = cursor.getColumnIndex(columns.get(column).toString());

                    if (column.startsWith("int.")) {
                        item.put(column.substring(4), cursor.getInt(columnIndex));
                        /*if (column.substring(4).equals("width") && item.getInt("width") == 0) {
                            System.err.println("cursor: " + cursor.getInt(columnIndex));
                        }*/
                    } else if (column.startsWith("float.")) {
                        item.put(column.substring(6), cursor.getFloat(columnIndex));
                    } else if (column.startsWith("date.")) {
                        long intDate = cursor.getLong(columnIndex);
                        Date date = new Date(intDate);
                        item.put(column.substring(5), mDateFormatter.format(date));
                    } else {
                        item.put(column, cursor.getString(columnIndex));
                    }
                }

                item.put("mimeType", getMimeType(item.getString("filePath")));
                buffer.add(item);

                // TODO: return partial result

            } while (cursor.moveToNext());
        }

        cursor.close();
        return buffer;
    }

    private static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        return type;
    }

    private class UploadFilePayload {
        protected CallbackContext mCallbackContext;
        protected JSONObject mJSONObject;
    }

    private class UploadFileTask extends AsyncTask<UploadFilePayload, Integer, Integer> {

        private CallbackContext mCallback;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected Integer doInBackground(UploadFilePayload... uploadFilePayloads) {

            Integer response_code = 0;

            JSONObject json = uploadFilePayloads[0].mJSONObject;
            mCallback = uploadFilePayloads[0].mCallbackContext;

            String file_path = json.optString("filePath");
            String upload_url = json.optString("serverUrl");
            String httpMethod = json.optString("httpMethod");
            String ISO8601_date = json.optString("createdDate");
            JSONObject headers = json.optJSONObject("headers");

            // Converting date in ISO 8601 to RFC 1123 using DateFormatter class
            Date date = DateFormatter.parseIso8601DateTime(ISO8601_date);
            String RFC_1123 = DateFormatter.formatRfc1123DateTime(date, TimeZone.getTimeZone("GMT"));

            OkHttpClient client = new OkHttpClient();
            Call call = null;
            String method = "POST";
            if(httpMethod != ""){
                method = httpMethod;
            }
            try {
                // get file sz
                final File file = new File(file_path);
                final long FILE_SZ = file.length();


                Headers.Builder hb = new Headers.Builder();

                // set custom headers
                Iterator<?> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = (String) keys.next();
                    String val = headers.getString(key);
                    hb.add(key, val);
                }
                hb.add("Content-Length","" + FILE_SZ);
                hb.add("Expect","100-continue");
              //  hb.add("Date",RFC_1123);
                final String contentType = hb.get("Content-Type");

                try {
                    byte[] md5 = this.calculateMD5(file);

                    if (md5 != null) {
                        byte[] encodedBytes = Base64.encode(md5, Base64.DEFAULT);
                        hb.add("Content-MD5", new String(encodedBytes).replace("\n",""));
                    }
                }
                catch (Exception e) {
                    Log.e("Exception while calculating MD5 checksum", e.toString());
                }

                RequestBody body = new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return MediaType.parse(contentType);
                    }

                    @Override
                    public long contentLength() {
                        return FILE_SZ;
                    }

                    @Override
                    public void writeTo(BufferedSink sink) throws IOException {
                        FileInputStream fis = new FileInputStream(file);

                        int bytes_read, bytes_available, buffer_size, total_bytes;
                        byte[] buffer;

                        // read input file chunks + publish progress
                        bytes_available = fis.available();
                        buffer_size = 4 * 1024;
                        buffer = new byte[buffer_size];
                        total_bytes = 0;
                        while ((bytes_read = fis.read(buffer)) != -1) {
                            sink.write(buffer, 0, bytes_read);
                            total_bytes += bytes_read;
                            publishProgress(buffer_size, total_bytes, (int) FILE_SZ);
                        }
                        fis.close();
                    }
                };
                Request request;
                if(method.equals("PUT")){
                   request = new Request.Builder()
                            .url(upload_url)
                            .headers(hb.build())
                            .put(body)
                            .build();
                } else {
                    request = new Request.Builder()
                            .url(upload_url)
                            .headers(hb.build())
                            .post(body)
                            .build();
                }


                call = client.newCall(request);
                Response response = call.execute();

                // get server response
                response_code = response.code();
                String response_message = response.message();

                // back to JS
                if (response_code / 100 != 2) {
                    // error
                    JSONObject json_error = new JSONObject();
                    json_error.put("code", response_code);
                    json_error.put("source", file_path);
                    json_error.put("target", upload_url);
                    json_error.put("message", response_message);

                    PluginResult pr = new PluginResult(PluginResult.Status.ERROR, json_error);
                    mCallback.sendPluginResult(pr);
                } else {
                    // ok
                    InputStream is = response.body().byteStream();
                    StringBuilder sb = new StringBuilder();
                    BufferedReader br = new BufferedReader(new InputStreamReader(is));
                    String read;
                    while ((read = br.readLine()) != null) {
                        sb.append(read);
                    }
                    br.close();
                    PluginResult pr = new PluginResult(PluginResult.Status.OK, sb.toString());
                    mCallback.sendPluginResult(pr);
                }
            } catch (Exception e) {
                e.printStackTrace();
                JSONObject json_error = new JSONObject();
                try {
                    json_error.put("code", -1);
                    json_error.put("source", file_path);
                    json_error.put("target", upload_url);
                    json_error.put("message", e.toString());
                } catch (JSONException ex) {

                }
                PluginResult pr = new PluginResult(PluginResult.Status.ERROR, json_error);
                mCallback.sendPluginResult(pr);
            } finally {
                if (call != null) {
                    call.cancel();
                }
            }
            return response_code;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);

            Log.i("", "Wrote " + values[0] + " bytes (total: " + values[1] + " / " + values[2] + ")");

            float percent = ((float) values[1] / (float) values[2]) * 100.f;
            JSONObject json_progress = new JSONObject();
            try {
                json_progress.put("progress",(int)percent);
            } catch (JSONException ex) {

            }
            PluginResult pr = new PluginResult(PluginResult.Status.OK, json_progress);
            pr.setKeepCallback(true);
            mCallback.sendPluginResult(pr);
        }

        @Override
        protected void onPostExecute(Integer aResponseCode) {
            super.onPostExecute(aResponseCode);
        }

        protected byte[] calculateMD5 (File updateFile) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                Log.e("Exception while getting digest", e.toString());
                return null;
            }

            InputStream is;
            try {
                is = new FileInputStream(updateFile);
            } catch (FileNotFoundException e) {
                Log.e("Exception while getting FileInputStream", e.toString());
                return null;
            }

            byte[] buffer = new byte[8192];
            int read;
            try {
                while ((read = is.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
                byte[] md5sum = digest.digest();
                return md5sum;
            } catch (IOException e) {
                throw new RuntimeException("Unable to process file for MD5", e);
            } finally {
                try {
                    is.close();
                } catch (IOException e) {
                    Log.e("Exception on closing MD5 input stream", e.toString());
                }
            }
        }
    }
}

class DateFormatter {

    // ISO 8601 constants
    private static final String ISO_8601_PATTERN_1 = "yyyy-MM-dd'T'HH:mm:ssZ";
    private static final String ISO_8601_PATTERN_2 = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
    private static final String[] SUPPORTED_ISO_8601_PATTERNS = new String[]{ISO_8601_PATTERN_1, ISO_8601_PATTERN_2};
    private static final int TICK_MARK_COUNT = 2;
    private static final int COLON_PREFIX_COUNT = "+00".length();

    // RFC 1123 constants
    private static final String RFC_1123_DATE_TIME = "EEE, dd MMM yyyy HH:mm:ss z";

    // This method parse a ISO8601 (non http date format) to a Date
    public static Date parseIso8601DateTime(String string) {
        if (string == null) {
            return null;
        }
        String s = string.replace("Z", "+00:00");
        for (String pattern : SUPPORTED_ISO_8601_PATTERNS) {
            String str = s;
            int colonPosition = pattern.lastIndexOf('Z') - TICK_MARK_COUNT + COLON_PREFIX_COUNT;
            if (str.length() > colonPosition) {
                str = str.substring(0, colonPosition) + str.substring(colonPosition + 1);
            }
            try {
                return new SimpleDateFormat(pattern).parse(str);
            } catch (final ParseException e) {
                // try the next one
            }
        }
        return null;
    }

    // This method format a Date to a RFC 1123 String
    public static String formatRfc1123DateTime(Date date, TimeZone timeZone) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(RFC_1123_DATE_TIME, Locale.US);
        if (timeZone != null) {
            dateFormat.setTimeZone(timeZone);
        }
        return dateFormat.format(date);
    }

}
