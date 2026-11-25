# Sansui Remote 3.0 (Google TV / Android TV)

## Connection (automatic order)
1. **Wi-Fi** (main): saved IP -> if the TV moved, it is found again automatically.
2. **Bluetooth** (backup): if Wi-Fi fails, the phone acts as a Bluetooth keyboard/media remote.
   One-time setup: TV Settings > Remotes & accessories > Add accessory, then in the app
   Connection > Bluetooth > "Make phone visible". Apps, Input and Settings need Wi-Fi.

## First use
Open app > Find my TV > type the 6-character code shown on the TV. Next time it connects by itself.

## Notes
- Picture / Sound: tap once, then record the key steps you use on your TV (Settings, arrows, OK).
  They replay on every tap. Same for any tile ("Key steps").
- Airtel Xstream / Browser: open the app on the TV, then in the tile editor tap
  "use the app open on the TV now".
- Typing: opens automatically when the TV shows a text field; every letter is mirrored live.

## Build the APK
GitHub: push this folder's contents -> Actions -> "Build Sansui Remote APK" -> Run -> download artifact.
Uninstall the previous version first. Or open the folder in Android Studio and Build APK.

## Code map
- `assets/sansui-tv-remote.html` - UI (offline; also opens in a desktop browser as a preview)
- `core/` - protocol: Proto, Identity, Pairing, Remote (keys, IME typing), Controller (auto-connect), HidMap (Bluetooth)
- `MainActivity.kt`, `NsdDiscovery.kt`, `BtHid.kt` - Android glue
