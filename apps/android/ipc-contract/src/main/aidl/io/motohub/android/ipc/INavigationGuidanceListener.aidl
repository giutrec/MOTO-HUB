package io.motohub.android.ipc;

import io.motohub.android.ipc.NavigationGuidanceParcel;

/** Receives instrument-cluster guidance updates from Core's Android Auto receiver. */
oneway interface INavigationGuidanceListener {
    void onGuidanceChanged(in NavigationGuidanceParcel guidance);
}
