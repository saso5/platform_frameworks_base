/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.DOCKED_STACK_CREATE_MODE_BOTTOM_OR_RIGHT;
import static android.app.ActivityManager.DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
import static android.app.ActivityManager.RESIZE_MODE_USER;
import static android.app.ActivityManager.RESIZE_MODE_USER_FORCED;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.dipToPixel;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_FREEFORM;
import static com.android.server.wm.WindowState.MINIMUM_VISIBLE_HEIGHT_IN_DP;
import static com.android.server.wm.WindowState.MINIMUM_VISIBLE_WIDTH_IN_DP;

import android.annotation.IntDef;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.input.InputApplicationHandle;
import com.android.server.input.InputWindowHandle;
import com.android.server.wm.WindowManagerService.H;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

class TaskPositioner implements DimLayer.DimLayerUser {
    private static final boolean DEBUG_ORIENTATION_VIOLATIONS = false;
    private static final String TAG_LOCAL = "TaskPositioner";
    private static final String TAG = TAG_WITH_CLASS_NAME ? TAG_LOCAL : TAG_WM;

    // The margin the pointer position has to be within the side of the screen to be
    // considered at the side of the screen.
    static final int SIDE_MARGIN_DIP = 100;

    @IntDef(flag = true,
            value = {
                    CTRL_NONE,
                    CTRL_LEFT,
                    CTRL_RIGHT,
                    CTRL_TOP,
                    CTRL_BOTTOM
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface CtrlType {}

    private static final int CTRL_NONE   = 0x0;
    private static final int CTRL_LEFT   = 0x1;
    private static final int CTRL_RIGHT  = 0x2;
    private static final int CTRL_TOP    = 0x4;
    private static final int CTRL_BOTTOM = 0x8;

    public static final float RESIZING_HINT_ALPHA = 0.5f;

    public static final int RESIZING_HINT_DURATION_MS = 0;

    // The minimal aspect ratio which needs to be met to count as landscape (or 1/.. for portrait).
    // Note: We do not use the 1.33 from the CDD here since the user is allowed to use what ever
    // aspect he desires.
    @VisibleForTesting
    static final float MIN_ASPECT = 1.2f;

    private final WindowManagerService mService;
    private WindowPositionerEventReceiver mInputEventReceiver;
    private Display mDisplay;
    private final DisplayMetrics mDisplayMetrics = new DisplayMetrics();
    private DimLayer mDimLayer;
    @CtrlType
    private int mCurrentDimSide;
    private Rect mTmpRect = new Rect();
    private int mSideMargin;
    private int mMinVisibleWidth;
    private int mMinVisibleHeight;

    private Task mTask;
    private boolean mResizing;
    private boolean mPreserveOrientation;
    private boolean mStartOrientationWasLandscape;
    private final Rect mWindowOriginalBounds = new Rect();
    private final Rect mWindowDragBounds = new Rect();
    private final Point mMaxVisibleSize = new Point();
    private float mStartDragX;
    private float mStartDragY;
    @CtrlType
    private int mCtrlType = CTRL_NONE;
    private boolean mDragEnded = false;

    InputChannel mServerChannel;
    InputChannel mClientChannel;
    InputApplicationHandle mDragApplicationHandle;
    InputWindowHandle mDragWindowHandle;

    private final class WindowPositionerEventReceiver extends BatchedInputEventReceiver {
        public WindowPositionerEventReceiver(
                InputChannel inputChannel, Looper looper, Choreographer choreographer) {
            super(inputChannel, looper, choreographer);
        }

        @Override
        public void onInputEvent(InputEvent event, int displayId) {
            if (!(event instanceof MotionEvent)
                    || (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0) {
                return;
            }
            final MotionEvent motionEvent = (MotionEvent) event;
            boolean handled = false;

            try {
                if (mDragEnded) {
                    // The drag has ended but the clean-up message has not been processed by
                    // window manager. Drop events that occur after this until window manager
                    // has a chance to clean-up the input handle.
                    handled = true;
                    return;
                }

                final float newX = motionEvent.getRawX();
                final float newY = motionEvent.getRawY();

                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        if (DEBUG_TASK_POSITIONING) {
                            Slog.w(TAG, "ACTION_DOWN @ {" + newX + ", " + newY + "}");
                        }
                    } break;

                    case MotionEvent.ACTION_MOVE: {
                        if (DEBUG_TASK_POSITIONING){
                            Slog.w(TAG, "ACTION_MOVE @ {" + newX + ", " + newY + "}");
                        }
                        synchronized (mService.mWindowMap) {
                            mDragEnded = notifyMoveLocked(newX, newY);
                            mTask.getDimBounds(mTmpRect);
                        }
                        if (!mTmpRect.equals(mWindowDragBounds)) {
                            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                                    "wm.TaskPositioner.resizeTask");
                            try {
                                mService.mActivityManager.resizeTask(
                                        mTask.mTaskId, mWindowDragBounds, RESIZE_MODE_USER);
                            } catch (RemoteException e) {
                            }
                            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
                        }
                    } break;

                    case MotionEvent.ACTION_UP: {
                        if (DEBUG_TASK_POSITIONING) {
                            Slog.w(TAG, "ACTION_UP @ {" + newX + ", " + newY + "}");
                        }
                        mDragEnded = true;
                    } break;

                    case MotionEvent.ACTION_CANCEL: {
                        if (DEBUG_TASK_POSITIONING) {
                            Slog.w(TAG, "ACTION_CANCEL @ {" + newX + ", " + newY + "}");
                        }
                        mDragEnded = true;
                    } break;
                }

                if (mDragEnded) {
                    final boolean wasResizing = mResizing;
                    synchronized (mService.mWindowMap) {
                        endDragLocked();
                        mTask.getDimBounds(mTmpRect);
                    }
                    try {
                        if (wasResizing && !mTmpRect.equals(mWindowDragBounds)) {
                            // We were using fullscreen surface during resizing. Request
                            // resizeTask() one last time to restore surface to window size.
                            mService.mActivityManager.resizeTask(
                                    mTask.mTaskId, mWindowDragBounds, RESIZE_MODE_USER_FORCED);
                        }

                        if (mCurrentDimSide != CTRL_NONE) {
                            final int createMode = mCurrentDimSide == CTRL_LEFT
                                    ? DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT
                                    : DOCKED_STACK_CREATE_MODE_BOTTOM_OR_RIGHT;
                            mService.mActivityManager.moveTaskToDockedStack(
                                    mTask.mTaskId, createMode, true /*toTop*/, true /* animate */,
                                    null /* initialBounds */);
                        }
                    } catch(RemoteException e) {}

                    // Post back to WM to handle clean-ups. We still need the input
                    // event handler for the last finishInputEvent()!
                    mService.mH.sendEmptyMessage(H.FINISH_TASK_POSITIONING);
                }
                handled = true;
            } catch (Exception e) {
                Slog.e(TAG, "Exception caught by drag handleMotion", e);
            } finally {
                finishInputEvent(event, handled);
            }
        }
    }

    TaskPositioner(WindowManagerService service) {
        mService = service;
    }

    @VisibleForTesting
    Rect getWindowDragBounds() {
        return mWindowDragBounds;
    }

    /**
     * @param display The Display that the window being dragged is on.
     */
    void register(Display display) {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "Registering task positioner");
        }

        if (mClientChannel != null) {
            Slog.e(TAG, "Task positioner already registered");
            return;
        }

        mDisplay = display;
        mDisplay.getMetrics(mDisplayMetrics);
        final InputChannel[] channels = InputChannel.openInputChannelPair(TAG);
        mServerChannel = channels[0];
        mClientChannel = channels[1];
        mService.mInputManager.registerInputChannel(mServerChannel, null);

        mInputEventReceiver = new WindowPositionerEventReceiver(
                mClientChannel, mService.mAnimationHandler.getLooper(),
                mService.mAnimator.getChoreographer());

        mDragApplicationHandle = new InputApplicationHandle(null);
        mDragApplicationHandle.name = TAG;
        mDragApplicationHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;

        mDragWindowHandle = new InputWindowHandle(mDragApplicationHandle, null, null,
                mDisplay.getDisplayId());
        mDragWindowHandle.name = TAG;
        mDragWindowHandle.inputChannel = mServerChannel;
        mDragWindowHandle.layer = mService.getDragLayerLocked();
        mDragWindowHandle.layoutParamsFlags = 0;
        mDragWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_DRAG;
        mDragWindowHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
        mDragWindowHandle.visible = true;
        mDragWindowHandle.canReceiveKeys = false;
        mDragWindowHandle.hasFocus = true;
        mDragWindowHandle.hasWallpaper = false;
        mDragWindowHandle.paused = false;
        mDragWindowHandle.ownerPid = Process.myPid();
        mDragWindowHandle.ownerUid = Process.myUid();
        mDragWindowHandle.inputFeatures = 0;
        mDragWindowHandle.scaleFactor = 1.0f;

        // The drag window cannot receive new touches.
        mDragWindowHandle.touchableRegion.setEmpty();

        // The drag window covers the entire display
        mDragWindowHandle.frameLeft = 0;
        mDragWindowHandle.frameTop = 0;
        final Point p = new Point();
        mDisplay.getRealSize(p);
        mDragWindowHandle.frameRight = p.x;
        mDragWindowHandle.frameBottom = p.y;

        // Pause rotations before a drag.
        if (DEBUG_ORIENTATION) {
            Slog.d(TAG, "Pausing rotation during re-position");
        }
        mService.pauseRotationLocked();

        mDimLayer = new DimLayer(mService, this, mDisplay.getDisplayId(), TAG_LOCAL);
        mSideMargin = dipToPixel(SIDE_MARGIN_DIP, mDisplayMetrics);
        mMinVisibleWidth = dipToPixel(MINIMUM_VISIBLE_WIDTH_IN_DP, mDisplayMetrics);
        mMinVisibleHeight = dipToPixel(MINIMUM_VISIBLE_HEIGHT_IN_DP, mDisplayMetrics);
        mDisplay.getRealSize(mMaxVisibleSize);

        mDragEnded = false;
    }

    void unregister() {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "Unregistering task positioner");
        }

        if (mClientChannel == null) {
            Slog.e(TAG, "Task positioner not registered");
            return;
        }

        mService.mInputManager.unregisterInputChannel(mServerChannel);

        mInputEventReceiver.dispose();
        mInputEventReceiver = null;
        mClientChannel.dispose();
        mServerChannel.dispose();
        mClientChannel = null;
        mServerChannel = null;

        mDragWindowHandle = null;
        mDragApplicationHandle = null;
        mDisplay = null;

        if (mDimLayer != null) {
            mDimLayer.destroySurface();
            mDimLayer = null;
        }
        mCurrentDimSide = CTRL_NONE;
        mDragEnded = true;

        // Resume rotations after a drag.
        if (DEBUG_ORIENTATION) {
            Slog.d(TAG, "Resuming rotation after re-position");
        }
        mService.resumeRotationLocked();
    }

    void startDrag(WindowState win, boolean resize, boolean preserveOrientation, float startX,
                   float startY) {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "startDrag: win=" + win + ", resize=" + resize
                    + ", preserveOrientation=" + preserveOrientation + ", {" + startX + ", "
                    + startY + "}");
        }
        mTask = win.getTask();
        // Use the dim bounds, not the original task bounds. The cursor
        // movement should be calculated relative to the visible bounds.
        // Also, use the dim bounds of the task which accounts for
        // multiple app windows. Don't use any bounds from win itself as it
        // may not be the same size as the task.
        mTask.getDimBounds(mTmpRect);
        startDrag(resize, preserveOrientation, startX, startY, mTmpRect);
    }

    @VisibleForTesting
    void startDrag(boolean resize, boolean preserveOrientation,
                   float startX, float startY, Rect startBounds) {
        mCtrlType = CTRL_NONE;
        mStartDragX = startX;
        mStartDragY = startY;
        mPreserveOrientation = preserveOrientation;

        if (resize) {
            if (startX < startBounds.left) {
                mCtrlType |= CTRL_LEFT;
            }
            if (startX > startBounds.right) {
                mCtrlType |= CTRL_RIGHT;
            }
            if (startY < startBounds.top) {
                mCtrlType |= CTRL_TOP;
            }
            if (startY > startBounds.bottom) {
                mCtrlType |= CTRL_BOTTOM;
            }
            mResizing = mCtrlType != CTRL_NONE;
        }

        // In case of !isDockedInEffect we are using the union of all task bounds. These might be
        // made up out of multiple windows which are only partially overlapping. When that happens,
        // the orientation from the window of interest to the entire stack might diverge. However
        // for now we treat them as the same.
        mStartOrientationWasLandscape = startBounds.width() >= startBounds.height();
        mWindowOriginalBounds.set(startBounds);

        // Make sure we always have valid drag bounds even if the drag ends before any move events
        // have been handled.
        mWindowDragBounds.set(startBounds);
    }

    private void endDragLocked() {
        mResizing = false;
        mTask.setDragResizing(false, DRAG_RESIZE_MODE_FREEFORM);
    }

    /** Returns true if the move operation should be ended. */
    private boolean notifyMoveLocked(float x, float y) {
        if (DEBUG_TASK_POSITIONING) {
            Slog.d(TAG, "notifyMoveLocked: {" + x + "," + y + "}");
        }

        if (mCtrlType != CTRL_NONE) {
            resizeDrag(x, y);
            mTask.setDragResizing(true, DRAG_RESIZE_MODE_FREEFORM);
            return false;
        }

        // This is a moving or scrolling operation.
        mTask.mStack.getDimBounds(mTmpRect);

        int nX = (int) x;
        int nY = (int) y;
        if (!mTmpRect.contains(nX, nY)) {
            // For a moving operation we allow the pointer to go out of the stack bounds, but
            // use the clamped pointer position for the drag bounds computation.
            nX = Math.min(Math.max(nX, mTmpRect.left), mTmpRect.right);
            nY = Math.min(Math.max(nY, mTmpRect.top), mTmpRect.bottom);
        }

        updateWindowDragBounds(nX, nY, mTmpRect);
        updateDimLayerVisibility(nX);
        return false;
    }

    /**
     * The user is drag - resizing the window.
     *
     * @param x The x coordinate of the current drag coordinate.
     * @param y the y coordinate of the current drag coordinate.
     */
    @VisibleForTesting
    void resizeDrag(float x, float y) {
        // This is a resizing operation.
        // We need to keep various constraints:
        // 1. mMinVisible[Width/Height] <= [width/height] <= mMaxVisibleSize.[x/y]
        // 2. The orientation is kept - if required.
        final int deltaX = Math.round(x - mStartDragX);
        final int deltaY = Math.round(y - mStartDragY);
        int left = mWindowOriginalBounds.left;
        int top = mWindowOriginalBounds.top;
        int right = mWindowOriginalBounds.right;
        int bottom = mWindowOriginalBounds.bottom;

        // The aspect which we have to respect. Note that if the orientation does not need to be
        // preserved the aspect will be calculated as 1.0 which neutralizes the following
        // computations.
        final float minAspect = !mPreserveOrientation
                ? 1.0f
                : (mStartOrientationWasLandscape ? MIN_ASPECT : (1.0f / MIN_ASPECT));
        // Calculate the resulting width and height of the drag operation.
        int width = right - left;
        int height = bottom - top;
        if ((mCtrlType & CTRL_LEFT) != 0) {
            width = Math.max(mMinVisibleWidth, width - deltaX);
        } else if ((mCtrlType & CTRL_RIGHT) != 0) {
            width = Math.max(mMinVisibleWidth, width + deltaX);
        }
        if ((mCtrlType & CTRL_TOP) != 0) {
            height = Math.max(mMinVisibleHeight, height - deltaY);
        } else if ((mCtrlType & CTRL_BOTTOM) != 0) {
            height = Math.max(mMinVisibleHeight, height + deltaY);
        }

        // If we have to preserve the orientation - check that we are doing so.
        final float aspect = (float) width / (float) height;
        if (mPreserveOrientation && ((mStartOrientationWasLandscape && aspect < MIN_ASPECT)
                || (!mStartOrientationWasLandscape && aspect > (1.0 / MIN_ASPECT)))) {
            // Calculate 2 rectangles fulfilling all requirements for either X or Y being the major
            // drag axis. What ever is producing the bigger rectangle will be chosen.
            int width1;
            int width2;
            int height1;
            int height2;
            if (mStartOrientationWasLandscape) {
                // Assuming that the width is our target we calculate the height.
                width1 = Math.max(mMinVisibleWidth, Math.min(mMaxVisibleSize.x, width));
                height1 = Math.min(height, Math.round((float)width1 / MIN_ASPECT));
                if (height1 < mMinVisibleHeight) {
                    // If the resulting height is too small we adjust to the minimal size.
                    height1 = mMinVisibleHeight;
                    width1 = Math.max(mMinVisibleWidth,
                            Math.min(mMaxVisibleSize.x, Math.round((float)height1 * MIN_ASPECT)));
                }
                // Assuming that the height is our target we calculate the width.
                height2 = Math.max(mMinVisibleHeight, Math.min(mMaxVisibleSize.y, height));
                width2 = Math.max(width, Math.round((float)height2 * MIN_ASPECT));
                if (width2 < mMinVisibleWidth) {
                    // If the resulting width is too small we adjust to the minimal size.
                    width2 = mMinVisibleWidth;
                    height2 = Math.max(mMinVisibleHeight,
                            Math.min(mMaxVisibleSize.y, Math.round((float)width2 / MIN_ASPECT)));
                }
            } else {
                // Assuming that the width is our target we calculate the height.
                width1 = Math.max(mMinVisibleWidth, Math.min(mMaxVisibleSize.x, width));
                height1 = Math.max(height, Math.round((float)width1 * MIN_ASPECT));
                if (height1 < mMinVisibleHeight) {
                    // If the resulting height is too small we adjust to the minimal size.
                    height1 = mMinVisibleHeight;
                    width1 = Math.max(mMinVisibleWidth,
                            Math.min(mMaxVisibleSize.x, Math.round((float)height1 / MIN_ASPECT)));
                }
                // Assuming that the height is our target we calculate the width.
                height2 = Math.max(mMinVisibleHeight, Math.min(mMaxVisibleSize.y, height));
                width2 = Math.min(width, Math.round((float)height2 / MIN_ASPECT));
                if (width2 < mMinVisibleWidth) {
                    // If the resulting width is too small we adjust to the minimal size.
                    width2 = mMinVisibleWidth;
                    height2 = Math.max(mMinVisibleHeight,
                            Math.min(mMaxVisibleSize.y, Math.round((float)width2 * MIN_ASPECT)));
                }
            }

            // Use the bigger of the two rectangles if the major change was positive, otherwise
            // do the opposite.
            final boolean grows = width > (right - left) || height > (bottom - top);
            if (grows == (width1 * height1 > width2 * height2)) {
                width = width1;
                height = height1;
            } else {
                width = width2;
                height = height2;
            }
        }

        // Update mWindowDragBounds to the new drag size.
        updateDraggedBounds(left, top, right, bottom, width, height);
    }

    /**
     * Given the old coordinates and the new width and height, update the mWindowDragBounds.
     *
     * @param left      The original left bound before the user started dragging.
     * @param top       The original top bound before the user started dragging.
     * @param right     The original right bound before the user started dragging.
     * @param bottom    The original bottom bound before the user started dragging.
     * @param newWidth  The new dragged width.
     * @param newHeight The new dragged height.
     */
    void updateDraggedBounds(int left, int top, int right, int bottom, int newWidth,
                             int newHeight) {
        // Generate the final bounds by keeping the opposite drag edge constant.
        if ((mCtrlType & CTRL_LEFT) != 0) {
            left = right - newWidth;
        } else { // Note: The right might have changed - if we pulled at the right or not.
            right = left + newWidth;
        }
        if ((mCtrlType & CTRL_TOP) != 0) {
            top = bottom - newHeight;
        } else { // Note: The height might have changed - if we pulled at the bottom or not.
            bottom = top + newHeight;
        }

        mWindowDragBounds.set(left, top, right, bottom);

        checkBoundsForOrientationViolations(mWindowDragBounds);
    }

    /**
     * Validate bounds against orientation violations (if DEBUG_ORIENTATION_VIOLATIONS is set).
     *
     * @param bounds The bounds to be checked.
     */
    private void checkBoundsForOrientationViolations(Rect bounds) {
        // When using debug check that we are not violating the given constraints.
        if (DEBUG_ORIENTATION_VIOLATIONS) {
            if (mStartOrientationWasLandscape != (bounds.width() >= bounds.height())) {
                Slog.e(TAG, "Orientation violation detected! should be "
                        + (mStartOrientationWasLandscape ? "landscape" : "portrait")
                        + " but is the other");
            } else {
                Slog.v(TAG, "new bounds size: " + bounds.width() + " x " + bounds.height());
            }
            if (mMinVisibleWidth > bounds.width() || mMinVisibleHeight > bounds.height()) {
                Slog.v(TAG, "Minimum requirement violated: Width(min, is)=(" + mMinVisibleWidth
                        + ", " + bounds.width() + ") Height(min,is)=("
                        + mMinVisibleHeight + ", " + bounds.height() + ")");
            }
            if (mMaxVisibleSize.x < bounds.width() || mMaxVisibleSize.y < bounds.height()) {
                Slog.v(TAG, "Maximum requirement violated: Width(min, is)=(" + mMaxVisibleSize.x
                        + ", " + bounds.width() + ") Height(min,is)=("
                        + mMaxVisibleSize.y + ", " + bounds.height() + ")");
            }
        }
    }

    private void updateWindowDragBounds(int x, int y, Rect stackBounds) {
        final int offsetX = Math.round(x - mStartDragX);
        final int offsetY = Math.round(y - mStartDragY);
        mWindowDragBounds.set(mWindowOriginalBounds);
        // Horizontally, at least mMinVisibleWidth pixels of the window should remain visible.
        final int maxLeft = stackBounds.right - mMinVisibleWidth;
        final int minLeft = stackBounds.left + mMinVisibleWidth - mWindowOriginalBounds.width();

        // Vertically, the top mMinVisibleHeight of the window should remain visible.
        // (This assumes that the window caption bar is at the top of the window).
        final int minTop = stackBounds.top;
        final int maxTop = stackBounds.bottom - mMinVisibleHeight;

        mWindowDragBounds.offsetTo(
                Math.min(Math.max(mWindowOriginalBounds.left + offsetX, minLeft), maxLeft),
                Math.min(Math.max(mWindowOriginalBounds.top + offsetY, minTop), maxTop));

        if (DEBUG_TASK_POSITIONING) Slog.d(TAG,
                "updateWindowDragBounds: " + mWindowDragBounds);
    }

    private void updateDimLayerVisibility(int x) {
        @CtrlType
        int dimSide = getDimSide(x);
        if (dimSide == mCurrentDimSide) {
            return;
        }

        mCurrentDimSide = dimSide;

        if (SHOW_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION updateDimLayerVisibility");
        mService.openSurfaceTransaction();
        if (mCurrentDimSide == CTRL_NONE) {
            mDimLayer.hide();
        } else {
            showDimLayer();
        }
        mService.closeSurfaceTransaction();
    }

    /**
     * Returns the side of the screen the dim layer should be shown.
     * @param x horizontal coordinate used to determine if the dim layer should be shown
     * @return Returns {@link #CTRL_LEFT} if the dim layer should be shown on the left half of the
     * screen, {@link #CTRL_RIGHT} if on the right side, or {@link #CTRL_NONE} if the dim layer
     * shouldn't be shown.
     */
    private int getDimSide(int x) {
        if (mTask.mStack.mStackId != FREEFORM_WORKSPACE_STACK_ID
                || !mTask.mStack.fillsParent()
                || mTask.mStack.getConfiguration().orientation != ORIENTATION_LANDSCAPE) {
            return CTRL_NONE;
        }

        mTask.mStack.getDimBounds(mTmpRect);
        if (x - mSideMargin <= mTmpRect.left) {
            return CTRL_LEFT;
        }
        if (x + mSideMargin >= mTmpRect.right) {
            return CTRL_RIGHT;
        }

        return CTRL_NONE;
    }

    private void showDimLayer() {
        mTask.mStack.getDimBounds(mTmpRect);
        if (mCurrentDimSide == CTRL_LEFT) {
            mTmpRect.right = mTmpRect.centerX();
        } else if (mCurrentDimSide == CTRL_RIGHT) {
            mTmpRect.left = mTmpRect.centerX();
        }

        mDimLayer.setBounds(mTmpRect);
        mDimLayer.show(mService.getDragLayerLocked(), RESIZING_HINT_ALPHA,
                RESIZING_HINT_DURATION_MS);
    }

    @Override /** {@link DimLayer.DimLayerUser} */
    public boolean dimFullscreen() {
        return isFullscreen();
    }

    boolean isFullscreen() {
        return false;
    }

    @Override /** {@link DimLayer.DimLayerUser} */
    public DisplayInfo getDisplayInfo() {
        return mTask.mStack.getDisplayInfo();
    }

    @Override
    public boolean isAttachedToDisplay() {
        return mTask != null && mTask.getDisplayContent() != null;
    }

    @Override
    public void getDimBounds(Rect out) {
        // This dim layer user doesn't need this.
    }

    @Override
    public String toShortString() {
        return TAG;
    }
}
