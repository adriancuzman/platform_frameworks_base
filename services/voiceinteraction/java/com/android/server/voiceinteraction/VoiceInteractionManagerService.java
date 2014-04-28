/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionService;
import android.service.voice.IVoiceInteractionSession;
import android.util.Slog;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;
import com.android.server.UiThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;


/**
 * SystemService that publishes an IVoiceInteractionManagerService.
 */
public class VoiceInteractionManagerService extends SystemService {

    static final String TAG = "VoiceInteractionManagerService";

    final Context mContext;
    final ContentResolver mResolver;

    public VoiceInteractionManagerService(Context context) {
        super(context);
        mContext = context;
        mResolver = context.getContentResolver();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.VOICE_INTERACTION_MANAGER_SERVICE, mServiceStub);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            mServiceStub.systemRunning(isSafeMode());
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        mServiceStub.switchUser(userHandle);
    }

    // implementation entry point and binder service
    private final VoiceInteractionManagerServiceStub mServiceStub
            = new VoiceInteractionManagerServiceStub();

    class VoiceInteractionManagerServiceStub extends IVoiceInteractionManagerService.Stub {

        VoiceInteractionManagerServiceImpl mImpl;

        private boolean mSafeMode;
        private int mCurUser;

        public void systemRunning(boolean safeMode) {
            mSafeMode = safeMode;

            mPackageMonitor.register(mContext, BackgroundThread.getHandler().getLooper(),
                    UserHandle.ALL, true);
            new SettingsObserver(UiThread.getHandler());

            synchronized (this) {
                mCurUser = ActivityManager.getCurrentUser();
                switchImplementationIfNeededLocked();
            }
        }

        public void switchUser(int userHandle) {
            synchronized (this) {
                mCurUser = userHandle;
                switchImplementationIfNeededLocked();
            }
        }

        void switchImplementationIfNeededLocked() {
            if (!mSafeMode) {
                String curService = Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.VOICE_INTERACTION_SERVICE, mCurUser);
                ComponentName serviceComponent = null;
                if (curService != null && !curService.isEmpty()) {
                    try {
                        serviceComponent = ComponentName.unflattenFromString(curService);
                    } catch (RuntimeException e) {
                        Slog.wtf(TAG, "Bad voice interaction service name " + curService, e);
                        serviceComponent = null;
                    }
                }
                if (mImpl == null || mImpl.mUser != mCurUser
                        || !mImpl.mComponent.equals(serviceComponent)) {
                    if (mImpl != null) {
                        mImpl.shutdownLocked();
                    }
                    if (serviceComponent != null) {
                        mImpl = new VoiceInteractionManagerServiceImpl(mContext,
                                UiThread.getHandler(), this, mCurUser, serviceComponent);
                        mImpl.startLocked();
                    } else {
                        mImpl = null;
                    }
                }
            }
        }

        @Override
        public void startVoiceActivity(Intent intent, String resolvedType,
                IVoiceInteractionService service, Bundle args) {
            synchronized (this) {
                if (mImpl == null || service.asBinder() != mImpl.mService.asBinder()) {
                    throw new SecurityException(
                            "Caller is not the current voice interaction service");
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    mImpl.startVoiceActivityLocked(callingPid, callingUid,
                            intent, resolvedType, args);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }
        }

        @Override
        public int deliverNewSession(IBinder token, IVoiceInteractionSession session,
                IVoiceInteractor interactor) {
            synchronized (this) {
                if (mImpl == null) {
                    Slog.w(TAG, "deliverNewSession without running voice interaction service");
                    return ActivityManager.START_CANCELED;
                }
                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long caller = Binder.clearCallingIdentity();
                try {
                    return mImpl.deliverNewSessionLocked(callingPid, callingUid, token, session,
                            interactor);
                } finally {
                    Binder.restoreCallingIdentity(caller);
                }
            }

        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump PowerManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }
            synchronized (this) {
                pw.println("VOICE INTERACTION MANAGER (dumpsys voiceinteraction)\n");
                if (mImpl == null) {
                    pw.println("  (No active implementation)");
                    return;
                }
                mImpl.dumpLocked(fd, pw, args);
            }
        }

        class SettingsObserver extends ContentObserver {
            SettingsObserver(Handler handler) {
                super(handler);
                ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(Settings.Secure.getUriFor(
                        Settings.Secure.VOICE_INTERACTION_SERVICE), false, this);
            }

            @Override public void onChange(boolean selfChange) {
                synchronized (VoiceInteractionManagerServiceStub.this) {
                    switchImplementationIfNeededLocked();
                }
            }
        }

        PackageMonitor mPackageMonitor = new PackageMonitor() {
            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
                return super.onHandleForceStop(intent, packages, uid, doit);
            }

            @Override
            public void onHandleUserStop(Intent intent, int userHandle) {
                super.onHandleUserStop(intent, userHandle);
            }

            @Override
            public void onPackageDisappeared(String packageName, int reason) {
                super.onPackageDisappeared(packageName, reason);
            }

            @Override
            public void onPackageAppeared(String packageName, int reason) {
                super.onPackageAppeared(packageName, reason);
            }

            @Override
            public void onPackageModified(String packageName) {
                super.onPackageModified(packageName);
            }

            @Override
            public void onSomePackagesChanged() {
                super.onSomePackagesChanged();
            }
        };
    }
}