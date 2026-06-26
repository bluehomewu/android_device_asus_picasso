/*
 * Copyright (C) 2020 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "Lights.h"

#include <android-base/logging.h>
#include <android-base/file.h>
#include <array>
#include <string>
#include <unistd.h>

namespace aidl {
namespace android {
namespace hardware {
namespace light {

namespace {

constexpr const char* kLedRoot = "/sys/class/leds";

struct LedChannel {
    const char* name;
    int value;
};

std::string ledPath(const std::string& led, const std::string& node) {
    return std::string(kLedRoot) + "/" + led + "/" + node;
}

bool nodeExists(const std::string& path) {
    return access(path.c_str(), F_OK) == 0;
}

bool writeNode(const std::string& path, const std::string& value) {
    if (!nodeExists(path)) {
        return false;
    }

    if (!::android::base::WriteStringToFile(value, path)) {
        PLOG(WARNING) << "Failed to write " << path;
        return false;
    }

    return true;
}

bool hasRgbLed() {
    return nodeExists(ledPath("red", "brightness")) ||
           nodeExists(ledPath("green", "brightness")) ||
           nodeExists(ledPath("blue", "brightness"));
}

bool isLit(const HwLightState& state) {
    return (state.color & 0x00ffffff) != 0;
}

std::array<LedChannel, 3> channelsFromColor(int color) {
    return {{
        {"red", (color >> 16) & 0xff},
        {"green", (color >> 8) & 0xff},
        {"blue", color & 0xff},
    }};
}

void setLedChannel(const LedChannel& channel, FlashMode mode, int onMs, int offMs) {
    const std::string led(channel.name);

    if (channel.value == 0) {
        writeNode(ledPath(led, "trigger"), "none");
        writeNode(ledPath(led, "breath"), "0");
        writeNode(ledPath(led, "brightness"), "0");
        return;
    }

    switch (mode) {
        case FlashMode::TIMED:
            writeNode(ledPath(led, "breath"), "0");
            writeNode(ledPath(led, "trigger"), "timer");
            writeNode(ledPath(led, "delay_on"), std::to_string(onMs > 0 ? onMs : 500));
            writeNode(ledPath(led, "delay_off"), std::to_string(offMs > 0 ? offMs : 500));
            writeNode(ledPath(led, "brightness"), std::to_string(channel.value));
            break;
        case FlashMode::HARDWARE:
            if (!writeNode(ledPath(led, "breath"), "1")) {
                writeNode(ledPath(led, "trigger"), "none");
            }
            writeNode(ledPath(led, "brightness"), std::to_string(channel.value));
            break;
        case FlashMode::NONE:
        default:
            writeNode(ledPath(led, "trigger"), "none");
            writeNode(ledPath(led, "breath"), "0");
            writeNode(ledPath(led, "brightness"), std::to_string(channel.value));
            break;
    }
}

void setRgbLed(const HwLightState& state) {
    for (const auto& channel : channelsFromColor(state.color)) {
        setLedChannel(channel, state.flashMode, state.flashOnMs, state.flashOffMs);
    }
}

HwLight makeLight(LightType type) {
    return {
        .id = static_cast<int32_t>(type),
        .ordinal = 0,
        .type = type,
    };
}

}  // namespace

Lights::Lights() {
    if (hasRgbLed()) {
        mAvailableLights.emplace_back(makeLight(LightType::BATTERY));
        mAvailableLights.emplace_back(makeLight(LightType::NOTIFICATIONS));
        mAvailableLights.emplace_back(makeLight(LightType::ATTENTION));
    } else {
        LOG(WARNING) << "No RGB notification LED found";
    }
}

ndk::ScopedAStatus Lights::setLightState(int id, const HwLightState& state) {
    if (!supportsLight(id)) {
        LOG(ERROR) << "Light not supported: " << id;
        return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    }

    {
        std::lock_guard<std::mutex> lock(mLock);
        switch (static_cast<LightType>(id)) {
            case LightType::BATTERY:
                mBatteryState = state;
                break;
            case LightType::NOTIFICATIONS:
                mNotificationsState = state;
                break;
            case LightType::ATTENTION:
                mAttentionState = state;
                break;
            default:
                return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
        }

        updateNotificationLed();
    }

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Lights::getLights(std::vector<HwLight>* lights) {
    for (const auto& light : mAvailableLights) {
        lights->push_back(light);
    }
    return ndk::ScopedAStatus::ok();
}

bool Lights::supportsLight(int id) {
    for (const auto& light : mAvailableLights) {
        if (light.id == id) {
            return true;
        }
    }
    return false;
}

void Lights::updateNotificationLed() {
    const HwLightState state = isLit(mNotificationsState) ? mNotificationsState
                             : isLit(mAttentionState)     ? mAttentionState
                             : isLit(mBatteryState)       ? mBatteryState
                                                          : HwLightState();
    setRgbLed(state);
}

}  // namespace light
}  // namespace hardware
}  // namespace android
}  // namespace aidl
