package com.kdt.mrpc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import java.net.URISyntaxException;
import android.util.Log;

public class RPCService extends Service implements RPCCallback {
    private final RPCBinder binder = new RPCBinder();
    private Thread wsThread;
    private Notification.Builder builder;
    private Notification notification;
    protected int lastCode = 0;
    protected String lastReason = null;
    protected RPCCallback callback;
    protected DiscordSocketClient webSocketClient;

    @Override
    public IBinder onBind(Intent i) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wsThread = new Thread(new Runnable(){
            @Override
            public void run() {
                try {
                    webSocketClient = new DiscordSocketClient(RPCService.this);
                    webSocketClient.connect();
                } catch (URISyntaxException e) {
                    // this should never happen
                    throw new RuntimeException(e);
                }
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel mChannel = new NotificationChannel(
                "MRPC",
                "MRPC",
                NotificationManager.IMPORTANCE_LOW);
            getManager().createNotificationChannel(mChannel);

            builder = new Notification.Builder(this, "MRPC");
        } else {
            builder = new Notification.Builder(this);
        }
        builder
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setContentTitle(getString(R.string.app_name))
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setOngoing(true);
        notification = builder.build();
        startForeground(1, notification);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        wsThread.start();
        //getManager().notify(1, mNotification);
        //startForeground(1, mNotification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webSocketClient != null && !webSocketClient.isClosed()) {
            webSocketClient.close(RPCCallback.STATUS_CLOSED);
        }
        
        stopForeground(true);

        if (lastCode != 0) {
            builder.setOngoing(false);
            notification = builder.build();
            getManager().notify(1, notification);
        }
    }

    @Override
    public void onStatusChanged(int code, String reason, boolean remote) {
        if (callback != null) {
            callback.onStatusChanged(code, reason, remote);
            return;
        }
        lastCode = code;
        lastReason = reason;
    }

    @Override
    public void appendToLog(String line) {
        if (callback != null) {
            callback.appendToLog(line);
        }
        Log.i(getString(R.string.app_name), line);
    }

    private NotificationManager getManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    public void setCallback(RPCCallback callback) {
        this.callback = callback;
        if (callback != null && lastCode != 0) {
            callback.onStatusChanged(lastCode, lastReason, false);
        }
    }

    protected void updateMessage(String s) {
        appendToLog(s);
        builder.setContentText(s);
        notification = builder.build();
        getManager().notify(1, notification);
    }

    public class RPCBinder extends Binder {
        public RPCService getService() {
            return RPCService.this;
        }
    }
}
