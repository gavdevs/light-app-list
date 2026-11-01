# App List

A text-only list of every app installed on the Light Phone III, with long-press
management. Built with the [Light SDK](https://github.com/lightphone/light-sdk).

The Light Phone ships a deliberately small set of tools, but the device is
Android underneath and can have apps installed that the launcher never shows.
This tool lists them all as plain text rows: tap to open anything, including
apps the launcher hides.

## Behavior

- Every launchable app, sorted alphabetically by the name on screen.
- **Tap** a row to open the app.
- **Long-press** for actions: rename, pin, move up/down, hide, app info, and
  uninstall (with a confirmation).
- **Hidden apps** are reachable from a row at the end of the list, not a
  setting you have to go find.
- Three curated system screens — Settings, Wi-Fi, Bluetooth — appear as normal
  rows because several settings screens have no launcher activity and cannot be
  enumerated.

Names, hiding, and pin order are stored in the tool's own DataStore. Renaming an
app to "Music" makes it sort under M and search under M; clearing the alias
restores the system label.

## Building

The tool consumes the Light SDK as an adjacent composite build, so it needs a
`light-sdk` checkout next to it:

```
light/
├── light-sdk/
└── light-app-list/
```

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk ./gradlew :app:assembleDebug
```

Point at a different SDK with `-PappList.sdkPath=/path/to/light-sdk`.

## How it reaches other apps

The SDK blocks tool code from `getSystemService`, `Intent`, `Context`, and
`startActivity`, so this tool declares the `app-access` capability and goes
through `lightContext.appAccess.apps`. The capability generates the scoped
`<queries>` block for launcher activities and a notification listener, and
does not request `QUERY_ALL_PACKAGES`.

## License

MIT.
