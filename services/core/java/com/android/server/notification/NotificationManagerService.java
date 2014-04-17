/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.IRingtonePlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.INotificationListener;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.notification.NotificationScorer;
import com.android.server.EventLogTags;
import com.android.server.notification.NotificationUsageStats.SingleNotificationStats;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.SystemService;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import libcore.io.IoUtils;

/** {@hide} */
public class NotificationManagerService extends SystemService {
    static final String TAG = "NotificationService";
    static final boolean DBG = false;

    static final int MAX_PACKAGE_NOTIFICATIONS = 50;

    // message codes
    static final int MESSAGE_TIMEOUT = 2;

    static final int LONG_DELAY = 3500; // 3.5 seconds
    static final int SHORT_DELAY = 2000; // 2 seconds

    static final long[] DEFAULT_VIBRATE_PATTERN = {0, 250, 250, 250};
    static final int VIBRATE_PATTERN_MAXLEN = 8 * 2 + 1; // up to eight bumps

    static final int DEFAULT_STREAM_TYPE = AudioManager.STREAM_NOTIFICATION;
    static final boolean SCORE_ONGOING_HIGHER = false;

    static final int JUNK_SCORE = -1000;
    static final int NOTIFICATION_PRIORITY_MULTIPLIER = 10;
    static final int SCORE_DISPLAY_THRESHOLD = Notification.PRIORITY_MIN * NOTIFICATION_PRIORITY_MULTIPLIER;

    // Notifications with scores below this will not interrupt the user, either via LED or
    // sound or vibration
    static final int SCORE_INTERRUPTION_THRESHOLD =
            Notification.PRIORITY_LOW * NOTIFICATION_PRIORITY_MULTIPLIER;

    static final boolean ENABLE_BLOCKED_NOTIFICATIONS = true;
    static final boolean ENABLE_BLOCKED_TOASTS = true;

    static final String ENABLED_NOTIFICATION_LISTENERS_SEPARATOR = ":";

    private IActivityManager mAm;
    AudioManager mAudioManager;
    StatusBarManagerInternal mStatusBar;
    Vibrator mVibrator;

    final IBinder mForegroundToken = new Binder();
    private WorkerHandler mHandler;

    private Light mNotificationLight;
    Light mAttentionLight;
    private int mDefaultNotificationColor;
    private int mDefaultNotificationLedOn;

    private int mDefaultNotificationLedOff;
    private long[] mDefaultVibrationPattern;

    private long[] mFallbackVibrationPattern;
    boolean mSystemReady;

    private boolean mDisableNotificationAlerts;
    NotificationRecord mSoundNotification;
    NotificationRecord mVibrateNotification;

    // for enabling and disabling notification pulse behavior
    private boolean mScreenOn = true;
    private boolean mInCall = false;
    private boolean mNotificationPulseEnabled;

    // used as a mutex for access to all active notifications & listeners
    final ArrayList<NotificationRecord> mNotificationList =
            new ArrayList<NotificationRecord>();
    final ArrayMap<String, NotificationRecord> mNotificationsByKey =
            new ArrayMap<String, NotificationRecord>();
    final ArrayList<ToastRecord> mToastQueue = new ArrayList<ToastRecord>();

    ArrayList<NotificationRecord> mLights = new ArrayList<NotificationRecord>();
    NotificationRecord mLedNotification;

    private AppOpsManager mAppOps;

    // contains connections to all connected listeners, including app services
    // and system listeners
    private ArrayList<NotificationListenerInfo> mListeners
            = new ArrayList<NotificationListenerInfo>();
    // things that will be put into mListeners as soon as they're ready
    private ArrayList<String> mServicesBinding = new ArrayList<String>();
    // lists the component names of all enabled (and therefore connected) listener
    // app services for current profiles.
    private HashSet<ComponentName> mEnabledListenersForCurrentProfiles
            = new HashSet<ComponentName>();
    // Just the packages from mEnabledListenersForCurrentProfiles
    private HashSet<String> mEnabledListenerPackageNames = new HashSet<String>();

    // Notification control database. For now just contains disabled packages.
    private AtomicFile mPolicyFile;
    private HashSet<String> mBlockedPackages = new HashSet<String>();

    private static final int DB_VERSION = 1;

    private static final String TAG_BODY = "notification-policy";
    private static final String ATTR_VERSION = "version";

    private static final String TAG_BLOCKED_PKGS = "blocked-packages";
    private static final String TAG_PACKAGE = "package";
    private static final String ATTR_NAME = "name";

    final ArrayList<NotificationScorer> mScorers = new ArrayList<NotificationScorer>();

    private final NotificationUsageStats mUsageStats = new NotificationUsageStats();

    private int mZenMode;
    // temporary, until we update apps to provide metadata
    private static final Set<String> CALL_PACKAGES = new HashSet<String>(Arrays.asList(
            "com.google.android.dialer",
            "com.android.phone"
            ));
    private static final Set<String> ALARM_PACKAGES = new HashSet<String>(Arrays.asList(
            "com.google.android.deskclock"
            ));
    private static final String EXTRA_INTERCEPT = "android.intercept";

    // Profiles of the current user.
    final protected SparseArray<UserInfo> mCurrentProfiles = new SparseArray<UserInfo>();

    private static final int MY_UID = Process.myUid();
    private static final int MY_PID = Process.myPid();
    private static final int REASON_DELEGATE_CLICK = 1;
    private static final int REASON_DELEGATE_CANCEL = 2;
    private static final int REASON_DELEGATE_CANCEL_ALL = 3;
    private static final int REASON_DELEGATE_ERROR = 4;
    private static final int REASON_PACKAGE_CHANGED = 5;
    private static final int REASON_USER_STOPPED = 6;
    private static final int REASON_PACKAGE_BANNED = 7;
    private static final int REASON_NOMAN_CANCEL = 8;
    private static final int REASON_NOMAN_CANCEL_ALL = 9;
    private static final int REASON_LISTENER_CANCEL = 10;
    private static final int REASON_LISTENER_CANCEL_ALL = 11;

    private class NotificationListenerInfo implements IBinder.DeathRecipient {
        INotificationListener listener;
        ComponentName component;
        int userid;
        boolean isSystem;
        ServiceConnection connection;
        int targetSdkVersion;

        public NotificationListenerInfo(INotificationListener listener, ComponentName component,
                int userid, boolean isSystem, int targetSdkVersion) {
            this.listener = listener;
            this.component = component;
            this.userid = userid;
            this.isSystem = isSystem;
            this.connection = null;
            this.targetSdkVersion = targetSdkVersion;
        }

        public NotificationListenerInfo(INotificationListener listener, ComponentName component,
                int userid, ServiceConnection connection, int targetSdkVersion) {
            this.listener = listener;
            this.component = component;
            this.userid = userid;
            this.isSystem = false;
            this.connection = connection;
            this.targetSdkVersion = targetSdkVersion;
        }

        boolean enabledAndUserMatches(StatusBarNotification sbn) {
            final int nid = sbn.getUserId();
            if (!isEnabledForCurrentProfiles()) {
                return false;
            }
            if (this.userid == UserHandle.USER_ALL) return true;
            if (nid == UserHandle.USER_ALL || nid == this.userid) return true;
            return supportsProfiles() && isCurrentProfile(nid);
        }

        boolean supportsProfiles() {
            return targetSdkVersion >= Build.VERSION_CODES.L;
        }

        public void notifyPostedIfUserMatch(StatusBarNotification sbn) {
            if (!enabledAndUserMatches(sbn)) {
                return;
            }
            try {
                listener.onNotificationPosted(sbn);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (posted): " + listener, ex);
            }
        }

        public void notifyRemovedIfUserMatch(StatusBarNotification sbn) {
            if (!enabledAndUserMatches(sbn)) return;
            try {
                listener.onNotificationRemoved(sbn);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (removed): " + listener, ex);
            }
        }

        @Override
        public void binderDied() {
            // Remove the listener, but don't unbind from the service. The system will bring the
            // service back up, and the onServiceConnected handler will readd the listener with the
            // new binding. If this isn't a bound service, and is just a registered
            // INotificationListener, just removing it from the list is all we need to do anyway.
            removeListenerImpl(this.listener, this.userid);
        }

        /** convenience method for looking in mEnabledListenersForCurrentProfiles */
        public boolean isEnabledForCurrentProfiles() {
            if (this.isSystem) return true;
            if (this.connection == null) return false;
            return mEnabledListenersForCurrentProfiles.contains(this.component);
        }
    }

    private static class Archive {
        static final int BUFFER_SIZE = 250;
        ArrayDeque<StatusBarNotification> mBuffer = new ArrayDeque<StatusBarNotification>(BUFFER_SIZE);

        public Archive() {
        }

        public String toString() {
            final StringBuilder sb = new StringBuilder();
            final int N = mBuffer.size();
            sb.append("Archive (");
            sb.append(N);
            sb.append(" notification");
            sb.append((N==1)?")":"s)");
            return sb.toString();
        }

        public void record(StatusBarNotification nr) {
            if (mBuffer.size() == BUFFER_SIZE) {
                mBuffer.removeFirst();
            }

            // We don't want to store the heavy bits of the notification in the archive,
            // but other clients in the system process might be using the object, so we
            // store a (lightened) copy.
            mBuffer.addLast(nr.cloneLight());
        }


        public void clear() {
            mBuffer.clear();
        }

        public Iterator<StatusBarNotification> descendingIterator() {
            return mBuffer.descendingIterator();
        }
        public Iterator<StatusBarNotification> ascendingIterator() {
            return mBuffer.iterator();
        }
        public Iterator<StatusBarNotification> filter(
                final Iterator<StatusBarNotification> iter, final String pkg, final int userId) {
            return new Iterator<StatusBarNotification>() {
                StatusBarNotification mNext = findNext();

                private StatusBarNotification findNext() {
                    while (iter.hasNext()) {
                        StatusBarNotification nr = iter.next();
                        if ((pkg == null || nr.getPackageName() == pkg)
                                && (userId == UserHandle.USER_ALL || nr.getUserId() == userId)) {
                            return nr;
                        }
                    }
                    return null;
                }

                @Override
                public boolean hasNext() {
                    return mNext == null;
                }

                @Override
                public StatusBarNotification next() {
                    StatusBarNotification next = mNext;
                    if (next == null) {
                        throw new NoSuchElementException();
                    }
                    mNext = findNext();
                    return next;
                }

                @Override
                public void remove() {
                    iter.remove();
                }
            };
        }

        public StatusBarNotification[] getArray(int count) {
            if (count == 0) count = Archive.BUFFER_SIZE;
            final StatusBarNotification[] a
                    = new StatusBarNotification[Math.min(count, mBuffer.size())];
            Iterator<StatusBarNotification> iter = descendingIterator();
            int i=0;
            while (iter.hasNext() && i < count) {
                a[i++] = iter.next();
            }
            return a;
        }

        public StatusBarNotification[] getArray(int count, String pkg, int userId) {
            if (count == 0) count = Archive.BUFFER_SIZE;
            final StatusBarNotification[] a
                    = new StatusBarNotification[Math.min(count, mBuffer.size())];
            Iterator<StatusBarNotification> iter = filter(descendingIterator(), pkg, userId);
            int i=0;
            while (iter.hasNext() && i < count) {
                a[i++] = iter.next();
            }
            return a;
        }

    }

    Archive mArchive = new Archive();

    private void loadBlockDb() {
        synchronized(mBlockedPackages) {
            if (mPolicyFile == null) {
                File dir = new File("/data/system");
                mPolicyFile = new AtomicFile(new File(dir, "notification_policy.xml"));

                mBlockedPackages.clear();

                FileInputStream infile = null;
                try {
                    infile = mPolicyFile.openRead();
                    final XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(infile, null);

                    int type;
                    String tag;
                    int version = DB_VERSION;
                    while ((type = parser.next()) != END_DOCUMENT) {
                        tag = parser.getName();
                        if (type == START_TAG) {
                            if (TAG_BODY.equals(tag)) {
                                version = Integer.parseInt(
                                        parser.getAttributeValue(null, ATTR_VERSION));
                            } else if (TAG_BLOCKED_PKGS.equals(tag)) {
                                while ((type = parser.next()) != END_DOCUMENT) {
                                    tag = parser.getName();
                                    if (TAG_PACKAGE.equals(tag)) {
                                        mBlockedPackages.add(
                                                parser.getAttributeValue(null, ATTR_NAME));
                                    } else if (TAG_BLOCKED_PKGS.equals(tag) && type == END_TAG) {
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (FileNotFoundException e) {
                    // No data yet
                } catch (IOException e) {
                    Log.wtf(TAG, "Unable to read blocked notifications database", e);
                } catch (NumberFormatException e) {
                    Log.wtf(TAG, "Unable to parse blocked notifications database", e);
                } catch (XmlPullParserException e) {
                    Log.wtf(TAG, "Unable to parse blocked notifications database", e);
                } finally {
                    IoUtils.closeQuietly(infile);
                }
            }
        }
    }

    /** Use this when you actually want to post a notification or toast.
     *
     * Unchecked. Not exposed via Binder, but can be called in the course of enqueue*().
     */
    private boolean noteNotificationOp(String pkg, int uid) {
        if (mAppOps.noteOpNoThrow(AppOpsManager.OP_POST_NOTIFICATION, uid, pkg)
                != AppOpsManager.MODE_ALLOWED) {
            Slog.v(TAG, "notifications are disabled by AppOps for " + pkg);
            return false;
        }
        return true;
    }

    private static String idDebugString(Context baseContext, String packageName, int id) {
        Context c = null;

        if (packageName != null) {
            try {
                c = baseContext.createPackageContext(packageName, 0);
            } catch (NameNotFoundException e) {
                c = baseContext;
            }
        } else {
            c = baseContext;
        }

        String pkg;
        String type;
        String name;

        Resources r = c.getResources();
        try {
            return r.getResourceName(id);
        } catch (Resources.NotFoundException e) {
            return "<name unknown>";
        }
    }


    /**
     * Remove notification access for any services that no longer exist.
     */
    void disableNonexistentListeners() {
        int[] userIds = getCurrentProfileIds();
        final int N = userIds.length;
        for (int i = 0 ; i < N; ++i) {
            disableNonexistentListeners(userIds[i]);
        }
    }

    void disableNonexistentListeners(int userId) {
        String flatIn = Settings.Secure.getStringForUser(
                getContext().getContentResolver(),
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                userId);
        if (!TextUtils.isEmpty(flatIn)) {
            if (DBG) Slog.v(TAG, "flat before: " + flatIn);
            PackageManager pm = getContext().getPackageManager();
            List<ResolveInfo> installedServices = pm.queryIntentServicesAsUser(
                    new Intent(NotificationListenerService.SERVICE_INTERFACE),
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                    userId);

            Set<ComponentName> installed = new HashSet<ComponentName>();
            for (int i = 0, count = installedServices.size(); i < count; i++) {
                ResolveInfo resolveInfo = installedServices.get(i);
                ServiceInfo info = resolveInfo.serviceInfo;

                if (!android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE.equals(
                                info.permission)) {
                    Slog.w(TAG, "Skipping notification listener service "
                            + info.packageName + "/" + info.name
                            + ": it does not require the permission "
                            + android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE);
                    continue;
                }
                installed.add(new ComponentName(info.packageName, info.name));
            }

            String flatOut = "";
            if (!installed.isEmpty()) {
                String[] enabled = flatIn.split(ENABLED_NOTIFICATION_LISTENERS_SEPARATOR);
                ArrayList<String> remaining = new ArrayList<String>(enabled.length);
                for (int i = 0; i < enabled.length; i++) {
                    ComponentName enabledComponent = ComponentName.unflattenFromString(enabled[i]);
                    if (installed.contains(enabledComponent)) {
                        remaining.add(enabled[i]);
                    }
                }
                flatOut = TextUtils.join(ENABLED_NOTIFICATION_LISTENERS_SEPARATOR, remaining);
            }
            if (DBG) Slog.v(TAG, "flat after: " + flatOut);
            if (!flatIn.equals(flatOut)) {
                Settings.Secure.putStringForUser(getContext().getContentResolver(),
                        Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                        flatOut, userId);
            }
        }
    }

    /**
     * Called whenever packages change, the user switches, or ENABLED_NOTIFICATION_LISTENERS
     * is altered. (For example in response to USER_SWITCHED in our broadcast receiver)
     */
    void rebindListenerServices() {
        final int[] userIds = getCurrentProfileIds();
        final int nUserIds = userIds.length;

        final SparseArray<String> flat = new SparseArray<String>();

        for (int i = 0; i < nUserIds; ++i) {
            flat.put(userIds[i], Settings.Secure.getStringForUser(
                    getContext().getContentResolver(),
                    Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                    userIds[i]));
        }

        NotificationListenerInfo[] toRemove = new NotificationListenerInfo[mListeners.size()];
        final SparseArray<ArrayList<ComponentName>> toAdd
                = new SparseArray<ArrayList<ComponentName>>();

        synchronized (mNotificationList) {
            // unbind and remove all existing listeners
            toRemove = mListeners.toArray(toRemove);

            final HashSet<ComponentName> newEnabled = new HashSet<ComponentName>();
            final HashSet<String> newPackages = new HashSet<String>();

            for (int i = 0; i < nUserIds; ++i) {
                final ArrayList<ComponentName> add = new ArrayList<ComponentName>();
                toAdd.put(userIds[i], add);

                // decode the list of components
                String toDecode = flat.get(userIds[i]);
                if (toDecode != null) {
                    String[] components = toDecode.split(ENABLED_NOTIFICATION_LISTENERS_SEPARATOR);
                    for (int j = 0; j < components.length; j++) {
                        final ComponentName component
                                = ComponentName.unflattenFromString(components[j]);
                        if (component != null) {
                            newEnabled.add(component);
                            add.add(component);
                            newPackages.add(component.getPackageName());
                        }
                    }

                }
            }
            mEnabledListenersForCurrentProfiles = newEnabled;
            mEnabledListenerPackageNames = newPackages;
        }

        for (NotificationListenerInfo info : toRemove) {
            final ComponentName component = info.component;
            final int oldUser = info.userid;
            Slog.v(TAG, "disabling notification listener for user "
                    + oldUser + ": " + component);
            unregisterListenerService(component, info.userid);
        }

        for (int i = 0; i < nUserIds; ++i) {
            final ArrayList<ComponentName> add = toAdd.get(userIds[i]);
            final int N = add.size();
            for (int j = 0; j < N; j++) {
                final ComponentName component = add.get(j);
                Slog.v(TAG, "enabling notification listener for user " + userIds[i] + ": "
                        + component);
                registerListenerService(component, userIds[i]);
            }
        }
    }


    /**
     * Version of registerListener that takes the name of a
     * {@link android.service.notification.NotificationListenerService} to bind to.
     *
     * This is the mechanism by which third parties may subscribe to notifications.
     */
    private void registerListenerService(final ComponentName name, final int userid) {
        checkCallerIsSystem();

        if (DBG) Slog.v(TAG, "registerListenerService: " + name + " u=" + userid);

        synchronized (mNotificationList) {
            final String servicesBindingTag = name.toString() + "/" + userid;
            if (mServicesBinding.contains(servicesBindingTag)) {
                // stop registering this thing already! we're working on it
                return;
            }
            mServicesBinding.add(servicesBindingTag);

            final int N = mListeners.size();
            for (int i=N-1; i>=0; i--) {
                final NotificationListenerInfo info = mListeners.get(i);
                if (name.equals(info.component)
                        && info.userid == userid) {
                    // cut old connections
                    if (DBG) Slog.v(TAG, "    disconnecting old listener: " + info.listener);
                    mListeners.remove(i);
                    if (info.connection != null) {
                        getContext().unbindService(info.connection);
                    }
                }
            }

            Intent intent = new Intent(NotificationListenerService.SERVICE_INTERFACE);
            intent.setComponent(name);

            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    R.string.notification_listener_binding_label);

            final PendingIntent pendingIntent = PendingIntent.getActivity(
                    getContext(), 0, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), 0);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, pendingIntent);

            ApplicationInfo appInfo = null;
            try {
                appInfo = getContext().getPackageManager().getApplicationInfo(
                        name.getPackageName(), 0);
            } catch (NameNotFoundException e) {
                // Ignore if the package doesn't exist we won't be able to bind to the service.
            }
            final int targetSdkVersion =
                    appInfo != null ? appInfo.targetSdkVersion : Build.VERSION_CODES.BASE;

            try {
                if (DBG) Slog.v(TAG, "binding: " + intent);
                if (!getContext().bindServiceAsUser(intent,
                        new ServiceConnection() {
                            INotificationListener mListener;

                            @Override
                            public void onServiceConnected(ComponentName name, IBinder service) {
                                boolean added = false;
                                synchronized (mNotificationList) {
                                    mServicesBinding.remove(servicesBindingTag);
                                    try {
                                        mListener = INotificationListener.Stub.asInterface(service);
                                        NotificationListenerInfo info
                                                = new NotificationListenerInfo(
                                                        mListener, name, userid, this,
                                                        targetSdkVersion);
                                        service.linkToDeath(info, 0);
                                        added = mListeners.add(info);
                                    } catch (RemoteException e) {
                                        // already dead
                                    }
                                }
                                if (added) {
                                    final String[] keys =
                                            getActiveNotificationKeysFromListener(mListener);
                                    try {
                                        mListener.onListenerConnected(keys);
                                    } catch (RemoteException e) {
                                        // we tried
                                    }
                                }
                            }

                            @Override
                            public void onServiceDisconnected(ComponentName name) {
                                Slog.v(TAG, "notification listener connection lost: " + name);
                            }
                        },
                        Context.BIND_AUTO_CREATE,
                        new UserHandle(userid)))
                {
                    mServicesBinding.remove(servicesBindingTag);
                    Slog.w(TAG, "Unable to bind listener service: " + intent);
                    return;
                }
            } catch (SecurityException ex) {
                Slog.e(TAG, "Unable to bind listener service: " + intent, ex);
                return;
            }
        }
    }


    /**
     * Remove a listener service for the given user by ComponentName
     */
    private void unregisterListenerService(ComponentName name, int userid) {
        checkCallerIsSystem();

        synchronized (mNotificationList) {
            final int N = mListeners.size();
            for (int i=N-1; i>=0; i--) {
                final NotificationListenerInfo info = mListeners.get(i);
                if (name.equals(info.component)
                        && info.userid == userid) {
                    mListeners.remove(i);
                    if (info.connection != null) {
                        try {
                            getContext().unbindService(info.connection);
                        } catch (IllegalArgumentException ex) {
                            // something happened to the service: we think we have a connection
                            // but it's bogus.
                            Slog.e(TAG, "Listener " + name + " could not be unbound: " + ex);
                        }
                    }
                }
            }
        }
    }

    /**
     * asynchronously notify all listeners about a new notification
     */
    void notifyPostedLocked(NotificationRecord n) {
        // make a copy in case changes are made to the underlying Notification object
        final StatusBarNotification sbn = n.sbn.clone();
        for (final NotificationListenerInfo info : mListeners) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    info.notifyPostedIfUserMatch(sbn);
                }});
        }
    }

    /**
     * asynchronously notify all listeners about a removed notification
     */
    void notifyRemovedLocked(NotificationRecord n) {
        // make a copy in case changes are made to the underlying Notification object
        // NOTE: this copy is lightweight: it doesn't include heavyweight parts of the notification
        final StatusBarNotification sbn_light = n.sbn.cloneLight();

        for (final NotificationListenerInfo info : mListeners) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    info.notifyRemovedIfUserMatch(sbn_light);
                }});
        }
    }

    // -- APIs to support listeners clicking/clearing notifications --

    private void checkNullListener(INotificationListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
    }

    private NotificationListenerInfo checkListenerTokenLocked(INotificationListener listener) {
        checkNullListener(listener);
        final IBinder token = listener.asBinder();
        final int N = mListeners.size();
        for (int i=0; i<N; i++) {
            final NotificationListenerInfo info = mListeners.get(i);
            if (info.listener.asBinder() == token) return info;
        }
        throw new SecurityException("Disallowed call from unknown listener: " + listener);
    }



    // -- end of listener APIs --

    public static final class NotificationRecord
    {
        final StatusBarNotification sbn;
        final SingleNotificationStats stats = new SingleNotificationStats();
        IBinder statusBarKey;

        NotificationRecord(StatusBarNotification sbn)
        {
            this.sbn = sbn;
        }

        public Notification getNotification() { return sbn.getNotification(); }
        public int getFlags() { return sbn.getNotification().flags; }
        public int getUserId() { return sbn.getUserId(); }

        void dump(PrintWriter pw, String prefix, Context baseContext) {
            final Notification notification = sbn.getNotification();
            pw.println(prefix + this);
            pw.println(prefix + "  uid=" + sbn.getUid() + " userId=" + sbn.getUserId());
            pw.println(prefix + "  icon=0x" + Integer.toHexString(notification.icon)
                    + " / " + idDebugString(baseContext, sbn.getPackageName(), notification.icon));
            pw.println(prefix + "  pri=" + notification.priority + " score=" + sbn.getScore());
            pw.println(prefix + "  key=" + sbn.getKey());
            pw.println(prefix + "  contentIntent=" + notification.contentIntent);
            pw.println(prefix + "  deleteIntent=" + notification.deleteIntent);
            pw.println(prefix + "  tickerText=" + notification.tickerText);
            pw.println(prefix + "  contentView=" + notification.contentView);
            pw.println(prefix + String.format("  defaults=0x%08x flags=0x%08x",
                    notification.defaults, notification.flags));
            pw.println(prefix + "  sound=" + notification.sound);
            pw.println(prefix + "  vibrate=" + Arrays.toString(notification.vibrate));
            pw.println(prefix + String.format("  led=0x%08x onMs=%d offMs=%d",
                    notification.ledARGB, notification.ledOnMS, notification.ledOffMS));
            if (notification.actions != null && notification.actions.length > 0) {
                pw.println(prefix + "  actions={");
                final int N = notification.actions.length;
                for (int i=0; i<N; i++) {
                    final Notification.Action action = notification.actions[i];
                    pw.println(String.format("%s    [%d] \"%s\" -> %s",
                            prefix,
                            i,
                            action.title,
                            action.actionIntent.toString()
                            ));
                }
                pw.println(prefix + "  }");
            }
            if (notification.extras != null && notification.extras.size() > 0) {
                pw.println(prefix + "  extras={");
                for (String key : notification.extras.keySet()) {
                    pw.print(prefix + "    " + key + "=");
                    Object val = notification.extras.get(key);
                    if (val == null) {
                        pw.println("null");
                    } else {
                        pw.print(val.toString());
                        if (val instanceof Bitmap) {
                            pw.print(String.format(" (%dx%d)",
                                    ((Bitmap) val).getWidth(),
                                    ((Bitmap) val).getHeight()));
                        } else if (val.getClass().isArray()) {
                            pw.println(" {");
                            final int N = Array.getLength(val);
                            for (int i=0; i<N; i++) {
                                if (i > 0) pw.println(",");
                                pw.print(prefix + "      " + Array.get(val, i));
                            }
                            pw.print("\n" + prefix + "    }");
                        }
                        pw.println();
                    }
                }
                pw.println(prefix + "  }");
            }
            pw.println(prefix + "  stats=" + stats.toString());
        }

        @Override
        public final String toString() {
            return String.format(
                    "NotificationRecord(0x%08x: pkg=%s user=%s id=%d tag=%s score=%d key=%s: %s)",
                    System.identityHashCode(this),
                    this.sbn.getPackageName(), this.sbn.getUser(), this.sbn.getId(),
                    this.sbn.getTag(), this.sbn.getScore(), this.sbn.getKey(),
                    this.sbn.getNotification());
        }
    }

    private static final class ToastRecord
    {
        final int pid;
        final String pkg;
        final ITransientNotification callback;
        int duration;

        ToastRecord(int pid, String pkg, ITransientNotification callback, int duration)
        {
            this.pid = pid;
            this.pkg = pkg;
            this.callback = callback;
            this.duration = duration;
        }

        void update(int duration) {
            this.duration = duration;
        }

        void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);
        }

        @Override
        public final String toString()
        {
            return "ToastRecord{"
                + Integer.toHexString(System.identityHashCode(this))
                + " pkg=" + pkg
                + " callback=" + callback
                + " duration=" + duration;
        }
    }

    private final NotificationDelegate mNotificationDelegate = new NotificationDelegate() {

        @Override
        public void onSetDisabled(int status) {
            synchronized (mNotificationList) {
                mDisableNotificationAlerts = (status & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
                if (mDisableNotificationAlerts) {
                    // cancel whatever's going on
                    long identity = Binder.clearCallingIdentity();
                    try {
                        final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                        if (player != null) {
                            player.stopAsync();
                        }
                    } catch (RemoteException e) {
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }

                    identity = Binder.clearCallingIdentity();
                    try {
                        mVibrator.cancel();
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }
        }

        @Override
        public void onClearAll(int callingUid, int callingPid, int userId) {
            synchronized (mNotificationList) {
                cancelAllLocked(callingUid, callingPid, userId, REASON_DELEGATE_CANCEL_ALL, null,
                        /*includeCurrentProfiles*/ true);
            }
        }

        @Override
        public void onNotificationClick(int callingUid, int callingPid,
                String pkg, String tag, int id, int userId) {
            cancelNotification(callingUid, callingPid, pkg, tag, id, Notification.FLAG_AUTO_CANCEL,
                    Notification.FLAG_FOREGROUND_SERVICE, false, userId, REASON_DELEGATE_CLICK, null);
        }

        @Override
        public void onNotificationClear(int callingUid, int callingPid,
                String pkg, String tag, int id, int userId) {
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0,
                    Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE,
                    true, userId, REASON_DELEGATE_CANCEL, null);
        }

        @Override
        public void onPanelRevealed() {
            EventLogTags.writeNotificationPanelRevealed();
            synchronized (mNotificationList) {
                // sound
                mSoundNotification = null;

                long identity = Binder.clearCallingIdentity();
                try {
                    final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                    if (player != null) {
                        player.stopAsync();
                    }
                } catch (RemoteException e) {
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                // vibrate
                mVibrateNotification = null;
                identity = Binder.clearCallingIdentity();
                try {
                    mVibrator.cancel();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

                // light
                mLights.clear();
                mLedNotification = null;
                updateLightsLocked();
            }
        }

        @Override
        public void onPanelHidden() {
            EventLogTags.writeNotificationPanelHidden();
        }

        @Override
        public void onNotificationError(int callingUid, int callingPid, String pkg, String tag, int id,
                int uid, int initialPid, String message, int userId) {
            Slog.d(TAG, "onNotification error pkg=" + pkg + " tag=" + tag + " id=" + id
                    + "; will crashApplication(uid=" + uid + ", pid=" + initialPid + ")");
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 0, false, userId,
                    REASON_DELEGATE_ERROR, null);
            long ident = Binder.clearCallingIdentity();
            try {
                ActivityManagerNative.getDefault().crashApplication(uid, initialPid, pkg,
                        "Bad notification posted from package " + pkg
                        + ": " + message);
            } catch (RemoteException e) {
            }
            Binder.restoreCallingIdentity(ident);
        }

        @Override
        public boolean allowDisable(int what, IBinder token, String pkg) {
            if (isCall(pkg, null)) {
                return mZenMode == Settings.Global.ZEN_MODE_OFF;
            }
            return true;
        }

        @Override
        public void onNotificationVisibilityChanged(
                String[] newlyVisibleKeys, String[] noLongerVisibleKeys) {
            // Using ';' as separator since eventlogs uses ',' to separate
            // args.
            EventLogTags.writeNotificationVisibilityChanged(
                    TextUtils.join(";", newlyVisibleKeys),
                    TextUtils.join(";", noLongerVisibleKeys));
        }
    };

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            boolean queryRestart = false;
            boolean queryRemove = false;
            boolean packageChanged = false;
            boolean cancelNotifications = true;
            
            if (action.equals(Intent.ACTION_PACKAGE_ADDED)
                    || (queryRemove=action.equals(Intent.ACTION_PACKAGE_REMOVED))
                    || action.equals(Intent.ACTION_PACKAGE_RESTARTED)
                    || (packageChanged=action.equals(Intent.ACTION_PACKAGE_CHANGED))
                    || (queryRestart=action.equals(Intent.ACTION_QUERY_PACKAGE_RESTART))
                    || action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                String pkgList[] = null;
                boolean queryReplace = queryRemove &&
                        intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                if (DBG) Slog.i(TAG, "queryReplace=" + queryReplace);
                if (action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                } else if (queryRestart) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                } else {
                    Uri uri = intent.getData();
                    if (uri == null) {
                        return;
                    }
                    String pkgName = uri.getSchemeSpecificPart();
                    if (pkgName == null) {
                        return;
                    }
                    if (packageChanged) {
                        // We cancel notifications for packages which have just been disabled
                        try {
                            final int enabled = getContext().getPackageManager()
                                    .getApplicationEnabledSetting(pkgName);
                            if (enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    || enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                                cancelNotifications = false;
                            }
                        } catch (IllegalArgumentException e) {
                            // Package doesn't exist; probably racing with uninstall.
                            // cancelNotifications is already true, so nothing to do here.
                            if (DBG) {
                                Slog.i(TAG, "Exception trying to look up app enabled setting", e);
                            }
                        }
                    }
                    pkgList = new String[]{pkgName};
                }

                boolean anyListenersInvolved = false;
                if (pkgList != null && (pkgList.length > 0)) {
                    for (String pkgName : pkgList) {
                        if (cancelNotifications) {
                            cancelAllNotificationsInt(MY_UID, MY_PID, pkgName, 0, 0, !queryRestart,
                                    UserHandle.USER_ALL, REASON_PACKAGE_CHANGED, null);
                        }
                        if (mEnabledListenerPackageNames.contains(pkgName)) {
                            anyListenersInvolved = true;
                        }
                    }
                }

                if (anyListenersInvolved) {
                    // if we're not replacing a package, clean up orphaned bits
                    if (!queryReplace) {
                        disableNonexistentListeners();
                    }
                    // make sure we're still bound to any of our
                    // listeners who may have just upgraded
                    rebindListenerServices();
                }
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                // Keep track of screen on/off state, but do not turn off the notification light
                // until user passes through the lock screen or views the notification.
                mScreenOn = true;
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
            } else if (action.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                mInCall = TelephonyManager.EXTRA_STATE_OFFHOOK
                        .equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE));
                updateNotificationPulse();
            } else if (action.equals(Intent.ACTION_USER_STOPPED)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userHandle >= 0) {
                    cancelAllNotificationsInt(MY_UID, MY_PID, null, 0, 0, true, userHandle,
                            REASON_USER_STOPPED, null);
                }
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                // turn off LED when user passes through lock screen
                mNotificationLight.turnOff();
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                // reload per-user settings
                mSettingsObserver.update(null);
                updateCurrentProfilesCache(context);
            } else if (action.equals(Intent.ACTION_USER_ADDED)) {
                updateCurrentProfilesCache(context);
            }
        }
    };

    class SettingsObserver extends ContentObserver {
        private final Uri NOTIFICATION_LIGHT_PULSE_URI
                = Settings.System.getUriFor(Settings.System.NOTIFICATION_LIGHT_PULSE);

        private final Uri ENABLED_NOTIFICATION_LISTENERS_URI
                = Settings.Secure.getUriFor(Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);

        private final Uri ZEN_MODE
                = Settings.Global.getUriFor(Settings.Global.ZEN_MODE);

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(NOTIFICATION_LIGHT_PULSE_URI,
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(ENABLED_NOTIFICATION_LISTENERS_URI,
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(ZEN_MODE,
                    false, this);
            update(null);
        }

        @Override public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            ContentResolver resolver = getContext().getContentResolver();
            if (uri == null || NOTIFICATION_LIGHT_PULSE_URI.equals(uri)) {
                boolean pulseEnabled = Settings.System.getInt(resolver,
                            Settings.System.NOTIFICATION_LIGHT_PULSE, 0) != 0;
                if (mNotificationPulseEnabled != pulseEnabled) {
                    mNotificationPulseEnabled = pulseEnabled;
                    updateNotificationPulse();
                }
            }
            if (uri == null || ENABLED_NOTIFICATION_LISTENERS_URI.equals(uri)) {
                rebindListenerServices();
            }
            if (ZEN_MODE.equals(uri)) {
                updateZenMode();
            }
        }
    }

    private SettingsObserver mSettingsObserver;

    static long[] getLongArray(Resources r, int resid, int maxlen, long[] def) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return def;
        }
        final int len = ar.length > maxlen ? maxlen : ar.length;
        long[] out = new long[len];
        for (int i=0; i<len; i++) {
            out[i] = ar[i];
        }
        return out;
    }

    public NotificationManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mAm = ActivityManagerNative.getDefault();
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mVibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);

        mHandler = new WorkerHandler();

        importOldBlockDb();

        mStatusBar = getLocalService(StatusBarManagerInternal.class);
        mStatusBar.setNotificationDelegate(mNotificationDelegate);

        final LightsManager lights = getLocalService(LightsManager.class);
        mNotificationLight = lights.getLight(LightsManager.LIGHT_ID_NOTIFICATIONS);
        mAttentionLight = lights.getLight(LightsManager.LIGHT_ID_ATTENTION);

        Resources resources = getContext().getResources();
        mDefaultNotificationColor = resources.getColor(
                R.color.config_defaultNotificationColor);
        mDefaultNotificationLedOn = resources.getInteger(
                R.integer.config_defaultNotificationLedOn);
        mDefaultNotificationLedOff = resources.getInteger(
                R.integer.config_defaultNotificationLedOff);

        mDefaultVibrationPattern = getLongArray(resources,
                R.array.config_defaultNotificationVibePattern,
                VIBRATE_PATTERN_MAXLEN,
                DEFAULT_VIBRATE_PATTERN);

        mFallbackVibrationPattern = getLongArray(resources,
                R.array.config_notificationFallbackVibePattern,
                VIBRATE_PATTERN_MAXLEN,
                DEFAULT_VIBRATE_PATTERN);

        // Don't start allowing notifications until the setup wizard has run once.
        // After that, including subsequent boots, init with notifications turned on.
        // This works on the first boot because the setup wizard will toggle this
        // flag at least once and we'll go back to 0 after that.
        if (0 == Settings.Global.getInt(getContext().getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0)) {
            mDisableNotificationAlerts = true;
        }
        updateZenMode();

        updateCurrentProfilesCache(getContext());

        // register for various Intents
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        getContext().registerReceiver(mIntentReceiver, filter);
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        pkgFilter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        pkgFilter.addDataScheme("package");
        getContext().registerReceiver(mIntentReceiver, pkgFilter);
        IntentFilter sdFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        getContext().registerReceiver(mIntentReceiver, sdFilter);

        mSettingsObserver = new SettingsObserver(mHandler);

        // spin up NotificationScorers
        String[] notificationScorerNames = resources.getStringArray(
                R.array.config_notificationScorers);
        for (String scorerName : notificationScorerNames) {
            try {
                Class<?> scorerClass = getContext().getClassLoader().loadClass(scorerName);
                NotificationScorer scorer = (NotificationScorer) scorerClass.newInstance();
                scorer.initialize(getContext());
                mScorers.add(scorer);
            } catch (ClassNotFoundException e) {
                Slog.w(TAG, "Couldn't find scorer " + scorerName + ".", e);
            } catch (InstantiationException e) {
                Slog.w(TAG, "Couldn't instantiate scorer " + scorerName + ".", e);
            } catch (IllegalAccessException e) {
                Slog.w(TAG, "Problem accessing scorer " + scorerName + ".", e);
            }
        }

        publishBinderService(Context.NOTIFICATION_SERVICE, mService);
        publishLocalService(NotificationManagerInternal.class, mInternalService);
    }

    /**
     * Read the old XML-based app block database and import those blockages into the AppOps system.
     */
    private void importOldBlockDb() {
        loadBlockDb();

        PackageManager pm = getContext().getPackageManager();
        for (String pkg : mBlockedPackages) {
            PackageInfo info = null;
            try {
                info = pm.getPackageInfo(pkg, 0);
                setNotificationsEnabledForPackageImpl(pkg, info.applicationInfo.uid, false);
            } catch (NameNotFoundException e) {
                // forget you
            }
        }
        mBlockedPackages.clear();
        if (mPolicyFile != null) {
            mPolicyFile.delete();
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            // no beeping until we're basically done booting
            mSystemReady = true;

            // Grab our optional AudioService
            mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);

        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            // This observer will force an update when observe is called, causing us to
            // bind to listener services.
            mSettingsObserver.observe();
        }
    }

    void setNotificationsEnabledForPackageImpl(String pkg, int uid, boolean enabled) {
        Slog.v(TAG, (enabled?"en":"dis") + "abling notifications for " + pkg);

        mAppOps.setMode(AppOpsManager.OP_POST_NOTIFICATION, uid, pkg,
                enabled ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);

        // Now, cancel any outstanding notifications that are part of a just-disabled app
        if (ENABLE_BLOCKED_NOTIFICATIONS && !enabled) {
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, 0, 0, true, UserHandle.getUserId(uid),
                    REASON_PACKAGE_BANNED, null);
        }
    }

    private final IBinder mService = new INotificationManager.Stub() {
        // Toasts
        // ============================================================================

        @Override
        public void enqueueToast(String pkg, ITransientNotification callback, int duration)
        {
            if (DBG) {
                Slog.i(TAG, "enqueueToast pkg=" + pkg + " callback=" + callback
                        + " duration=" + duration);
            }

            if (pkg == null || callback == null) {
                Slog.e(TAG, "Not doing toast. pkg=" + pkg + " callback=" + callback);
                return ;
            }

            final boolean isSystemToast = isCallerSystem() || ("android".equals(pkg));

            if (ENABLE_BLOCKED_TOASTS && !noteNotificationOp(pkg, Binder.getCallingUid())) {
                if (!isSystemToast) {
                    Slog.e(TAG, "Suppressing toast from package " + pkg + " by user request.");
                    return;
                }
            }

            synchronized (mToastQueue) {
                int callingPid = Binder.getCallingPid();
                long callingId = Binder.clearCallingIdentity();
                try {
                    ToastRecord record;
                    int index = indexOfToastLocked(pkg, callback);
                    // If it's already in the queue, we update it in place, we don't
                    // move it to the end of the queue.
                    if (index >= 0) {
                        record = mToastQueue.get(index);
                        record.update(duration);
                    } else {
                        // Limit the number of toasts that any given package except the android
                        // package can enqueue.  Prevents DOS attacks and deals with leaks.
                        if (!isSystemToast) {
                            int count = 0;
                            final int N = mToastQueue.size();
                            for (int i=0; i<N; i++) {
                                 final ToastRecord r = mToastQueue.get(i);
                                 if (r.pkg.equals(pkg)) {
                                     count++;
                                     if (count >= MAX_PACKAGE_NOTIFICATIONS) {
                                         Slog.e(TAG, "Package has already posted " + count
                                                + " toasts. Not showing more. Package=" + pkg);
                                         return;
                                     }
                                 }
                            }
                        }

                        record = new ToastRecord(callingPid, pkg, callback, duration);
                        mToastQueue.add(record);
                        index = mToastQueue.size() - 1;
                        keepProcessAliveLocked(callingPid);
                    }
                    // If it's at index 0, it's the current toast.  It doesn't matter if it's
                    // new or just been updated.  Call back and tell it to show itself.
                    // If the callback fails, this will remove it from the list, so don't
                    // assume that it's valid after this.
                    if (index == 0) {
                        showNextToastLocked();
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @Override
        public void cancelToast(String pkg, ITransientNotification callback) {
            Slog.i(TAG, "cancelToast pkg=" + pkg + " callback=" + callback);

            if (pkg == null || callback == null) {
                Slog.e(TAG, "Not cancelling notification. pkg=" + pkg + " callback=" + callback);
                return ;
            }

            synchronized (mToastQueue) {
                long callingId = Binder.clearCallingIdentity();
                try {
                    int index = indexOfToastLocked(pkg, callback);
                    if (index >= 0) {
                        cancelToastLocked(index);
                    } else {
                        Slog.w(TAG, "Toast already cancelled. pkg=" + pkg
                                + " callback=" + callback);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @Override
        public void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
                Notification notification, int[] idOut, int userId) throws RemoteException {
            enqueueNotificationInternal(pkg, opPkg, Binder.getCallingUid(),
                    Binder.getCallingPid(), tag, id, notification, idOut, userId);
        }

        @Override
        public void cancelNotificationWithTag(String pkg, String tag, int id, int userId) {
            checkCallerIsSystemOrSameApp(pkg);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, true, false, "cancelNotificationWithTag", pkg);
            // Don't allow client applications to cancel foreground service notis.
            cancelNotification(Binder.getCallingUid(), Binder.getCallingPid(), pkg, tag, id, 0,
                    Binder.getCallingUid() == Process.SYSTEM_UID
                    ? 0 : Notification.FLAG_FOREGROUND_SERVICE, false, userId, REASON_NOMAN_CANCEL,
                    null);
        }

        @Override
        public void cancelAllNotifications(String pkg, int userId) {
            checkCallerIsSystemOrSameApp(pkg);

            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, true, false, "cancelAllNotifications", pkg);

            // Calling from user space, don't allow the canceling of actively
            // running foreground services.
            cancelAllNotificationsInt(Binder.getCallingUid(), Binder.getCallingPid(),
                    pkg, 0, Notification.FLAG_FOREGROUND_SERVICE, true, userId,
                    REASON_NOMAN_CANCEL_ALL, null);
        }

        @Override
        public void setNotificationsEnabledForPackage(String pkg, int uid, boolean enabled) {
            checkCallerIsSystem();

            setNotificationsEnabledForPackageImpl(pkg, uid, enabled);
        }

        /**
         * Use this when you just want to know if notifications are OK for this package.
         */
        @Override
        public boolean areNotificationsEnabledForPackage(String pkg, int uid) {
            checkCallerIsSystem();
            return (mAppOps.checkOpNoThrow(AppOpsManager.OP_POST_NOTIFICATION, uid, pkg)
                    == AppOpsManager.MODE_ALLOWED);
        }

        /**
         * System-only API for getting a list of current (i.e. not cleared) notifications.
         *
         * Requires ACCESS_NOTIFICATIONS which is signature|system.
         */
        @Override
        public StatusBarNotification[] getActiveNotifications(String callingPkg) {
            // enforce() will ensure the calling uid has the correct permission
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_NOTIFICATIONS,
                    "NotificationManagerService.getActiveNotifications");

            StatusBarNotification[] tmp = null;
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg)
                    == AppOpsManager.MODE_ALLOWED) {
                synchronized (mNotificationList) {
                    tmp = new StatusBarNotification[mNotificationList.size()];
                    final int N = mNotificationList.size();
                    for (int i=0; i<N; i++) {
                        tmp[i] = mNotificationList.get(i).sbn;
                    }
                }
            }
            return tmp;
        }

        /**
         * System-only API for getting a list of recent (cleared, no longer shown) notifications.
         *
         * Requires ACCESS_NOTIFICATIONS which is signature|system.
         */
        @Override
        public StatusBarNotification[] getHistoricalNotifications(String callingPkg, int count) {
            // enforce() will ensure the calling uid has the correct permission
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_NOTIFICATIONS,
                    "NotificationManagerService.getHistoricalNotifications");

            StatusBarNotification[] tmp = null;
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg)
                    == AppOpsManager.MODE_ALLOWED) {
                synchronized (mArchive) {
                    tmp = mArchive.getArray(count);
                }
            }
            return tmp;
        }

        /**
         * Register a listener binder directly with the notification manager.
         *
         * Only works with system callers. Apps should extend
         * {@link android.service.notification.NotificationListenerService}.
         */
        @Override
        public void registerListener(final INotificationListener listener,
                final ComponentName component, final int userid) {
            checkCallerIsSystem();
            checkNullListener(listener);
            registerListenerImpl(listener, component, userid);
        }

        /**
         * Remove a listener binder directly
         */
        @Override
        public void unregisterListener(INotificationListener listener, int userid) {
            checkNullListener(listener);
            // no need to check permissions; if your listener binder is in the list,
            // that's proof that you had permission to add it in the first place
            unregisterListenerImpl(listener, userid);
        }

        /**
         * Allow an INotificationListener to simulate a "clear all" operation.
         *
         * {@see com.android.server.StatusBarManagerService.NotificationCallbacks#onClearAllNotifications}
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void cancelNotificationsFromListener(INotificationListener token, String[] keys) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationList) {
                    final NotificationListenerInfo info = checkListenerTokenLocked(token);
                    if (keys != null) {
                        final int N = keys.length;
                        for (int i = 0; i < N; i++) {
                            NotificationRecord r = mNotificationsByKey.get(keys[i]);
                            final int userId = r.sbn.getUserId();
                            if (userId != info.userid && userId != UserHandle.USER_ALL &&
                                    !isCurrentProfile(userId)) {
                                throw new SecurityException("Disallowed call from listener: "
                                        + info.listener);
                            }
                            if (r != null) {
                                cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                        r.sbn.getPackageName(), r.sbn.getTag(), r.sbn.getId(),
                                        userId);
                            }
                        }
                    } else {
                        cancelAllLocked(callingUid, callingPid, info.userid,
                                REASON_LISTENER_CANCEL_ALL, info, info.supportsProfiles());
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void cancelNotificationFromListenerLocked(NotificationListenerInfo info,
                int callingUid, int callingPid, String pkg, String tag, int id, int userId) {
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0,
                    Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE,
                    true,
                    userId, REASON_LISTENER_CANCEL, info);
        }

        /**
         * Allow an INotificationListener to simulate clearing (dismissing) a single notification.
         *
         * {@see com.android.server.StatusBarManagerService.NotificationCallbacks#onNotificationClear}
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void cancelNotificationFromListener(INotificationListener token, String pkg,
                String tag, int id) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationList) {
                    final NotificationListenerInfo info = checkListenerTokenLocked(token);
                    if (info.supportsProfiles()) {
                        Log.e(TAG, "Ignoring deprecated cancelNotification(pkg, tag, id) "
                                + "from " + info.component
                                + " use cancelNotification(key) instead.");
                    } else {
                        cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                pkg, tag, id, info.userid);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to request the list of outstanding notifications seen by
         * the current user. Useful when starting up, after which point the listener callbacks
         * should be used.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public StatusBarNotification[] getActiveNotificationsFromListener(
                INotificationListener token, String[] keys) {
            synchronized (mNotificationList) {
                final NotificationListenerInfo info = checkListenerTokenLocked(token);
                final ArrayList<StatusBarNotification> list
                        = new ArrayList<StatusBarNotification>();
                if (keys == null) {
                    final int N = mNotificationList.size();
                    for (int i=0; i<N; i++) {
                        StatusBarNotification sbn = mNotificationList.get(i).sbn;
                        if (info.enabledAndUserMatches(sbn)) {
                            list.add(sbn);
                        }
                    }
                } else {
                    final int N = keys.length;
                    for (int i=0; i<N; i++) {
                        NotificationRecord r = mNotificationsByKey.get(keys[i]);
                        if (r != null && info.enabledAndUserMatches(r.sbn)) {
                            list.add(r.sbn);
                        }
                    }
                }
                return list.toArray(new StatusBarNotification[list.size()]);
            }
        }

        @Override
        public String[] getActiveNotificationKeysFromListener(INotificationListener token) {
            return NotificationManagerService.this.getActiveNotificationKeysFromListener(token);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump NotificationManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            dumpImpl(pw);
        }
    };

    private String[] getActiveNotificationKeysFromListener(INotificationListener token) {
        synchronized (mNotificationList) {
            final NotificationListenerInfo info = checkListenerTokenLocked(token);
            final ArrayList<String> keys = new ArrayList<String>();
            final int N = mNotificationList.size();
            for (int i=0; i<N; i++) {
                final StatusBarNotification sbn = mNotificationList.get(i).sbn;
                if (info.enabledAndUserMatches(sbn)) {
                    keys.add(sbn.getKey());
                }
            }
            return keys.toArray(new String[keys.size()]);
        }
    }

    void dumpImpl(PrintWriter pw) {
        pw.println("Current Notification Manager state:");

        pw.println("  Listeners (" + mEnabledListenersForCurrentProfiles.size()
                + ") enabled for current profiles:");
        for (ComponentName cmpt : mEnabledListenersForCurrentProfiles) {
            pw.println("    " + cmpt);
        }

        pw.println("  Live listeners (" + mListeners.size() + "):");
        for (NotificationListenerInfo info : mListeners) {
            pw.println("    " + info.component
                    + " (user " + info.userid + "): " + info.listener
                    + (info.isSystem?" SYSTEM":""));
        }

        int N;

        synchronized (mToastQueue) {
            N = mToastQueue.size();
            if (N > 0) {
                pw.println("  Toast Queue:");
                for (int i=0; i<N; i++) {
                    mToastQueue.get(i).dump(pw, "    ");
                }
                pw.println("  ");
            }

        }

        synchronized (mNotificationList) {
            N = mNotificationList.size();
            if (N > 0) {
                pw.println("  Notification List:");
                for (int i=0; i<N; i++) {
                    mNotificationList.get(i).dump(pw, "    ", getContext());
                }
                pw.println("  ");
            }

            N = mLights.size();
            if (N > 0) {
                pw.println("  Lights List:");
                for (int i=0; i<N; i++) {
                    pw.println("    " + mLights.get(i));
                }
                pw.println("  ");
            }

            pw.println("  mSoundNotification=" + mSoundNotification);
            pw.println("  mVibrateNotification=" + mVibrateNotification);
            pw.println("  mDisableNotificationAlerts=" + mDisableNotificationAlerts);
            pw.println("  mZenMode=" + Settings.Global.zenModeToString(mZenMode));
            pw.println("  mSystemReady=" + mSystemReady);
            pw.println("  mArchive=" + mArchive.toString());
            Iterator<StatusBarNotification> iter = mArchive.descendingIterator();
            int i=0;
            while (iter.hasNext()) {
                pw.println("    " + iter.next());
                if (++i >= 5) {
                    if (iter.hasNext()) pw.println("    ...");
                    break;
                }
            }

            pw.println("\n  Usage Stats:");
            mUsageStats.dump(pw, "    ");

        }
    }

    /**
     * The private API only accessible to the system process.
     */
    private final NotificationManagerInternal mInternalService = new NotificationManagerInternal() {
        @Override
        public void enqueueNotification(String pkg, String opPkg, int callingUid, int callingPid,
                String tag, int id, Notification notification, int[] idReceived, int userId) {
            enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification,
                    idReceived, userId);
        }
    };

    void enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid,
            final int callingPid, final String tag, final int id, final Notification notification,
            int[] idOut, int incomingUserId) {
        if (DBG) {
            Slog.v(TAG, "enqueueNotificationInternal: pkg=" + pkg + " id=" + id
                    + " notification=" + notification);
        }
        checkCallerIsSystemOrSameApp(pkg);
        final boolean isSystemNotification = isUidSystem(callingUid) || ("android".equals(pkg));

        final int userId = ActivityManager.handleIncomingUser(callingPid,
                callingUid, incomingUserId, true, false, "enqueueNotification", pkg);
        final UserHandle user = new UserHandle(userId);

        // Limit the number of notifications that any given package except the android
        // package can enqueue.  Prevents DOS attacks and deals with leaks.
        if (!isSystemNotification) {
            synchronized (mNotificationList) {
                int count = 0;
                final int N = mNotificationList.size();
                for (int i=0; i<N; i++) {
                    final NotificationRecord r = mNotificationList.get(i);
                    if (r.sbn.getPackageName().equals(pkg) && r.sbn.getUserId() == userId) {
                        count++;
                        if (count >= MAX_PACKAGE_NOTIFICATIONS) {
                            Slog.e(TAG, "Package has already posted " + count
                                    + " notifications.  Not showing more.  package=" + pkg);
                            return;
                        }
                    }
                }
            }
        }

        // This conditional is a dirty hack to limit the logging done on
        //     behalf of the download manager without affecting other apps.
        if (!pkg.equals("com.android.providers.downloads")
                || Log.isLoggable("DownloadManager", Log.VERBOSE)) {
            EventLogTags.writeNotificationEnqueue(callingUid, callingPid,
                    pkg, id, tag, userId, notification.toString());
        }

        if (pkg == null || notification == null) {
            throw new IllegalArgumentException("null not allowed: pkg=" + pkg
                    + " id=" + id + " notification=" + notification);
        }
        if (notification.icon != 0) {
            if (notification.contentView == null) {
                throw new IllegalArgumentException("contentView required: pkg=" + pkg
                        + " id=" + id + " notification=" + notification);
            }
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {

                // === Scoring ===

                // 0. Sanitize inputs
                notification.priority = clamp(notification.priority, Notification.PRIORITY_MIN,
                        Notification.PRIORITY_MAX);
                // Migrate notification flags to scores
                if (0 != (notification.flags & Notification.FLAG_HIGH_PRIORITY)) {
                    if (notification.priority < Notification.PRIORITY_MAX) {
                        notification.priority = Notification.PRIORITY_MAX;
                    }
                } else if (SCORE_ONGOING_HIGHER &&
                        0 != (notification.flags & Notification.FLAG_ONGOING_EVENT)) {
                    if (notification.priority < Notification.PRIORITY_HIGH) {
                        notification.priority = Notification.PRIORITY_HIGH;
                    }
                }

                // 1. initial score: buckets of 10, around the app
                int score = notification.priority * NOTIFICATION_PRIORITY_MULTIPLIER; //[-20..20]

                // 2. Consult external heuristics (TBD)

                // 3. Apply local rules

                int initialScore = score;
                if (!mScorers.isEmpty()) {
                    if (DBG) Slog.v(TAG, "Initial score is " + score + ".");
                    for (NotificationScorer scorer : mScorers) {
                        try {
                            score = scorer.getScore(notification, score);
                        } catch (Throwable t) {
                            Slog.w(TAG, "Scorer threw on .getScore.", t);
                        }
                    }
                    if (DBG) Slog.v(TAG, "Final score is " + score + ".");
                }

                // add extra to indicate score modified by NotificationScorer
                notification.extras.putBoolean(Notification.EXTRA_SCORE_MODIFIED,
                        score != initialScore);

                // blocked apps
                if (ENABLE_BLOCKED_NOTIFICATIONS && !noteNotificationOp(pkg, callingUid)) {
                    if (!isSystemNotification) {
                        score = JUNK_SCORE;
                        Slog.e(TAG, "Suppressing notification from package " + pkg
                                + " by user request.");
                    }
                }

                if (DBG) {
                    Slog.v(TAG, "Assigned score=" + score + " to " + notification);
                }

                if (score < SCORE_DISPLAY_THRESHOLD) {
                    // Notification will be blocked because the score is too low.
                    return;
                }

                // Is this notification intercepted by zen mode?
                final boolean intercept = shouldIntercept(pkg, notification);
                notification.extras.putBoolean(EXTRA_INTERCEPT, intercept);

                // Should this notification make noise, vibe, or use the LED?
                final boolean canInterrupt = (score >= SCORE_INTERRUPTION_THRESHOLD) && !intercept;
                if (DBG) Slog.v(TAG, "canInterrupt=" + canInterrupt + " intercept=" + intercept);
                synchronized (mNotificationList) {
                    final StatusBarNotification n = new StatusBarNotification(
                            pkg, opPkg, id, tag, callingUid, callingPid, score, notification,
                            user);
                    NotificationRecord r = new NotificationRecord(n);
                    NotificationRecord old = null;

                    int index = indexOfNotificationLocked(pkg, tag, id, userId);
                    if (index < 0) {
                        mNotificationList.add(r);
                        mUsageStats.registerPostedByApp(r);
                    } else {
                        old = mNotificationList.get(index);
                        mNotificationList.set(index, r);
                        mUsageStats.registerUpdatedByApp(r);
                        // Make sure we don't lose the foreground service state.
                        if (old != null) {
                            notification.flags |=
                                old.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE;
                        }
                    }
                    if (old != null) {
                        mNotificationsByKey.remove(old.sbn.getKey());
                    }
                    mNotificationsByKey.put(n.getKey(), r);

                    // Ensure if this is a foreground service that the proper additional
                    // flags are set.
                    if ((notification.flags&Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                        notification.flags |= Notification.FLAG_ONGOING_EVENT
                                | Notification.FLAG_NO_CLEAR;
                    }

                    final int currentUser;
                    final long token = Binder.clearCallingIdentity();
                    try {
                        currentUser = ActivityManager.getCurrentUser();
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }

                    if (notification.icon != 0) {
                        if (old != null && old.statusBarKey != null) {
                            r.statusBarKey = old.statusBarKey;
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                mStatusBar.updateNotification(r.statusBarKey, n);
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                        } else {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                r.statusBarKey = mStatusBar.addNotification(n);
                                if ((n.getNotification().flags & Notification.FLAG_SHOW_LIGHTS) != 0
                                        && canInterrupt) {
                                    mAttentionLight.pulse();
                                }
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                        }
                        // Send accessibility events only for the current user.
                        if (currentUser == userId) {
                            sendAccessibilityEvent(notification, pkg);
                        }

                        notifyPostedLocked(r);
                    } else {
                        Slog.e(TAG, "Not posting notification with icon==0: " + notification);
                        if (old != null && old.statusBarKey != null) {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                mStatusBar.removeNotification(old.statusBarKey);
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }

                            notifyRemovedLocked(r);
                        }
                        // ATTENTION: in a future release we will bail out here
                        // so that we do not play sounds, show lights, etc. for invalid
                        // notifications
                        Slog.e(TAG, "WARNING: In a future release this will crash the app: "
                                + n.getPackageName());
                    }

                    // If we're not supposed to beep, vibrate, etc. then don't.
                    if (!mDisableNotificationAlerts
                            && (!(old != null
                                && (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 ))
                            && (r.getUserId() == UserHandle.USER_ALL ||
                                (r.getUserId() == userId && r.getUserId() == currentUser) ||
                                isCurrentProfile(r.getUserId()))
                            && canInterrupt
                            && mSystemReady
                            && mAudioManager != null) {
                        if (DBG) Slog.v(TAG, "Interrupting!");
                        // sound

                        // should we use the default notification sound? (indicated either by
                        // DEFAULT_SOUND or because notification.sound is pointing at
                        // Settings.System.NOTIFICATION_SOUND)
                        final boolean useDefaultSound =
                               (notification.defaults & Notification.DEFAULT_SOUND) != 0 ||
                                       Settings.System.DEFAULT_NOTIFICATION_URI
                                               .equals(notification.sound);

                        Uri soundUri = null;
                        boolean hasValidSound = false;

                        if (useDefaultSound) {
                            soundUri = Settings.System.DEFAULT_NOTIFICATION_URI;

                            // check to see if the default notification sound is silent
                            ContentResolver resolver = getContext().getContentResolver();
                            hasValidSound = Settings.System.getString(resolver,
                                   Settings.System.NOTIFICATION_SOUND) != null;
                        } else if (notification.sound != null) {
                            soundUri = notification.sound;
                            hasValidSound = (soundUri != null);
                        }

                        if (hasValidSound) {
                            boolean looping =
                                    (notification.flags & Notification.FLAG_INSISTENT) != 0;
                            int audioStreamType;
                            if (notification.audioStreamType >= 0) {
                                audioStreamType = notification.audioStreamType;
                            } else {
                                audioStreamType = DEFAULT_STREAM_TYPE;
                            }
                            mSoundNotification = r;
                            // do not play notifications if stream volume is 0 (typically because
                            // ringer mode is silent) or if there is a user of exclusive audio focus
                            if ((mAudioManager.getStreamVolume(audioStreamType) != 0)
                                    && !mAudioManager.isAudioFocusExclusive()) {
                                final long identity = Binder.clearCallingIdentity();
                                try {
                                    final IRingtonePlayer player =
                                            mAudioManager.getRingtonePlayer();
                                    if (player != null) {
                                        if (DBG) Slog.v(TAG, "Playing sound " + soundUri
                                                + " on stream " + audioStreamType);
                                        player.playAsync(soundUri, user, looping, audioStreamType);
                                    }
                                } catch (RemoteException e) {
                                } finally {
                                    Binder.restoreCallingIdentity(identity);
                                }
                            }
                        }

                        // vibrate
                        // Does the notification want to specify its own vibration?
                        final boolean hasCustomVibrate = notification.vibrate != null;

                        // new in 4.2: if there was supposed to be a sound and we're in vibrate
                        // mode, and no other vibration is specified, we fall back to vibration
                        final boolean convertSoundToVibration =
                                   !hasCustomVibrate
                                && hasValidSound
                                && (mAudioManager.getRingerMode()
                                           == AudioManager.RINGER_MODE_VIBRATE);

                        // The DEFAULT_VIBRATE flag trumps any custom vibration AND the fallback.
                        final boolean useDefaultVibrate =
                                (notification.defaults & Notification.DEFAULT_VIBRATE) != 0;

                        if ((useDefaultVibrate || convertSoundToVibration || hasCustomVibrate)
                                && !(mAudioManager.getRingerMode()
                                        == AudioManager.RINGER_MODE_SILENT)) {
                            mVibrateNotification = r;

                            if (useDefaultVibrate || convertSoundToVibration) {
                                // Escalate privileges so we can use the vibrator even if the
                                // notifying app does not have the VIBRATE permission.
                                long identity = Binder.clearCallingIdentity();
                                try {
                                    mVibrator.vibrate(r.sbn.getUid(), r.sbn.getOpPkg(),
                                        useDefaultVibrate ? mDefaultVibrationPattern
                                            : mFallbackVibrationPattern,
                                        ((notification.flags & Notification.FLAG_INSISTENT) != 0)
                                                ? 0: -1, notification.audioStreamType);
                                } finally {
                                    Binder.restoreCallingIdentity(identity);
                                }
                            } else if (notification.vibrate.length > 1) {
                                // If you want your own vibration pattern, you need the VIBRATE
                                // permission
                                mVibrator.vibrate(r.sbn.getUid(), r.sbn.getOpPkg(),
                                        notification.vibrate,
                                    ((notification.flags & Notification.FLAG_INSISTENT) != 0)
                                            ? 0: -1, notification.audioStreamType);
                            }
                        }
                    }

                    // light
                    // the most recent thing gets the light
                    mLights.remove(old);
                    if (mLedNotification == old) {
                        mLedNotification = null;
                    }
                    //Slog.i(TAG, "notification.lights="
                    //        + ((old.notification.lights.flags & Notification.FLAG_SHOW_LIGHTS)
                    //                  != 0));
                    if ((notification.flags & Notification.FLAG_SHOW_LIGHTS) != 0
                            && canInterrupt) {
                        mLights.add(r);
                        updateLightsLocked();
                    } else {
                        if (old != null
                                && ((old.getFlags() & Notification.FLAG_SHOW_LIGHTS) != 0)) {
                            updateLightsLocked();
                        }
                    }
                }
            }
        });

        idOut[0] = id;
    }

     void registerListenerImpl(final INotificationListener listener,
            final ComponentName component, final int userid) {
        synchronized (mNotificationList) {
            try {
                NotificationListenerInfo info
                        = new NotificationListenerInfo(listener, component, userid,
                        /*isSystem*/ true, Build.VERSION_CODES.L);
                listener.asBinder().linkToDeath(info, 0);
                mListeners.add(info);
            } catch (RemoteException e) {
                // already dead
            }
        }
    }

    /**
     * Removes a listener from the list and unbinds from its service.
     */
    void unregisterListenerImpl(final INotificationListener listener, final int userid) {
        NotificationListenerInfo info = removeListenerImpl(listener, userid);
        if (info != null && info.connection != null) {
            getContext().unbindService(info.connection);
        }
    }

    /**
     * Removes a listener from the list but does not unbind from the listener's service.
     *
     * @return the removed listener.
     */
    NotificationListenerInfo removeListenerImpl(
            final INotificationListener listener, final int userid) {
        NotificationListenerInfo listenerInfo = null;
        synchronized (mNotificationList) {
            final int N = mListeners.size();
            for (int i=N-1; i>=0; i--) {
                final NotificationListenerInfo info = mListeners.get(i);
                if (info.listener.asBinder() == listener.asBinder()
                        && info.userid == userid) {
                    listenerInfo = mListeners.remove(i);
                }
            }
        }
        return listenerInfo;
    }

    void showNextToastLocked() {
        ToastRecord record = mToastQueue.get(0);
        while (record != null) {
            if (DBG) Slog.d(TAG, "Show pkg=" + record.pkg + " callback=" + record.callback);
            try {
                record.callback.show();
                scheduleTimeoutLocked(record);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Object died trying to show notification " + record.callback
                        + " in package " + record.pkg);
                // remove it from the list and let the process die
                int index = mToastQueue.indexOf(record);
                if (index >= 0) {
                    mToastQueue.remove(index);
                }
                keepProcessAliveLocked(record.pid);
                if (mToastQueue.size() > 0) {
                    record = mToastQueue.get(0);
                } else {
                    record = null;
                }
            }
        }
    }

    void cancelToastLocked(int index) {
        ToastRecord record = mToastQueue.get(index);
        try {
            record.callback.hide();
        } catch (RemoteException e) {
            Slog.w(TAG, "Object died trying to hide notification " + record.callback
                    + " in package " + record.pkg);
            // don't worry about this, we're about to remove it from
            // the list anyway
        }
        mToastQueue.remove(index);
        keepProcessAliveLocked(record.pid);
        if (mToastQueue.size() > 0) {
            // Show the next one. If the callback fails, this will remove
            // it from the list, so don't assume that the list hasn't changed
            // after this point.
            showNextToastLocked();
        }
    }

    private void scheduleTimeoutLocked(ToastRecord r)
    {
        mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(mHandler, MESSAGE_TIMEOUT, r);
        long delay = r.duration == Toast.LENGTH_LONG ? LONG_DELAY : SHORT_DELAY;
        mHandler.sendMessageDelayed(m, delay);
    }

    private void handleTimeout(ToastRecord record)
    {
        if (DBG) Slog.d(TAG, "Timeout pkg=" + record.pkg + " callback=" + record.callback);
        synchronized (mToastQueue) {
            int index = indexOfToastLocked(record.pkg, record.callback);
            if (index >= 0) {
                cancelToastLocked(index);
            }
        }
    }

    // lock on mToastQueue
    int indexOfToastLocked(String pkg, ITransientNotification callback)
    {
        IBinder cbak = callback.asBinder();
        ArrayList<ToastRecord> list = mToastQueue;
        int len = list.size();
        for (int i=0; i<len; i++) {
            ToastRecord r = list.get(i);
            if (r.pkg.equals(pkg) && r.callback.asBinder() == cbak) {
                return i;
            }
        }
        return -1;
    }

    // lock on mToastQueue
    void keepProcessAliveLocked(int pid)
    {
        int toastCount = 0; // toasts from this pid
        ArrayList<ToastRecord> list = mToastQueue;
        int N = list.size();
        for (int i=0; i<N; i++) {
            ToastRecord r = list.get(i);
            if (r.pid == pid) {
                toastCount++;
            }
        }
        try {
            mAm.setProcessForeground(mForegroundToken, pid, toastCount > 0);
        } catch (RemoteException e) {
            // Shouldn't happen.
        }
    }

    private final class WorkerHandler extends Handler
    {
        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                case MESSAGE_TIMEOUT:
                    handleTimeout((ToastRecord)msg.obj);
                    break;
            }
        }
    }


    // Notifications
    // ============================================================================
    static int clamp(int x, int low, int high) {
        return (x < low) ? low : ((x > high) ? high : x);
    }

    void sendAccessibilityEvent(Notification notification, CharSequence packageName) {
        AccessibilityManager manager = AccessibilityManager.getInstance(getContext());
        if (!manager.isEnabled()) {
            return;
        }

        AccessibilityEvent event =
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(packageName);
        event.setClassName(Notification.class.getName());
        event.setParcelableData(notification);
        CharSequence tickerText = notification.tickerText;
        if (!TextUtils.isEmpty(tickerText)) {
            event.getText().add(tickerText);
        }

        manager.sendAccessibilityEvent(event);
    }

    private void cancelNotificationLocked(NotificationRecord r, boolean sendDelete, int reason) {
        // tell the app
        if (sendDelete) {
            if (r.getNotification().deleteIntent != null) {
                try {
                    r.getNotification().deleteIntent.send();
                } catch (PendingIntent.CanceledException ex) {
                    // do nothing - there's no relevant way to recover, and
                    //     no reason to let this propagate
                    Slog.w(TAG, "canceled PendingIntent for " + r.sbn.getPackageName(), ex);
                }
            }
        }

        // status bar
        if (r.getNotification().icon != 0) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mStatusBar.removeNotification(r.statusBarKey);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            r.statusBarKey = null;
            notifyRemovedLocked(r);
        }

        // sound
        if (mSoundNotification == r) {
            mSoundNotification = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                if (player != null) {
                    player.stopAsync();
                }
            } catch (RemoteException e) {
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        // vibrate
        if (mVibrateNotification == r) {
            mVibrateNotification = null;
            long identity = Binder.clearCallingIdentity();
            try {
                mVibrator.cancel();
            }
            finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        // light
        mLights.remove(r);
        if (mLedNotification == r) {
            mLedNotification = null;
        }

        // Record usage stats
        switch (reason) {
            case REASON_DELEGATE_CANCEL:
            case REASON_DELEGATE_CANCEL_ALL:
            case REASON_LISTENER_CANCEL:
            case REASON_LISTENER_CANCEL_ALL:
                mUsageStats.registerDismissedByUser(r);
                break;
            case REASON_NOMAN_CANCEL:
            case REASON_NOMAN_CANCEL_ALL:
                mUsageStats.registerRemovedByApp(r);
                break;
            case REASON_DELEGATE_CLICK:
                mUsageStats.registerCancelDueToClick(r);
                break;
            default:
                mUsageStats.registerCancelUnknown(r);
                break;
        }

        // Save it for users of getHistoricalNotifications()
        mArchive.record(r.sbn);
    }

    /**
     * Cancels a notification ONLY if it has all of the {@code mustHaveFlags}
     * and none of the {@code mustNotHaveFlags}.
     */
    void cancelNotification(final int callingUid, final int callingPid,
            final String pkg, final String tag, final int id,
            final int mustHaveFlags, final int mustNotHaveFlags, final boolean sendDelete,
            final int userId, final int reason, final NotificationListenerInfo listener) {
        // In enqueueNotificationInternal notifications are added by scheduling the
        // work on the worker handler. Hence, we also schedule the cancel on this
        // handler to avoid a scenario where an add notification call followed by a
        // remove notification call ends up in not removing the notification.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, id, tag, userId,
                        mustHaveFlags, mustNotHaveFlags, reason,
                        listener == null ? null : listener.component.toShortString());

                synchronized (mNotificationList) {
                    int index = indexOfNotificationLocked(pkg, tag, id, userId);
                    if (index >= 0) {
                        NotificationRecord r = mNotificationList.get(index);

                        // Ideally we'd do this in the caller of this method. However, that would
                        // require the caller to also find the notification.
                        if (reason == REASON_DELEGATE_CLICK) {
                            mUsageStats.registerClickedByUser(r);
                        }

                        if ((r.getNotification().flags & mustHaveFlags) != mustHaveFlags) {
                            return;
                        }
                        if ((r.getNotification().flags & mustNotHaveFlags) != 0) {
                            return;
                        }

                        mNotificationList.remove(index);
                        mNotificationsByKey.remove(r.sbn.getKey());

                        cancelNotificationLocked(r, sendDelete, reason);
                        updateLightsLocked();
                    }
                }
            }
        });
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard).
     */
    private boolean notificationMatchesUserId(NotificationRecord r, int userId) {
        return
                // looking for USER_ALL notifications? match everything
                   userId == UserHandle.USER_ALL
                // a notification sent to USER_ALL matches any query
                || r.getUserId() == UserHandle.USER_ALL
                // an exact user match
                || r.getUserId() == userId;
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard) or
     * because it matches one of the users profiles.
     */
    private boolean notificationMatchesCurrentProfiles(NotificationRecord r, int userId) {
        return notificationMatchesUserId(r, userId)
                || isCurrentProfile(r.getUserId());
    }

    /**
     * Cancels all notifications from a given package that have all of the
     * {@code mustHaveFlags}.
     */
    boolean cancelAllNotificationsInt(int callingUid, int callingPid, String pkg, int mustHaveFlags,
            int mustNotHaveFlags, boolean doit, int userId, int reason,
            NotificationListenerInfo listener) {
        EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                pkg, userId, mustHaveFlags, mustNotHaveFlags, reason,
                listener == null ? null : listener.component.toShortString());

        synchronized (mNotificationList) {
            final int N = mNotificationList.size();
            boolean canceledSomething = false;
            for (int i = N-1; i >= 0; --i) {
                NotificationRecord r = mNotificationList.get(i);
                if (!notificationMatchesUserId(r, userId)) {
                    continue;
                }
                // Don't remove notifications to all, if there's no package name specified
                if (r.getUserId() == UserHandle.USER_ALL && pkg == null) {
                    continue;
                }
                if ((r.getFlags() & mustHaveFlags) != mustHaveFlags) {
                    continue;
                }
                if ((r.getFlags() & mustNotHaveFlags) != 0) {
                    continue;
                }
                if (pkg != null && !r.sbn.getPackageName().equals(pkg)) {
                    continue;
                }
                canceledSomething = true;
                if (!doit) {
                    return true;
                }
                mNotificationList.remove(i);
                mNotificationsByKey.remove(r.sbn.getKey());
                cancelNotificationLocked(r, false, reason);
            }
            if (canceledSomething) {
                updateLightsLocked();
            }
            return canceledSomething;
        }
    }



    // Return true if the UID is a system or phone UID and therefore should not have
    // any notifications or toasts blocked.
    boolean isUidSystem(int uid) {
        final int appid = UserHandle.getAppId(uid);
        return (appid == Process.SYSTEM_UID || appid == Process.PHONE_UID || uid == 0);
    }

    // same as isUidSystem(int, int) for the Binder caller's UID.
    boolean isCallerSystem() {
        return isUidSystem(Binder.getCallingUid());
    }

    void checkCallerIsSystem() {
        if (isCallerSystem()) {
            return;
        }
        throw new SecurityException("Disallowed call for uid " + Binder.getCallingUid());
    }

    void checkCallerIsSystemOrSameApp(String pkg) {
        if (isCallerSystem()) {
            return;
        }
        final int uid = Binder.getCallingUid();
        try {
            ApplicationInfo ai = AppGlobals.getPackageManager().getApplicationInfo(
                    pkg, 0, UserHandle.getCallingUserId());
            if (!UserHandle.isSameApp(ai.uid, uid)) {
                throw new SecurityException("Calling uid " + uid + " gave package"
                        + pkg + " which is owned by uid " + ai.uid);
            }
        } catch (RemoteException re) {
            throw new SecurityException("Unknown package " + pkg + "\n" + re);
        }
    }

    void cancelAllLocked(int callingUid, int callingPid, int userId, int reason,
            NotificationListenerInfo listener, boolean includeCurrentProfiles) {
        EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                null, userId, 0, 0, reason,
                listener == null ? null : listener.component.toShortString());

        final int N = mNotificationList.size();
        for (int i=N-1; i>=0; i--) {
            NotificationRecord r = mNotificationList.get(i);
            if (includeCurrentProfiles) {
                if (!notificationMatchesCurrentProfiles(r, userId)) {
                    continue;
                }
            } else {
                if (!notificationMatchesUserId(r, userId)) {
                    continue;
                }
            }

            if ((r.getFlags() & (Notification.FLAG_ONGOING_EVENT
                            | Notification.FLAG_NO_CLEAR)) == 0) {
                mNotificationList.remove(i);
                mNotificationsByKey.remove(r.sbn.getKey());
                cancelNotificationLocked(r, true, reason);
            }
        }
        updateLightsLocked();
    }

    // lock on mNotificationList
    void updateLightsLocked()
    {
        // handle notification lights
        if (mLedNotification == null) {
            // get next notification, if any
            int n = mLights.size();
            if (n > 0) {
                mLedNotification = mLights.get(n-1);
            }
        }

        // Don't flash while we are in a call or screen is on
        if (mLedNotification == null || mInCall || mScreenOn) {
            mNotificationLight.turnOff();
        } else {
            final Notification ledno = mLedNotification.sbn.getNotification();
            int ledARGB = ledno.ledARGB;
            int ledOnMS = ledno.ledOnMS;
            int ledOffMS = ledno.ledOffMS;
            if ((ledno.defaults & Notification.DEFAULT_LIGHTS) != 0) {
                ledARGB = mDefaultNotificationColor;
                ledOnMS = mDefaultNotificationLedOn;
                ledOffMS = mDefaultNotificationLedOff;
            }
            if (mNotificationPulseEnabled) {
                // pulse repeatedly
                mNotificationLight.setFlashing(ledARGB, Light.LIGHT_FLASH_TIMED,
                        ledOnMS, ledOffMS);
            }
        }
    }

    // lock on mNotificationList
    int indexOfNotificationLocked(String pkg, String tag, int id, int userId)
    {
        ArrayList<NotificationRecord> list = mNotificationList;
        final int len = list.size();
        for (int i=0; i<len; i++) {
            NotificationRecord r = list.get(i);
            if (!notificationMatchesUserId(r, userId) || r.sbn.getId() != id) {
                continue;
            }
            if (tag == null) {
                if (r.sbn.getTag() != null) {
                    continue;
                }
            } else {
                if (!tag.equals(r.sbn.getTag())) {
                    continue;
                }
            }
            if (r.sbn.getPackageName().equals(pkg)) {
                return i;
            }
        }
        return -1;
    }

    private void updateNotificationPulse() {
        synchronized (mNotificationList) {
            updateLightsLocked();
        }
    }

    private void updateZenMode() {
        final int mode = Settings.Global.getInt(getContext().getContentResolver(),
                Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
        if (mode != mZenMode) {
            Slog.d(TAG, String.format("updateZenMode: %s -> %s",
                    Settings.Global.zenModeToString(mZenMode),
                    Settings.Global.zenModeToString(mode)));
        }
        mZenMode = mode;

        final String[] exceptionPackages = null; // none (for now)

        // call restrictions
        final boolean muteCalls = mZenMode != Settings.Global.ZEN_MODE_OFF;
        mAppOps.setRestriction(AppOpsManager.OP_VIBRATE, AudioManager.STREAM_RING,
                muteCalls ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);
        mAppOps.setRestriction(AppOpsManager.OP_PLAY_AUDIO, AudioManager.STREAM_RING,
                muteCalls ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);

        // alarm restrictions
        final boolean muteAlarms = false; // TODO until we save user config
        mAppOps.setRestriction(AppOpsManager.OP_VIBRATE, AudioManager.STREAM_ALARM,
                muteAlarms ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);
        mAppOps.setRestriction(AppOpsManager.OP_PLAY_AUDIO, AudioManager.STREAM_ALARM,
                muteAlarms ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);

        // restrict vibrations with no hints
        mAppOps.setRestriction(AppOpsManager.OP_VIBRATE, AudioManager.USE_DEFAULT_STREAM_TYPE,
                (muteAlarms || muteCalls) ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);
    }

    private void updateCurrentProfilesCache(Context context) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager != null) {
            int currentUserId = ActivityManager.getCurrentUser();
            List<UserInfo> profiles = userManager.getProfiles(currentUserId);
            synchronized (mCurrentProfiles) {
                mCurrentProfiles.clear();
                for (UserInfo user : profiles) {
                    mCurrentProfiles.put(user.id, user);
                }
            }
        }
    }

    private int[] getCurrentProfileIds() {
        synchronized (mCurrentProfiles) {
            int[] users = new int[mCurrentProfiles.size()];
            final int N = mCurrentProfiles.size();
            for (int i = 0; i < N; ++i) {
                users[i] = mCurrentProfiles.keyAt(i);
            }
            return users;
        }
    }

    private boolean isCurrentProfile(int userId) {
        synchronized (mCurrentProfiles) {
            return mCurrentProfiles.get(userId) != null;
        }
    }

    private boolean isCall(String pkg, Notification n) {
        return CALL_PACKAGES.contains(pkg);
    }

    private boolean isAlarm(String pkg, Notification n) {
        return ALARM_PACKAGES.contains(pkg);
    }

    private boolean shouldIntercept(String pkg, Notification n) {
        if (mZenMode != Settings.Global.ZEN_MODE_OFF) {
            return !isAlarm(pkg, n);
        }
        return false;
    }
}