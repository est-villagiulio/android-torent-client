# Shigaflow 1.1.0

Shigaflow is an Android Torrent Client downloader by Shiga.

## Changes in this source update
- Download working data is stored temporarily in the app-private `torrent-work` directory while active. On successful completion, files are exported to the standard Downloads directory (or the custom folder selected in Settings), then the private working copies are removed to prevent completed downloads consuming storage twice.
- Completed torrent records are restored from local preferences without recreating the private data copy.
- Delete with “delete files too” also deletes exported file URIs recorded by the app, including after an app restart. “Keep files” removes the list entry and leaves exported files intact.
- Settings, speed limits, foreground notification, and navigation remain.

## Storage limitation
Because libtorrent writes to a filesystem path and Android restricts direct writes to arbitrary shared-storage folders, active/incomplete torrent pieces still live in app-private working storage until completion and export. The app therefore can still show increased app storage during an active download; this update specifically removes the duplicate private copy after a successful export. Fully downloading directly into arbitrary SAF-selected folders would require a different storage integration.

## Build notes
- Compile/target SDK are set to API 36 for current Google Play submission requirements.
- Been using the debug_app.apk for a week now, everything normal.
- Will be no Ads. Ever. Period.

Let me tell you a story, Bough BitTorent Add-free Client since 2019, 2 weeks ago, its suddenly Update and the Restore Purchase are Missing, Emailing BitTorent Customer Service bear no Result. to put it simply, its my petty way to revenge.

The App Already Build/compiled, Check app/build/release. ill release the windows version sometime later. 

<img width="400" height="820" alt="image" src="https://github.com/user-attachments/assets/5b5480f0-7d7e-47ba-979c-9c76f8dfb35e" />
<img width="400" height="820" alt="image" src="https://github.com/user-attachments/assets/43a7c9f7-93f4-47d2-8a01-2694b6558ca4" />


