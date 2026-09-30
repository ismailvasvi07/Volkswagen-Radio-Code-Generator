package com.alcomet.maintenance;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final String PREFS = "alcomet_mobile";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_CREDENTIAL_BLOB = "remember_credentials_blob";
    private static final String CREDENTIAL_KEY_ALIAS = "alcomet_maintenance_credentials_v1";
    private static final int FILE_CHOOSER_REQ = 1201;

    private WebView webView;
    private ProgressBar progress;
    private FrameLayout rootView;
    private FrameLayout splashOverlay;
    private boolean splashDismissed = false;
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
        addStartupSplash();
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

        if (currentServer.isEmpty()) { hideStartupSplash(); showServerDialog(true); }
        else loadServer();
    }

    private void addStartupSplash() {
        splashOverlay = new FrameLayout(this);
        splashOverlay.setBackgroundColor(Color.rgb(250, 251, 252));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView logo = new TextView(this);
        logo.setText("ALC⚙MET");
        logo.setTextColor(Color.rgb(16, 153, 217));
        logo.setTextSize(30);
        logo.setGravity(Gravity.CENTER);
        logo.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        logo.setLetterSpacing(0.03f);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(logo, logoParams);

        TextView sub = new TextView(this);
        sub.setText("Maintenance");
        sub.setTextColor(Color.rgb(118, 139, 153));
        sub.setTextSize(11);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.gravity = Gravity.CENTER_HORIZONTAL;
        subParams.topMargin = dp(2);
        box.addView(sub, subParams);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        spinnerParams.gravity = Gravity.CENTER_HORIZONTAL;
        spinnerParams.topMargin = dp(22);
        box.addView(spinner, spinnerParams);

        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.gravity = Gravity.CENTER;
        splashOverlay.addView(box, boxParams);

        rootView.addView(splashOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logo.setAlpha(0f);
        logo.setScaleX(0.88f);
        logo.setScaleY(0.88f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).start();
    }

    private void hideStartupSplash() {
        if (splashOverlay == null || splashDismissed) return;
        splashDismissed = true;
        splashOverlay.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (rootView != null && splashOverlay != null) rootView.removeView(splashOverlay);
            splashOverlay = null;
        }).start();
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
                injectModernDesign();
                injectCalendarCompactCount();
                injectWeekCompactCount();
                injectRememberMe();
                injectNotificationReliabilityFix();
                injectWorkshopDropdown();
                injectTaskFileRemoval();
                hideStartupSplash();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) { hideStartupSplash(); showConnectionError(); }
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

    private String readAssetText(String name) {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    private void injectModernDesign() {
        String css = readAssetText("alcomet-modern.css") + "\n" + readAssetText("alcomet-retouch.css");
        if (css.isEmpty()) return;
        String js = "(function(){"
                + "document.documentElement.classList.add('apk-modern');"
                + "document.body&&document.body.classList.add('apk-modern-body');"
                + "var old=document.getElementById('alcomet-modern-apk-style');if(old)old.remove();"
                + "var s=document.createElement('style');s.id='alcomet-modern-apk-style';"
                + "s.textContent=" + JSONObject.quote(css) + ";document.head.appendChild(s);"
                + "})();";
        webView.evaluateJavascript(js, null);
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
                + "var sx=0,sy=0,active=false,dragging=false,parts=[];"
                + "function currentParts(){"
                + "var shell=document.querySelector('.calendar-shell');if(!shell)return [];"
                + "var head=shell.querySelector('.week-head');"
                + "var month=shell.querySelector('.month-grid');"
                + "var week=shell.querySelector('.week-strip');"
                + "var board=shell.querySelector('.mobile-week-board')||shell.querySelector('.workboard');"
                + "var arr=[];"
                + "if(head)arr.push(head);"
                + "if(month&&month.offsetParent!==null)arr.push(month);"
                + "else{if(week)arr.push(week);if(board)arr.push(board);}"
                + "return arr;"
                + "}"
                + "function clearParts(arr){(arr||[]).forEach(function(el){el.style.transition='';el.style.transform='';el.style.opacity='';el.style.willChange='';});}"
                + "function applyParts(arr,dx,opacity,transition){(arr||[]).forEach(function(el){el.style.willChange='transform,opacity';el.style.transition=transition||'none';el.style.transform='translate3d('+dx+'px,0,0)';el.style.opacity=String(opacity);});}"
                + "document.addEventListener('touchstart',function(e){"
                + "var t=e.target;if(!t||!t.closest)return;"
                + "var hit=t.closest('.week-head,.month-grid,.week-strip,.workboard,.mobile-week-board');"
                + "if(!hit||!t.closest('.calendar-shell')||t.closest('button,input,select,textarea,[data-calendar-picker],[data-calendar-week-picker]')){active=false;dragging=false;parts=[];return;}"
                + "if(!e.touches||e.touches.length!==1){active=false;return;}"
                + "sx=e.touches[0].clientX;sy=e.touches[0].clientY;active=true;dragging=false;parts=currentParts();"
                + "parts.forEach(function(el){el.style.willChange='transform,opacity';});"
                + "},{passive:true});"
                + "document.addEventListener('touchmove',function(e){"
                + "if(!active||!parts.length||!e.touches||e.touches.length!==1)return;"
                + "var dx=e.touches[0].clientX-sx,dy=e.touches[0].clientY-sy;"
                + "if(!dragging){"
                + "if(Math.abs(dy)>12&&Math.abs(dy)>Math.abs(dx)*1.15){active=false;clearParts(parts);parts=[];return;}"
                + "if(Math.abs(dx)<8||Math.abs(dx)<Math.abs(dy)*1.15)return;"
                + "dragging=true;"
                + "}"
                + "e.preventDefault();"
                + "var shell=document.querySelector('.calendar-shell');var w=(shell&&shell.clientWidth)||window.innerWidth;var max=w*0.44;"
                + "if(dx>max)dx=max;if(dx<-max)dx=-max;"
                + "applyParts(parts,dx,1-Math.min(.18,Math.abs(dx)/Math.max(1,w)*.38),'none');"
                + "},{passive:false});"
                + "document.addEventListener('touchend',function(e){"
                + "if(!active||!parts.length){active=false;dragging=false;return;}"
                + "var oldParts=parts.slice();active=false;parts=[];"
                + "if(!e.changedTouches||e.changedTouches.length!==1){clearParts(oldParts);dragging=false;return;}"
                + "var dx=e.changedTouches[0].clientX-sx,dy=e.changedTouches[0].clientY-sy;"
                + "var valid=dragging&&Math.abs(dx)>=55&&Math.abs(dx)>=Math.abs(dy)*1.2;dragging=false;"
                + "if(!valid){applyParts(oldParts,0,1,'transform 170ms cubic-bezier(.22,.75,.24,1),opacity 170ms ease');setTimeout(function(){clearParts(oldParts);},190);return;}"
                + "e.preventDefault();"
                + "var next=dx<0;var btn=document.querySelector(next?'[data-cal-next]':'[data-cal-prev]');"
                + "if(!btn){clearParts(oldParts);return;}"
                + "var shell=document.querySelector('.calendar-shell');var w=(shell&&shell.clientWidth)||window.innerWidth;"
                + "applyParts(oldParts,next?-w:w,.30,'transform 190ms cubic-bezier(.32,.72,0,1),opacity 190ms ease');"
                + "setTimeout(function(){"
                + "btn.click();"
                + "setTimeout(function(){"
                + "var newParts=currentParts();var inX=next?w:-w;"
                + "applyParts(newParts,inX,.30,'none');"
                + "requestAnimationFrame(function(){requestAnimationFrame(function(){"
                + "applyParts(newParts,0,1,'transform 230ms cubic-bezier(.22,.8,.2,1),opacity 210ms ease');"
                + "setTimeout(function(){clearParts(newParts);},260);"
                + "});});"
                + "},25);"
                + "},190);"
                + "},{passive:false});"
                + "document.addEventListener('touchcancel',function(){clearParts(parts);active=false;dragging=false;parts=[];},{passive:true});"
                + "}"                + "})();";
        webView.evaluateJavascript(js, null);
    }


    private void injectCalendarCompactCount() {
        String js = "(function(){"
                + "function compact(){"
                + "document.querySelectorAll('.month-cell .day-icons').forEach(function(box){"
                + "var items=Array.prototype.slice.call(box.querySelectorAll('.task-dot'));"
                + "items.forEach(function(n,i){n.style.display=i<4?'':'none';});"
                + "var hidden=Math.max(0,items.length-4);"
                + "var more=box.querySelector('.apk-more-tasks');"
                + "if(hidden>0){"
                + "if(!more){more=document.createElement('span');more.className='apk-more-tasks';box.appendChild(more);}"
                + "more.textContent='+'+hidden;"
                + "more.title='Още '+hidden+' задачи';"
                + "more.style.display='grid';"
                + "}else if(more){more.remove();}"
                + "});"
                + "}"
                + "compact();"
                + "if(!window.__alcometCompactObserver){"
                + "var t=null;window.__alcometCompactObserver=new MutationObserver(function(muts){"
                + "var relevant=muts.some(function(m){return Array.prototype.some.call(m.addedNodes,function(n){return n.nodeType===1&&(n.matches&&n.matches('.month-grid,.month-cell,.day-icons,.task-dot')||n.querySelector&&n.querySelector('.month-cell,.day-icons,.task-dot'));});});"
                + "if(!relevant)return;clearTimeout(t);t=setTimeout(compact,20);"
                + "});"
                + "window.__alcometCompactObserver.observe(document.body,{childList:true,subtree:true});"
                + "}"
                + "if(!window.__alcometCompactTimer){window.__alcometCompactTimer=setInterval(compact,700);}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void injectWeekCompactCount() {
        String js = "(function(){"
                + "function compactWeek(){"
                + "document.querySelectorAll('.week-strip').forEach(function(strip){"
                + "strip.querySelectorAll('*').forEach(function(n){"
                + "var t=(n.textContent||'').trim();"
                + "if(/^(Седмица|Week)\\s+\\d+$/i.test(t)&&n.children.length===0)n.style.setProperty('display','none','important');"
                + "});"
                + "var days=Array.prototype.slice.call(strip.querySelectorAll('.week-day'));"
                + "var maxTasks=0;"
                + "days.forEach(function(day){"
                + "day.querySelectorAll('.apk-week-more-tasks').forEach(function(n){n.remove();});"
                + "var items=Array.prototype.slice.call(day.querySelectorAll('.task-dot'));"
                + "if(!items.length){var box=day.querySelector('.day-icons');if(box){items=Array.prototype.slice.call(box.children).filter(function(n){return n.nodeType===1&&!n.classList.contains('apk-week-more-tasks');});}}"
                + "maxTasks=Math.max(maxTasks,items.length);"
                + "items.forEach(function(n,i){n.style.setProperty('display',i<4?'':'none','important');});"
                + "var hidden=Math.max(0,items.length-4);"
                + "if(hidden>0&&items.length){"
                + "var host=items[0].parentElement||day;"
                + "var more=document.createElement('span');more.className='apk-week-more-tasks';more.textContent='+'+hidden;more.title='Още '+hidden+' задачи';host.appendChild(more);"
                + "}"
                + "});"
                + "var h=maxTasks===0?72:(maxTasks<=2?88:108);"
                + "days.forEach(function(day){day.style.setProperty('height',h+'px','important');day.style.setProperty('min-height',h+'px','important');});"
                + "strip.style.setProperty('min-height',h+'px','important');"
                + "strip.style.setProperty('height',h+'px','important');"
                + "strip.classList.toggle('apk-week-empty',maxTasks===0);"
                + "strip.classList.toggle('apk-week-low',maxTasks>0&&maxTasks<=2);"
                + "strip.classList.toggle('apk-week-busy',maxTasks>2);"
                + "});"
                + "}"
                + "compactWeek();"
                + "if(window.__alcometWeekCompactTimer)clearInterval(window.__alcometWeekCompactTimer);"
                + "window.__alcometWeekCompactTimer=setInterval(compactWeek,250);"
                + "if(window.__alcometWeekCompactObserver){try{window.__alcometWeekCompactObserver.disconnect();}catch(e){}}"
                + "var t=null;window.__alcometWeekCompactObserver=new MutationObserver(function(){clearTimeout(t);t=setTimeout(compactWeek,15);});"
                + "window.__alcometWeekCompactObserver.observe(document.body,{childList:true,subtree:true});"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private SecretKey getOrCreateCredentialKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(CREDENTIAL_KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(CREDENTIAL_KEY_ALIAS, null);
            return entry.getSecretKey();
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                CREDENTIAL_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return keyGenerator.generateKey();
    }

    private void saveCredentialsSecure(String username, String password) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("username", username == null ? "" : username);
            payload.put("password", password == null ? "" : password);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateCredentialKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
            String blob = Base64.encodeToString(iv, Base64.NO_WRAP)
                    + "."
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs.edit().putString(KEY_CREDENTIAL_BLOB, blob).apply();
        } catch (Exception e) {
            prefs.edit().remove(KEY_CREDENTIAL_BLOB).apply();
        }
    }

    private String loadCredentialsSecure() {
        try {
            String blob = prefs.getString(KEY_CREDENTIAL_BLOB, "");
            if (blob == null || blob.isEmpty() || !blob.contains(".")) return "{}";
            String[] parts = blob.split("\\.", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateCredentialKey(), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(encrypted);
            JSONObject payload = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            payload.put("remember", true);
            return payload.toString();
        } catch (Exception e) {
            prefs.edit().remove(KEY_CREDENTIAL_BLOB).apply();
            return "{}";
        }
    }

    private void clearCredentialsSecure() {
        prefs.edit().remove(KEY_CREDENTIAL_BLOB).apply();
    }

    private void injectRememberMe() {
        String js = "(function(){"
                + "var form=document.getElementById('loginForm');if(!form)return;"
                + "if(!document.getElementById('apkRememberRow')){"
                + "var pass=document.getElementById('loginPass');"
                + "var row=document.createElement('label');row.id='apkRememberRow';row.className='apk-remember-row';"
                + "row.innerHTML='<span class=\\\"apk-remember-check\\\"><input id=\\\"apkRememberMe\\\" type=\\\"checkbox\\\"> <b>Запомни ме</b></span>';"
                + "var pl=pass&&pass.closest('label');if(pl)pl.insertAdjacentElement('afterend',row);"
                + "}"
                + "var remember=document.getElementById('apkRememberMe');"
                + "var user=document.getElementById('loginUser');var pass=document.getElementById('loginPass');"
                + "var saved={};try{saved=JSON.parse(ALCOMETAndroid.getSavedCredentials()||'{}');}catch(e){}"
                + "if(saved.remember){if(user)user.value=saved.username||'';if(pass)pass.value=saved.password||'';if(remember)remember.checked=true;}"
                + "var pending=null;"
                + "if(!form.__apkRememberBound){form.__apkRememberBound=true;"
                + "form.addEventListener('submit',function(){"
                + "var on=!!(remember&&remember.checked);"
                + "pending={username:user?user.value:'',password:pass?pass.value:'',remember:on};"
                + "if(!on){try{ALCOMETAndroid.clearSavedCredentials();}catch(e){}}"
                + "},true);"
                + "var app=document.getElementById('appView');if(app){"
                + "new MutationObserver(function(){"
                + "if(pending&&pending.remember&&!app.classList.contains('hidden')){"
                + "try{ALCOMETAndroid.saveCredentials(pending.username,pending.password,true);}catch(e){}pending=null;"
                + "}"
                + "}).observe(app,{attributes:true,attributeFilter:['class']});"
                + "}"
                + "}"
                + "if(saved.remember&&!window.__apkAutoLoginTried){window.__apkAutoLoginTried=true;"
                + "setTimeout(function(){var lv=document.getElementById('loginView');"
                + "if(lv&&!lv.classList.contains('hidden')&&form){if(form.requestSubmit)form.requestSubmit();else form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));}},450);"
                + "}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void injectNotificationReliabilityFix() {
        String js = "(function(){"
                + "var old=document.getElementById('notificationBtn');"
                + "if(!old||old.__apkNotificationFixed)return;"
                + "var b=old.cloneNode(true);b.__apkNotificationFixed=true;"
                + "old.replaceWith(b);"
                + "b.addEventListener('click',function(e){"
                + "e.preventDefault();e.stopPropagation();"
                + "try{setNotificationPanel(!document.body.classList.contains('mobile-notification-open'));}catch(err){}"
                + "},false);"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void injectWorkshopDropdown() {
        String js = "(function(){"
                + "function buildWorkshopSelect(){"
                + "var filter=document.querySelector('.calendar-workshop-filter');"
                + "if(!filter)return;"
                + "var options=filter.querySelector('.calendar-workshop-options');"
                + "if(!options)return;"
                + "var chips=Array.prototype.slice.call(options.querySelectorAll('.workshop-filter-chip'));"
                + "var allBtn=options.querySelector('.workshop-filter-all');"
                + "if(!chips.length&&!allBtn)return;"
                + "var wrap=filter.querySelector('.apk-workshop-select-wrap');"
                + "if(!wrap){wrap=document.createElement('div');wrap.className='apk-workshop-select-wrap';"
                + "var sel=document.createElement('select');sel.className='apk-workshop-select';"
                + "wrap.appendChild(sel);filter.insertBefore(wrap,options);"
                + "sel.addEventListener('change',function(){"
                + "var val=this.value;"
                + "var currentOptions=filter.querySelector('.calendar-workshop-options');if(!currentOptions)return;"
                + "var currentAll=currentOptions.querySelector('.workshop-filter-all');"
                + "var currentChips=Array.prototype.slice.call(currentOptions.querySelectorAll('.workshop-filter-chip'));"
                + "if(val==='all'){if(currentAll&&!currentAll.classList.contains('active'))currentAll.click();return;}"
                + "var idx=parseInt(val,10);var target=currentChips[idx];if(!target)return;"
                + "if(currentAll&&currentAll.classList.contains('active'))currentAll.click();"
                + "currentChips.forEach(function(ch,i){var inp=ch.querySelector('input');if(i!==idx&&inp&&inp.checked)ch.click();});"
                + "var ti=target.querySelector('input');if(!ti||!ti.checked)target.click();"
                + "setTimeout(syncWorkshopSelect,60);"
                + "});"
                + "}"
                + "var sel=wrap.querySelector('select');if(!sel)return;"
                + "var sig=(allBtn?(allBtn.textContent||''):'')+'|'+chips.map(function(ch){return(ch.textContent||'').trim();}).join('|');"
                + "if(sel.dataset.sig!==sig){"
                + "sel.innerHTML='';"
                + "var ao=document.createElement('option');ao.value='all';ao.textContent=(allBtn&&(allBtn.textContent||'').trim())||'Всички цехове';sel.appendChild(ao);"
                + "chips.forEach(function(ch,i){var o=document.createElement('option');o.value=String(i);o.textContent=(ch.textContent||'').trim();sel.appendChild(o);});"
                + "sel.dataset.sig=sig;"
                + "}"
                + "syncWorkshopSelect();"
                + "}"
                + "function syncWorkshopSelect(){"
                + "var filter=document.querySelector('.calendar-workshop-filter');if(!filter)return;"
                + "var options=filter.querySelector('.calendar-workshop-options');var sel=filter.querySelector('.apk-workshop-select');"
                + "if(!options||!sel)return;"
                + "var allBtn=options.querySelector('.workshop-filter-all');"
                + "var chips=Array.prototype.slice.call(options.querySelectorAll('.workshop-filter-chip'));"
                + "if(allBtn&&allBtn.classList.contains('active')){sel.value='all';return;}"
                + "var idx=chips.findIndex(function(ch){var inp=ch.querySelector('input');return !!(inp&&inp.checked);});"
                + "sel.value=idx>=0?String(idx):'all';"
                + "}"
                + "buildWorkshopSelect();"
                + "if(!window.__alcometWorkshopDropdownObserver){"
                + "var t=null;window.__alcometWorkshopDropdownObserver=new MutationObserver(function(){clearTimeout(t);t=setTimeout(buildWorkshopSelect,40);});"
                + "window.__alcometWorkshopDropdownObserver.observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['class','checked']});"
                + "window.__alcometWorkshopDropdownTimer=setInterval(buildWorkshopSelect,1000);"
                + "}"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void injectTaskFileRemoval() {
        String js = "(function(){"
                + "function bind(){"
                + "var input=document.getElementById('taskFiles');var list=document.getElementById('taskFileList');"
                + "if(!input||!list||input.__apkRemoveBound)return;"
                + "input.__apkRemoveBound=true;"
                + "var original=input.onchange;"
                + "function render(){"
                + "var files=Array.prototype.slice.call(input.files||[]);"
                + "list.innerHTML='';"
                + "files.forEach(function(file,index){"
                + "var row=document.createElement('div');row.className='apk-task-file-row';"
                + "var name=document.createElement('span');name.className='apk-task-file-name';"
                + "name.textContent=file.name+' · '+Math.ceil(file.size/1024)+' KB';"
                + "var remove=document.createElement('button');remove.type='button';remove.className='apk-task-file-remove';"
                + "remove.setAttribute('aria-label','Премахни файла');remove.title='Премахни файла';remove.textContent='×';"
                + "remove.addEventListener('click',function(e){"
                + "e.preventDefault();e.stopPropagation();"
                + "var current=Array.prototype.slice.call(input.files||[]);"
                + "var dt=new DataTransfer();"
                + "current.forEach(function(f,i){if(i!==index)dt.items.add(f);});"
                + "input.files=dt.files;"
                + "render();"
                + "});"
                + "row.appendChild(name);row.appendChild(remove);list.appendChild(row);"
                + "});"
                + "}"
                + "input.onchange=function(e){if(typeof original==='function')original.call(input,e);setTimeout(render,0);};"
                + "render();"
                + "}"
                + "bind();"
                + "if(!window.__alcometTaskFileRemoveObserver){"
                + "var t=null;window.__alcometTaskFileRemoveObserver=new MutationObserver(function(){clearTimeout(t);t=setTimeout(bind,25);});"
                + "window.__alcometTaskFileRemoveObserver.observe(document.body,{childList:true,subtree:true});"
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

        @JavascriptInterface
        public String getSavedCredentials() {
            return loadCredentialsSecure();
        }

        @JavascriptInterface
        public void saveCredentials(String username, String password, boolean remember) {
            if (remember) saveCredentialsSecure(username, password);
            else clearCredentialsSecure();
        }

        @JavascriptInterface
        public void clearSavedCredentials() {
            clearCredentialsSecure();
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
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (rootView != null) {
            rootView.requestApplyInsets();
            rootView.requestLayout();
        }
        if (webView != null) {
            webView.requestLayout();
            webView.invalidate();
        }
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
