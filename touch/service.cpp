/*
 * Copyright (C) 2021 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vendor.lineage.touch-service.picasso"

#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <cstdlib>
#include <memory>
#include <string>

#include "GloveMode.h"

using ::aidl::vendor::lineage::touch::GloveMode;
using ::aidl::vendor::lineage::touch::IGloveMode;

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    std::shared_ptr<GloveMode> gloveMode = ndk::SharedRefBase::make<GloveMode>();
    const std::string instance = std::string() + IGloveMode::descriptor + "/default";

    if (AServiceManager_addService(gloveMode->asBinder().get(), instance.c_str()) != STATUS_OK) {
        LOG(ERROR) << "Cannot register touchscreen glove HAL service.";
        return EXIT_FAILURE;
    }

    LOG(INFO) << "Touchscreen HAL service ready.";

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
