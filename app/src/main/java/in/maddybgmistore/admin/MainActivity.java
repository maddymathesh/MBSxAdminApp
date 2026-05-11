package in.maddybgmistore.admin;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // Exact Chrome UA — prevents Google Error 403: disallowed_useragent
    private static final String CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; CPH2609) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final String ADMIN_URL = "https://www.maddybgmistore.in/admin";

    // All domains that must NEVER be opened in an external browser.
    // Opening any of these externally destroys the WebView's sessionStorage.
    private static final String[] INTERNAL_DOMAINS = {
            "maddybgmistore.in",
            "accounts.google.com",
            "firebaseapp.com",
            "oauth2.googleapis.com",
            "securetoken.googleapis.com",
            "identitytoolkit.googleapis.com",
            "google.com/o/oauth2",
            "__/auth/handler",
            "__/auth/iframe",
            "googleapis.com",
            "gstatic.com",
            "googleusercontent.com",
            "firebase.com"
    };

    // -----------------------------------------------------------------------
    // ROOT CAUSE & FIX:
    //
    // Firebase signInWithRedirect stores OAuth "initial state" into sessionStorage
    // BEFORE navigating to accounts.google.com. When Google redirects back, the
    // WebView starts a new page load — Android WebView wipes sessionStorage on
    // every top-level navigation because it treats each navigation as a new
    // "browsing context session". Firebase then reads sessionStorage, finds nothing,
    // and throws "Unable to process request due to missing initial state."
    //
    // The fix: inject JavaScript that:
    //   1. Backs sessionStorage writes into localStorage (survives navigations)
    //   2. Restores them into sessionStorage on every new page load BEFORE Firebase runs
    //   3. Proxies Storage.prototype.setItem/removeItem/clear to keep them in sync
    //
    // This script is injected in onPageStarted (earliest possible moment) AND
    // onPageFinished (safety net for SPAs that re-bootstrap after DOMContentLoaded).
    // -----------------------------------------------------------------------
    private static final String SESSION_STORAGE_BRIDGE_JS =
        "(function() {" +
        "  var SESSION_KEY = '__mbs_wv_session__';" +

        // 1. RESTORE: Copy saved state from localStorage back to sessionStorage
        "  try {" +
        "    var raw = localStorage.getItem(SESSION_KEY);" +
        "    if (raw) {" +
        "      var saved = JSON.parse(raw);" +
        "      for (var k in saved) {" +
        "        if (saved.hasOwnProperty(k) && !sessionStorage.getItem(k)) {" +
        "          sessionStorage.setItem(k, saved[k]);" +
        "        }" +
        "      }" +
        "    }" +
        "  } catch(e) {}" +

        // 2. PERSISTENCE FIX: Force Firebase to use LOCAL persistence if it's currently being initialized
        "  try {" +
        "    if (window.firebase && window.firebase.auth) {" +
        "      window.firebase.auth().setPersistence(window.firebase.auth.Auth.Persistence.LOCAL);" +
        "    }" +
        "  } catch(e) {}" +

        // Guard against double-patching
        "  if (window.__mbsBridgeInstalled) return;" +
        "  window.__mbsBridgeInstalled = true;" +

        // 3. SYNC: Mirror all sessionStorage changes to localStorage
        "  var _set = Storage.prototype.setItem;" +
        "  var _rem = Storage.prototype.removeItem;" +
        "  var _clr = Storage.prototype.clear;" +

        "  function syncToLocal() {" +
        "    try {" +
        "      var out = {};" +
        "      for (var i = 0; i < sessionStorage.length; i++) {" +
        "        var key = sessionStorage.key(i);" +
        "        out[key] = sessionStorage.getItem(key);" +
        "      }" +
        "      localStorage.setItem(SESSION_KEY, JSON.stringify(out));" +
        "    } catch(e) {}" +
        "  }" +

        "  Storage.prototype.setItem = function(key, value) {" +
        "    _set.call(this, key, value);" +
        "    if (this === sessionStorage) syncToLocal();" +
        "  };" +

        "  Storage.prototype.removeItem = function(key) {" +
        "    _rem.call(this, key);" +
        "    if (this === sessionStorage) syncToLocal();" +
        "  };" +

        "  Storage.prototype.clear = function() {" +
        "    _clr.call(this);" +
        "    if (this === sessionStorage) {" +
        "      try { localStorage.removeItem(SESSION_KEY); } catch(e) {}" +
        "    }" +
        "  };" +

        // 4. POPUP FIX: Keep auth popups in the same WebView context
        "  var _winOpen = window.open;" +
        "  window.open = function(url, name, features) {" +
        "    if (url && (url.indexOf('accounts.google.com') !== -1 ||" +
        "               url.indexOf('firebaseapp.com') !== -1 ||" +
        "               url.indexOf('/__/auth/') !== -1)) {" +
        "      window.location.href = url;" +
        "      return null;" +
        "    }" +
        "    return _winOpen.apply(window, arguments);" +
        "  };" +

        "})();";

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private View layoutOffline;

    private ValueCallback<Uri[]> fileUploadCallback;
    private Uri cameraImageUri;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileUploadCallback == null) return;
                Uri[] results = null;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                } else if (cameraImageUri != null) {
                    results = new Uri[]{cameraImageUri};
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
                cameraImageUri = null;
            });

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayShowTitleEnabled(false);

        webView       = findViewById(R.id.webview);
        swipeRefresh  = findViewById(R.id.swipe_refresh);
        progressBar   = findViewById(R.id.progress_bar);
        layoutOffline = findViewById(R.id.layout_offline);

        // UI/UX: Native app feel - remove scrollbars and edge effects
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        setupWebView();
        setupSwipeRefresh();
        requestStoragePermissions();

        if (isNetworkAvailable()) {
            layoutOffline.setVisibility(View.GONE);
            webView.loadUrl(ADMIN_URL);
        } else {
            showOfflineScreen();
        }

        findViewById(R.id.btn_retry).setOnClickListener(v -> {
            if (isNetworkAvailable()) {
                layoutOffline.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                webView.loadUrl(ADMIN_URL);
            } else {
                Toast.makeText(this, "Still offline. Check your connection.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();

        // --- Firebase required settings ---
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);

        // Exact Chrome UA — do NOT modify or append anything
        s.setUserAgentString(CHROME_UA);

        // Third-party cookies required for Firebase auth iframes
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        // CRITICAL: allow popup windows — Firebase uses child windows for auth flows
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        // General UX settings
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        // Disable accidental zoom
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);

        s.setDefaultTextEncodingName("utf-8");
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        webView.setWebViewClient(new AdminWebViewClient());
        webView.setWebChromeClient(new AdminWebChromeClient());
        webView.setDownloadListener((url, ua, contentDisposition, mimeType, contentLength) ->
                handleDownload(url, contentDisposition, mimeType));
    }

    // -----------------------------------------------------------------------
    // WebViewClient
    // -----------------------------------------------------------------------
    private class AdminWebViewClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            progressBar.setVisibility(View.VISIBLE);
            swipeRefresh.setRefreshing(false);

            // Inject sessionStorage bridge as early as possible — before Firebase's
            // auth JS runs — so saved state is in sessionStorage when Firebase calls
            // sessionStorage.getItem("firebase:pendingRedirect:...")
            view.evaluateJavascript(SESSION_STORAGE_BRIDGE_JS, null);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);
            swipeRefresh.setRefreshing(false);

            // Re-inject after page finish as a safety net for SPAs
            view.evaluateJavascript(SESSION_STORAGE_BRIDGE_JS, null);

            CookieManager.getInstance().flush();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            // ALL internal/auth URLs load inside this WebView — same session — sessionStorage intact
            if (isInternalUrl(url)) return false;
            // External URLs → system browser
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception ignored) {}
            return true;
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            if (!isNetworkAvailable()) showOfflineScreen();
        }
    }

    // -----------------------------------------------------------------------
    // WebChromeClient — popup window support for Firebase auth
    // -----------------------------------------------------------------------
    private class AdminWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            progressBar.setProgress(newProgress);
            if (newProgress == 100) progressBar.setVisibility(View.GONE);
        }

        // Firebase opens a popup window for Google consent. Handle it in-app.
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView popup = new WebView(MainActivity.this);

            // Configure popup WebView with same critical settings
            WebSettings ps = popup.getSettings();
            ps.setJavaScriptEnabled(true);
            ps.setDomStorageEnabled(true);
            ps.setDatabaseEnabled(true);
            ps.setSupportMultipleWindows(true);
            ps.setJavaScriptCanOpenWindowsAutomatically(true);
            ps.setUserAgentString(CHROME_UA);
            ps.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

            // Cookies & Storage persistence
            CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);

            // Visual setup for popup (hidden, just for auth state)
            popup.setVerticalScrollBarEnabled(false);
            popup.setHorizontalScrollBarEnabled(false);
            popup.setOverScrollMode(View.OVER_SCROLL_NEVER);

            popup.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView v, String url, Bitmap fav) {
                    super.onPageStarted(v, url, fav);
                    v.evaluateJavascript(SESSION_STORAGE_BRIDGE_JS, null);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView popupView, WebResourceRequest request) {
                    String url = request.getUrl().toString();

                    // If it's the main app or the auth handler, load it in the main WebView
                    if (url.contains("maddybgmistore.in") || url.contains("__/auth/handler")) {
                        webView.loadUrl(url);
                        // Clean up the popup
                        popupView.stopLoading();
                        popupView.destroy();
                        return true;
                    }

                    // Keep Google/Firebase auth pages in this popup context if needed,
                    // or force them into the main WebView if they are internal.
                    if (isInternalUrl(url)) return false;

                    // External URLs go to system browser
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                    catch (Exception ignored) {}
                    return true;
                }

                @Override
                public void onPageFinished(WebView pv, String url) {
                    super.onPageFinished(pv, url);
                    pv.evaluateJavascript(SESSION_STORAGE_BRIDGE_JS, null);
                }
            });

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            if (fileUploadCallback != null) fileUploadCallback.onReceiveValue(null);
            fileUploadCallback = filePathCallback;
            launchFilePicker(fileChooserParams);
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    private boolean isInternalUrl(String url) {
        if (url == null) return false;
        for (String domain : INTERNAL_DOMAINS) {
            if (url.contains(domain)) return true;
        }
        return false;
    }

    private void launchFilePicker(WebChromeClient.FileChooserParams params) {
        Intent cameraIntent = null;
        try {
            File photoFile = createImageFile();
            cameraImageUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", photoFile);
            cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (IOException e) {
            cameraImageUri = null;
        }

        Intent galleryIntent = (params != null) ? params.createIntent() : new Intent(Intent.ACTION_GET_CONTENT);
        if (galleryIntent.getType() == null) galleryIntent.setType("*/*");

        Intent chooser = Intent.createChooser(galleryIntent, "Select or take photo");
        if (cameraIntent != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
        filePickerLauncher.launch(chooser);
    }

    private File createImageFile() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile("MBS_" + ts + "_", ".jpg", dir);
    }

    private void handleDownload(String url, String contentDisposition, String mimeType) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
            request.setTitle(filename);
            request.setDescription("Downloading from Maddy BGMI Store...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.addRequestHeader("User-Agent", CHROME_UA);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, "Downloading: " + filename, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.gold_primary, R.color.green_live);
        swipeRefresh.setOnRefreshListener(() -> {
            if (isNetworkAvailable()) webView.reload();
            else { swipeRefresh.setRefreshing(false); showOfflineScreen(); }
        });
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private void showOfflineScreen() {
        webView.setVisibility(View.GONE);
        layoutOffline.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.CAMERA});
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA});
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) { webView.reload(); return true; }
        if (id == R.id.action_home)    { webView.loadUrl(ADMIN_URL); return true; }
        if (id == R.id.action_lock)    { lockAndExit(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void lockAndExit() {
        webView.clearHistory();
        CookieManager.getInstance().removeSessionCookies(null);
        Intent intent = new Intent(this, LockActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause()   { super.onPause();   webView.onPause(); }
    @Override protected void onResume()  { super.onResume();  webView.onResume(); }
    @Override
    protected void onDestroy() {
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }
}
