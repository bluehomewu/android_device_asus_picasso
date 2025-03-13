#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2024 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

blob_fixups: blob_fixups_user_type = {
    ('vendor/etc/media_codecs.xml', 'vendor/etc/media_codecs_lahaina.xml', 'vendor/etc/media_codecs_lahaina_vendor.xml', 'vendor/etc/media_codecs_yupik_v1.xml'): blob_fixup()
        .regex_replace('.*media_codecs_(google_audio|google_c2|google_telephony|vendor_audio).*\n', ''),
    'vendor/etc/msm_irqbalance.conf': blob_fixup()
        .regex_replace('IGNORED_IRQ=27,23,38$', 'IGNORED_IRQ=27,23,38,115,332'),
    'vendor/etc/wifi/wpa_supplicant_overlay.conf': blob_fixup()
        .regex_replace('^pmf=.*\n', '')
        .regex_replace('^sae_pwe=.*\n', '')
        .add_line_if_missing('pmf=1')
        .add_line_if_missing('sae_pwe=2'),
}  # fmt: skip

module = ExtractUtilsModule(
    'picasso',
    'asus',
    blob_fixups=blob_fixups,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
