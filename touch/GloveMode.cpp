/*
 * Copyright (C) 2021 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "GloveModeService"

#include "GloveMode.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/strings.h>

namespace aidl {
namespace vendor {
namespace lineage {
namespace touch {

const std::string kGloveModePath = "/proc/driver/glove";

ndk::ScopedAStatus GloveMode::getEnabled(bool* _aidl_return) {
    std::string buf;
    if (!android::base::ReadFileToString(kGloveModePath, &buf)) {
        LOG(ERROR) << "Failed to read " << kGloveModePath;
        *_aidl_return = false;
        return ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    *_aidl_return = android::base::Trim(buf).find("Glove Mode: On") != std::string::npos;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus GloveMode::setEnabled(bool enabled) {
    if (!android::base::WriteStringToFile(enabled ? "1" : "0", kGloveModePath)) {
        LOG(ERROR) << "Failed to write " << kGloveModePath;
        return ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
    }

    return ndk::ScopedAStatus::ok();
}

}  // namespace touch
}  // namespace lineage
}  // namespace vendor
}  // namespace aidl
