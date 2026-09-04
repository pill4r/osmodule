# Third-party notices

## Osmosis

osmodule is derived from [Osmosis](https://github.com/KonradIT/osmosis) and retains its MIT-licensed
implementation and attribution. The applicable MIT license and copyright notice are retained in
the repository root [LICENSE.txt](LICENSE.txt).

## Additional protocol research references

The following upstream projects were consulted while developing and validating camera discovery,
pairing, transport and media behavior. Their own licenses and terms remain authoritative; listing a
project here does not relicense its work.

- [o-gs](https://github.com/o-gs)
- [osmo-download](https://github.com/SemiConscious/osmo-download)
- [DJI-Wifi-Connect](https://github.com/sniffingpickles/DJI-Wifi-Connect)
- [lib-osmo-ble](https://github.com/yigitkonur/lib-osmo-ble)

## DJI Osmo GPS Controller Demo and R-SDK protocol

osmodule's R-SDK framing, CRC, command identifiers, BLE session, GPS push and status handling are
ported and modified from DJI's
[Osmo GPS Controller Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo). This is a direct
code and protocol source, not merely a research reference.

Copyright (c) 2025 SZ DJI Technology Co., Ltd.

The upstream [LICENSE](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo/blob/main/LICENSE) offers
the sample under the MIT License and states that the DJI R-SDK protocol and its documentation are
subject to DJI's [End User License Agreement](https://developer.dji.com/policies/eula/). The
applicable upstream notice and complete MIT text are retained in
[DJI-Osmo-GPS-Controller-Demo.txt](LICENSES/DJI-Osmo-GPS-Controller-Demo.txt) and packaged in every
osmodule APK.

## dji-remote

The DUML framing, CRC, byte reader/writer and selected command payload implementations are vendored
or adapted from [dji-remote](https://github.com/dimadesu/dji-remote).

Copyright (c) 2026 Dmytro Antonov

dji-remote is licensed under the MIT License. Its complete copyright and license text are retained
in [dji-remote-MIT.txt](LICENSES/dji-remote-MIT.txt) and packaged in every osmodule APK.

## OpenPocketCine

The Pocket 4P RC DUML command routes, payload layouts and status interpretation are adapted from
[OpenPocketCine](https://github.com/erik-sutton95/OpenPocketCine) as reviewed at commit
[`9762fdd`](https://github.com/erik-sutton95/OpenPocketCine/tree/9762fddbb7add84b980f93c03fa8f8c0e2917fba).
The implementation has been modified and reimplemented in Kotlin for osmodule's plugin architecture.

Copyright 2026 Erik Sutton and OpenPocketCine contributors

OpenPocketCine is licensed under the
[Apache License 2.0](LICENSES/OpenPocketCine-Apache-2.0.txt). Its applicable upstream attribution is
retained in [OpenPocketCine-NOTICE.txt](LICENSES/OpenPocketCine-NOTICE.txt); both files are also
packaged in the Pocket 4P RC APK. OpenPocketCine and osmodule are independent third-party projects;
neither is affiliated with or endorsed by DJI, and neither uses an official DJI SDK for this protocol
implementation.

## PanoForge

The Osmo 360 `djmd` factory-calibration decoder and calibrated fisheye projection use research and
algorithms published by [PanoForge](https://github.com/Belenos-Toutatis/PanoForge).

MIT License

Copyright (c) 2026 Emmanuel Wenner

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## osmo360 Android Live Preview

The Osmo 360 UDP/TCP live-preview handshake and AVC framing are adapted from
[yesbhautik/osmo360](https://github.com/yesbhautik/osmo360).

MIT License

Copyright (c) 2026 Bhautik Bavadiya

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
