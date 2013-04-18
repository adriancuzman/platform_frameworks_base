/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.transitiontests;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.transition.Scene;
import android.widget.TextView;
import android.view.transition.Fade;
import android.view.transition.Recolor;
import android.view.transition.Slide;
import android.view.transition.Transition;
import android.view.transition.TransitionGroup;
import android.view.transition.TransitionManager;


public class LoginActivity extends Activity {
    ViewGroup mSceneRoot;
    Scene mCurrentScene;
    TransitionManager mTransitionManager;
    Scene mLoginScene, mPasswordScene, mIncorrectPasswordScene, mSuccessScene, mUsernameTakenScene,
            mNewUserScene;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mLoginScene = new Scene(mSceneRoot, R.layout.activity_login, this);
        mPasswordScene = new Scene(mSceneRoot, R.layout.login_password, this);
        mIncorrectPasswordScene = new Scene(mSceneRoot, R.layout.incorrect_password, this);
        mUsernameTakenScene = new Scene(mSceneRoot, R.layout.username_taken, this);
        mSuccessScene = new Scene(mSceneRoot, R.layout.success, this);
        mNewUserScene = new Scene(mSceneRoot, R.layout.new_user, this);

        mTransitionManager = new TransitionManager();

        // Custom transitions in/out of NewUser screen - slide in the 2nd password UI
        TransitionGroup slider = new TransitionGroup();
        slider.addTransitions(new Slide().setTargetIds(R.id.retype, R.id.retypeEdit));
        slider.addTransitions(new Recolor().setTargetIds(R.id.password, R.id.passwordEdit));
        slider.addTransitions(new Fade());
        mTransitionManager.setTransition(mLoginScene, mNewUserScene, slider);
        mTransitionManager.setTransition(mPasswordScene, mNewUserScene, slider);
        mTransitionManager.setTransition(mNewUserScene, mLoginScene, slider);
        mTransitionManager.setTransition(mNewUserScene, mPasswordScene, slider);

        // Custom transitions with recoloring password field
        Transition colorizer = new Recolor().setTargetIds(R.id.password, R.id.passwordEdit);
        mTransitionManager.setTransition(mLoginScene, mPasswordScene, colorizer);
        mTransitionManager.setTransition(mPasswordScene, mLoginScene, colorizer);

        mCurrentScene = mLoginScene;
        mSceneRoot.setCurrentScene(mLoginScene);
    }

    public void applyScene(Scene scene) {
        mTransitionManager.transitionTo(scene);
        mCurrentScene = scene;
    }

    public void sendMessage(View view) {
        TextView textView = (TextView) view;
        CharSequence text = textView.getText();
        if (text.equals("Cancel")) {
            applyScene(mLoginScene);
        } else if (text.equals("Submit")) {
            if (mCurrentScene == mLoginScene) {
                applyScene(mPasswordScene);
            } else if (mCurrentScene == mPasswordScene) {
                applyScene(Math.random() < .5 ? mSuccessScene : mIncorrectPasswordScene);
            } else if (mCurrentScene == mNewUserScene) {
                applyScene(Math.random() < .5 ? mSuccessScene : mUsernameTakenScene);
            }
        } else if (text.equals("New User?")) {
            applyScene(mNewUserScene);
        } else if (text.equals("Okay")) {
            if (mCurrentScene == mIncorrectPasswordScene) {
                applyScene(mPasswordScene);
            } else { // username taken scene
                applyScene(mNewUserScene);
            }
        } else if (text.equals("Reset")) {
            applyScene(mLoginScene);
        }
    }
}
