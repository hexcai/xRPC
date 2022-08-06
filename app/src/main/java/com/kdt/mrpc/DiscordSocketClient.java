package com.kdt.mrpc;

import android.util.ArrayMap;
import android.util.Log;
import com.google.gson.reflect.TypeToken;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLParameters;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class DiscordSocketClient extends WebSocketClient
{
    protected String authToken;
    private final RPCService service;
    private int heartbeat_interval, seq;
    private final Runnable heartbeatRunnable;
    private Thread heartbeatThr;
    private String currentStatus;

    public DiscordSocketClient(final RPCService service) throws URISyntaxException {
        super(new URI("wss://gateway.discord.gg/?encoding=json&v=9"));
        this.service = service;
        heartbeatRunnable = new Runnable(){
            @Override
            public void run() {
                try {
                    service.updateMessage("Heartbeat wait for " + heartbeat_interval);
                    if (heartbeat_interval < 10000) throw new RuntimeException("invalid");
                    Thread.sleep(heartbeat_interval);
                    send(/*encodeString*/("{\"op\":1, \"d\":" + (seq==0?"null":Integer.toString(seq)) + "}"));
                    service.updateMessage("Heartbeat sent");
                } catch (InterruptedException e) {}
            }
        };
    }

    @Override
    public void close(int code) {
        heartbeatThr.interrupt();
        super.close(code);
    }

    @Override
    public void onOpen(ServerHandshake s) {
        service.updateMessage("Connection opened");
    }

    private void onMessageDispatch(ArrayMap<String, Object> map) {
        Map data;
        switch (((String)map.get("t"))) {
            case "READY":
                data = (Map) ((Map)map.get("d")).get("user");
                service.updateMessage("Connected to " + data.get("username") + "#" + data.get("discriminator"));
                service.onStatusChanged(RPCCallback.STATUS_CONNECTED, null, true);
                break;
            case "SESSIONS_REPLACE":
                data = (Map) ((List)map.get("d")).get(0);
                currentStatus = (String) data.get("status");
                service.appendToLog("Status set to " + currentStatus);
                break;
        }
    }
    
    @Override
    public void onMessage(ByteBuffer message) {
        // onMessage(decodeString(message.array()));
    }

    @Override
    public void onMessage(String message) {
        //service.mCallback.appendToLog("onTextReceived: " + message);

        ArrayMap<String, Object> map = MainActivity.GSON.fromJson(
            message, new TypeToken<ArrayMap<String, Object>>() {}.getType()
        );

        // obtain sequence number
        Object o = map.get("s");
        if (o != null) {
            seq = ((Double)o).intValue();
        }

        int opcode = ((Double)map.get("op")).intValue();
        switch (opcode) {
            case 0: // Dispatch event
                onMessageDispatch(map);
                break;
            case 10: // Hello
                Map data = (Map) map.get("d");
                heartbeat_interval = ((Double)data.get("heartbeat_interval")).intValue();
                heartbeatThr = new Thread(heartbeatRunnable);
                heartbeatThr.start();
                sendIdentify();
                break;
            case 1: // Heartbeat request
                if (!heartbeatThr.interrupted()) {
                    heartbeatThr.interrupt();
                }
                send(/*encodeString*/("{\"op\":1, \"d\":" + (seq==0?"null":Integer.toString(seq)) + "}"));

                break;
            case 11: // Heartbeat ACK
                if (!heartbeatThr.interrupted()) {
                    heartbeatThr.interrupt();
                }
                heartbeatThr = new Thread(heartbeatRunnable);
                heartbeatThr.start();
                break;
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        service.onStatusChanged(code, reason, remote);
        service.updateMessage("Connection closed: " + code + ": " + reason);
        if (!heartbeatThr.interrupted()) {
            heartbeatThr.interrupt();
        }
        /*
        runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    buttonConnect.setText("Connect");
                    buttonSetActivity.setEnabled(false);
                }
            });
        */
        throw new RuntimeException("Interrupt");
    }

    @Override
    public void onError(Exception e) {
        if (!e.getMessage().equals("Interrupt") && service.callback != null) {
            service.callback.appendToLog(Log.getStackTraceString(e));
        }
    }

    @Override
    protected void onSetSSLParameters(SSLParameters p) {
        try {
            super.onSetSSLParameters(p);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void sendIdentify() {
        ArrayMap<String, Object> prop = new ArrayMap<>();
        prop.put("$os", "linux");
        prop.put("$browser", "Discord Android");
        prop.put("$device", "unknown");

        ArrayMap<String, Object> data = new ArrayMap<>();
        data.put("token", MainActivity.PREF.getString("authToken", null));
        data.put("properties", prop);
        data.put("compress", false);
        data.put("intents", 0);

        ArrayMap<String, Object> arr = new ArrayMap<>();
        arr.put("op", 2);
        arr.put("d", data);

        send(MainActivity.GSON.toJson(arr));
    }

    private String processImageLink(String link) {
        if (link.isEmpty()) {
            return null;
        }
        if (link.contains("://")) {
            link = link.split("://")[1];
        }
        if (link.startsWith("media.discordapp.net/")) {
            return link.replace("media.discordapp.net/", "mp:");
        } else if (link.startsWith("cdn.discordapp.com")) {
            // Trick: allow using CDN URL for custom image
            // https://cdn.discordapp.com/app-assets/application-id/../../whatever.png_or_gif#.png
            // ".." resolves to the parent directory
            // "#" at the end to exclude ".png" from the link
            // so it becomes
            // https://cdn.discordapp.com/whatever.png_or_gif
            return link.replace("cdn.discordapp.com/", "../../") + "#";
        }
        return link;
    }

    public void sendPresenceUpdate(String name, String details, String state, String largeImage, String smallImage) {
        long current = System.currentTimeMillis();

        ArrayMap<String, Object> presence = new ArrayMap<>();

        if (name.isEmpty()) {
            presence.put("activities", new Object[]{});
        } else {
            ArrayMap<String, Object> activity = new ArrayMap<>();
            activity.put("name", name);
            if (!state.isEmpty()) {
                activity.put("state", state);
            }
            if (!details.isEmpty()) {
                activity.put("details", details);
            }
            activity.put("type", 0);

            ArrayMap<String, Object> assets = new ArrayMap<>();
            assets.put("large_image", processImageLink(largeImage));
            assets.put("small_image", processImageLink(smallImage));
            activity.put("assets", assets);

            activity.put("application_id", "567994086452363286");
            ArrayMap<String, Object> button = new ArrayMap<>();
            button.put("label", "Open GitHub");
            button.put("url", "https://github.com");
            //activity.put("buttons", new Object[]{button});

            ArrayMap<String, Object> timestamps = new ArrayMap<>();
            timestamps.put("start", current);

            activity.put("timestamps", timestamps);
            presence.put("activities", new Object[]{activity});
        }

        presence.put("afk", true);
        presence.put("since", current);
        presence.put("status", currentStatus);

        ArrayMap<String, Object> arr = new ArrayMap<>();
        arr.put("op", 3);
        arr.put("d", presence);

        service.appendToLog(MainActivity.GSON.toJson(arr));
        send(MainActivity.GSON.toJson(arr));
    }
}
