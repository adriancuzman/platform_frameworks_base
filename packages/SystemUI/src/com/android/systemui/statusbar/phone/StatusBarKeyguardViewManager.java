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
import android.os.RemoteException;
import android.util.Slog;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;

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

    private ViewGroup mContainer;
    private StatusBarWindowManager mStatusBarWindowManager;

    private boolean mScreenOn = false;
    private KeyguardBouncer mBouncer;
    private boolean mShowing;
    private boolean mOccluded;

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
        mBouncer = new KeyguardBouncer(mContext, mViewMediatorCallback, mLockPatternUtils,
                mStatusBarWindowManager, container);
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public void show(Bundle options) {
        mShowing = true;
        mStatusBarWindowManager.setKeyguardShowing(true);
        showBouncerOrKeyguard();
        updateStates();
    }

    /**
     * Shows the notification keyguard or the bouncer depending on
     * {@link KeyguardBouncer#needsFullscreenBouncer()}.
     */
    private void showBouncerOrKeyguard() {
        if (mBouncer.needsFullscreenBouncer()) {

            // The keyguard might be showing (already). So we need to hide it.
            mPhoneStatusBar.hideKeyguard();
            mBouncer.show();
        } else {
            mPhoneStatusBar.showKeyguard();
            mBouncer.hide();
            mBouncer.prepare();
        }
    }

    private void showBouncer() {
        mBouncer.show();
        updateStates();
    }

    /**
     * Reset the state of the view.
     */
    public void reset() {
        showBouncerOrKeyguard();
        updateStates();
    }

    public void onScreenTurnedOff() {
        mScreenOn = false;
        mBouncer.onScreenTurnedOff();
    }

    public void onScreenTurnedOn(final IKeyguardShowCallback callback) {
        mScreenOn = true;
        if (callback != null) {
            callbackAfterDraw(callback);
        }
    }

    private void callbackAfterDraw(final IKeyguardShowCallback callback) {
        mContainer.post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onShown(mContainer.getWindowToken());
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception calling onShown():", e);
                }
            }
        });
    }

    public void verifyUnlock() {
        dismiss();
    }

    public void setNeedsInput(boolean needsInput) {
        mStatusBarWindowManager.setKeyguardNeedsInput(needsInput);
    }

    public void updateUserActivityTimeout() {
        mStatusBarWindowManager.setKeyguardUserActivityTimeout(mBouncer.getUserActivityTimeout());
    }

    public void setOccluded(boolean occluded) {
        mOccluded = occluded;
        mStatusBarWindowManager.setKeyguardOccluded(occluded);
        updateStates();
    }

    /**
     * Hides the keyguard view
     */
    public void hide() {
        mShowing = false;
        mPhoneStatusBar.hideKeyguard();
        mStatusBarWindowManager.setKeyguardShowing(false);
        mBouncer.hide();
        mViewMediatorCallback.keyguardGone();
        updateStates();
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    public void dismiss() {
        if (mScreenOn) {
            showBouncer();
        }
    }

    public boolean isSecure() {
        return mBouncer.isSecure();
    }

    /**
     * @return Whether the keyguard is showing
     */
    public boolean isShowing() {
        return mShowing;
    }

    /**
     * Notifies this manager that the back button has been pressed.
     *
     * @return whether the back press has been handled
     */
    public boolean onBackPressed() {
        if (mBouncer.isShowing()) {
            mBouncer.hide();
            mPhoneStatusBar.showKeyguard();
            updateStates();
            return true;
        }
        return false;
    }

    private void updateStates() {
        int vis = mContainer.getSystemUiVisibility();
        boolean bouncerDismissable = mBouncer.isShowing() && !mBouncer.needsFullscreenBouncer();
        if (bouncerDismissable || !mShowing) {
            mContainer.setSystemUiVisibility(vis & ~View.STATUS_BAR_DISABLE_BACK);
        } else {
            mContainer.setSystemUiVisibility(vis | View.STATUS_BAR_DISABLE_BACK);
        }
        if (mPhoneStatusBar.getNavigationBarView() != null) {
            if (!(mShowing && !mOccluded) || mBouncer.isShowing()) {
                mPhoneStatusBar.getNavigationBarView().setVisibility(View.VISIBLE);
            } else {
                mPhoneStatusBar.getNavigationBarView().setVisibility(View.GONE);
            }
        }
        mPhoneStatusBar.setBouncerShowing(mBouncer.isShowing());
    }

    public boolean onMenuPressed() {
        return mBouncer.onMenuPressed();
    }
}