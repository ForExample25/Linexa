package com.fem.linexa.di

import android.content.Context
import com.fem.linexa.ui.screens.device.DeviceInfoLabels
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Supplies the resource-backed [DeviceInfoLabels] to
 * [com.fem.linexa.ui.screens.device.DeviceInfoViewModel].
 *
 * The binding holds the application context, not a resolved locale: every
 * label is looked up when the mapper runs, so changing the app language while
 * Device & System is open takes effect on the next two-second poll rather than
 * pinning the language captured at first injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceInfoLabelsModule {

    @Provides
    fun provideDeviceInfoLabels(@ApplicationContext context: Context): DeviceInfoLabels =
        DeviceInfoLabels.from(context)
}
