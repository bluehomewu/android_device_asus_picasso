#
# Copyright (C) 2021 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from the device configuration.
$(call inherit-product, device/asus/picasso/device.mk)

# Inherit from the Lineage configuration.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

PRODUCT_BRAND := asus
PRODUCT_DEVICE := picasso
PRODUCT_MANUFACTURER := asus
PRODUCT_MODEL := ASUS_I007D
PRODUCT_NAME := lineage_picasso

PRODUCT_GMS_CLIENTID_BASE := android-asus

PRODUCT_BUILD_PROP_OVERRIDES += \
    PRODUCT_DEVICE=ASUS_I007_1 \
    PRODUCT_NAME=WW_I007D

BUILD_FINGERPRINT := asus/WW_I007D/ASUS_I007_1:11/RKQ1.201112.002/18.1055.2307.269-0:user/release-keys
