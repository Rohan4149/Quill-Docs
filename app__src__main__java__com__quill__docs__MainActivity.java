package com.quill.docs;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private static final int FILE_CHOOSER = 1001;

    private WebView web;
    private WebView printWeb;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);

        web.addJavascriptInterface(new Bridge(), "QuillAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                if (host.equals("appassets.androidplatform.net")) return false;
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto") || scheme.equals("tel")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (url.startsWith("blob:")) {
                String js = "(function(){fetch('" + url + "').then(function(r){return r.blob();}).then(function(b){"
                        + "var fr=new FileReader();fr.onloadend=function(){var d=fr.result;"
                        + "QuillAndroid.saveBase64(d.substring(d.indexOf(',')+1),'" + (mimeType == null ? "application/octet-stream" : mimeType.replace("'", "")) + "','" + name.replace("'", "") + "');};"
                        + "fr.readAsDataURL(b);});})();";
                web.evaluateJavascript(js, null);
            } else if (url.startsWith("data:")) {
                try {
                    int comma = url.indexOf(',');
                    String meta = url.substring(5, comma);
                    String data = url.substring(comma + 1);
                    byte[] bytes = meta.contains(";base64") ? Base64.decode(data, Base64.DEFAULT) : Uri.decode(data).getBytes("UTF-8");
                    saveBytes(bytes, mimeType, name);
                } catch (Exception e) {
                    toast("Could not save file");
                }
            } else {
                try {
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                    toast("Downloading " + name);
                } catch (Exception e) {
                    toast("Could not download file");
                }
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);
    }

    private class Bridge {
        @JavascriptInterface
        public void printPage(final String title) {
            runOnUiThread(() -> {
                try {
                    final String t = (title == null || title.isEmpty()) ? "Document" : title;
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    final PrintDocumentAdapter base = web.createPrintDocumentAdapter(t);
                    PrintDocumentAdapter ad = new PrintDocumentAdapter() {
                        @Override public void onStart() { base.onStart(); }
                        @Override public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal c, LayoutResultCallback cb, Bundle e) { base.onLayout(o, n, c, cb, e); }
                        @Override public void onWrite(PageRange[] p, ParcelFileDescriptor fd, CancellationSignal c, WriteResultCallback cb) { base.onWrite(p, fd, c, cb); }
                        @Override public void onFinish() {
                            base.onFinish();
                            if (web != null) web.evaluateJavascript("window.__printDone&&window.__printDone()", null);
                        }
                    };
                    pm.print(t, ad, new PrintAttributes.Builder().build());
                } catch (Exception e) {
                    toast("Printing is not available");
                }
            });
        }

        @JavascriptInterface
        public String bridgeInfo() { return "print+share v2"; }

        @JavascriptInterface
        public int getVersionCode() {
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception e) { return 0; }
        }

        @JavascriptInterface
        public String getVersionName() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void openUrl(String u) {
            try {
                if (u != null && u.startsWith("https://")) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void installApk(final String url) {
            runOnUiThread(() -> {
                try {
                    if (url == null || !url.startsWith("https://github.com/Rohan4149/Quill-Docs/")) {
                        toast("Update link not allowed");
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                        toast("Allow installs from Quill Docs, then tap Update again");
                        Intent st = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                        st.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(st);
                        return;
                    }
                    final DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
                    rq.setTitle("Quill Docs update");
                    rq.setMimeType("application/vnd.android.package-archive");
                    rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    rq.setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS, "QuillDocs-update.apk");
                    final long id = dm.enqueue(rq);
                    BroadcastReceiver r = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent i) {
                            if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                            try { unregisterReceiver(this); } catch (Exception ignored) {}
                            Uri apk = dm.getUriForDownloadedFile(id);
                            if (apk == null) { toast("Update download failed"); return; }
                            Intent in = new Intent(Intent.ACTION_VIEW);
                            in.setDataAndType(apk, "application/vnd.android.package-archive");
                            in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(in);
                        }
                    };
                    if (Build.VERSION.SDK_INT >= 33)
                        registerReceiver(r, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
                    else
                        registerReceiver(r, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
                    toast("Downloading update\u2026");
                } catch (Exception e) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void printHtml(final String title, final String html) {
            runOnUiThread(() -> {
                try {
                    final String t = (title == null || title.isEmpty()) ? "Document" : title;
                    if (printWeb != null) { try { printWeb.destroy(); } catch (Exception ignored) {} }
                    final WebView pw = new WebView(MainActivity.this);
                    printWeb = pw;
                    try {
                        pw.setAlpha(0.01f);
                        ((android.view.ViewGroup) getWindow().getDecorView()).addView(pw, new android.view.ViewGroup.LayoutParams(1, 1));
                    } catch (Exception ignored) {}
                    /* JS is on only so the page can report when its fonts have loaded; no bridge is attached to this view */
                    pw.getSettings().setJavaScriptEnabled(true);
                    pw.getSettings().setAllowFileAccess(false);
                    pw.getSettings().setAllowContentAccess(false);
                    pw.getSettings().setTextZoom(100);
                    pw.setWebViewClient(new WebViewClient() {
                        boolean started = false;
                        @Override
                        public void onPageFinished(final WebView v, String u) {
                            if (started) return;
                            started = true;
                            final Runnable go = () -> {
                                try {
                                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                                    final PrintDocumentAdapter base = v.createPrintDocumentAdapter(t);
                                    PrintDocumentAdapter ad = new PrintDocumentAdapter() {
                                        @Override public void onStart() { base.onStart(); }
                                        @Override public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal c, LayoutResultCallback cb, Bundle e) { base.onLayout(o, n, c, cb, e); }
                                        @Override public void onWrite(PageRange[] p, ParcelFileDescriptor fd, CancellationSignal c, WriteResultCallback cb) { base.onWrite(p, fd, c, cb); }
                                        @Override public void onFinish() {
                                            base.onFinish();
                                            try { ((android.view.ViewGroup) getWindow().getDecorView()).removeView(pw); } catch (Exception ignored) {}
                                            try { if (printWeb == pw) { pw.destroy(); printWeb = null; } } catch (Exception ignored) {}
                                        }
                                    };
                                    pm.print(t, ad, new PrintAttributes.Builder().build());
                                } catch (Exception e) {
                                    toast("Print failed: " + e);
                                }
                            };
                            final int[] tries = {0};
                            final Runnable[] poll = new Runnable[1];
                            poll[0] = () -> {
                                v.evaluateJavascript("document.documentElement.getAttribute('data-fr')", val -> {
                                    boolean ready = val != null && val.contains("1");
                                    if (ready || ++tries[0] > 40) v.postDelayed(go, 250);
                                    else v.postDelayed(poll[0], 150);
                                });
                            };
                            v.postDelayed(poll[0], 200);
                        }
                    });
                    pw.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null);
                } catch (Exception e) {
                    toast("Print failed: " + e);
                }
            });
        }

        @JavascriptInterface
        public void shareFile(final String name, final String mime, final String base64) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    String safe = (name == null || name.isEmpty()) ? "document" : name.replaceAll("[\\\\/:*?\"<>|]", "-");
                    File dir = new File(getCacheDir(), "share");
                    dir.mkdirs();
                    File[] old = dir.listFiles();
                    if (old != null) for (File o : old) o.delete();
                    File f = new File(dir, safe);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(bytes); }
                    Uri uri = Uri.parse("content://" + getPackageName() + ".share/" + Uri.encode(safe));
                    Intent in = new Intent(Intent.ACTION_SEND);
                    in.setType((mime == null || mime.isEmpty()) ? "application/octet-stream" : mime);
                    in.putExtra(Intent.EXTRA_STREAM, uri);
                    in.putExtra(Intent.EXTRA_SUBJECT, safe);
                    in.setClipData(ClipData.newRawUri(safe, uri));
                    in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent ch = Intent.createChooser(in, "Share " + safe);
                    try {
                        for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(in, 0))
                            grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    startActivity(ch);
                } catch (Exception e) {
                    toast("Share failed: " + e);
                }
            });
        }

        @JavascriptInterface
        public void saveBase64(String base64, String mime, String name) {
            try {
                saveBytes(Base64.decode(base64, Base64.DEFAULT), mime, name);
            } catch (Exception e) {
                toast("Could not save file");
            }
        }
    }

    /** Lets other apps read the file being shared (kept in the app's cache/share folder). */
    public static class ShareProvider extends android.content.ContentProvider {
        private File fileFor(Uri uri) {
            File dir = new File(getContext().getCacheDir(), "share");
            return new File(dir, new File(String.valueOf(uri.getLastPathSegment())).getName());
        }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        }
        @Override public String getType(Uri uri) {
            String n = fileFor(uri).getName();
            int i = n.lastIndexOf('.');
            String m = i >= 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(i + 1).toLowerCase()) : null;
            return m == null ? "application/octet-stream" : m;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Uri insert(Uri uri, ContentValues v) { return null; }
        @Override public int delete(Uri uri, String s, String[] a) { return 0; }
        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    }

    private void saveBytes(byte[] bytes, String mime, String name) throws Exception {
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("insert failed");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(bytes);
            }
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            try (FileOutputStream os = new FileOutputStream(new File(dir, name))) {
                os.write(bytes);
            }
        }
        toast("Saved to Downloads: " + name);
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    result = android.webkit.WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                }
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
