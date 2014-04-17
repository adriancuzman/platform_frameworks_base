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
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.systemui.R;
import com.android.systemui.recents.model.Task;


/* The task bar view */
class TaskBarView extends FrameLayout {
    Task mTask;

    ImageView mApplicationIcon;
    ImageView mActivityIcon;
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
        mActivityIcon = (ImageView) findViewById(R.id.activity_icon);
        mActivityDescription = (TextView) findViewById(R.id.activity_description);
    }

    /** Binds the bar view to the task */
    void rebindToTask(Task t, boolean animate) {
        mTask = t;
        if (t.applicationIcon != null) {
            mApplicationIcon.setImageDrawable(t.applicationIcon);
            mActivityDescription.setText(t.activityLabel);
            if (t.activityIcon != null) {
                mActivityIcon.setImageBitmap(t.activityIcon);
                mActivityIcon.setVisibility(View.VISIBLE);
            }
            if (animate) {
                // XXX: Investigate how expensive it will be to create a second bitmap and crossfade
            }
        }
    }

    /** Unbinds the bar view from the task */
    void unbindFromTask() {
        mTask = null;
        mApplicationIcon.setImageDrawable(null);
        mActivityIcon.setImageBitmap(null);
        mActivityIcon.setVisibility(View.INVISIBLE);
        mActivityDescription.setText("");
    }
}