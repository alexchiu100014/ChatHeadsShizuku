package com.chatheads.launcher.shizuku;

import android.content.Intent;

interface IFreeformService {
    void startActivityInFreeform(in Intent intent, int left, int top, int right, int bottom);
    void enableFreeformMode();
    boolean isFreeformEnabled();
    void destroy();
}
