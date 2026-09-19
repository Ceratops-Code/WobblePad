# WobblePad disclaimer

Last updated: 2026-09-19

## Independent project

WobblePad is an independent, unofficial, community-developed interoperability
project. It is not affiliated with, authorized by, endorsed by, certified by,
or sponsored by BO&BO Ltd. or any distributor, healthcare provider, game
publisher, Android device manufacturer, or Shizuku maintainer.

“BoBo,” “BoBo Wobbly,” and related product names or marks are the property of
their respective owners. They are used in this repository only to identify the
hardware with which the software is intended to interoperate. The repository
does not use the vendor's logo, trade dress, screenshots, marketing artwork, or
other brand assets.

## Independent interoperability work

The BLE service identifiers and packet observations documented here were
independently obtained from data transmitted by a user-owned device during
ordinary local BLE communication. This repository contains no BO&BO source
code, application binaries, decompiled application code, firmware, artwork,
games, account credentials, backend access, subscription content, or paid
features.

The protocol is undocumented by its manufacturer and may change. Descriptions
in this repository are observations, not an official specification. Do not use
this project to defeat access controls, obtain restricted content, interfere
with services, impersonate a device, or violate another party's rights.

## No legal or contractual assurance

Publication under an open-source license does not determine whether a
particular user's activity is permitted by local law, a device warranty, a
manufacturer agreement, a game agreement, an employer policy, or another
contract. Those rules vary by place and use. You are responsible for reviewing
and following the terms that apply to your hardware, Android device, apps, and
games.

The project maintainers do not represent that every form of reverse
engineering, interoperability testing, controller emulation, or distribution
is permitted in every jurisdiction. Nothing in this repository is legal
advice.

Users should review the manufacturer's current
[terms and conditions](https://bobo-balance.shop/pages/terms-and-conditions)
and safety materials for their own device. This project does not interpret,
replace, or speak for those materials.

## Experimental software and compatibility

WobblePad is experimental. It may fail to discover or connect to a board,
misread future packet formats, lose calibration, reconnect repeatedly, create
incorrect controller values, stop working after an Android or firmware update,
or be rejected by a receiving app. Bluetooth radio conditions, manufacturer
customizations, `/dev/uhid` availability, Shizuku state, and game input support
can change behavior.

No claim is made that WobblePad works with every BoBo model, hardware revision,
Android device, operating-system build, game, or accessibility configuration.
Back up anything important and stop using the software if its output is
unexpected.

## Shizuku and elevated local access

An ordinary Android application cannot create a system-wide virtual controller
for other apps. WobblePad therefore uses the separately installed Shizuku
service, with the user's approval, to run a narrow helper with Android shell
identity and access `/dev/uhid`.

Shizuku access is security-sensitive. Install Shizuku only from a source you
trust, review its documentation, keep the Android device protected, and grant
access only to applications you trust. Wireless debugging and developer
options can expand the attack surface of a device; turn them off when they are
not needed. WobblePad does not install Shizuku, root the device, unlock the
bootloader, alter system partitions, or modify board firmware.

## Games, services, and fair use

A game or service may prohibit automation, emulated input, accessibility-based
control, unusual peripherals, or third-party tools even when Android permits
the technical operation. Anti-cheat systems may block or penalize virtual input.
Use WobblePad only where the receiving app's rules allow it. The project is an
input-access and interoperability tool, not a means to obtain an unfair
advantage or evade enforcement.

## Physical and medical safety

A balance board can cause falls, strains, collisions, and other injury. Use a
clear, stable area; inspect the board; follow the manufacturer's safety and
weight instructions; use appropriate support or supervision; and stop if you
feel pain, dizziness, instability, or unusual discomfort. Software output must
never distract from physical safety.

WobblePad is not a medical device and is not designed, validated, or approved
to diagnose, monitor, treat, rehabilitate, or prevent any condition. It does
not replace professional medical advice, assessment, therapy, or supervision.
Do not base a health or rehabilitation decision on its sensor values or game
output.

## Data and privacy

The application manifest does not request Internet access. BLE packets,
Bluetooth addresses, calibration samples, and the bounded recent-packet buffer
are processed locally. Saved calibration remains in private application
storage. CSV data is exported only when the user chooses a destination through
Android's document picker.

Bluetooth addresses and motion captures can still identify hardware or reveal
activity patterns. Review and sanitize exported diagnostics before sharing
them. Android, Shizuku, the receiving game, the operating system, and any file
destination selected by the user have their own privacy behavior outside this
project's control.

## Third-party software

WobblePad depends on third-party libraries and tools, including Nordic
Semiconductor's Android BLE Library and the Shizuku API. Those projects are
maintained and licensed separately. Their inclusion does not imply endorsement
of WobblePad, and WobblePad does not imply endorsement of them. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Warranty and liability

The software is provided on an “AS IS” and “AS AVAILABLE” basis, without
warranties or conditions of any kind, to the extent permitted by law. This
includes no warranty of merchantability, fitness for a particular purpose,
title, non-infringement, accuracy, reliability, availability, safety, or
compatibility.

To the extent permitted by law, contributors and maintainers are not liable for
direct, indirect, incidental, special, exemplary, or consequential loss arising
from use of or inability to use the software, including device damage, data
loss, account action, service interruption, personal injury, or reliance on
sensor or controller output.

The Apache License 2.0 in [LICENSE](LICENSE) governs permission to use, copy,
modify, and distribute this project's source code. This disclaimer explains
project status and operational risk; it does not add restrictions to the
license. If wording here conflicts with the license on a licensing question,
the license controls.
