/* gaeproxy - GAppProxy / WallProxy client App for Android
 * Copyright (C) 2011 <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * 
 *                            ___====-_  _-====___
 *                      _--^^^#####//      \\#####^^^--_
 *                   _-^##########// (    ) \\##########^-_
 *                  -############//  |\^^/|  \\############-
 *                _/############//   (@::@)   \\############\_
 *               /#############((     \\//     ))#############\
 *              -###############\\    (oo)    //###############-
 *             -#################\\  / VV \  //#################-
 *            -###################\\/      \//###################-
 *           _#/|##########/\######(   /\   )######/\##########|\#_
 *           |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
 *           `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
 *              `   `  `      `   / | |  | | \   '      '  '   '
 *                               (  | |  | |  )
 *                              __\ | |  | | /__
 *                             (vvv(VVV)(VVV)vvv)
 *
 *                              HERE BE DRAGONS
 *
 */

package org.gaeproxy;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.RemoteViews;
//import com.google.analytics.tracking.android.EasyTracker;
import org.apache.commons.codec.binary.Base64;
import org.xbill.DNS.*;

import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashSet;
import java.util.Random;
import java.lang.Process;

public class GAEProxyService extends Service {

    private Notification notification;
    private NotificationManager notificationManager;
    private Intent intent;
    private PendingIntent pendIntent;
    private PowerManager.WakeLock mWakeLock;

    public static final String BASE = "/data/data/org.gaeproxy/";
    private static final int MSG_CONNECT_START = 0;
    private static final int MSG_CONNECT_FINISH = 1;
    private static final int MSG_CONNECT_SUCCESS = 2;
    private static final int MSG_CONNECT_FAIL = 3;
    private static final int MSG_HOST_CHANGE = 4;
    private static final int MSG_STOP_SELF = 5;

    final static String CMD_IPTABLES_RETURN = " -t nat -A OUTPUT -p tcp -d 0.0.0.0 -j RETURN\n";

    final static String CMD_IPTABLES_REDIRECT_ADD_HTTP = " -t nat -A OUTPUT -p tcp "
            + "--dport 80 -j REDIRECT --to 8123\n";
    final static String CMD_IPTABLES_REDIRECT_ADD_HTTPS = " -t nat -A OUTPUT -p tcp "
            + "--dport 443 -j REDIRECT --to 8124\n";

    final static String CMD_IPTABLES_DNAT_ADD_HTTP = " -t nat -A OUTPUT -p tcp "
            + "--dport 80 -j DNAT --to-destination 127.0.0.1:8123\n";
    final static String CMD_IPTABLES_DNAT_ADD_HTTPS = " -t nat -A OUTPUT -p tcp "
            + "--dport 443 -j DNAT --to-destination 127.0.0.1:8124\n";

    private static final String TAG = "GAEProxyService";
    private static final String DEFAULT_HOST = "74.125.128.18";

    public static volatile boolean statusLock = false;

    private Process httpProcess = null;
    private DataOutputStream httpOS = null;

    private String proxyType = "GAE";
    private String appId;
    private String appPath;
    private String appHost = DEFAULT_HOST;
    private String[] appMask;
    private int port;
    private String sitekey;
    private String dnsHost = null;
    private DNSServer dnsServer = null;
    private int dnsPort = 8153;

    private SharedPreferences settings = null;

    private boolean hasRedirectSupport = true;
    private boolean isGlobalProxy = false;
    private boolean isHTTPSProxy = false;
    private boolean isGFWList = false;
    private boolean isProxyDns = false;

    private ProxyedApp apps[];

    private static final Class<?>[] mStartForegroundSignature = new Class[]{
            int.class, Notification.class};
    private static final Class<?>[] mStopForegroundSignature = new Class[]{boolean.class};
    private static final Class<?>[] mSetForegroundSignature = new Class[]{boolean.class};

    private Method mSetForeground;
    private Method mStartForeground;
    private Method mStopForeground;

    private Object[] mSetForegroundArgs = new Object[1];
    private Object[] mStartForegroundArgs = new Object[2];
    private Object[] mStopForegroundArgs = new Object[1];

    /*
    * This is a hack see
    * http://www.mail-archive.com/android-developers@googlegroups
    * .com/msg18298.html we are not really able to decide if the service was
    * started. So we remember a week reference to it. We set it if we are
    * running and clear it if we are stopped. If anything goes wrong, the
    * reference will hopefully vanish
    */
    private static WeakReference<GAEProxyService> sRunningInstance = null;

    public final static boolean isServiceStarted() {
        final boolean isServiceStarted;
        if (sRunningInstance == null) {
            isServiceStarted = false;
        } else if (sRunningInstance.get() == null) {
            isServiceStarted = false;
            sRunningInstance = null;
        } else {
            isServiceStarted = true;
        }
        return isServiceStarted;
    }

    final Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Editor ed = settings.edit();
            switch (msg.what) {
                case MSG_CONNECT_START:
                    ed.putBoolean("isConnecting", true);
                    statusLock = true;

                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK
                            | PowerManager.ON_AFTER_RELEASE, "GAEProxy");

                    mWakeLock.acquire();

                    break;
                case MSG_CONNECT_FINISH:
                    ed.putBoolean("isConnecting", false);
                    statusLock = false;

                    if (mWakeLock != null && mWakeLock.isHeld())
                        mWakeLock.release();

                    break;
                case MSG_CONNECT_SUCCESS:
                    ed.putBoolean("isRunning", true);
                    break;
                case MSG_CONNECT_FAIL:
                    ed.putBoolean("isRunning", false);
                    break;
                case MSG_HOST_CHANGE:
                    ed.putString("appHost", appHost);
                    break;
                case MSG_STOP_SELF:
                    stopSelf();
                    break;
            }
            ed.commit();
            super.handleMessage(msg);
        }
    };

    private boolean parseProxyURL(String url) {
        if (proxyType.equals("PaaS")) {
            Uri uri = Uri.parse(url);
            if (uri == null) {
                return false;
            }
            appId = uri.getHost();
            appPath = url + (url.endsWith("/") ? "" : "/");
            return true;
        }
        if (url == null)
            return false;
        String[] proxyString = url.split("\\/");
        if (proxyString.length < 4)
            return false;
        appPath = proxyString[3];
        String[] ids = proxyString[2].split("\\.");
        if (ids.length < 3)
            return false;
        appId = ids[0];
        return true;
    }

    public boolean connect() {

        try {

            StringBuffer sb = new StringBuffer();

            sb.append(BASE + "localproxy.sh");
            sb.append(" \"" + Utils.getDataPath(this) + "\"");
            sb.append(" \"" + proxyType + "\"");
            sb.append(" \"" + appId + "\"");
            sb.append(" \"" + port + "\"");
            sb.append(" \"" + appHost + "\"");
            sb.append(" \"" + appPath + "\"");
            sb.append(" \"" + sitekey + "\"");

            final String cmd = sb.toString();

            Log.e(TAG, cmd);

            if (Utils.isRoot()) {
                Utils.runRootCommand(cmd);
            } else {
                Utils.runCommand(cmd);
            }

        } catch (Exception e) {
            Log.e(TAG, "Cannot connect");
            return false;
        }

        return true;
    }

    private String getVersionName() {
        String version;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(
                    getPackageName(), 0);
            version = pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "Package name not found";
        }
        return version;
    }

    public void handleCommand(Intent intent) {

        Log.d(TAG, "Service Start");

        if (intent == null) {
            stopSelf();
            return;
        }

        Bundle bundle = intent.getExtras();

        if (bundle == null) {
            stopSelf();
            return;
        }

        port = bundle.getInt("port");
        sitekey = bundle.getString("sitekey");
        proxyType = bundle.getString("proxyType");

        if (!"GAE".equals(proxyType) && !"PaaS".equals(proxyType)) {
            proxyType = "GAE";
        }

        isGlobalProxy = bundle.getBoolean("isGlobalProxy");
        isHTTPSProxy = bundle.getBoolean("isHTTPSProxy");
        isGFWList = bundle.getBoolean("isGFWList");
        isProxyDns = bundle.getBoolean("isProxyDns");

        if (!parseProxyURL(bundle.getString("proxy"))) {
            stopSelf();
            return;
        }

        if ("fetch.py".equals(appPath)) appPath = "2";

        Log.e(TAG, "Proxy: " + appId + " " + appPath);
        Log.e(TAG, "Local Port: " + port);

        // APNManager.setAPNProxy("127.0.0.1", Integer.toString(port), this);

        new Thread(new Runnable() {
            @Override
            public void run() {

                handler.sendEmptyMessage(MSG_CONNECT_START);

                Log.d(TAG, "IPTABLES: " + Utils.getIptables());

                // Test for Redirect Support
                hasRedirectSupport = Utils.getHasRedirectSupport();

                if (handleConnection()) {
                    // Connection and forward successful
                    notifyAlert(getString(R.string.forward_success),
                            getString(R.string.service_running));

                    handler.sendEmptyMessageDelayed(MSG_CONNECT_SUCCESS, 500);

                    // for widget, maybe exception here
                    try {
                        RemoteViews views = new RemoteViews(getPackageName(),
                                R.layout.gaeproxy_appwidget);
                        views.setImageViewResource(R.id.serviceToggle,
                                R.drawable.on);
                        AppWidgetManager awm = AppWidgetManager
                                .getInstance(GAEProxyService.this);
                        awm.updateAppWidget(awm
                                .getAppWidgetIds(new ComponentName(
                                        GAEProxyService.this,
                                        GAEProxyWidgetProvider.class)), views);
                    } catch (Exception ignore) {
                        // Nothing
                    }

                } else {
                    // Connection or forward unsuccessful
                    notifyAlert(getString(R.string.forward_fail),
                            getString(R.string.service_failed));

                    stopSelf();
                    handler.sendEmptyMessageDelayed(MSG_CONNECT_FAIL, 500);
                }

                handler.sendEmptyMessageDelayed(MSG_CONNECT_FINISH, 500);

            }
        }).start();
        markServiceStarted();
    }

    private String parseHost(String host) {
        String address = null;
        try {
            Lookup lookup = new Lookup(host, Type.A);
            Resolver resolver = new SimpleResolver("8.8.4.4");
            resolver.setTCP(true);
            resolver.setTimeout(10);
            lookup.setResolver(resolver);
            Record[] records = lookup.run();
            if (records.length > 0) {
                boolean first = true;
                StringBuffer sb = new StringBuffer();
                for (Record record : records) {
                    ARecord r = (ARecord) record;
                    InetAddress addr = r.getAddress();
                    if (!first) {
                        sb.append("|");
                    } else {
                        first = false;
                    }
                    sb.append(addr.getHostAddress());
                }
                address = sb.toString();
            } else {
                address = null;
            }
        } catch (Exception ignore) {
            address = null;
        }

        if (address == null) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                address = addr.getHostAddress();
            } catch (Exception ignore) {
                address = null;
            }
        }

        return address;
    }

    /**
     * Called when the activity is first created.
     */
    public boolean handleConnection() {

        if (proxyType.equals("GAE")) {
            appHost = parseHost("www.google.com");
            if (appHost == null || appHost.equals("")) {
                appHost = DEFAULT_HOST;
            }
            dnsHost = appHost;
        } else if (proxyType.equals("PaaS")) {
            appHost = parseHost(appId);
            if (appHost == null || appHost.equals("")) {
                return false;
            }
            dnsHost = parseHost("www.google.com");
            if (dnsHost == null || dnsHost.equals("")) {
                dnsHost = DEFAULT_HOST;
            }
        }

        try {
            dnsHost = dnsHost.split("\\|")[0];
            appMask = appHost.split("\\|");
        } catch (Exception ex) {
            return false;
        }

        // DNS Proxy Setup
        // with AsyncHttpClient
        if (isProxyDns) {
            dnsServer = new DNSServer(this, appMask[0]);
            dnsPort = dnsServer.getServPort();
        }


        // Random mirror for load balance
        // only affect when appid equals proxyofmax
        if (appId.equals("proxyofmax")) {
            String mirror_string = new String(
                    Base64.decodeBase64(getString(R.string.mirror_list).getBytes()));
            appId = mirror_string;
            appPath = getString(R.string.mirror_path);
            sitekey = getString(R.string.mirror_sitekey);
        }

        if (!preConnection())
            return false;

        if (isProxyDns) {
            Thread dnsThread = new Thread(dnsServer);
            dnsThread.setDaemon(true);
            dnsThread.start();
        }


        connect();

        return true;
    }

    private void initSoundVibrateLights(Notification notification) {
        final String ringtone = settings.getString(
                "settings_key_notif_ringtone", null);
        AudioManager audioManager = (AudioManager) this
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0) {
            notification.sound = null;
        } else if (ringtone != null)
            notification.sound = Uri.parse(ringtone);
        else
            notification.defaults |= Notification.DEFAULT_SOUND;

        if (settings.getBoolean("settings_key_notif_vibrate", false)) {
            long[] vibrate = {0, 500};
            notification.vibrate = vibrate;
        }

        notification.defaults |= Notification.DEFAULT_LIGHTS;
    }

    void invokeMethod(Method method, Object[] args) {
        try {
            method.invoke(this, mStartForegroundArgs);
        } catch (InvocationTargetException e) {
            // Should not happen.
            Log.w(TAG, "Unable to invoke method", e);
        } catch (IllegalAccessException e) {
            // Should not happen.
            Log.w(TAG, "Unable to invoke method", e);
        }
    }

    private void markServiceStarted() {
        sRunningInstance = new WeakReference<GAEProxyService>(this);
    }

    private void markServiceStopped() {
        sRunningInstance = null;
    }

    private void notifyAlert(String title, String info) {
        notification.icon = R.drawable.ic_stat_gaeproxy;
        notification.tickerText = title;
        notification.flags = Notification.FLAG_ONGOING_EVENT;
        initSoundVibrateLights(notification);
        // notification.defaults = Notification.DEFAULT_SOUND;
        notification.setLatestEventInfo(this, getString(R.string.app_name),
                info, pendIntent);
        startForegroundCompat(1, notification);
    }

    private void notifyAlert(String title, String info, int flags) {
        notification.icon = R.drawable.ic_stat_gaeproxy;
        notification.tickerText = title;
        notification.flags = flags;
        initSoundVibrateLights(notification);
        notification.setLatestEventInfo(this, getString(R.string.app_name),
                info, pendIntent);
        notificationManager.notify(0, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

//    EasyTracker.getTracker().trackEvent("service", "start",
//        getVersionName(), 0L);

        settings = PreferenceManager.getDefaultSharedPreferences(this);
        notificationManager = (NotificationManager) this
                .getSystemService(NOTIFICATION_SERVICE);

        intent = new Intent(this, GAEProxy.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        pendIntent = PendingIntent.getActivity(this, 0, intent, 0);
        notification = new Notification();

        try {
            mStartForeground = getClass().getMethod("startForeground",
                    mStartForegroundSignature);
            mStopForeground = getClass().getMethod("stopForeground",
                    mStopForegroundSignature);
        } catch (NoSuchMethodException e) {
            // Running on an older platform.
            mStartForeground = mStopForeground = null;
        }

        try {
            mSetForeground = getClass().getMethod("setForeground",
                    mSetForegroundSignature);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "OS doesn't have Service.startForeground OR Service.setForeground!");
        }
    }

    /**
     * Called when the activity is closed.
     */
    @Override
    public void onDestroy() {

//    EasyTracker.getTracker().trackEvent("service", "stop",
//        getVersionName(), 0L);

        statusLock = true;

        stopForegroundCompat(1);

        notifyAlert(getString(R.string.forward_stop),
                getString(R.string.service_stopped),
                Notification.FLAG_AUTO_CANCEL);

        try {
            if (httpOS != null) {
                httpOS.close();
                httpOS = null;
            }
            if (httpProcess != null) {
                httpProcess.destroy();
                httpProcess = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "HTTP Server close unexpected");
        }

        if (isProxyDns) {
            try {
                if (dnsServer != null)
                    dnsServer.close();
            } catch (Exception e) {
                Log.e(TAG, "DNS Server close unexpected");
            }
        }


        new Thread() {
            @Override
            public void run() {

                // Make sure the connection is closed, important here
                onDisconnect();

            }
        }.start();

        // for widget, maybe exception here
        try {
            RemoteViews views = new RemoteViews(getPackageName(),
                    R.layout.gaeproxy_appwidget);
            views.setImageViewResource(R.id.serviceToggle, R.drawable.off);
            AppWidgetManager awm = AppWidgetManager.getInstance(this);
            awm.updateAppWidget(awm.getAppWidgetIds(new ComponentName(this,
                    GAEProxyWidgetProvider.class)), views);
        } catch (Exception ignore) {
            // Nothing
        }

        Editor ed = settings.edit();
        ed.putBoolean("isRunning", false);
        ed.putBoolean("isConnecting", false);
        ed.commit();

        try {
            notificationManager.cancel(0);
        } catch (Exception ignore) {
            // Nothing
        }

        try {
            ProxySettings.resetProxy(this);
        } catch (Exception ignore) {
            // Nothing
        }

        // APNManager.clearAPNProxy("127.0.0.1", Integer.toString(port), this);

        super.onDestroy();

        statusLock = false;

        markServiceStopped();
    }

    private void onDisconnect() {
        Utils.runRootCommand(Utils.getIptables() + " -t nat -F OUTPUT");
        if (Utils.isRoot())
            Utils.runRootCommand(BASE + "proxy.sh stop");
        else
            Utils.runCommand(BASE + "proxy.sh stop");
    }

    // This is the old onStart method that will be called on the pre-2.0
    // platform. On 2.0 or later we override onStartCommand() so this
    // method will not be called.
    @Override
    public void onStart(Intent intent, int startId) {

        handleCommand(intent);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleCommand(intent);
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    private boolean preConnection() {

//        if (isHTTPSProxy) {
//            InputStream is = null;
//
//            String socksIp = settings.getString("socksIp", null);
//            String socksPort = settings.getString("socksPort", null);
//
//            String sig = Utils.getSignature(this);
//
//            if (sig == null)
//                return false;
//
//            for (int tries = 0; tries < 2; tries++) {
//                try {
//                    URL aURL = new URL(
//                            "http://myhosts.sinaapp.com/port4.php?sig=" + sig);
//                    HttpURLConnection conn = (HttpURLConnection) aURL
//                            .openConnection();
//                    conn.setConnectTimeout(4000);
//                    conn.setReadTimeout(8000);
//                    conn.connect();
//                    is = conn.getInputStream();
//
//                    BufferedReader reader = new BufferedReader(
//                            new InputStreamReader(is));
//
//                    String line = reader.readLine();
//
//                    if (line.startsWith("ERROR"))
//                        return false;
//
//                    if (!line.startsWith("#ip"))
//                        throw new Exception("Format error");
//                    line = reader.readLine();
//                    socksIp = line.trim().toLowerCase();
//
//                    line = reader.readLine();
//                    if (!line.startsWith("#port"))
//                        throw new Exception("Format error");
//                    line = reader.readLine();
//                    socksPort = line.trim().toLowerCase();
//
//                    Editor ed = settings.edit();
//                    ed.putString("socksIp", socksIp);
//                    ed.putString("socksPort", socksPort);
//                    ed.commit();
//
//                } catch (Exception e) {
//                    Log.e(TAG, "cannot get remote port info", e);
//                    continue;
//                }
//                break;
//            }
//
//            if (socksIp == null || socksPort == null)
//                return false;
//
//            // Configure file for Stunnel
//            FileOutputStream fs;
//            try {
//                fs = new FileOutputStream(BASE + "stunnel.conf");
//                String conf = "debug = 0\n" + "client = yes\n" + "pid = "
//                        + BASE + "stunnel.pid\n" + "[https]\n"
//                        + "sslVersion = all\n" + "accept = 127.0.0.1:8126\n"
//                        + "connect = " + socksIp + ":" + socksPort + "\n";
//                fs.write(conf.getBytes());
//                fs.flush();
//                fs.close();
//            } catch (FileNotFoundException e) {
//            } catch (IOException e) {
//            }
//
//            // Start stunnel here
//            Utils.runRootCommand(BASE + "stunnel " + BASE + "stunnel.conf");
//
//            // Reset host / port
//            socksIp = "127.0.0.1";
//            socksPort = "8126";
//
//            Log.d(TAG, "Forward Successful");
//            if (Utils.isRoot())
//                Utils.runRootCommand(BASE + "proxy.sh start " + port + " "
//                        + socksIp + " " + socksPort);
//            else
//                Utils.runCommand(BASE + "proxy.sh start " + port + " "
//                        + socksIp + " " + socksPort);
//
//        } else {

            Log.d(TAG, "Forward Successful");
            if (Utils.isRoot())
                Utils.runRootCommand(BASE + "proxy.sh start " + port + " "
                        + "127.0.0.1" + " " + port);
            else
                Utils.runCommand(BASE + "proxy.sh start " + port + " "
                        + "127.0.0.1" + " " + port);
//        }

        StringBuffer init_sb = new StringBuffer();

        StringBuffer http_sb = new StringBuffer();

        StringBuffer https_sb = new StringBuffer();

        init_sb.append(Utils.getIptables() + " -t nat -F OUTPUT\n");

        Log.d(TAG, "gaeproxy.isProxyDns=" + isProxyDns);

        if (isProxyDns) {
            if (hasRedirectSupport) {
                init_sb.append(Utils.getIptables()
                        + " -t nat -A OUTPUT -p udp --dport 53 -j REDIRECT --to "
                        + dnsPort + "\n");
            } else {
                init_sb.append(Utils.getIptables()
                        + " -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:"
                        + dnsPort + "\n");
            }
        }


        String cmd_bypass = Utils.getIptables() + CMD_IPTABLES_RETURN;

        for (String mask : appMask) {
            init_sb.append(cmd_bypass.replace("0.0.0.0", mask));
        }
        if (Utils.isRoot()) {
            init_sb.append(cmd_bypass.replace("-d 0.0.0.0", "-p tcp -m owner --uid-owner "
                    + 0));
        } else {
            init_sb.append(cmd_bypass.replace("-d 0.0.0.0", "-p tcp -m owner --uid-owner "
                    + getApplicationInfo().uid));
        }
        init_sb.append(cmd_bypass.replace("0.0.0.0", dnsHost));

        if (isGFWList) {
            String[] chn_list = getResources().getStringArray(R.array.chn_list);

            for (String item : chn_list) {
                init_sb.append(cmd_bypass.replace("0.0.0.0", item));
            }
        }
        if (isGlobalProxy) {
            http_sb.append(hasRedirectSupport ? Utils.getIptables()
                    + CMD_IPTABLES_REDIRECT_ADD_HTTP : Utils.getIptables()
                    + CMD_IPTABLES_DNAT_ADD_HTTP);
            https_sb.append(hasRedirectSupport ? Utils.getIptables()
                    + CMD_IPTABLES_REDIRECT_ADD_HTTPS : Utils.getIptables()
                    + CMD_IPTABLES_DNAT_ADD_HTTPS);
        } else {
            // for proxy specified apps
            if (apps == null || apps.length <= 0)
                apps = AppManager.getProxyedApps(this);

            HashSet<Integer> uidSet = new HashSet<Integer>();
            for (int i = 0; i < apps.length; i++) {
                if (apps[i].isProxyed()) {
                    uidSet.add(apps[i].getUid());
                }
            }
            for (int uid : uidSet) {
                http_sb.append((hasRedirectSupport ? Utils.getIptables()
                        + CMD_IPTABLES_REDIRECT_ADD_HTTP : Utils.getIptables()
                        + CMD_IPTABLES_DNAT_ADD_HTTP).replace("-t nat",
                        "-t nat -m owner --uid-owner " + uid));
                https_sb.append((hasRedirectSupport ? Utils.getIptables()
                        + CMD_IPTABLES_REDIRECT_ADD_HTTPS : Utils.getIptables()
                        + CMD_IPTABLES_DNAT_ADD_HTTPS).replace("-t nat",
                        "-t nat -m owner --uid-owner " + uid));
            }
        }

        String init_rules = init_sb.toString();
        Utils.runRootCommand(init_rules, 30 * 1000);

        String redt_rules = http_sb.toString();

        redt_rules += https_sb.toString();

        Utils.runRootCommand(redt_rules);

        return true;

    }

    /**
     * This is a wrapper around the new startForeground method, using the older
     * APIs if it is not available.
     */
    void startForegroundCompat(int id, Notification notification) {
        // If we have the new startForeground API, then use it.
        if (mStartForeground != null) {
            mStartForegroundArgs[0] = Integer.valueOf(id);
            mStartForegroundArgs[1] = notification;
            invokeMethod(mStartForeground, mStartForegroundArgs);
            return;
        }

        // Fall back on the old API.
        mSetForegroundArgs[0] = Boolean.TRUE;
        invokeMethod(mSetForeground, mSetForegroundArgs);
        notificationManager.notify(id, notification);
    }

    /**
     * This is a wrapper around the new stopForeground method, using the older
     * APIs if it is not available.
     */
    void stopForegroundCompat(int id) {
        // If we have the new stopForeground API, then use it.
        if (mStopForeground != null) {
            mStopForegroundArgs[0] = Boolean.TRUE;
            try {
                mStopForeground.invoke(this, mStopForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w(TAG, "Unable to invoke stopForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w(TAG, "Unable to invoke stopForeground", e);
            }
            return;
        }

        // Fall back on the old API. Note to cancel BEFORE changing the
        // foreground state, since we could be killed at that point.
        notificationManager.cancel(id);
        mSetForegroundArgs[0] = Boolean.FALSE;
        invokeMethod(mSetForeground, mSetForegroundArgs);
    }

}
