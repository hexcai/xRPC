package com.kdt.mrpc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;

public class MainActivity extends Activity implements ServiceConnection, RPCCallback
{
    protected static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    protected static SharedPreferences PREF;

    private Intent intent;
    private RPCService service;

    private WebView webView;
    private TextView textviewLog;
    private Button buttonConnect, buttonSetActivity;
    private EditText editActivityName, editActivityState,
        editActivityDetails, editActivityLargeImage, editActivitySmallImage;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        PREF = PreferenceManager.getDefaultSharedPreferences(this);

        webView = (WebView) findViewById(R.id.mainWebView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAppCacheEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                Log.d("Web", "Attempt to enter " + url);
                if (url.endsWith("/app")) {
                    webView.evaluateJavascript("javascript:window.localStorage.getItem('token');", new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String s) {
                                webView.setVisibility(View.GONE);
                                appendToLog("value = " + s);
                            }
                        });
                    
                    webView.stopLoading();
                    extractToken();
                    login(v);
                    
                    return false;
                }
                return super.shouldOverrideUrlLoading(v, url);
            }
        });

        textviewLog = (TextView) findViewById(R.id.textviewLog);
        buttonConnect = (Button) findViewById(R.id.buttonConnect);
        buttonSetActivity = (Button) findViewById(R.id.buttonSetActivity);
        buttonSetActivity.setEnabled(false);
        editActivityName = (EditText) findViewById(R.id.editActivityName);
        editActivityState = (EditText) findViewById(R.id.editActivityState);
        editActivityDetails = (EditText) findViewById(R.id.editActivityDetails);
        editActivityLargeImage = findViewById(R.id.editActivityLargeImage);
        editActivitySmallImage = findViewById(R.id.editActivitySmallImage);

        intent = new Intent(this, RPCService.class);
        bindService(intent, this, BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (service.lastCode < 2) {
            unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder binder) {
        service = ((RPCService.RPCBinder)binder).getService();
        service.setCallback(this);
        if (service.lastCode == RPCCallback.STATUS_CONNECTED) {
            appendToLog("Connected to the running service");
        }
        if (service.lastCode != 0) {
            appendToLog("Previous code: " + service.lastCode + ", reason: " + service.lastReason);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        service.setCallback(null);
        service = null;
    }

    /*
     @Override
     protected void onResume() {
     super.onResume();
     Uri data = getIntent().getData(); 
     if (data != null && wsThr == null) {
     if (data.toString().contains("access_token=")) {
     accessToken = data.toString().substring(
     data.toString().indexOf("access_token=") + 13,
     data.toString().indexOf("&expires_in")
     );
     actualConnect(null);
     } else {
     actualConnect(data.getQueryParameter("code"));
     }
     }
     }
     */

    public void sendPresenceUpdate(View v) {
        service.webSocketClient.sendPresenceUpdate(
            editActivityName.getText().toString(),
            editActivityDetails.getText().toString(),
            editActivityState.getText().toString(),
            editActivityLargeImage.getText().toString(),
            editActivitySmallImage.getText().toString());
    }

    @Override
    public void appendToLog(final String msg) {
        runOnUiThread(new Runnable(){
            @Override
            public void run() {
                textviewLog.append(msg + "\n");
            }
        });
    }

    @Override
    public void onStatusChanged(final int code, final String reason, final boolean remote) {
        service.lastCode = code;
        service.lastReason = reason;
        runOnUiThread(new Runnable(){
            @Override
            public void run() {
                buttonConnect.setEnabled(true);
                buttonSetActivity.setEnabled(code == RPCCallback.STATUS_CONNECTED);
                if (code == RPCCallback.STATUS_CONNECTED) {
                    buttonConnect.setText("Disconnect");
                } else {
                    disconnect(false);
                }
            }
        });
    }

    public void login(View v) {
        if (webView.getVisibility() == View.VISIBLE) {
            webView.stopLoading();
            webView.setVisibility(View.GONE);
            return;
        }
        /*
        if (PREF.contains("authToken")) {
            appendToLog("Logged in");
            connect(null);
            return;
        }
        */
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl("https://discord.com/login");
    }

    public void connect(View v) {
        if (v != null && !extractToken()) {
            Toast.makeText(this, "Token is not found, opening login page", Toast.LENGTH_SHORT).show();
            login(null);
            return;
        }
        
        if (buttonConnect.getText().equals("Connect")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            buttonConnect.setEnabled(false);
        } else {
            disconnect(true);
        }
    }

    public void disconnect(boolean unbind) {
        if (unbind) {
            unbindService(this);
            stopService(intent);
        }
        buttonConnect.setText("Connect");
        buttonSetActivity.setEnabled(false);
    }

    public boolean extractToken() {
        // ~~extract token in an ugly way :troll:~~
        try {
            File f = new File(getFilesDir().getParentFile(), "app_webview/Default/Local Storage/leveldb");
            File[] fArr = f.listFiles(new FilenameFilter(){
                @Override
                public boolean accept(File file, String name) {
                    return name.endsWith(".log");
                }
            });
            if (fArr.length == 0) {
                return false;
            }
            f = fArr[0];
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("token")) {
                    break;
                }
            }
            line = line.substring(line.indexOf("token") + 5);
            line = line.substring(line.indexOf("\"") + 1);
            PREF.edit().putString("authToken", line.substring(0, line.indexOf("\""))).commit();
            appendToLog("Successfully extracted token");
            return true;
        } catch (Throwable e) {
            appendToLog("Failed to extract token: " + Log.getStackTraceString(e));
            return false;
        }
    }

    /*
     public void code2AccessToken(String code) {
     try {
     HttpURLConnection conn = (HttpURLConnection) new URL("https://discord.com/api/oauth2/token").openConnection();
     conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
     conn.setDoInput(true);

     ArrayMap<String, Object> prop = new ArrayMap<>();
     prop.put("client_id", "591317049637339146");
     prop.put("client_secret", "Discord Android");
     prop.put("grant_type", "authorization_code");
     prop.put("code", code);
     prop.put("redirect_url", "https://account.samsung.com/accounts/oauth/callback");

     byte[] arr = gson.toJson(prop).getBytes();

     conn.getOutputStream().write(arr, 0, arr.length);
     } catch (Throwable e) {
     throw new RuntimeException(e);
     }
     }

    public String decodeString(byte[] arr) {
        Inflater i = new Inflater();
        i.setInput(arr, 0, arr.length);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(arr.length); 
        byte[] buf = new byte[1024];
        try {
            int count = 1;
            while (!i.finished() && count > 0)  { 
                count = i.inflate(buf);
                appendlnToLog("decode " + count);
                bos.write(buf, 0, count); 
            }
        } catch (DataFormatException e) {
            throw new RuntimeException(e);
        }
        i.end();
        try {
            bos.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new String(bos.toByteArray());
    }

    public byte[] encodeString(String str) {
        byte[] arr = str.getBytes();
        Deflater compresser = new Deflater();
        compresser.setInput(arr);
        compresser.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(arr.length); 
        // Compress the data 
        byte[] buf = new byte[1024]; 
        while (!compresser.finished())  { 
            int count = compresser.deflate(buf); 
            bos.write(buf, 0, count);
        } 
        compresser.end();
        try {
            bos.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        
        return bos.toByteArray();
    }
 */
}
