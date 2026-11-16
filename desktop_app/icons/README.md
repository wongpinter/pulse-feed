# Desktop app icons

`pulsefeed-macos.png`, `pulsefeed-windows.png`, `pulsefeed.icns`, and
`pulsefeed.ico` are deterministic derivatives of the canonical artwork at
`metadata/en-US/images/icon.png`. Regenerate all of them from the
repository root with:

```sh
python3 desktop_app/icons/generate_macos_icon.py \
  metadata/en-US/images/icon.png \
  desktop_app/icons/pulsefeed-macos.png \
  --icns desktop_app/icons/pulsefeed.icns \
  --windows desktop_app/icons/pulsefeed-windows.png \
  --ico desktop_app/icons/pulsefeed.ico
```

The macOS geometry follows the local Canvas recipe published by
<https://icon.msgbyte.com/>. Its optional glossy highlight is omitted to keep
the source artwork and brand colors of Pulse Feed intact. The Windows version uses
tighter outer padding so it remains legible at taskbar sizes while retaining
transparent, rounded corners.
