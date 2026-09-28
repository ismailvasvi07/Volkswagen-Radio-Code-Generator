package com.alcomet.maintenance;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREFS = "alcomet_mobile";
    private static final String KEY_SERVER = "server_url";
    private static final int FILE_CHOOSER_REQ = 1201;

    private WebView webView;
    private ProgressBar progress;
    private FrameLayout rootView;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;
    private String currentServer = "";
    private boolean connectionDialogVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        rootView = new FrameLayout(this);
        webView = new WebView(this);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);

        rootView.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        pp.gravity = Gravity.TOP;
        rootView.addView(progress, pp);
        setContentView(rootView);

        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                topInset = insets.getInsets(WindowInsets.Type.statusBars()).top;
            } else {
                topInset = insets.getSystemWindowInsetTop();
            }
            v.setPadding(0, topInset, 0, 0);
            return insets;
        });
        rootView.requestApplyInsets();

        applySystemBars(false);
        configureWebView();
        currentServer = normalizeServer(prefs.getString(KEY_SERVER, ""));

        if (currentServer.isEmpty()) showServerDialog(true);
        else loadServer();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);

        webView.addJavascriptInterface(new AndroidBridge(), "ALCOMETAndroid");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQ);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this,
                            "Няма приложение за избор на файл.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                connectionDialogVisible = false;
                progress.setVisibility(View.GONE);
                injectAndroidEnhancements();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) showConnectionError();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {}
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null && !cookies.isEmpty()) req.addRequestHeader("Cookie", cookies);
                    if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
                    req.setTitle(fileName);
                    req.setDescription("ALCOMET Maintenance");
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    if (mimetype != null) req.setMimeType(mimetype);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    ((DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                    Toast.makeText(MainActivity.this,
                            "Изтегляне: " + fileName, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "Изтеглянето не може да бъде стартирано.", Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setOnLongClickListener(v -> {
            showServerDialog(false);
            return true;
        });
    }

    private void injectAndroidEnhancements() {
        String js = "(function(){"
                + "var id='alcomet-android-v102';"
                + "if(!document.getElementById(id)){"
                + "var s=document.createElement('style');s.id=id;"
                + "s.textContent='"
                + "@media(max-width:900px){"
                + "#newHandoverBtn{width:auto!important;min-width:145px!important;max-width:160px!important;height:42px!important;min-height:42px!important;padding:0 12px!important;justify-content:center!important;white-space:nowrap!important;font-size:12px!important;line-height:1!important;}"
                + ".calendar-shell{overflow:visible!important;}"
                + ".calendar-control{overflow:visible!important;position:relative!important;z-index:40!important;}"
                + ".month-nav{overflow:visible!important;position:relative!important;z-index:45!important;}"
                + ".calendar-month-picker,.calendar-week-picker{z-index:10000!important;top:calc(100% + 6px)!important;}"
                + ".status-task-legend{padding:14px 14px 16px!important;}"
                + ".status-task-legend .legend-groups{gap:14px!important;}"
                + ".status-task-legend .legend-items{display:grid!important;width:100%!important;justify-content:stretch!important;align-items:center!important;gap:8px 14px!important;}"
                + ".status-task-legend .legend-group:first-child .legend-items{grid-template-columns:repeat(2,minmax(0,1fr))!important;}"
                + ".status-task-legend .legend-group:last-child .legend-items{grid-template-columns:repeat(3,minmax(0,1fr))!important;}"
                + ".status-task-legend .legend-item,.status-task-legend .type-legend-item,.status-task-legend .status-legend-item{width:auto!important;min-width:0!important;flex:none!important;justify-content:flex-start!important;margin:0!important;font-size:9.5px!important;line-height:1.2!important;gap:7px!important;}"
                + ".status-task-legend .type-icon,.status-task-legend .emergency-ring{width:22px!important;height:22px!important;flex:0 0 22px!important;}"
                + ".status-task-legend .status-swatch{width:14px!important;height:14px!important;flex:0 0 14px!important;}"
                + "}"
                + "@media(max-width:430px){.status-task-legend .legend-group:last-child .legend-items{grid-template-columns:repeat(2,minmax(0,1fr))!important;}}"
                + "';document.head.appendChild(s);}"
                + "function syncTheme(){var d=document.documentElement.getAttribute('data-theme')==='dark';"
                + "try{ALCOMETAndroid.setDarkMode(d);}catch(e){}}"
                + "syncTheme();"
                + "if(!window.__alcometAndroidThemeObserver){"
                + "window.__alcometAndroidThemeObserver=new MutationObserver(syncTheme);"
                + "window.__alcometAndroidThemeObserver.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});"
                + "}"
                + "if(!window.__alcometCalendarSwipeBound){"
                + "window.__alcometCalendarSwipeBound=true;"
                + "var sx=0,sy=0,active=false;"
                + "document.addEventListener('touchstart',function(e){"
                + "var t=e.target;"
                + "if(!t||!t.closest)return;"
                + "var area=t.closest('.month-grid,.week-strip,.workboard,.mobile-week-board');"
                + "if(!area||!t.closest('.calendar-shell')||t.closest('button,input,select,textarea,[data-calendar-picker],[data-calendar-week-picker]')){active=false;return;}"
                + "if(!e.touches||e.touches.length!==1){active=false;return;}"
                + "sx=e.touches[0].clientX;sy=e.touches[0].clientY;active=true;"
                + "},{passive:true});"
                + "document.addEventListener('touchend',function(e){"
                + "if(!active){return;}active=false;"
                + "if(!e.changedTouches||e.changedTouches.length!==1)return;"
                + "var dx=e.changedTouches[0].clientX-sx,dy=e.changedTouches[0].clientY-sy;"
                + "if(Math.abs(dx)<55||Math.abs(dx)<Math.abs(dy)*1.25)return;"
                + "var btn=document.querySelector(dx<0?'[data-cal-next]':'[data-cal-prev]');"
                + "if(btn){e.preventDefault();btn.click();}"
                + "},{passive:false});"
                + "}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void applySystemBars(boolean dark) {
        int bg = dark ? Color.rgb(7, 21, 35) : Color.rgb(250, 251, 252);
        if (rootView != null) rootView.setBackgroundColor(bg);

        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int appearance = dark ? 0 : mask;
                controller.setSystemBarsAppearance(appearance, mask);
            }
        } else {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void setDarkMode(final boolean dark) {
            runOnUiThread(() -> applySystemBars(dark));
        }
    }

    private void loadServer() {
        connectionDialogVisible = false;
        webView.loadUrl(currentServer + "/");
    }

    private String normalizeServer(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://"))
            value = "http://" + value;
        while (value.endsWith("/"))
            value = value.substring(0, value.length() - 1);
        return value;
    }

    private void showServerDialog(boolean mandatory) {
        if (isFinishing()) return;

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://192.168.1.50:8765");
        input.setText(currentServer);
        input.setSelectAllOnFocus(true);

        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(dp(20), 0, dp(20), 0);
        holder.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("ALCOMET Maintenance сървър")
                .setMessage("Въведи LAN адреса на компютъра/сървъра. Например: http://192.168.1.50:8765")
                .setView(holder)
                .setPositiveButton("Свържи", null);

        if (!mandatory) builder.setNegativeButton("Отказ", null);

        AlertDialog dialog = builder.create();
        dialog.setCancelable(!mandatory);
        dialog.setOnShowListener(x ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String value = normalizeServer(input.getText().toString());
                    if (value.isEmpty()) {
                        input.setError("Въведи адрес на сървъра");
                        return;
                    }
                    currentServer = value;
                    prefs.edit().putString(KEY_SERVER, currentServer).apply();
                    dialog.dismiss();
                    loadServer();
                }));
        dialog.show();
    }

    private void showConnectionError() {
        if (isFinishing() || connectionDialogVisible) return;
        connectionDialogVisible = true;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Няма връзка със сървъра")
                .setMessage("Провери дали Alcomet Maintenance сървърът работи и дали телефонът е в същата мрежа.\n\nТекущ адрес:\n" + currentServer)
                .setPositiveButton("Опитай отново", (d, w) -> {
                    connectionDialogVisible = false;
                    loadServer();
                })
                .setNeutralButton("Смени адреса", (d, w) -> {
                    connectionDialogVisible = false;
                    showServerDialog(false);
                })
                .setNegativeButton("Затвори", (d, w) -> connectionDialogVisible = false)
                .create();

        dialog.setOnCancelListener(d -> connectionDialogVisible = false);
        dialog.show();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQ) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int i = 0; i < count; i++)
                    result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }

        if (fileCallback != null) {
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("ALCOMETAndroid");
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
