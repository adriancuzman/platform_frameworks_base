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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSimpleHostView;
import com.android.keyguard.R;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.keyguard.KeyguardViewMediator;

/**
 * Manages creating, showing, hiding and resetting the keyguard within the status bar. Calls back
 * via {@link ViewMediatorCallback} to poke the wake lock and report that the keyguard is done,
 * which is in turn, reported to this class by the current
 * {@link com.android.keyguard.KeyguardViewBase}.
 */
public class StatusBarKeyguardViewManager {
    private static String TAG = "StatusBarKeyguardViewManager";

    private final Context mContext;

    private LockPatternUtils mLockPatternUtils;
    private ViewMediatorCallback mViewMediatorCallback;
    private PhoneStatusBar mPhoneStatusBar;

    private KeyguardSimpleHostView mKeyguardView;
    private ViewGroup mRoot;
    private ViewGroup mContainer;
    private StatusBarWindowManager mStatusBarWindowManager;

    private boolean mScreenOn = false;
    private boolean mShowOnRegister;

    public StatusBarKeyguardViewManager(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        mContext = context;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;

    }

    public void registerStatusBar(PhoneStatusBar phoneStatusBar,
            ViewGroup container, StatusBarWindowManager statusBarWindowManager) {
        mPhoneStatusBar = phoneStatusBar;
        mContainer = container;
        mStatusBarWindowManager = statusBarWindowManager;
        if (mShowOnRegister) {
            mShowOnRegister = false;
            show(null);
            if (mScreenOn) {
                onScreenTurnedOn(null);
            }
        }
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public void show(Bundle options) {
        if (mStatusBarWindowManager != null) {
            ensureView();
            mStatusBarWindowManager.setKeyguardShowing(true);
            mKeyguardView.requestFocus();
        } else {
            mShowOnRegister = true;
        }
    }

    private void ensureView() {
        if (mRoot == null) {
            inflateView();
        }
    }

    private void inflateView() {
        removeView();
        mRoot = (ViewGroup) LayoutInflater.from(mContext).inflate(R.layout.keyguard_bouncer, null);
        mKeyguardView = (KeyguardSimpleHostView) mRoot.findViewById(R.id.keyguard_host_view);
        mKeyguardView.setLockPatternUtils(mLockPatternUtils);
        mKeyguardView.setViewMediatorCallback(mViewMediatorCallback);
        mContainer.addView(mRoot, mContainer.getChildCount());
        mRoot.setSystemUiVisibility(View.STATUS_BAR_DISABLE_HOME);
    }

    private void removeView() {
        if (mRoot != null && mRoot.getParent() == mContainer) {
            mContainer.removeView(mRoot);
            mRoot = null;
        }
    }

    /**
     * Reset the state of the view.
     */
    public void reset() {
        inflateView();
    }

    public void onScreenTurnedOff() {
        mScreenOn = false;
        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOff();
        }
    }

    public void onScreenTurnedOn(final IKeyguardShowCallback callback) {
        mScreenOn = true;
        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOn();
            if (callback != null) {
                callbackAfterDraw(callback);
            }
        } else {
            try {
                if (callback != null) {
                    callback.onShown(null);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception calling onShown():", e);
            }
        }
    }

    private void callbackAfterDraw(final IKeyguardShowCallback callback) {
        mKeyguardView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onShown(mKeyguardView.getWindowToken());
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception calling onShown():", e);
                }
            }
        });
    }

    public void verifyUnlock() {
        show(null);
        mKeyguardView.verifyUnlock();
    }

    public void setNeedsInput(boolean needsInput) {
        if (mStatusBarWindowManager != null) {
            mStatusBarWindowManager.setKeyguardNeedsInput(needsInput);
        }
    }

    public void updateUserActivityTimeout() {

        // Use the user activity timeout requested by the keyguard view, if any.
        if (mKeyguardView != null) {
            long timeout = mKeyguardView.getUserActivityTimeout();
            if (timeout >= 0) {
                mStatusBarWindowManager.setKeyguardUserActivityTimeout(timeout);
                return;
            }
        }

        // Otherwise, use the default timeout.
        mStatusBarWindowManager.setKeyguardUserActivityTimeout(
                KeyguardViewMediator.AWAKE_INTERVAL_DEFAULT_MS);
    }

    public void setOccluded(boolean occluded) {
        if (mStatusBarWindowManager != null) {
            mStatusBarWindowManager.setKeyguardOccluded(occluded);
        }
    }

    /**
     * Hides the keyguard view
     */
    public void hide() {
        if (mPhoneStatusBar != null) {
            mStatusBarWindowManager.setKeyguardShowing(false);
            if (mKeyguardView != null) {
                mKeyguardView.cleanUp();
                mViewMediatorCallback.keyguardGone();
            }
            removeView();
        }
        mShowOnRegister = false;
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    public void dismiss() {
        if (mScreenOn) {
            mKeyguardView.dismiss();
        }
    }

    /**
     * @return Whether the keyguard is showing
     */
    public boolean isShowing() {
        return mRoot != null && mRoot.getVisibility() == View.VISIBLE;
    }
}
