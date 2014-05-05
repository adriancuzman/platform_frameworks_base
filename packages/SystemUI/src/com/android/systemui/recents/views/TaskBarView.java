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

package com.android.systemui.recents.views;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.Utilities;
import com.android.systemui.recents.model.Task;


/* The task bar view */
class TaskBarView extends FrameLayout {
    Task mTask;

    ImageView mApplicationIcon;
    TextView mActivityDescription;

    public TaskBarView(Context context) {
        this(context, null);
    }

    public TaskBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskBarView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        // Initialize the icon and description views
        mApplicationIcon = (ImageView) findViewById(R.id.application_icon);
        mActivityDescription = (TextView) findViewById(R.id.activity_description);
    }

    /** Binds the bar view to the task */
    void rebindToTask(Task t, boolean animate) {
        RecentsConfiguration configuration = RecentsConfiguration.getInstance();
        mTask = t;
        // If an activity icon is defined, then we use that as the primary icon to show in the bar,
        // otherwise, we fall back to the application icon
        if (t.activityIcon != null) {
            mApplicationIcon.setImageDrawable(t.activityIcon);
        } else if (t.applicationIcon != null) {
            mApplicationIcon.setImageDrawable(t.applicationIcon);
        }
        mActivityDescription.setText(t.activityLabel);
        // Try and apply the system ui tint
        int tint = t.colorPrimary;
        if (Constants.DebugFlags.App.EnableTaskBarThemeColors && tint != 0) {
            setBackgroundColor(tint);
            mActivityDescription.setTextColor(Utilities.getIdealTextColorForBackgroundColor(tint));
        } else {
            setBackgroundColor(configuration.taskBarViewDefaultBackgroundColor);
            mActivityDescription.setTextColor(configuration.taskBarViewDefaultTextColor);
        }
        if (animate) {
            // XXX: Investigate how expensive it will be to create a second bitmap and crossfade
        }
    }

    /** Unbinds the bar view from the task */
    void unbindFromTask() {
        mTask = null;
        mApplicationIcon.setImageDrawable(null);
        mActivityDescription.setText("");
    }
}